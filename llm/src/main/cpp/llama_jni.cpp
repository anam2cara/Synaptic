#include <jni.h>
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

// Stored model path & requested GPU preference (used for CPU fallback)
static std::string g_model_path;
static bool g_try_gpu_requested = false;

static void freeStateLocked() {
    if (g_state.sampler) { llama_sampler_free(g_state.sampler); g_state.sampler = nullptr; }
    if (g_state.ctx)     { llama_free(g_state.ctx);             g_state.ctx     = nullptr; }
    if (g_state.model)   { llama_model_free(g_state.model);     g_state.model   = nullptr; }
    g_state.loaded = false;
    g_state.n_past = 0;
}

static bool reloadModelCPU(int nCtx) {
    try {
        std::lock_guard<std::mutex> lock(g_stateMutex);
        freeStateLocked();

        llama_backend_init();
        ggml_backend_load_all();

        llama_model_params mparams = llama_model_default_params();
        mparams.use_mmap = true;
        mparams.n_gpu_layers = 0; // force CPU

        g_state.model = llama_model_load_from_file(g_model_path.c_str(), mparams);
        if (!g_state.model) return false;

        llama_context_params cparams = llama_context_default_params();
        cparams.n_ctx = nCtx > 0 ? nCtx : 1792;
        cparams.n_threads = 4;
        cparams.n_threads_batch = 4;

        g_state.ctx = llama_init_from_model(g_state.model, cparams);
        if (!g_state.ctx) { freeStateLocked(); return false; }

        g_state.loaded = true;
        LOGI("[FALLBACK] Model reloaded on CPU (n_gpu_layers=0)");
        return true;
    } catch (const std::exception &e) {
        LOGE("[FALLBACK] reloadModelCPU exception: %s", e.what());
        return false;
    } catch (...) {
        LOGE("[FALLBACK] reloadModelCPU unknown exception");
        return false;
    }
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
Java_com_synaptic_ai_llm_LlamaJNI_loadModel(JNIEnv* env, jobject, jstring modelPath, jboolean tryGpu, jint nCtx) {
    try {
        std::lock_guard<std::mutex> lock(g_stateMutex);
        const char* path = env->GetStringUTFChars(modelPath, nullptr);
        freeStateLocked();
                // Store model path and requested GPU preference for possible fallback
                g_model_path = std::string(path);
                g_try_gpu_requested = tryGpu ? true : false;

        LOGI("[STAGE: LOAD] JNI_SAFE_MODE_4.0 - Adaptive Ctx: %d", nCtx);
        llama_backend_init();

        ggml_backend_load_all();

        llama_model_params mparams = llama_model_default_params();
        mparams.use_mmap = true;

        // Restore GPU enable path: tryGpu controls GPU usage. Keep conservative default layers.
        if (tryGpu) {
             LOGI("[VULKAN] Mencoba inisialisasi GPU...");
             // Beberapa chip mid-range mungkin bermasalah; gunakan sedikit layer terlebih dahulu
             mparams.n_gpu_layers = 4; // minimal layer untuk tes stabilitas
        } else {
             mparams.n_gpu_layers = 0;
        }

        g_state.model = llama_model_load_from_file(path, mparams);
        env->ReleaseStringUTFChars(modelPath, path);
        if (!g_state.model) return JNI_FALSE;

        llama_context_params cparams = llama_context_default_params();
        cparams.n_ctx   = nCtx > 0 ? nCtx : 1792;
        cparams.n_threads = 4;
        cparams.n_threads_batch = 4;

        g_state.ctx = llama_init_from_model(g_state.model, cparams);
        if (!g_state.ctx) {
            freeStateLocked();
            return JNI_FALSE;
        }

        g_state.loaded = true;
        LOGI("[STAGE: LOAD] Success. gpu_layers=%d", mparams.n_gpu_layers);
        return JNI_TRUE;
    } catch (const std::exception& e) {
        LOGE("[CRITICAL] Native exception in loadModel: %s", e.what());
        return JNI_FALSE;
    } catch (...) {
        LOGE("[CRITICAL] Unknown native error in loadModel");
        return JNI_FALSE;
    }
}

JNIEXPORT void JNICALL
Java_com_synaptic_ai_llm_LlamaJNI_generateStream(JNIEnv* env, jobject, jstring prompt, jstring grammar, jint maxTokens, jobject callback) {
    try {
        std::unique_lock<std::mutex> lock(g_stateMutex);
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
        g_state.sampler = llama_sampler_chain_init(sparams);
        if (!g_state.sampler) {
            LOGE("[CRITICAL] sampler init failed");
            emitError("Native sampler init failed");
            env->DeleteLocalRef(cbClass);
            return;
        }

        // 1. Repeat Penalty (Pencegah loop/kacau)
        llama_sampler_chain_add(g_state.sampler, llama_sampler_init_penalties(
            64,   // last_n_tokens
            1.1f, // penalty_repeat
            0.0f, // penalty_freq
            0.0f  // penalty_present
        ));

        // 2. Top-K, Top-P, Min-P (Filter probabilitas)
        llama_sampler_chain_add(g_state.sampler, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(g_state.sampler, llama_sampler_init_top_p(0.95f, 1));
        llama_sampler_chain_add(g_state.sampler, llama_sampler_init_min_p(0.05f, 1));

        // 3. Temperature
        llama_sampler_chain_add(g_state.sampler, llama_sampler_init_temp(0.7f));

        // 4. Grammar Enforcement (Jika ada)
        if (grammar != nullptr) {
            const char* g_str = env->GetStringUTFChars(grammar, nullptr);
            if (strlen(g_str) > 0) {
                // Catatan: llama_sampler_init_grammar_simple tersedia di API terbaru llama.cpp
                // Jika tidak ada, kita bisa memakai llama_grammar_init secara manual.
                // Untuk stabilitas, kita pakai sampler grammar jika didukung.
                llama_sampler_chain_add(g_state.sampler, llama_sampler_init_grammar(vocab, g_str, "root"));
            }
            env->ReleaseStringUTFChars(grammar, g_str);
        }

        // 5. Terakhir: Distribusi/Seed
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

            int decodeResult = 0;
            try {
                decodeResult = llama_decode(g_state.ctx, batch);
            } catch (const std::exception &e) {
                std::string why = e.what();
                LOGE("[CRITICAL] llama_decode threw: %s", why.c_str());
                if (why.find("DeviceLost") != std::string::npos || why.find("vk::DeviceLost") != std::string::npos) {
                    LOGE("[FALLBACK] Vulkan DeviceLost detected — attempting CPU reload");
                    // try reload on CPU
                    if (reloadModelCPU(n_ctx)) {
                        LOGI("[FALLBACK] Reloaded model on CPU, retrying decode");
                        // retry decode on CPU
                        try {
                            decodeResult = llama_decode(g_state.ctx, batch);
                        } catch (const std::exception &e2) {
                            LOGE("[FALLBACK] retry llama_decode failed: %s", e2.what());
                            emitError("Native decode failed after CPU fallback");
                            llama_batch_free(batch);
                            env->DeleteLocalRef(cbClass);
                            return;
                        }
                    } else {
                        LOGE("[FALLBACK] reloadModelCPU failed");
                        emitError("DeviceLost and CPU reload failed");
                        llama_batch_free(batch);
                        env->DeleteLocalRef(cbClass);
                        return;
                    }
                } else {
                    // propagate as native error
                    LOGE("[CRITICAL] Non-Vulkan exception in llama_decode: %s", why.c_str());
                    emitError("Native decode exception: check logs");
                    llama_batch_free(batch);
                    env->DeleteLocalRef(cbClass);
                    return;
                }
            }

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
            try {
                if (llama_decode(g_state.ctx, s_batch) != 0) {
                    emitError("Native decode gagal saat generate");
                    llama_batch_free(s_batch);
                    env->DeleteLocalRef(cbClass);
                    return;
                }
            } catch (const std::exception &e) {
                std::string why = e.what();
                LOGE("[CRITICAL] llama_decode threw during generate: %s", why.c_str());
                if (why.find("DeviceLost") != std::string::npos || why.find("vk::DeviceLost") != std::string::npos) {
                    LOGE("[FALLBACK] Vulkan DeviceLost detected during generate — attempting CPU reload");
                    if (reloadModelCPU(n_ctx)) {
                        LOGI("[FALLBACK] Reloaded model on CPU — aborting this generate call, please retry");
                        emitError("Device lost: switched to CPU, retry generate");
                        llama_batch_free(s_batch);
                        env->DeleteLocalRef(cbClass);
                        return;
                    } else {
                        LOGE("[FALLBACK] reloadModelCPU failed during generate");
                        emitError("DeviceLost and CPU reload failed");
                        llama_batch_free(s_batch);
                        env->DeleteLocalRef(cbClass);
                        return;
                    }
                } else {
                    LOGE("[CRITICAL] Non-Vulkan exception in llama_decode during generate: %s", why.c_str());
                    emitError("Native decode exception during generate: check logs");
                    llama_batch_free(s_batch);
                    env->DeleteLocalRef(cbClass);
                    return;
                }
            }
        }
        llama_batch_free(s_batch);
        LOGI("[STAGE: GEN] Selesai.");
        env->CallVoidMethod(callback, onComplete);
        env->DeleteLocalRef(cbClass);
    } catch (const std::exception& e) {
        LOGE("[CRITICAL] Native exception in generateStream: %s", e.what());
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
JNIEXPORT void JNICALL Java_com_synaptic_ai_llm_LlamaJNI_freeModel(JNIEnv*, jobject) { std::lock_guard<std::mutex> lock(g_stateMutex); freeStateLocked(); }
JNIEXPORT jboolean JNICALL Java_com_synaptic_ai_llm_LlamaJNI_isLoaded(JNIEnv*, jobject) { return g_state.loaded; }
JNIEXPORT void JNICALL Java_com_synaptic_ai_llm_LlamaJNI_clearCache(JNIEnv*, jobject) { if (g_state.ctx) llama_memory_clear(llama_get_memory(g_state.ctx), true); g_state.n_past = 0; }

}








