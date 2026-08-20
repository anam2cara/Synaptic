#include <jni.h>
#include <string>
#include <vector>
#include <thread>
#include <atomic>
#include <mutex>
#include <algorithm>
#include <android/log.h>
#include "llama.h"
#include "ggml-backend.h"

#define TAG "SynapticJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct LlamaState {
    llama_model   * model         = nullptr;
    llama_context * ctx           = nullptr;
    llama_sampler * sampler       = nullptr;
    bool            loaded        = false;
    int32_t         n_past        = 0;
    std::vector<llama_token> cached_tokens;
};

static LlamaState g_state;
static bool g_backendInit = false;
static std::atomic<bool> g_abort{false};
static std::mutex g_mutex; // guards g_state; TIDAK dipegang oleh stopGeneration (harus tetap bisa interupsi generation yang sedang lock)

static void llama_log_callback_android(ggml_log_level level, const char* text, void*) {
    if (level == GGML_LOG_LEVEL_ERROR) LOGE("%s", text);
    else                               LOGI("%s", text);
}

static std::string jstring2str(JNIEnv* env, jstring jstr) {
    if (!jstr) return "";
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    if (!chars) return "";
    std::string result(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return result;
}

// Panjang prefix `s` yang berisi codepoint UTF-8 lengkap. Sisa byte multi-byte
// yang belum lengkap di ujung TIDAK ikut, supaya tidak ada token boundary yang
// memotong satu karakter jadi dua callback (penyebab NewStringUTF crash).
static size_t utf8SafePrefixLen(const std::string& s) {
    if (s.empty()) return 0;
    size_t i = s.size();
    size_t back = 0;
    while (i > 0 && back < 4) {
        --i; ++back;
        unsigned char c = (unsigned char)s[i];
        if ((c & 0xC0) != 0x80) {
            int expectedLen;
            if      ((c & 0x80) == 0x00) expectedLen = 1;
            else if ((c & 0xE0) == 0xC0) expectedLen = 2;
            else if ((c & 0xF0) == 0xE0) expectedLen = 3;
            else if ((c & 0xF8) == 0xF0) expectedLen = 4;
            else return s.size();
            size_t have = s.size() - i;
            if (have < (size_t)expectedLen) return i;
            return s.size();
        }
    }
    return s.size();
}

static void freeStateLocked_NoLock() {
    if (g_state.sampler) { llama_sampler_free(g_state.sampler); g_state.sampler = nullptr; }
    if (g_state.ctx)     { llama_free(g_state.ctx);             g_state.ctx     = nullptr; }
    if (g_state.model)   { llama_model_free(g_state.model);     g_state.model   = nullptr; }
    g_state.loaded = false;
    g_state.n_past = 0;
    g_state.cached_tokens.clear();
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_synaptic_ai_llm_LlamaJNI_loadModel(JNIEnv* env, jobject, jstring modelPath) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    if (!path) { LOGE("modelPath null"); return JNI_FALSE; }
    LOGI("Loading model: %s", path);
    freeStateLocked_NoLock();
    if (!g_backendInit) {
        llama_log_set(llama_log_callback_android, nullptr);
        llama_backend_init();
        ggml_backend_load_all();
        {
            size_t ndev = ggml_backend_dev_count();
            LOGI("ggml_backend_load_all: %zu device(s) registered", ndev);
            for (size_t i = 0; i < ndev; i++) {
                ggml_backend_dev_t d = ggml_backend_dev_get(i);
                LOGI("  device[%zu]: %s (%s)", i, ggml_backend_dev_name(d), ggml_backend_dev_description(d));
            }
        }
        g_backendInit = true;
    }
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // CPU fallback test
    g_state.model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(modelPath, path);
    if (!g_state.model) {
        LOGE("Gagal load model (file rusak / format tidak didukung / RAM kurang)");
        return JNI_FALSE;
    }
    unsigned int hwThreads = std::thread::hardware_concurrency();
    int nThreads = (hwThreads >= 8) ? 6 : (hwThreads >= 6) ? 4 : 2;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = 4096;
    cparams.n_threads       = nThreads;
    cparams.n_threads_batch = nThreads;
    g_state.ctx = llama_init_from_model(g_state.model, cparams);
    if (!g_state.ctx) {
        LOGE("Gagal membuat context");
        llama_model_free(g_state.model);
        g_state.model = nullptr;
        return JNI_FALSE;
    }
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    g_state.sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(g_state.sampler, llama_sampler_init_temp(0.4f));
    llama_sampler_chain_add(g_state.sampler, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(g_state.sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    g_state.loaded = true;
    LOGI("Model loaded OK. ctx=%d threads=%d", cparams.n_ctx, nThreads);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_synaptic_ai_llm_LlamaJNI_generateStream(JNIEnv* env, jobject, jstring prompt, jint maxTokens, jobject callback) {
    std::lock_guard<std::mutex> lock(g_mutex);

    if (!callback) { LOGE("generateStream: callback null, abort"); return; }
    jclass cbClass = env->GetObjectClass(callback);
    jmethodID midToken    = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
    jmethodID midComplete = env->GetMethodID(cbClass, "onComplete", "()V");
    jmethodID midError    = env->GetMethodID(cbClass, "onError", "(Ljava/lang/String;)V");
    if (!midToken || !midComplete || !midError) {
        LOGE("generateStream: StreamCallback method signature mismatch");
        env->ExceptionClear();
        return;
    }

    auto reportError = [&](const char* msg) {
        LOGE("%s", msg);
        jstring jmsg = env->NewStringUTF(msg);
        env->CallVoidMethod(callback, midError, jmsg);
        env->DeleteLocalRef(jmsg);
        env->ExceptionClear();
    };

    if (!g_state.loaded) { reportError("Model belum dimuat"); return; }

    g_abort.store(false);
    std::string p = jstring2str(env, prompt);
    const llama_vocab* vocab = llama_model_get_vocab(g_state.model);

    std::vector<llama_token> tokens((int32_t)p.size() + 64);
    int n_tokens = llama_tokenize(vocab, p.c_str(), (int32_t)p.size(),
                                   tokens.data(), (int32_t)tokens.size(), true, true);
    if (n_tokens < 0) {
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(vocab, p.c_str(), (int32_t)p.size(),
                                   tokens.data(), (int32_t)tokens.size(), true, true);
    }
    if (n_tokens <= 0) { reportError("Tokenize gagal"); return; }
    tokens.resize(n_tokens);

    int32_t limit = (maxTokens > 0 && maxTokens <= 4096) ? maxTokens : 512;
    int32_t n_ctx = (int32_t)llama_n_ctx(g_state.ctx);

    int32_t common_len = 0;
    {
        int32_t min_len = (int32_t)std::min(tokens.size(), g_state.cached_tokens.size());
        for (int32_t i = 0; i < min_len; i++) {
            if (tokens[i] == g_state.cached_tokens[i]) common_len++;
            else break;
        }
    }

    if (n_tokens >= n_ctx - 64) {
        LOGI("Prompt terlalu panjang (%d token), clear KV Cache", n_tokens);
        llama_memory_clear(llama_get_memory(g_state.ctx), true);
        g_state.n_past = 0;
        g_state.cached_tokens.clear();
        common_len = 0;
    }
    int32_t available = n_ctx - n_tokens - 64;
    if (available <= 0) { reportError("Prompt terlalu panjang"); return; }
    if (limit > available) limit = available;

    if (common_len < g_state.n_past) {
        llama_memory_seq_rm(llama_get_memory(g_state.ctx), 0, common_len, -1);
        g_state.n_past = common_len;
        LOGI("KV Cache dipangkas ke %d token", common_len);
    }

    if (common_len < n_tokens) {
        int32_t n_new = n_tokens - common_len;
        llama_batch batch = llama_batch_init(n_new, 0, 1);
        for (int32_t i = 0; i < n_new; i++) {
            batch.token[i]     = tokens[common_len + i];
            batch.pos[i]       = g_state.n_past + i;
            batch.n_seq_id[i]  = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i]    = (i == n_new - 1) ? 1 : 0;
        }
        batch.n_tokens = n_new;
        int rc = llama_decode(g_state.ctx, batch);
        llama_batch_free(batch);
        if (rc != 0) { reportError("Decode gagal untuk prompt"); return; }
        g_state.n_past += n_new;
        LOGI("Decoded %d token baru | cache hit: %d token", n_new, common_len);
    } else {
        LOGI("Full cache hit: %d token, skip decode prompt", common_len);
    }

    g_state.cached_tokens = tokens;

    llama_sampler_reset(g_state.sampler);
    std::string pending;

    llama_batch step_batch = llama_batch_init(1, 0, 1);
    step_batch.n_seq_id[0]  = 1;
    step_batch.seq_id[0][0] = 0;
    step_batch.logits[0]    = 1;
    step_batch.n_tokens     = 1;

    bool hadError = false;
    for (int i = 0; i < limit; i++) {
        llama_token new_token = llama_sampler_sample(g_state.sampler, g_state.ctx, -1);
        llama_sampler_accept(g_state.sampler, new_token);

        if (g_abort.load() || llama_vocab_is_eog(vocab, new_token)) break;

        char buf[256] = {0};
        int len = llama_token_to_piece(vocab, new_token, buf, sizeof(buf) - 1, 0, false);
        if (len > 0) {
            pending.append(buf, len);
            size_t safeLen = utf8SafePrefixLen(pending);
            if (safeLen > 0) {
                jstring jchunk = env->NewStringUTF(pending.substr(0, safeLen).c_str());
                env->CallVoidMethod(callback, midToken, jchunk);
                env->DeleteLocalRef(jchunk);
                if (env->ExceptionCheck()) {
                    LOGE("Callback onToken melempar exception, hentikan generasi");
                    env->ExceptionClear();
                    hadError = true;
                    break;
                }
                pending.erase(0, safeLen);
            }
        } else if (len < 0) {
            LOGE("token_to_piece buffer kurang (token korup?), skip token ini");
        }

        step_batch.token[0] = new_token;
        step_batch.pos[0]   = g_state.n_past;
        if (llama_decode(g_state.ctx, step_batch) != 0) {
            LOGE("llama_decode gagal pada generated token ke-%d", i);
            hadError = true;
            break;
        }
        g_state.n_past++;
        g_state.cached_tokens.push_back(new_token);
    }
    llama_batch_free(step_batch);

    if (!pending.empty()) {
        LOGI("Sisa %zu byte UTF-8 tidak lengkap di akhir, dibuang", pending.size());
    }

    if (hadError) {
        reportError("Generasi berhenti karena error internal");
    } else {
        env->CallVoidMethod(callback, midComplete);
        env->ExceptionClear();
    }
}

JNIEXPORT void JNICALL
Java_com_synaptic_ai_llm_LlamaJNI_clearCache(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_state.ctx) {
        llama_memory_clear(llama_get_memory(g_state.ctx), true);
    }
    g_state.n_past = 0;
    g_state.cached_tokens.clear();
    LOGI("KV Cache cleared (session switch)");
}

JNIEXPORT void JNICALL
Java_com_synaptic_ai_llm_LlamaJNI_freeModel(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    freeStateLocked_NoLock();
    LOGI("Model freed");
}

JNIEXPORT jboolean JNICALL
Java_com_synaptic_ai_llm_LlamaJNI_isLoaded(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_state.loaded ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_synaptic_ai_llm_LlamaJNI_stopGeneration(JNIEnv*, jobject) {
    g_abort.store(true);
    LOGI("stopGeneration: abort flag set");
}

} // extern "C"
