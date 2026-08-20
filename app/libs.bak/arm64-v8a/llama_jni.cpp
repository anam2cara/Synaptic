// llama_jni.cpp — JNI bridge ke libllama.so
// Package: com.supppa.deviceai
// Compile target: arm64-v8a

#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "llama.h"

#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static void llama_log_callback_android(ggml_log_level level, const char* text, void* /*user_data*/) {
    if (level == GGML_LOG_LEVEL_ERROR) {
        LOGE("[llama.cpp] %s", text);
    } else {
        LOGI("[llama.cpp] %s", text);
    }
}

// ─── State global per-instance ───────────────────────────────────────────────

struct LlamaState {
    llama_model   * model   = nullptr;
    llama_context * ctx     = nullptr;
    llama_sampler * sampler = nullptr;
    bool            loaded  = false;
};

static LlamaState g_state;

// ─── Helper: convert Java String ke std::string ──────────────────────────────

static std::string jstring2str(JNIEnv* env, jstring jstr) {
    if (!jstr) return "";
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return result;
}

// ─── JNI: loadModel ──────────────────────────────────────────────────────────
// Signature diubah: menerima jint fd (file descriptor dari Java)
// Java membuka file via ParcelFileDescriptor sehingga bypass FUSE Android 10+

extern "C" JNIEXPORT jboolean JNICALL
Java_com_supppa_deviceai_data_LlamaJNI_loadModelNative(
        JNIEnv* env, jobject /* this */,
        jstring modelPath,
        jint    nCtx,
        jint    nThreads)
{
    // Bebaskan state sebelumnya
    if (g_state.sampler) { llama_sampler_free(g_state.sampler); g_state.sampler = nullptr; }
    if (g_state.ctx)     { llama_free(g_state.ctx);             g_state.ctx     = nullptr; }
    if (g_state.model)   { llama_model_free(g_state.model);     g_state.model   = nullptr; }
    g_state.loaded = false;

    llama_log_set(llama_log_callback_android, nullptr);
    llama_backend_init();

    std::string path = jstring2str(env, modelPath);
    LOGI("Loading model via path: %s", path.c_str());

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // CPU only

    g_state.model = llama_model_load_from_file(path.c_str(), mparams);
    if (!g_state.model) {
        LOGE("Failed to load model: %s", path.c_str());
        return JNI_FALSE;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx      = (uint32_t)(nCtx > 0 ? nCtx : 2048);
    cparams.n_threads  = (int32_t)(nThreads > 0 ? nThreads : 4);
    cparams.n_threads_batch = cparams.n_threads;

    g_state.ctx = llama_init_from_model(g_state.model, cparams);
    if (!g_state.ctx) {
        LOGE("Failed to create context");
        llama_model_free(g_state.model);
        g_state.model = nullptr;
        return JNI_FALSE;
    }

    // Sampler chain: greedy default (bisa diganti temp+top_p)
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    g_state.sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(g_state.sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(g_state.sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(g_state.sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    g_state.loaded = true;
    LOGI("Model loaded OK. ctx_size=%d threads=%d", cparams.n_ctx, cparams.n_threads);
    return JNI_TRUE;
}

// ─── JNI: isLoaded ───────────────────────────────────────────────────────────

extern "C" JNIEXPORT jboolean JNICALL
Java_com_supppa_deviceai_data_LlamaJNI_isLoaded(
        JNIEnv* /* env */, jobject /* this */)
{
    return g_state.loaded ? JNI_TRUE : JNI_FALSE;
}

// ─── JNI: generate ───────────────────────────────────────────────────────────
// Dipanggil dari thread Java. Setiap token di-callback via onToken().

extern "C" JNIEXPORT jstring JNICALL
Java_com_supppa_deviceai_data_LlamaJNI_generate(
        JNIEnv* env, jobject thiz,
        jstring prompt,
        jint    maxNewTokens,
        jobject callback)   // interface TokenCallback
{
    if (!g_state.loaded) {
        return env->NewStringUTF("[ERROR] Model belum dimuat");
    }

    std::string p = jstring2str(env, prompt);

    // Tokenize
    const llama_vocab* vocab = llama_model_get_vocab(g_state.model);
    std::vector<llama_token> tokens(p.size() + 64);
    int n_tokens = llama_tokenize(vocab, p.c_str(), (int32_t)p.size(),
                                  tokens.data(), (int32_t)tokens.size(),
                                  /*add_special=*/true, /*parse_special=*/true);
    if (n_tokens < 0) {
        // Butuh buffer lebih besar
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(vocab, p.c_str(), (int32_t)p.size(),
                                  tokens.data(), (int32_t)tokens.size(),
                                  true, true);
    }
    if (n_tokens <= 0) {
        return env->NewStringUTF("[ERROR] Tokenize gagal");
    }
    tokens.resize(n_tokens);

    // Reset sampler
    llama_sampler_reset(g_state.sampler);

    // Resolve callback method
    jclass cbClass = nullptr;
    jmethodID onToken = nullptr;
    if (callback) {
        cbClass  = env->GetObjectClass(callback);
        onToken  = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
    }

    std::string result;
    int limit = (maxNewTokens > 0 && maxNewTokens <= 4096) ? maxNewTokens : 512;

    for (int i = 0; i < limit; i++) {
        // Batch decode
        llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t)tokens.size());
        if (llama_decode(g_state.ctx, batch) != 0) {
            LOGE("llama_decode failed at step %d", i);
            break;
        }

        // Sample token berikutnya
        llama_token new_token = llama_sampler_sample(g_state.sampler, g_state.ctx, -1);
        llama_sampler_accept(g_state.sampler, new_token);

        // Cek EOG
        if (llama_vocab_is_eog(vocab, new_token)) break;

        // Detokenize ke string
        char buf[256] = {0};
        int len = llama_token_to_piece(vocab, new_token, buf, sizeof(buf) - 1, 0, false);
        if (len < 0) len = 0;
        buf[len] = '\0';

        result += buf;

        // Callback per token (streaming ke Java)
        if (callback && onToken && len > 0) {
            jstring jpiece = env->NewStringUTF(buf);
            env->CallVoidMethod(callback, onToken, jpiece);
            env->DeleteLocalRef(jpiece);
        }

        // Siapkan batch untuk token berikutnya
        tokens = {new_token};
    }

    return env->NewStringUTF(result.c_str());
}

// ─── JNI: freeModel ──────────────────────────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_com_supppa_deviceai_data_LlamaJNI_freeModel(
        JNIEnv* /* env */, jobject /* this */)
{
    if (g_state.sampler) { llama_sampler_free(g_state.sampler); g_state.sampler = nullptr; }
    if (g_state.ctx)     { llama_free(g_state.ctx);             g_state.ctx     = nullptr; }
    if (g_state.model)   { llama_model_free(g_state.model);     g_state.model   = nullptr; }
    llama_backend_free();
    g_state.loaded = false;
    LOGI("Model freed");
}
