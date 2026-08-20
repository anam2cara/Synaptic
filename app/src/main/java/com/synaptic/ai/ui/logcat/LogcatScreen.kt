package com.synaptic.ai.ui.logcat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

private const val TAG = "LogcatScreen"

data class LogEntry(val text: String, val level: LogLevel)
enum class LogLevel(val prefix: String) {
    VERBOSE("V"), DEBUG("D"), INFO("I"), WARN("W"), ERROR("E")
}

class LogcatViewModel : ViewModel() {
    var logs by mutableStateOf(listOf<LogEntry>())
        private set
    var isLoading by mutableStateOf(false)
        private set
    var autoRefresh by mutableStateOf(false)
        private set

    private var autoRefreshJob: kotlinx.coroutines.Job? = null

    init { loadLogs() }

    fun loadLogs() {
        viewModelScope.launch {
            isLoading = true
            logs = withContext(Dispatchers.IO) {
                try {
                    val process = Runtime.getRuntime().exec("logcat -d -v time --pid=" + android.os.Process.myPid())
                    val reader = process.inputStream.bufferedReader()
                    reader.readLines().map { line ->
                        val level = when {
                            line.contains(" E ") -> LogLevel.ERROR
                            line.contains(" W ") -> LogLevel.WARN
                            line.contains(" I ") -> LogLevel.INFO
                            line.contains(" D ") -> LogLevel.DEBUG
                            else -> LogLevel.VERBOSE
                        }
                        LogEntry(line, level)
                    }.reversed()
                } catch (e: Exception) {
                    listOf(LogEntry("Error reading logs: " + e.message, LogLevel.ERROR))
                }
            }
            isLoading = false
        }
    }

    fun clearLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            try { Runtime.getRuntime().exec("logcat -c") } catch (e: Exception) { }
        }
        logs = emptyList()
    }

    fun toggleAutoRefresh() {
        autoRefresh = !autoRefresh
        if (autoRefresh) {
            autoRefreshJob = viewModelScope.launch {
                while (autoRefresh) {
                    delay(2000)
                    loadLogs()
                }
            }
        } else {
            autoRefreshJob?.cancel()
        }
    }

    fun getFilteredLogs(filter: LogLevel): List<LogEntry> {
        return logs.filter { it.level.ordinal >= filter.ordinal }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogcatScreen(
    onBack: () -> Unit,
    viewModel: LogcatViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var filter by remember { mutableStateOf(LogLevel.VERBOSE) }
    var searchQuery by remember { mutableStateOf("") }
    val filteredLogs = viewModel.getFilteredLogs(filter).let { list ->
        if (searchQuery.isBlank()) list else list.filter { it.text.contains(searchQuery, ignoreCase = true) }
    }

    LaunchedEffect(filteredLogs.size) {
        if (filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Logcat Viewer") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadLogs() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = { viewModel.clearLogs() }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("logs", filteredLogs.joinToString("\n") { it.text })
                        clipboard.setPrimaryClip(clip)
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "Copy")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                LogLevel.values().forEach { level ->
                    val selected = filter == level
                    Button(
                        onClick = { filter = level },
                        colors = if (selected) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(level.prefix)
                    }
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                placeholder = { Text("Cari keyword atau tag...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true
            )

            Row(
                modifier = Modifier.padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = viewModel.autoRefresh,
                    onClick = { viewModel.toggleAutoRefresh() },
                    label = { Text(if (viewModel.autoRefresh) "Auto ON" else "Auto OFF") }
                )
                Text("Total: " + filteredLogs.size + " logs", style = MaterialTheme.typography.bodySmall)
            }

            if (viewModel.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp)
            ) {
                items(filteredLogs) { log ->
                    Text(
                        text = log.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = when (log.level) {
                            LogLevel.ERROR -> MaterialTheme.colorScheme.error
                            LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
                            LogLevel.INFO -> MaterialTheme.colorScheme.primary
                            LogLevel.DEBUG -> MaterialTheme.colorScheme.secondary
                            LogLevel.VERBOSE -> LocalContentColor.current
                        },
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                    HorizontalDivider(thickness = 0.5.dp)
                }
            }
        }
    }
}