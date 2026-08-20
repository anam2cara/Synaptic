package com.synaptic.ai.ui.shell

import android.app.Application
import androidx.lifecycle.*
import com.synaptic.ai.tools.ShellExecutor
import com.synaptic.ai.tools.ShizukuHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class ShellViewModel(application: Application) : AndroidViewModel(application) {
    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    
    private val _isShizukuActive = MutableLiveData<Boolean>()
    val isShizukuActive: LiveData<Boolean> = _isShizukuActive

    private val _terminalOutput = MutableStateFlow<List<String>>(listOf("synaptic@android $ _"))
    val terminalOutput: StateFlow<List<String>> = _terminalOutput

    init {
        checkShizuku()
    }

    fun checkShizuku() {
        _isShizukuActive.value = ShizukuHelper.isShizukuAvailable() && ShizukuHelper.hasPermission()
    }

    fun runCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return

        if (
            trimmed.equals("clear", ignoreCase = true) ||
            trimmed.equals("cls", ignoreCase = true)
        ) {
            _terminalOutput.value = listOf("synaptic@android $ _")
            return
        }

        val newLines = _terminalOutput.value.toMutableList()
        newLines.add("synaptic@android $ $command")
        _terminalOutput.value = newLines

        backgroundExecutor.execute {
            val result = ShellExecutor.run(command)

            viewModelScope.launch {
                val updatedLines = _terminalOutput.value.toMutableList()

                if (result.isNotEmpty()) {
                    updatedLines.addAll(result.split("\n"))
                }

                updatedLines.add("synaptic@android $ _")
                _terminalOutput.value = updatedLines
            }
        }
    }
}
