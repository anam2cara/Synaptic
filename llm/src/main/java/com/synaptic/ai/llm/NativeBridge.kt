package com.synaptic.ai.llm

interface NativeBridge {
    fun getModelMetadata(modelPath: String): LlamaJNI.ModelMetadata?
    fun loadModel(modelPath: String, nGpuLayers: Int, nCtx: Int): Boolean
    fun generateStream(prompt: String, grammar: String?, maxTokens: Int, callback: LlamaJNI.StreamCallback)
    fun freeModel()
    fun isLoaded(): Boolean
    fun clearCache()
    fun stopGeneration()
}
