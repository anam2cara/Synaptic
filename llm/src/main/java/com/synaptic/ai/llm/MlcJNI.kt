package com.synaptic.ai.llm

/** JNI bridge stub for MLC engine (native implementation to be added) */
class MlcJNI {

    interface StreamCallback {
        fun onToken(token: String)
        fun onComplete()
        fun onError(message: String)
    }

    companion object {
        init {
            // Placeholder: native library for MLC should be named "mlcjni" and provided later
            try {
                System.loadLibrary("mlcjni")
            } catch (e: UnsatisfiedLinkError) {
                // library not present yet; calls will fail with clear error from native layer
            }
        }
    }

    external fun loadModel(modelPath: String, tryGpu: Boolean, nCtx: Int): Boolean
    external fun generateStream(prompt: String, grammar: String?, maxTokens: Int, callback: MlcJNI.StreamCallback)
    external fun freeModel()
    external fun isLoaded(): Boolean
    external fun clearCache()
    external fun stopGeneration()
}
