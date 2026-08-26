package com.synaptic.ai.ui.shell

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.synaptic.ai.ui.Badge
import com.synaptic.ai.ui.SynapticColors

import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.text.input.ImeAction

@Composable
fun ShellScreen(viewModel: ShellViewModel = viewModel()) {
    val isShizukuActive by viewModel.isShizukuActive.observeAsState(false)
    val terminalOutput by viewModel.terminalOutput.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        viewModel.checkShizuku()
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SynapticColors.Background)
            .imePadding() // Memastikan input naik saat keyboard muncul
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (isShizukuActive) {
            Badge("Status: Terhubung ke Shizuku", SynapticColors.Success)
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                TerminalOutput(terminalOutput)
            }
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("pm trim-caches 999G", "dumpsys battery", "top -n 1 -b").forEach {
                    SuggestionChip(it) { 
                        viewModel.runCommand(it)
                        focusRequester.requestFocus()
                        keyboardController?.show()
                    }
                }
            }
            CommandInput(
                value = inputText,
                onValueChange = { inputText = it },
                onSend = { 
                    viewModel.runCommand(inputText)
                    inputText = ""
                    // Paksa keyboard tetap terbuka dan fokus
                    focusRequester.requestFocus()
                    keyboardController?.show()
                },
                focusRequester = focusRequester,
                enabled = true
            )
        } else {
            // ... UI Shizuku tidak aktif tetap sama
            Badge("Status: Shizuku Tidak Aktif", SynapticColors.Text2)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SynapticColors.Surface1),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Akses Shell memerlukan izin Shizuku.\nAktifkan Shizuku untuk menjalankan perintah.",
                    color = SynapticColors.Text3,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp,
                    modifier = Modifier.padding(24.dp)
                )
            }
            CommandInput(enabled = false)
        }
    }
}

@Composable
private fun TerminalOutput(lines: List<String>) {
    val listState = rememberLazyListState()
    
    // Auto-scroll ke bawah saat ada output baru
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            listState.animateScrollToItem(lines.size - 1)
        }
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = SynapticColors.Surface1
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxSize(),
        border = BorderStroke(
            1.dp,
            Color.White.copy(alpha = 0.05f)
        )
    ) {
        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(lines) { line ->
                    val isPrompt = line.startsWith("synaptic@android $")
                    val isError =
                        line.startsWith("Error:", ignoreCase = true) ||
                        line.contains("failed", ignoreCase = true) ||
                        line.contains("error", ignoreCase = true)

                    val lineColor = when {
                        isPrompt -> SynapticColors.Accent
                        isError -> Color(0xFFFF6B6B)
                        else -> Color(0xFFA0C090)
                    }

                    val lineWeight = if (isPrompt) {
                        FontWeight.Bold
                    } else {
                        FontWeight.Normal
                    }

                    Text(
                        text = line,
                        color = lineColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        fontWeight = lineWeight,
                        lineHeight = 19.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun SuggestionChip(text: String, onClick: () -> Unit) {
    Surface(
        color = SynapticColors.Surface2,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = text,
            color = SynapticColors.Accent,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun CommandInput(
    value: String = "",
    onValueChange: (String) -> Unit = {},
    onSend: () -> Unit = {},
    focusRequester: FocusRequester = remember { FocusRequester() },
    enabled: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SynapticColors.Surface1)
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("$", color = SynapticColors.Accent, fontSize = 16.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            textStyle = androidx.compose.ui.text.TextStyle(
                color = if (enabled) SynapticColors.Text1 else SynapticColors.Text3,
                fontSize = 15.sp,
                fontFamily = FontFamily.Monospace
            ),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { 
                if (value.isNotBlank()) onSend() 
            }),
            decorationBox = { innerTextField ->
                if (value.isEmpty()) {
                    Text(
                        text = if (enabled) "Ketik perintah di sini..." else "Shell tidak tersedia",
                        color = SynapticColors.Text3,
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                innerTextField()
            }
        )
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (enabled) SynapticColors.Accent else SynapticColors.Surface3)
                .clickable(enabled = enabled && value.isNotBlank()) { onSend() },
            contentAlignment = Alignment.Center
        ) {
            Text("▶", color = Color.White, fontSize = 14.sp)
        }
    }
}







