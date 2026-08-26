package com.synaptic.ai.llm

import com.synaptic.ai.tools.ToolRegistry

object SystemPromptBuilder {

    fun buildSystemPrompt(): String {
        return """
            Synaptic: Asisten Pro Android. Fokus: Telemetri sistem & diagnosa.
            - Tolak pertanyaan umum (sains, sejarah).
            - Pakai data [DEVICE_DIAGNOSTICS] secara ketat.
            - Jawaban: Teknis, akurat, ringkas.
            - Tindakan: Pakai TOOL:nama|{args}.
            Tools: ${ToolRegistry.promptDescription()}
        """.trimIndent()
    }

    fun getToolGrammar(): String {
        return """
            root  ::= thought? (tool | text)
            thought ::= "<think>" [^<]* "</think>"
            tool  ::= "TOOL:" [a-z0-9_]+ "|" "{" [^}]* "}"
            text  ::= [^\t\n\r\f]+
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

