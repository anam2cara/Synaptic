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

        @JvmStatic
        fun addBreadcrumb(event: String, metadata: String?) {
            com.synaptic.ai.diagnostic.DiagnosticManager.addBreadcrumb("NATIVE_$event", metadata)
        }
    }

    data class ModelMetadata(
        val totalSize: Long,
        val nParams: Long,
        val description: String,
        val nLayer: Int,
        val nCtxTrain: Int
    )

    external fun getModelMetadata(modelPath: String): ModelMetadata?
    external fun loadModel(modelPath: String, nGpuLayers: Int, nCtx: Int): Boolean
    external fun generateStream(prompt: String, grammar: String?, maxTokens: Int, callback: StreamCallback)
    external fun freeModel()
    external fun isLoaded(): Boolean
    external fun clearCache()
    external fun stopGeneration()
}
