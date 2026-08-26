package com.synaptic.ai.ui.chat

import android.content.Context
import android.net.Uri
import com.synaptic.ai.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

object ImportModelHelper {

    suspend fun importModel(context: Context, uri: Uri, fileName: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val modelsDir = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
            if (!modelsDir.exists()) modelsDir.mkdirs()

            val safeName = fileName.replace(Regex("[^a-zA-Z0-9.\\-]"), "_")
            val finalFile = File(modelsDir, safeName)

            context.contentResolver.openInputStream(uri)?.use { input ->
                finalFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Gagal membuka aliran data model")

            if (finalFile.length() <= 0L) {
                finalFile.delete()
                throw IllegalStateException("File model kosong")
            }

            // Update preferences
            AppPreferences(context).modelPath = finalFile.absolutePath
            
            finalFile.absolutePath
        }
    }
}
