#include <jni.h>
#include <string>
#include <android/log.h>

#define TAG "MlcJNIStub"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jboolean JNICALL Java_com_synaptic_ai_llm_MlcJNI_loadModel(JNIEnv* env, jobject, jstring modelPath, jboolean, jint) {
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    LOGE("MLC native engine not available. Requested load: %s", path);
    env->ReleaseStringUTFChars(modelPath, path);
    return JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_synaptic_ai_llm_MlcJNI_generateStream(JNIEnv* env, jobject, jstring, jstring, jint, jobject callback) {
    // Try to call onError(callback, "...") so Java side receives an immediate failure
    if (callback == nullptr) return;
    jclass cbClass = env->GetObjectClass(callback);
    if (cbClass == nullptr) return;
    jmethodID onError = env->GetMethodID(cbClass, "onError", "(Ljava/lang/String;)V");
    if (!onError) return;
    jstring msg = env->NewStringUTF("MLC native engine not built into the APK. Please add libmlcjni.so or compile native MLC.");
    env->CallVoidMethod(callback, onError, msg);
    env->DeleteLocalRef(msg);
}

JNIEXPORT void JNICALL Java_com_synaptic_ai_llm_MlcJNI_freeModel(JNIEnv*, jobject) {
    LOGI("freeModel called on MLC stub (no-op)");
}

JNIEXPORT jboolean JNICALL Java_com_synaptic_ai_llm_MlcJNI_isLoaded(JNIEnv*, jobject) {
    return JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_synaptic_ai_llm_MlcJNI_clearCache(JNIEnv*, jobject) {
    LOGI("clearCache called on MLC stub (no-op)");
}

JNIEXPORT void JNICALL Java_com_synaptic_ai_llm_MlcJNI_stopGeneration(JNIEnv*, jobject) {
    LOGI("stopGeneration called on MLC stub (no-op)");
}

}
