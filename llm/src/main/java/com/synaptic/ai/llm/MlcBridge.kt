package com.synaptic.ai.llm

// Adapter that delegates to MlcJNI (native implementation not yet added)
class MlcBridge : NativeBridge {
    private val impl = MlcJNI()

    override fun loadModel(modelPath: String, tryGpu: Boolean, nCtx: Int): Boolean = impl.loadModel(modelPath, tryGpu, nCtx)
    override fun generateStream(prompt: String, grammar: String?, maxTokens: Int, callback: LlamaJNI.StreamCallback) {
        // adapt callback types between MlcJNI and Llama-style StreamCallback
        val cb = object : MlcJNI.StreamCallback {
            override fun onToken(token: String) { callback.onToken(token) }
            override fun onComplete() { callback.onComplete() }
            override fun onError(message: String) { callback.onError(message) }
        }
        impl.generateStream(prompt, grammar, maxTokens, cb)
    }
    override fun freeModel() = impl.freeModel()
    override fun isLoaded(): Boolean = impl.isLoaded()
    override fun clearCache() = impl.clearCache()
    override fun stopGeneration() = impl.stopGeneration()
}
