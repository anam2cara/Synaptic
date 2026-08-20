package com.synaptic.ai.monitor

data class ProcessInfo(
    val pid: Int,
    val name: String,
    val memoryKb: Long
)
