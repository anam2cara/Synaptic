package com.synaptic.ai.tools

/**
 * Single source of truth untuk tool Synaptic.
 *
 * Registry ini dipakai oleh:
 * - SystemPromptBuilder
 * - ToolExecutor
 * - Agent/router
 *
 * Jangan duplikasi nama/deskripsi tool di file lain.
 */
object ToolRegistry {

    enum class Permission {
        NONE,
        SHIZUKU
    }

    data class ToolDefinition(
        val name: String,
        val description: String,
        val argumentExample: String = "{}",
        val permission: Permission = Permission.NONE,
        val requiresConfirmation: Boolean = false,
        val directRoute: Boolean = false
    )

    val tools: List<ToolDefinition> = listOf(

        ToolDefinition(
            name = "device_status",
            description = "Cek baterai, suhu, RAM, CPU, storage dan status device.",
            permission = Permission.NONE,
            directRoute = true
        ),

        ToolDefinition(
            name = "device_analysis",
            description = "Analisis kesehatan/performa device berdasarkan telemetry.",
            permission = Permission.NONE,
            directRoute = true
        ),

        ToolDefinition(
            name = "list_processes",
            description = "Tampilkan proses aplikasi user yang sedang berjalan.",
            permission = Permission.NONE,
            directRoute = true
        ),

        ToolDefinition(
            name = "read_screen",
            description = "Baca isi/tampilan layar menggunakan Accessibility Service.",
            permission = Permission.NONE,
            directRoute = true
        ),

        ToolDefinition(
            name = "read_logs",
            description = "Baca log error/warning Android terbaru.",
            permission = Permission.NONE,
            directRoute = true
        ),

        ToolDefinition(
            name = "native_backend_status",
            description = "Tampilkan status backend native lokal: Vulkan, OpenCL, server mode, dan pemakaian LLM on-demand.",
            permission = Permission.NONE,
            directRoute = true
        ),

        ToolDefinition(
            name = "pgvector_status",
            description = "Tampilkan status konfigurasi PostgreSQL/pgVector opsional.",
            permission = Permission.NONE,
            directRoute = true
        ),

        ToolDefinition(
            name = "n8n_status",
            description = "Tampilkan status konfigurasi n8n opsional.",
            permission = Permission.NONE,
            directRoute = true
        ),

        ToolDefinition(
            name = "n8n_trigger",
            description = "Trigger webhook n8n yang sudah dikonfigurasi.",
            argumentExample = """{"payload":"..."}""",
            permission = Permission.NONE,
            requiresConfirmation = true,
            directRoute = false
        ),

        ToolDefinition(
            name = "shell",
            description = "Jalankan perintah shell Android.",
            argumentExample = """{"command":"..."}""",
            permission = Permission.SHIZUKU,
            requiresConfirmation = true,
            directRoute = false
        ),

        ToolDefinition(
            name = "python",
            description = "Jalankan Python lokal melalui shell.",
            argumentExample = """{"code":"..."}""",
            permission = Permission.SHIZUKU,
            requiresConfirmation = true,
            directRoute = false
        )
    )

    private val byName = tools.associateBy { it.name }

    fun get(name: String): ToolDefinition? {
        return byName[name.lowercase()]
    }

    fun exists(name: String): Boolean {
        return get(name) != null
    }

    fun directTools(): List<ToolDefinition> {
        return tools.filter { it.directRoute }
    }

    fun promptDescription(): String {
        return buildString {
            tools.forEach { tool ->
                append("- ${tool.name}|${tool.argumentExample} : ")
                appendLine(tool.description)
            }
        }.trimEnd()
    }

    fun validate(toolName: String, args: String): String? {
        val tool = get(toolName)
            ?: return "Tool tidak dikenal: $toolName"

        if (args.isBlank()) {
            return "Argument tool kosong: ${tool.name}"
        }

        return null
    }
}

