package com.synaptic.ai.llm

/** JNI bridge ke llama.cpp native library */
class LlamaJNI {

    interface StreamCallback {
        fun onToken(token: String)
        fun onComplete()
        fun onError(message: String)
    }

    companion object {
        init {
            System.loadLibrary("llamajni")
        }
    }

    external fun loadModel(modelPath: String, tryGpu: Boolean): Boolean
    external fun generateStream(prompt: String, grammar: String?, maxTokens: Int, callback: StreamCallback)
    external fun freeModel()
    external fun isLoaded(): Boolean
    external fun clearCache()
    external fun stopGeneration()
}
