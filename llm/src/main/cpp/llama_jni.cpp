#include <jni.h>
#include <sys/stat.h>
#include <string>
#include <vector>
#include <thread>
#include <atomic>
#include <mutex>
#include <algorithm>
#include <cstring>
#include <stdexcept>
#include <android/log.h>
#include "llama.h"
#include "ggml.h"
#include "ggml-backend.h"
#include <sched.h>

#define TAG "SynapticJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Redirect logging internal ggml/llama.cpp (default-nya fprintf ke stderr, muncul di
// logcat tanpa tag/format yang konsisten) ke __android_log_print dengan TAG yang sama
// supaya seluruh log inferensi LLM murni lewat satu jalur logcat yang rapi.
static void ggmlAndroidLogCallback(enum ggml_log_level level, const char * text, void * /*user_data*/) {
    if (!text) return;
    size_t len = strlen(text);
    while (len > 0 && (text[len - 1] == '\n' || text[len - 1] == '\r')) len--;
    if (len == 0) return;

    android_LogPriority prio;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_INFO:  prio = ANDROID_LOG_INFO;  break;
        case GGML_LOG_LEVEL_DEBUG: prio = ANDROID_LOG_DEBUG; break;
        default: return; // GGML_LOG_LEVEL_NONE / GGML_LOG_LEVEL_CONT diabaikan
    }
    __android_log_print(prio, TAG, "%.*s", (int) len, text);
}

static std::once_flag g_nativeLogInitFlag;
static void ensureNativeLoggingInstalled() {
    std::call_once(g_nativeLogInitFlag, []() {
        llama_log_set(ggmlAndroidLogCallback, nullptr);
        ggml_log_set(ggmlAndroidLogCallback, nullptr);
    });
}

static void nativeBreadcrumb(JNIEnv* env, const char* event, const char* metadata = nullptr) {
    jclass cls = env->FindClass("com/synaptic/ai/llm/LlamaJNI");
    if (!cls) return;
    jmethodID mid = env->GetStaticMethodID(cls, "addBreadcrumb", "(Ljava/lang/String;Ljava/lang/String;)V");
    if (!mid) return;
    jstring jevent = env->NewStringUTF(event);
    jstring jmeta = metadata ? env->NewStringUTF(metadata) : nullptr;
    env->CallStaticVoidMethod(cls, mid, jevent, jmeta);
    if (jevent) env->DeleteLocalRef(jevent);
    if (jmeta) env->DeleteLocalRef(jmeta);
}

struct LlamaState {
    llama_model   * model   = nullptr;
    llama_context * ctx     = nullptr;
    llama_sampler * sampler = nullptr;
    bool            loaded  = false;
    int32_t         n_past  = 0;
};

static LlamaState g_state;
static std::recursive_mutex g_stateMutex;
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

JNIEXPORT jobject JNICALL
Java_com_synaptic_ai_llm_LlamaJNI_getModelMetadata(JNIEnv* env, jobject, jstring modelPath) {
    nativeBreadcrumb(env, "GET_METADATA_START");

    if (modelPath == nullptr) {
        nativeBreadcrumb(env, "GET_METADATA_ERROR", "null model path");
        return nullptr;
    }

    const char* path = env->GetStringUTFChars(modelPath, nullptr);

    if (path == nullptr) {
        nativeBreadcrumb(env, "GET_METADATA_ERROR", "GetStringUTFChars failed");
        return nullptr;
    }

    struct stat st {};
    const int statResult = stat(path, &st);

    env->ReleaseStringUTFChars(modelPath, path);

    if (statResult != 0 || st.st_size < 0) {
        nativeBreadcrumb(env, "GET_METADATA_ERROR", "cannot stat model file");
        return nullptr;
    }

    jclass cls = env->FindClass("com/synaptic/ai/llm/LlamaJNI$ModelMetadata");
    if (!cls) {
        nativeBreadcrumb(env, "GET_METADATA_ERROR", "ModelMetadata class not found");
        return nullptr;
    }

    jmethodID constructor = env->GetMethodID(
        cls,
        "<init>",
        "(JJLjava/lang/String;II)V"
    );

    if (!constructor) {
        nativeBreadcrumb(env, "GET_METADATA_ERROR", "ModelMetadata constructor not found");
        return nullptr;
    }

    jstring jdesc = env->NewStringUTF("Local model");

    if (!jdesc) {
        nativeBreadcrumb(env, "GET_METADATA_ERROR", "NewStringUTF failed");
        return nullptr;
    }

    jobject obj = env->NewObject(
        cls,
        constructor,
        static_cast<jlong>(st.st_size),
        static_cast<jlong>(0),
        jdesc,
        static_cast<jint>(0),
        static_cast<jint>(0)
    );

    env->DeleteLocalRef(jdesc);

    if (!obj) {
        nativeBreadcrumb(env, "GET_METADATA_ERROR", "NewObject failed");
        return nullptr;
    }

    nativeBreadcrumb(env, "GET_METADATA_SUCCESS");
    return obj;
}
JNIEXPORT jboolean JNICALL
Java_com_synaptic_ai_llm_LlamaJNI_loadModel(JNIEnv* env, jobject, jstring modelPath, jint nGpuLayers, jint nCtx) {
    nativeBreadcrumb(env, "LOAD_START");
    try {
        std::lock_guard<std::recursive_mutex> lock(g_stateMutex);
        const char* path = env->GetStringUTFChars(modelPath, nullptr);
        freeStateLocked();

        ensureNativeLoggingInstalled();
        LOGI("[STAGE: LOAD] JNI_DEVICE_AWARE_1.0 - Ctx: %d, GPU Layers: %d", nCtx, nGpuLayers);
        llama_backend_init();

        ggml_backend_load_all();

        llama_model_params mparams = llama_model_default_params();
        mparams.use_mmap = true;
        mparams.use_mlock = false;

        mparams.n_gpu_layers = nGpuLayers;

        if (nGpuLayers > 0) {
             LOGI("[VULKAN] Probing for GPU device...");
             ggml_backend_dev_t vulkanDevice = nullptr;
             for (size_t i = 0; i < ggml_backend_reg_count() && vulkanDevice == nullptr; ++i) {
                ggml_backend_reg_t reg = ggml_backend_reg_get(i);
                if (!reg) continue;
                const char * backendName = ggml_backend_reg_name(reg);
                if (!backendName || std::string(backendName) != "Vulkan") continue;
                if (ggml_backend_reg_dev_count(reg) > 0) {
                    vulkanDevice = ggml_backend_reg_dev_get(reg, 0);
                    break;
                }
            }
            if (vulkanDevice) {
                ggml_backend_dev_t devices[] = { vulkanDevice, nullptr };
                mparams.devices = devices;
                LOGI("[VULKAN] Explicitly using device: %s", ggml_backend_dev_name(vulkanDevice));
            }
        }

        g_state.model = llama_model_load_from_file(path, mparams);
        env->ReleaseStringUTFChars(modelPath, path);
        if (!g_state.model) {
            LOGE("[ERROR] llama_model_load_from_file failed");
            return JNI_FALSE;
        }

        llama_context_params cparams = llama_context_default_params();
        cparams.n_ctx   = nCtx > 0 ? nCtx : 512;
        cparams.n_threads = 4;
        cparams.n_threads_batch = 4;
        cparams.offload_kqv = true;

        cpu_set_t perfMask; CPU_ZERO(&perfMask); CPU_SET(4,&perfMask); CPU_SET(5,&perfMask); CPU_SET(6,&perfMask); CPU_SET(7,&perfMask);
        if (sched_setaffinity(0, sizeof(perfMask), &perfMask) != 0) { LOGE("[WARN] sched_setaffinity perf-core pin failed"); }
        g_state.ctx = llama_init_from_model(g_state.model, cparams);
        if (!g_state.ctx) {
            LOGE("[ERROR] llama_init_from_model failed");
            freeStateLocked();
            return JNI_FALSE;
        }

        g_state.loaded = true;
        LOGI("[STAGE: LOAD] Success.");
        nativeBreadcrumb(env, "LOAD_SUCCESS");
        return JNI_TRUE;
    } catch (const std::exception& e) {
        LOGE("[CRITICAL] Native exception in loadModel: %s", e.what());
        nativeBreadcrumb(env, "LOAD_EXCEPTION", e.what());
        return JNI_FALSE;
    } catch (...) {
        LOGE("[CRITICAL] Unknown native error in loadModel");
        nativeBreadcrumb(env, "LOAD_ERROR_UNKNOWN");
        return JNI_FALSE;
    }
}

JNIEXPORT void JNICALL
Java_com_synaptic_ai_llm_LlamaJNI_generateStream(JNIEnv* env, jobject, jstring prompt, jstring grammar, jint maxTokens, jobject callback) {
    nativeBreadcrumb(env, "GENERATE_START");
    try {
        std::unique_lock<std::recursive_mutex> lock(g_stateMutex);
        jclass cbClass = env->GetObjectClass(callback);
        jmethodID onToken = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
        jmethodID onComplete = env->GetMethodID(cbClass, "onComplete", "()V");
        jmethodID onError = env->GetMethodID(cbClass, "onError", "(Ljava/lang/String;)V");

        auto emitError = [&](const char * message) {
            if (onError) {
                jstring jm = env->NewStringUTF(message);
                if (jm) {
                    env->CallVoidMethod(callback, onError, jm);
                    env->DeleteLocalRef(jm);
                }
            }
        };

        if (!g_state.loaded) {
            emitError("Model native belum dimuat");
            env->DeleteLocalRef(cbClass);
            return;
        }

        const char* pStr = env->GetStringUTFChars(prompt, nullptr);
        const llama_vocab* vocab = llama_model_get_vocab(g_state.model);

        std::vector<llama_token> tokens(strlen(pStr) + 64);
        int n_tokens = llama_tokenize(vocab, pStr, strlen(pStr), tokens.data(), tokens.size(), true, true);
        env->ReleaseStringUTFChars(prompt, pStr);

        const int n_ctx = llama_n_ctx(g_state.ctx);
        if (n_tokens > n_ctx - 64) {
            int to_remove = n_tokens - (n_ctx - 64);
            tokens.erase(tokens.begin(), tokens.begin() + to_remove);
            n_tokens = tokens.size();
            LOGI("[STAGE: TOKENIZE] Prompt too large, pruned %d tokens", to_remove);
        }

        if (n_tokens <= 0) {
            emitError("Tokenisasi prompt gagal");
            env->DeleteLocalRef(cbClass);
            return;
        }

        if (g_state.sampler) llama_sampler_free(g_state.sampler);

        // Buat sampler chain yang jauh lebih cerdas (Robus Sampling)
        llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
        // Sampler Sederhana (Paling Stabil untuk Android)
        g_state.sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
        llama_sampler_chain_add(g_state.sampler, llama_sampler_init_temp(0.7f));
        llama_sampler_chain_add(g_state.sampler, llama_sampler_init_dist(time(NULL)));

        llama_memory_clear(llama_get_memory(g_state.ctx), true);
        g_state.n_past = 0;

        // Gunakan chunk size lebih kecil (8) untuk stabilitas GPU di Android 15
        constexpr int PREFILL_CHUNK_SIZE = 8;
        int processed = 0;

        while (processed < n_tokens) {
            const int chunkSize = std::min(PREFILL_CHUNK_SIZE, n_tokens - processed);
            llama_batch batch = llama_batch_init(chunkSize, 0, 1);
            batch.n_tokens = chunkSize;

            for (int i = 0; i < chunkSize; i++) {
                const int tokenIndex = processed + i;
                batch.token[i] = tokens[tokenIndex];
                batch.pos[i] = tokenIndex;
                batch.n_seq_id[i] = 1;
                batch.seq_id[i][0] = 0;
                batch.logits[i] = (tokenIndex == n_tokens - 1);
            }

            const int decodeResult = llama_decode(g_state.ctx, batch);
            llama_batch_free(batch);

            if (decodeResult != 0) {
                LOGE("[STAGE: PREFILL] llama_decode gagal pada token %d, ret=%d", processed, decodeResult);
                emitError("Native prefill gagal (Vulkan Error?)");
                env->DeleteLocalRef(cbClass);
                return;
            }

            processed += chunkSize;
            LOGI("[STAGE: PREFILL] processed=%d/%d", processed, n_tokens);
            // Berikan nafas kecil pada thread agar GPU tidak terblokir total
            std::this_thread::yield();
        }

        g_state.n_past = n_tokens;
        g_abort.store(false);
        nativeBreadcrumb(env, "PREFILL_SUCCESS");
        std::string pending;
        llama_batch s_batch = llama_batch_init(1, 0, 1);
        s_batch.n_tokens = 1;
        s_batch.n_seq_id[0] = 1;
        s_batch.seq_id[0][0] = 0;
        s_batch.logits[0] = true;

        const int generationLimit = std::min(std::max(static_cast<int>(maxTokens), 1), 1024);

        for (int i = 0; i < generationLimit; i++) {
            if (g_abort.load()) break;
            if (!g_state.sampler) {
                LOGE("[CRITICAL] g_state.sampler is null before sampling");
                emitError("Native sampler not initialized");
                env->DeleteLocalRef(cbClass);
                return;
            }
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
            if (llama_decode(g_state.ctx, s_batch) != 0) {
                emitError("Native decode gagal saat generate");
                llama_batch_free(s_batch);
                env->DeleteLocalRef(cbClass);
                return;
            }
        }
        llama_batch_free(s_batch);
        LOGI("[STAGE: GEN] Selesai.");
        nativeBreadcrumb(env, "GENERATE_SUCCESS");
        env->CallVoidMethod(callback, onComplete);
        env->DeleteLocalRef(cbClass);
    } catch (const std::exception& e) {
        LOGE("[CRITICAL] Native exception in generateStream: %s", e.what());
        nativeBreadcrumb(env, "GENERATE_EXCEPTION", e.what());
        // Emit error via callback if possible
        jclass cbClass = env->GetObjectClass(callback);
        jmethodID onError = env->GetMethodID(cbClass, "onError", "(Ljava/lang/String;)V");
        if (onError) {
            jstring jm = env->NewStringUTF(e.what());
            if (jm) { env->CallVoidMethod(callback, onError, jm); env->DeleteLocalRef(jm); }
        }
        env->DeleteLocalRef(cbClass);
    } catch (...) {
        LOGE("[CRITICAL] Unknown native error in generateStream");
    }
}

JNIEXPORT void JNICALL Java_com_synaptic_ai_llm_LlamaJNI_stopGeneration(JNIEnv*, jobject) { g_abort.store(true); }
JNIEXPORT void JNICALL Java_com_synaptic_ai_llm_LlamaJNI_freeModel(JNIEnv*, jobject) { std::lock_guard<std::recursive_mutex> lock(g_stateMutex); freeStateLocked(); }
JNIEXPORT jboolean JNICALL Java_com_synaptic_ai_llm_LlamaJNI_isLoaded(JNIEnv*, jobject) { return g_state.loaded; }
JNIEXPORT void JNICALL Java_com_synaptic_ai_llm_LlamaJNI_clearCache(JNIEnv*, jobject) { if (g_state.ctx) llama_memory_clear(llama_get_memory(g_state.ctx), true); g_state.n_past = 0; }

}








