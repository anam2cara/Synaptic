package com.synaptic.ai.llm

import com.synaptic.ai.data.model.ChatMessage
import org.junit.Assert.*
import org.junit.Test

class ContextEngineTest {

    private fun msg(role: String, content: String): ChatMessage =
        ChatMessage(sessionId = "test-session", role = role, content = content)

    private fun buildHistory(count: Int, startIndex: Int = 0): List<ChatMessage> {
        return (startIndex until startIndex + count).map { i ->
            msg(if (i % 2 == 0) "user" else "assistant", "Pesan ke-$i")
        }
    }

    @Test
    fun `compressHistory selalu menyimpan pesan pertama`() {
        val messages = buildHistory(count = 10)
        val result = ContextEngine.compressHistory(messages)
        assertEquals(messages.first(), result.messages.first())
    }

    @Test
    fun `compressHistory selalu menyimpan N pesan terakhir`() {
        val messages = buildHistory(count = 10)
        val result = ContextEngine.compressHistory(messages)
        assertEquals(messages.takeLast(4), result.messages.takeLast(4))
    }

    @Test
    fun `compressHistory menghasilkan ringkasan persis satu kali`() {
        val messages = buildHistory(count = 10)
        val result = ContextEngine.compressHistory(messages)
        val summaryCount = result.messages.count { it.role == "system" && it.content.startsWith("[...") }
        assertEquals(1, summaryCount)
    }

    @Test
    fun `compressHistory tidak exception saat dropped list kosong`() {
        // 1 pasang tool_call+tool_result persis habis terserap importantMiddle -> dropped = emptyList()
        val messages = mutableListOf(msg("user", "Pesan pembuka"))
        messages.add(msg("tool_call", "cek_ram()"))
        messages.add(msg("tool_result", "[device_status] OK\nRAM 50%"))
        messages.addAll(buildHistory(count = 4, startIndex = 100))

        val result = ContextEngine.compressHistory(messages)
        assertTrue(result.messages.isNotEmpty())
        assertEquals(messages.first(), result.messages.first())
    }

    @Test
    fun `summarizeDroppedMessages tidak exception untuk list kosong`() {
        val summary = ContextEngine.summarizeDroppedMessages(emptyList())
        assertTrue(summary.isNotEmpty())
    }

    @Test
    fun `compressHistory di bawah threshold mengembalikan messages apa adanya`() {
        val messages = buildHistory(count = 5)
        val result = ContextEngine.compressHistory(messages)
        assertEquals(messages, result.messages)
        assertTrue(result.memoryCandidates.isEmpty())
    }
}
