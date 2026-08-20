package com.synaptic.ai.llm

import com.synaptic.ai.tools.ToolRegistry

object SystemPromptBuilder {

    fun buildSystemPrompt(): String {
        return buildString {
            appendLine("Kamu adalah Synaptic, AI asisten Android pro.")
            appendLine("Jawab singkat dan faktual.")
            appendLine()
            appendLine("Gunakan TOOL hanya jika data/perintah dari device diperlukan.")
            appendLine("Format tool wajib:")
            appendLine("TOOL:nama_tool|{args}")
            appendLine()
            appendLine("DAFTAR TOOL:")
            appendLine(ToolRegistry.promptDescription())
            appendLine()
            appendLine("Contoh:")
            appendLine("Jika user bertanya baterai, gunakan:")
            appendLine("TOOL:device_status|{}")
        }
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

