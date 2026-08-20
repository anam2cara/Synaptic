package com.synaptic.ai.llm

import com.synaptic.ai.data.model.ChatMessage

/**
 * Membantu meringkas dan memotong konteks (Context Pruning) ala Hermes Agent
 * untuk mengurangi beban CPU dan mempercepat TTFT.
 */
object ContextEngine {

    private const val MAX_TOOL_OUTPUT_CHARS = 1200
    private const val MAX_HISTORY_RELEVANT_TURNS = 6

    // Daftar kata kunci "sampah" yang sering muncul di Accessibility Service
    private val SCREEN_JUNK_KEYWORDS = setOf(
        "unlabeled", "button", "divider", "spacer", "image", "icon",
        "container", "layout", "nav_bar", "status_bar"
    )

    /**
     * Memotong output tool yang terlalu panjang atau tidak relevan.
     */
    fun pruneToolResult(toolName: String, output: String): String {
        if (output.isBlank()) return "(Tanpa output)"
        
        return when (toolName) {
            "read_logs" -> {
                // Hanya ambil baris error/warning yang unik dan tidak mengandung noise sistem umum
                output.lineSequence()
                    .filter { line -> 
                        (line.contains("E/") || line.contains("W/")) &&
                        !line.contains("type=1400") && // Audit logs noise
                        !line.contains("Choreographer") // UI skip frame noise
                    }
                    .distinct()
                    .take(25)
                    .joinToString("\n") + "\n[... log teknis diringkas ...]"
            }
            "list_processes" -> {
                // Ambil header dan 12 proses teratas (biasanya sudah disortir di tool)
                output.lineSequence().take(13).joinToString("\n") + "\n[... daftar proses dipangkas ...]"
            }
            "read_screen" -> {
                // Header aplikasi aktif harus selalu ada
                val appHeader = output.lineSequence().firstOrNull { it.startsWith("[Aplikasi") } ?: ""
                
                // Filter elemen layar: Buang teks sampah dan ambil yang bermakna
                val screenContent = output.lineSequence()
                    .filter { !it.startsWith("[Aplikasi") }
                    .map { it.trim() }
                    .filter { it.length > 2 }
                    .filter { text -> 
                        val lower = text.lowercase()
                        SCREEN_JUNK_KEYWORDS.none { lower == it } // Hanya buang jika SAMA PERSIS dengan kata sampah
                    }
                    .distinct()
                    .take(35)
                    .joinToString(" | ")

                if (screenContent.isEmpty()) {
                    "$appHeader\nLayar kosong atau tidak terbaca."
                } else {
                    "$appHeader\nIsi Layar: $screenContent\n[... diringkas ...]"
                }
            }
            "device_status" -> {
                // Status device biasanya pendek, tapi pastikan tidak melebihi limit
                output.take(MAX_TOOL_OUTPUT_CHARS)
            }
            else -> {
                if (output.length > MAX_TOOL_OUTPUT_CHARS) {
                    output.take(MAX_TOOL_OUTPUT_CHARS) + "...\n[Dipotong karena terlalu panjang]"
                } else output
            }
        }
    }

    /**
     * Meringkas riwayat percakapan secara cerdas.
     */
    fun compressHistory(messages: List<ChatMessage>): List<ChatMessage> {
        if (messages.size <= MAX_HISTORY_RELEVANT_TURNS + 2) return messages

        val result = mutableListOf<ChatMessage>()
        
        // 1. Selalu simpan pesan pertama (Instruksi awal user)
        result.add(messages.first())
        
        // 2. Cari pesan penting di tengah (yang mengandung tool_call)
        val importantMiddle = messages.subList(1, messages.size - MAX_HISTORY_RELEVANT_TURNS)
            .filter { it.role == "tool_call" || it.role == "tool_result" }
            .takeLast(2) // Ambil maksimal 2 aksi terakhir yang relevan

        if (importantMiddle.isNotEmpty()) {
            result.add(ChatMessage("", "system", "[... beberapa percakapan awal diringkas ...]"))
            result.addAll(importantMiddle)
        }

        // 3. Tambahkan placeholder ringkasan jika gap masih besar
        result.add(ChatMessage("", "system", "[... konteks riwayat disederhanakan untuk efisiensi RAM ...]"))

        // 4. Selalu simpan N pesan terakhir (konteks aktif)
        result.addAll(messages.takeLast(MAX_HISTORY_RELEVANT_TURNS))
        
        return result
    }
}
