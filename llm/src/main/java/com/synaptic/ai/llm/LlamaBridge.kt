package com.synaptic.ai.llm

// Simple adapter that delegates to existing LlamaJNI implementation
class LlamaBridge : NativeBridge {
    private val impl = LlamaJNI()

    override fun getModelMetadata(modelPath: String) = impl.getModelMetadata(modelPath)
    override fun loadModel(modelPath: String, nGpuLayers: Int, nCtx: Int): Boolean = impl.loadModel(modelPath, nGpuLayers, nCtx)
    override fun generateStream(prompt: String, grammar: String?, maxTokens: Int, callback: LlamaJNI.StreamCallback) = impl.generateStream(prompt, grammar, maxTokens, callback)
    override fun freeModel() = impl.freeModel()
    override fun isLoaded(): Boolean = impl.isLoaded()
    override fun clearCache() = impl.clearCache()
    override fun stopGeneration() = impl.stopGeneration()
}