package com.synaptic.ai.llm

interface NativeBridge {
    fun loadModel(modelPath: String, tryGpu: Boolean, nCtx: Int): Boolean
    fun generateStream(prompt: String, grammar: String?, maxTokens: Int, callback: LlamaJNI.StreamCallback)
    fun freeModel()
    fun isLoaded(): Boolean
    fun clearCache()
    fun stopGeneration()
}
