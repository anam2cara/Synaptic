package com.synaptic.ai.llm

import com.synaptic.ai.tools.ToolRegistry

object SystemPromptBuilder {

    fun buildSystemPrompt(): String {
        val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        return """
            # Synaptic OS: High-Level System Intelligence
            WAKTU_SISTEM: $now
            
            Anda adalah inti kecerdasan Synaptic. Tugas utama Anda adalah MENDIAGNOSA dan MENGONTROL sistem Android pengguna berdasarkan bukti data nyata.
            
            ## PRINSIP KERJA:
            1. **Berbasis Bukti (Evidence-Based)**: Dilarang menebak. Gunakan data dari [REALTIME_SYSTEM_EVIDENCE] atau panggil tool untuk mendapatkan data terbaru.
            2. **Analisis Mendalam**: Jika sistem melambat, cari tahu penyebabnya (CPU, RAM, atau Thermal) menggunakan `device_status` atau `list_processes`.
            3. **Respon Teknis**: Berikan wawasan teknis yang akurat. (Contoh: "Baterai boros karena aplikasi X sering melakukan wakelock").
            4. **Agentic Flow**: Gunakan tag `<think>...</think>` untuk merencanakan langkah sebelum memanggil tool atau menjawab.
            
            ## FORMAT PERINTAH:
            Panggil tool dengan: `TOOL:nama_tool|{"arg": "value"}`
            
            ## DAFTAR TOOLS:
            ${ToolRegistry.promptDescription()}
        """.trimIndent()
    }

    fun getToolGrammar(): String {
        // Redesign Grammar: Jauh lebih fleksibel, hanya memandu format TOOL jika terdeteksi
        return """
            root ::= (thought | text | tool_call)*
            thought ::= "<think>" [^<]* "</think>"
            tool_call ::= "TOOL:" [a-z0-9_]+ "|" "{" [^}]* "}"
            text ::= [^<T]+ | [T<]
        """.trimIndent()
    }

    fun parseToolCall(r: String): ToolCall? {
        val pattern = """TOOL:(\w+)\|\{(.*?)\}"""
        val match = Regex(
            pattern,
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(r) ?: return null

        val toolName = match.groupValues[1].lowercase()
        val argsBody = match.groupValues[2]

        if (!ToolRegistry.exists(toolName)) {
            return null
        }

        return ToolCall(
            toolName = toolName,
            argsJson = "{$argsBody}"
        )
    }

    data class ToolCall(
        val toolName: String,
        val argsJson: String
    )
}

