#include <jni.h>
#include <string>
#include <vector>
#include <thread>
#include <atomic>
#include <mutex>
#include <android/log.h>
#include "llama.h"
#include "ggml.h"
#include "ggml-backend.h"

#define TAG "SynapticJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct LlamaState {
    llama_model   * model   = nullptr;
    llama_context * ctx     = nullptr;
    llama_sampler * sampler = nullptr;
    bool            loaded  = false;
    int32_t         n_past  = 0;
};

static LlamaState g_state;
static std::mutex g_stateMutex;
static std::atomic<bool> g_abort{false};

static void freeStateLocked() {
    if (g_state.sampler) { llama_sampler_free(g_state.sampler); g_state.sampler = nullptr; }
    if (g_state.ctx)     { llama_free(g_state.ctx);             g_state.ctx     = nullptr; }
    if (g_state.model)   { llama_model_free(g_state.model);     g_state.model   = nullptr; }
    g_state.loaded = false;
    g_state.n_past = 0;
}

// Filter UTF-8 Tanpa Typo
static bool isValidUtf8Sequence(
    const unsigned char * data,
    size_t len,
    size_t & consumed
) {
    consumed = 0;

    if (len == 0) return false;

    const unsigned char c0 = data[0];

    if (c0 <= 0x7F) {
        consumed = 1;
        return true;
    }

    if (c0 >= 0xC2 && c0 <= 0xDF) {
        if (len < 2) return false;

        const unsigned char c1 = data[1];
        if (c1 < 0x80 || c1 > 0xBF) return false;

        consumed = 2;
        return true;
    }

    if (c0 >= 0xE0 && c0 <= 0xEF) {
        if (len < 3) return false;

        const unsigned char c1 = data[1];
        const unsigned char c2 = data[2];

        if (c2 < 0x80 || c2 > 0xBF) return false;

        if (c0 == 0xE0) {
            if (c1 < 0xA0 || c1 > 0xBF) return false;
        } else if (c0 == 0xED) {
            if (c1 < 0x80 || c1 > 0x9F) return false;
        } else {
            if (c1 < 0x80 || c1 > 0xBF) return false;
        }

        consumed = 3;
        return true;
    }

    if (c0 >= 0xF0 && c0 <= 0xF4) {
        if (len < 4) return false;

        const unsigned char c1 = data[1];
        const unsigned char c2 = data[2];
        const unsigned char c3 = data[3];

        if (c2 < 0x80 || c2 > 0xBF) return false;
        if (c3 < 0x80 || c3 > 0xBF) return false;

        if (c0 == 0xF0) {
            if (c1 < 0x90 || c1 > 0xBF) return false;
        } else if (c0 == 0xF4) {
            if (c1 < 0x80 || c1 > 0x8F) return false;
        } else {
            if (c1 < 0x80 || c1 > 0xBF) return false;
        }

        consumed = 4;
        return true;
    }

    return false;
}

static size_t utf8SafePrefixLen(std::string & s) {
    size_t pos = 0;

    while (pos < s.size()) {
        size_t consumed = 0;

        if (!isValidUtf8Sequence(
                reinterpret_cast<const unsigned char *>(s.data() + pos),
                s.size() - pos,
                consumed)) {

            const unsigned char c0 =
                static_cast<unsigned char>(s[pos]);

            const size_t remaining = s.size() - pos;

            const bool possibleIncomplete =
                remaining <= 3 &&
                (
                    (c0 >= 0xC2 && c0 <= 0xDF) ||
                    (c0 >= 0xE0 && c0 <= 0xEF) ||
                    (c0 >= 0xF0 && c0 <= 0xF4)
                );

            if (possibleIncomplete) {
                return pos;
            }

            s.erase(pos, 1);
            continue;
        }

        pos += consumed;
    }

    return pos;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_synaptic_ai_llm_LlamaJNI_loadModel(JNIEnv* env, jobject, jstring modelPath, jboolean tryGpu) {
    std::lock_guard<std::mutex> lock(g_stateMutex);
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    freeStateLocked();

    LOGI("[STAGE: LOAD] JNI_SAFE_MODE_2.0");
    llama_backend_init();

    // Explicitly load all available ggml backends so Vulkan can be enumerated.
    ggml_backend_load_all();

    LOGI("[STAGE: BACKEND] Enumerating available ggml backends...");

    for (size_t i = 0; i < ggml_backend_reg_count(); ++i) {
        ggml_backend_reg_t reg = ggml_backend_reg_get(i);
        if (!reg) {
            continue;
        }

        const char * backendName = ggml_backend_reg_name(reg);
        LOGI(
            "[STAGE: BACKEND] backend[%zu]=%s",
            i,
            backendName ? backendName : "(null)"
        );

        const size_t deviceCount = ggml_backend_reg_dev_count(reg);

        for (size_t d = 0; d < deviceCount; ++d) {
            ggml_backend_dev_t dev =
                ggml_backend_reg_dev_get(reg, d);

            if (!dev) {
                continue;
            }

            LOGI(
                "[STAGE: BACKEND] device=%s type=%d",
                ggml_backend_dev_name(dev),
                static_cast<int>(ggml_backend_dev_type(dev))
            );
        }
    }

    llama_model_params mparams = llama_model_default_params();
    mparams.use_mmap = true;
    mparams.n_gpu_layers = 1; // Canary: offload 1 layer ke GPU
    
    // Explicitly select the Vulkan device for model offloading.
    ggml_backend_dev_t vulkanDevice = nullptr;

    for (size_t i = 0;
         i < ggml_backend_reg_count() && vulkanDevice == nullptr;
         ++i) {

        ggml_backend_reg_t reg = ggml_backend_reg_get(i);

        if (!reg) {
            continue;
        }

        const char * backendName = ggml_backend_reg_name(reg);

        if (!backendName || std::string(backendName) != "Vulkan") {
            continue;
        }

        const size_t deviceCount =
            ggml_backend_reg_dev_count(reg);

        for (size_t d = 0; d < deviceCount; ++d) {
            ggml_backend_dev_t dev =
                ggml_backend_reg_dev_get(reg, d);

            if (!dev) {
                continue;
            }

            vulkanDevice = dev;
            break;
        }
    }

    LOGI(
        "[STAGE: GPU] llama_supports_gpu_offload=%d",
        llama_supports_gpu_offload() ? 1 : 0
    );

    if (vulkanDevice) {
        LOGI(
            "[STAGE: GPU] Selected device=%s type=%d",
            ggml_backend_dev_name(vulkanDevice),
            static_cast<int>(ggml_backend_dev_type(vulkanDevice))
        );

        ggml_backend_dev_t devices[] = {
            vulkanDevice,
            nullptr
        };

        mparams.devices = devices;

        LOGI(
            "[STAGE: GPU] Vulkan device explicitly assigned to model params."
        );
    } else {
        LOGI(
            "[STAGE: GPU] Vulkan device NOT FOUND; default device selection."
        );
    }

    g_state.model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(modelPath, path);
    if (!g_state.model) return JNI_FALSE;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx   = 512; // Context sangat kecil agar RAM aman
    cparams.n_threads = 4;
    cparams.n_threads_batch = 4;

    g_state.ctx = llama_init_from_model(g_state.model, cparams);
    if (!g_state.ctx) return JNI_FALSE;

    g_state.loaded = true;
    LOGI("[STAGE: LOAD] Sukses di CPU-Only Mode.");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_synaptic_ai_llm_LlamaJNI_generateStream(JNIEnv* env, jobject, jstring prompt, jstring grammar, jint maxTokens, jobject callback) {
    std::unique_lock<std::mutex> lock(g_stateMutex);
    if (!g_state.loaded) return;

    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
    jmethodID onComplete = env->GetMethodID(cbClass, "onComplete", "()V");

    const char* pStr = env->GetStringUTFChars(prompt, nullptr);
    const llama_vocab* vocab = llama_model_get_vocab(g_state.model);

    std::vector<llama_token> tokens(strlen(pStr) + 64);
    int n_tokens = llama_tokenize(vocab, pStr, strlen(pStr), tokens.data(), tokens.size(), true, true);
    env->ReleaseStringUTFChars(prompt, pStr);
    if (n_tokens <= 0) return;

    // Reset Sampler & Memory TOTAL
    if (g_state.sampler) llama_sampler_free(g_state.sampler);
    g_state.sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(g_state.sampler, llama_sampler_init_temp(0.5f));
    llama_sampler_chain_add(g_state.sampler, llama_sampler_init_dist(42));

    llama_memory_clear(llama_get_memory(g_state.ctx), true);
    g_state.n_past = 0;

    // PREFILL: proses prompt bertahap untuk mengurangi peak memory/GPU.
    constexpr int PREFILL_CHUNK_SIZE = 32;

    int processed = 0;

    while (processed < n_tokens) {
        const int chunkSize =
            std::min(PREFILL_CHUNK_SIZE, n_tokens - processed);

        llama_batch batch =
            llama_batch_init(chunkSize, 0, 1);

        batch.n_tokens = chunkSize;

        for (int i = 0; i < chunkSize; i++) {
            const int tokenIndex = processed + i;

            batch.token[i] = tokens[tokenIndex];
            batch.pos[i] = tokenIndex;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;

            // Hanya token terakhir dari seluruh prompt
            // yang perlu menghasilkan logits untuk sampling berikutnya.
            batch.logits[i] =
                (tokenIndex == n_tokens - 1);
        }

        const int decodeResult =
            llama_decode(g_state.ctx, batch);

        llama_batch_free(batch);

        if (decodeResult != 0) {
            LOGE(
                "[STAGE: PREFILL] llama_decode gagal pada token %d, ret=%d",
                processed,
                decodeResult
            );
            return;
        }

        processed += chunkSize;

        LOGI(
            "[STAGE: PREFILL] processed=%d/%d",
            processed,
            n_tokens
        );
    }

    g_state.n_past = n_tokens;
    // GEN
    g_abort.store(false);
    std::string pending;
    llama_batch s_batch = llama_batch_init(1, 0, 1);
    s_batch.n_tokens = 1;
    s_batch.n_seq_id[0] = 1;
    s_batch.seq_id[0][0] = 0;
    s_batch.logits[0] = true;

    for (int i = 0; i < 256; i++) {
        if (g_abort.load()) break;
        llama_token id = llama_sampler_sample(g_state.sampler, g_state.ctx, -1);
        llama_sampler_accept(g_state.sampler, id);
        if (llama_vocab_is_eog(vocab, id)) break;

        char buf[256];
        int n = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, false);
        if (n > 0) {
            pending.append(buf, n);
            size_t safe = utf8SafePrefixLen(pending);
            if (safe > 0) {
                jstring jp = env->NewStringUTF(pending.substr(0, safe).c_str());
                if (jp) { env->CallVoidMethod(callback, onToken, jp); env->DeleteLocalRef(jp); }
                pending.erase(0, safe);
            }
        }
        s_batch.token[0] = id;
        s_batch.pos[0] = g_state.n_past++;
        if (llama_decode(g_state.ctx, s_batch) != 0) break;
    }
    llama_batch_free(s_batch);
    LOGI("[STAGE: GEN] Selesai.");
    env->CallVoidMethod(callback, onComplete);
    env->DeleteLocalRef(cbClass);
}

JNIEXPORT void JNICALL Java_com_synaptic_ai_llm_LlamaJNI_stopGeneration(JNIEnv*, jobject) { g_abort.store(true); }
JNIEXPORT void JNICALL Java_com_synaptic_ai_llm_LlamaJNI_freeModel(JNIEnv*, jobject) { std::lock_guard<std::mutex> lock(g_stateMutex); freeStateLocked(); }
JNIEXPORT jboolean JNICALL Java_com_synaptic_ai_llm_LlamaJNI_isLoaded(JNIEnv*, jobject) { return g_state.loaded; }
JNIEXPORT void JNICALL Java_com_synaptic_ai_llm_LlamaJNI_clearCache(JNIEnv*, jobject) { if (g_state.ctx) llama_memory_clear(llama_get_memory(g_state.ctx), true); g_state.n_past = 0; }

}








