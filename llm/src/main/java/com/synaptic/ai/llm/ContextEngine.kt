package com.synaptic.ai.llm

import com.synaptic.ai.data.model.ChatMessage
import com.synaptic.ai.data.model.Memory

/**
 * Membantu meringkas dan memotong konteks (Context Pruning) ala Hermes Agent
 * untuk mengurangi beban CPU dan mempercepat TTFT.
 */
object ContextEngine {

    private const val MAX_TOOL_OUTPUT_CHARS = 800
    private const val MAX_HISTORY_RELEVANT_TURNS = 4
    private const val SUMMARY_SNIPPET_MAX_CHARS = 60

    private val SCREEN_JUNK_KEYWORDS = setOf(
        "unlabeled", "button", "divider", "spacer", "image", "icon",
        "container", "layout", "nav_bar", "status_bar"
    )

    // Format tool_result: "[toolName] status\n..." (lihat ChatViewModel.kt)
    private val TOOL_NAME_REGEX = Regex("""^\[([^\]]+)]""")

    private val MEMORY_KEYWORDS = listOf(
        "ingat", "biasanya", "selalu", "jangan pernah", "tolong catat"
    )

    /**
     * Hasil kompresi riwayat: pesan yang sudah dipadatkan + kandidat memori baru
     * dari pesan yang dibuang. Penyimpanan ke MemoryDao tetap tugas caller
     * (ContextEngine sengaja tidak depend langsung ke MemoryDao/Room).
     */
    data class CompressedHistoryResult(
        val messages: List<ChatMessage>,
        val memoryCandidates: List<Memory>
    )

    fun pruneToolResult(toolName: String, output: String): String {
        if (output.isBlank()) return "(Tanpa output)"

        return when (toolName) {
            "read_logs" -> {
                output.lineSequence()
                    .filter { line ->
                        (line.contains("E/") || line.contains("W/")) &&
                        !line.contains("type=1400") &&
                        !line.contains("Choreographer")
                    }
                    .distinct()
                    .take(25)
                    .joinToString("\n") + "\n[... log teknis diringkas ...]"
            }
            "list_processes" -> {
                output.lineSequence().take(13).joinToString("\n") + "\n[... daftar proses dipangkas ...]"
            }
            "read_screen" -> {
                val appHeader = output.lineSequence().firstOrNull { it.startsWith("[Aplikasi") } ?: ""

                val screenContent = output.lineSequence()
                    .filter { !it.startsWith("[Aplikasi") }
                    .map { it.trim() }
                    .filter { it.length > 2 }
                    .filter { text ->
                        val lower = text.lowercase()
                        SCREEN_JUNK_KEYWORDS.none { lower == it }
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
     * Ringkasan satu baris berbasis data dari pesan yang dibuang. Deterministik,
     * tanpa panggilan LLM.
     */
    fun summarizeDroppedMessages(dropped: List<ChatMessage>): String {
        if (dropped.isEmpty()) return "[... tidak ada riwayat tambahan yang dibuang ...]"

        val toolNames = dropped.asSequence()
            .filter { it.role == "tool_result" }
            .mapNotNull { TOOL_NAME_REGEX.find(it.content)?.groupValues?.get(1) }
            .distinct()
            .toList()

        val userMessages = dropped.filter { it.role == "user" }
        val firstSnippet = userMessages.firstOrNull()?.let { truncateSnippet(it.content) }
        val lastSnippet = userMessages.lastOrNull()?.let { truncateSnippet(it.content) }

        val parts = mutableListOf("[... ${dropped.size} pesan diringkas")

        if (toolNames.isNotEmpty()) {
            parts.add("tool: ${toolNames.joinToString(", ")}")
        }

        if (firstSnippet != null) {
            parts.add(
                if (firstSnippet == lastSnippet) "topik: \"$firstSnippet\""
                else "dari \"$firstSnippet\" ke \"$lastSnippet\""
            )
        }

        return parts.joinToString(" | ") + " ...]"
    }

    private fun truncateSnippet(text: String): String {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        return if (clean.length <= SUMMARY_SNIPPET_MAX_CHARS) clean
        else clean.take(SUMMARY_SNIPPET_MAX_CHARS - 1).trimEnd() + "…"
    }

    /**
     * Cek satu batch pesan terhadap kata kunci penanda memori jangka panjang.
     * Dipanggil per-kandidat oleh compressHistory() (bukan sekali untuk seluruh
     * riwayat), supaya tiap pesan yang cocok menghasilkan satu Memory terpisah.
     */
    fun extractMemoryCandidate(messages: List<ChatMessage>): Memory? {
        val hit = messages.firstOrNull { msg ->
            msg.role == "user" && MEMORY_KEYWORDS.any { kw -> msg.content.contains(kw, ignoreCase = true) }
        } ?: return null

        return Memory(
            key = "auto_extracted",
            value = hit.content.trim(),
            importance = 0.5f
        )
    }

    /**
     * Meringkas riwayat percakapan secara deterministik.
     * Return type diubah dari List<ChatMessage> -> CompressedHistoryResult:
     * sudah dicek, fungsi ini TANPA caller aktif (grep di seluruh project),
     * jadi aman diubah tanpa merusak pemanggil manapun.
     */
    fun compressHistory(messages: List<ChatMessage>): CompressedHistoryResult {
        if (messages.size <= MAX_HISTORY_RELEVANT_TURNS + 2) {
            return CompressedHistoryResult(messages, emptyList())
        }

        val result = mutableListOf<ChatMessage>()

        // 1. Selalu simpan pesan pertama
        result.add(messages.first())

        // 2. Cari pesan penting di tengah (tool_call/tool_result)
        val middleRange = messages.subList(1, messages.size - MAX_HISTORY_RELEVANT_TURNS)
        val toolMessages = middleRange.filter { it.role == "tool_call" || it.role == "tool_result" }
        val importantMiddle = toolMessages.takeLast(2)

        // Pesan yang benar-benar dibuang = middleRange dikurangi importantMiddle
        val dropped = middleRange.filterNot { m -> importantMiddle.any { it === m } }

        // 3. Ekstrak kandidat memori dari tiap pesan user yang akan dibuang
        val memoryCandidates = dropped
            .filter { it.role == "user" }
            .mapNotNull { candidate -> extractMemoryCandidate(listOf(candidate)) }

        // 4. SATU ringkasan berbasis data, menggantikan dua placeholder statis lama
        result.add(ChatMessage("", "system", summarizeDroppedMessages(dropped)))

        if (importantMiddle.isNotEmpty()) {
            result.addAll(importantMiddle)
        }

        // 5. Selalu simpan N pesan terakhir
        result.addAll(messages.takeLast(MAX_HISTORY_RELEVANT_TURNS))

        return CompressedHistoryResult(result, memoryCandidates)
    }
}
