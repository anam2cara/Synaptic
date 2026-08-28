package com.synaptic.ai.llm

// Simple adapter that delegates to existing LlamaJNI implementation
class LlamaBridge : NativeBridge {
    private val impl = LlamaJNI()

    override fun loadModel(modelPath: String, tryGpu: Boolean, nCtx: Int): Boolean = impl.loadModel(modelPath, tryGpu, nCtx)
    override fun generateStream(prompt: String, grammar: String?, maxTokens: Int, callback: LlamaJNI.StreamCallback) = impl.generateStream(prompt, grammar, maxTokens, callback)
    override fun freeModel() = impl.freeModel()
    override fun isLoaded(): Boolean = impl.isLoaded()
    override fun clearCache() = impl.clearCache()
    override fun stopGeneration() = impl.stopGeneration()
}
