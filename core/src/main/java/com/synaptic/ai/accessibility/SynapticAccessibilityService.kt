package com.synaptic.ai.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class SynapticAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
    }

    override fun onServiceConnected() {
        instance = this
    }

    fun dumpCurrentScreen(): String {
        val root = rootInActiveWindow ?: return "NO_ACTIVE_WINDOW"
        val nodes = mutableListOf<String>()
        val packageName = root.packageName?.toString() ?: "unknown"
        
        nodes.add("[Aplikasi Aktif: $packageName]")

        val startTime = System.currentTimeMillis()
        val TIMEOUT = 2000L // Maksimal 2 detik untuk dump layar

        fun walk(node: AccessibilityNodeInfo?) {
            // Proteksi: Berhenti jika sudah terlalu banyak node atau waktu habis
            // Kurangi ke 50 node terpenting saja untuk stabilitas LLM Lokal
            if (node == null || nodes.size > 50 || (System.currentTimeMillis() - startTime) > TIMEOUT) return
            
            if (node.isVisibleToUser) {
                val text = node.text?.toString()
                val description = node.contentDescription?.toString()
                val className = node.className?.toString()?.substringAfterLast('.') ?: ""
                
                val info = text ?: description ?: ""
                
                if (info.isNotBlank()) {
                    val label = if (text == null && description != null) "($className: $description)" else info
                    nodes.add(label)
                }
            }
            
            for (i in 0 until node.childCount) {
                walk(node.getChild(i))
            }
        }
        walk(root)
        return nodes.distinct().joinToString("\n")
    }

    companion object {
        @Volatile
        var instance: SynapticAccessibilityService? = null
    }
}
