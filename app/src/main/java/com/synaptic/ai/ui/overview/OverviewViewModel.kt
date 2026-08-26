package com.synaptic.ai.ui.overview

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.synaptic.ai.core.model.DeviceSnapshot
import com.synaptic.ai.monitor.DeviceMonitor
import com.synaptic.ai.tools.ShellExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class OverviewViewModel(application: Application) : AndroidViewModel(application) {
    
    private val deviceMonitor = DeviceMonitor(
        application,
        shellRunner = { command -> ShellExecutor.run(command) }
    )

    private val _snapshot = MutableStateFlow(DeviceSnapshot())
    val snapshot: StateFlow<DeviceSnapshot> = _snapshot

    init {
        startMonitoring()
    }

    private fun startMonitoring() {
        viewModelScope.launch {
            while (true) {
                _snapshot.value = deviceMonitor.getSnapshot()
                delay(1000) // Update setiap detik
            }
        }
    }
}
