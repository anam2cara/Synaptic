package com.synaptic.ai.ui.chat

import android.util.Log
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synaptic.ai.data.model.ChatMessage
import com.synaptic.ai.ui.IconBubble
import com.synaptic.ai.ui.SynapticColors
import com.synaptic.ai.ui.SynapticTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(viewModel: ChatViewModel, demoMode: Boolean = false) {
    if (demoMode) {
        ChatDemoBody()
        return
    }

    val messages by viewModel.messages.observeAsState(emptyList())
    val uiState by viewModel.uiState.observeAsState(ChatViewModel.UiState.IDLE)
    val streamingText by viewModel.outputFlow.collectAsStateWithLifecycle()
    val pendingAction by viewModel.pendingAction.observeAsState()
    val errorMsg by viewModel.errorMessage.observeAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Status "dekat bottom" dihitung sekali via derivedStateOf, dipakai untuk
    // auto-scroll streaming maupun untuk menampilkan tombol Scroll to Bottom.
    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) {
                true
            } else {
                val lastVisible = visibleItems.last().index
                lastVisible >= layoutInfo.totalItemsCount - 2
            }
        }
    }

    val showScrollToBottom by remember {
        derivedStateOf {
            (messages.isNotEmpty() || streamingText.isNotEmpty()) && !isAtBottom
        }
    }

    LaunchedEffect(messages.size, streamingText) {
        if (messages.isNotEmpty() || streamingText.isNotEmpty()) {
            val lastIndex = if (streamingText.isNotEmpty()) messages.size else messages.size - 1
            if (lastIndex >= 0 && isAtBottom) {
                listState.scrollToItem(lastIndex)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty() && streamingText.isEmpty()) {
                ChatEmptyState()
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        ChatBubble(
                            message = message,
                            context = context,
                            onEdit = { viewModel.editMessage(message, it) },
                            onRegenerate = { viewModel.regenerateLastMessage() }
                        )
                    }
                    if (streamingText.isNotEmpty()) {
                        item {
                            ChatBubble(
                                message = ChatMessage("", "assistant", streamingText),
                                context = context,
                                isStreaming = true
                            )
                        }
                    }
                }
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = showScrollToBottom,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp),
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut()
            ) {
                SmallFloatingActionButton(
                    onClick = {
                        scope.launch {
                            val targetIndex = if (streamingText.isNotEmpty()) messages.size else messages.size - 1
                            if (targetIndex >= 0) {
                                listState.animateScrollToItem(targetIndex)
                            }
                        }
                    },
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.primary,
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 2.dp)
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Scroll ke bawah")
                }
            }

            errorMsg?.let { msg ->
                Snackbar(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    containerColor = SynapticColors.Danger,
                    contentColor = Color.White,
                    action = {
                        TextButton(onClick = { viewModel.clearError() }) {
                            Text("OK", color = Color.White)
                        }
                    }
                ) { Text(msg) }
            }

            if (uiState == ChatViewModel.UiState.LOADING_MODEL || uiState == ChatViewModel.UiState.GENERATING) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                    color = SynapticColors.Accent,
                    trackColor = Color.Transparent
                )
            }
        }

        if (uiState == ChatViewModel.UiState.AWAITING_CONFIRM && pendingAction != null) {
            ConfirmBar(
                command = pendingAction?.displayCommand ?: "",
                onConfirm = { viewModel.confirmAction() },
                onCancel = { viewModel.rejectAction() }
            )
        }

        ChatInputBar(
            isGenerating = uiState == ChatViewModel.UiState.GENERATING,
            onSend = { viewModel.sendMessage(it) },
            onStop = { viewModel.stopGeneration() }
        )
    }
}

@Composable
private fun ChatEmptyState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            modifier = Modifier.size(64.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Psychology, 
                    null, 
                    tint = MaterialTheme.colorScheme.onPrimaryContainer, 
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Apa yang bisa saya bantu?", 
            color = MaterialTheme.colorScheme.onBackground, 
            fontSize = 20.sp, 
            fontWeight = FontWeight.Black
        )
        Text(
            "Tanya apa saja, lampirkan file, atau pantau performa perangkat Anda secara real-time.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 20.sp,
            modifier = Modifier.padding(top = 12.dp)
        )
    }
}

@Composable
private fun ChatDemoBody() {
    val context = LocalContext.current
    var sampleMessages by remember {
        mutableStateOf(
            listOf(
                ChatMessage(
                    sessionId = "preview",
                    role = "user",
                    content = "aplikasi Dana kayaknya bikin hp panas, force stop dong",
                    timestamp = 1_725_801_600_000
                ),
                ChatMessage(
                    sessionId = "preview",
                    role = "assistant",
                    content = """
                    Deteksi: Dana aktif cukup berat di latar belakang. Suhu device naik ke 41°C.

                    Rekomendasi:
                    - cek proses yang dominan
                    - lakukan force stop bila perlu
                    - pantau ulang suhu setelah 1-2 menit
                """.trimIndent(),
                    timestamp = 1_725_801_660_000
                )
            )
        )
    }
    var pendingDemoAction by remember {
        mutableStateOf<String?>("am force-stop id.dana")
    }

    fun appendDemoMessage(content: String) {
        sampleMessages = sampleMessages + ChatMessage(
            sessionId = "preview",
            role = "assistant",
            content = content,
            timestamp = System.currentTimeMillis()
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(sampleMessages, key = { it.timestamp }) { message ->
                    ChatBubble(
                        message = message,
                        context = context,
                        isStreaming = false
                    )
                }
            }
        }

        if (pendingDemoAction != null) {
            ConfirmBar(
                command = pendingDemoAction ?: "",
                onConfirm = {
                    pendingDemoAction = null
                    appendDemoMessage("Dana dihentikan di demo mode.")
                    Toast.makeText(context, "Perintah dijalankan", Toast.LENGTH_SHORT).show()
                },
                onCancel = {
                    pendingDemoAction = null
                    appendDemoMessage("Perintah dibatalkan.")
                    Toast.makeText(context, "Perintah dibatalkan", Toast.LENGTH_SHORT).show()
                }
            )
        }

        ChatInputBar(
            isGenerating = false,
            onSend = { },
            onStop = { }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatBubble(
    message: ChatMessage,
    context: Context,
    onEdit: ((String) -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null,
    isStreaming: Boolean = false
) {
    val isUser = message.role == "user"
    val clipboardManager = LocalClipboardManager.current

    var isEditing by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf(message.content) }
    var showMenu by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    val formattedDate = remember(message.timestamp) {
        SimpleDateFormat("d MMM yyyy, HH:mm", Locale("id")).format(Date(message.timestamp))
    }
    val bubbleShape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = if (isUser) 18.dp else 4.dp,
        bottomEnd = if (isUser) 4.dp else 18.dp
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Box {
            Column(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .wrapContentWidth()
                    .clip(bubbleShape)
                    .background(
                        if (isUser) MaterialTheme.colorScheme.primary 
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .combinedClickable(
                        enabled = !isStreaming && !isEditing,
                        onClick = {},
                        onLongClick = { showMenu = true }
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                if (!isUser) {
                    Text(
                        text = "SYNAPTIC",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }

                if (isEditing) {
                    TextField(
                        value = editText,
                        onValueChange = { editText = it },
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedTextColor = MaterialTheme.colorScheme.onPrimary,
                            unfocusedTextColor = MaterialTheme.colorScheme.onPrimary,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { isEditing = false }) {
                            Text("Batal", color = MaterialTheme.colorScheme.onPrimary)
                        }
                        TextButton(
                            onClick = {
                                onEdit?.invoke(editText)
                                isEditing = false
                            }
                        ) {
                            Text("Simpan", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    val contentColor = if (isUser) MaterialTheme.colorScheme.onPrimary 
                                     else MaterialTheme.colorScheme.onSurfaceVariant
                    val contentSize = 15.sp
                    val contentLineHeight = 22.sp

                    if (isStreaming) {
                        Text(
                            text = message.content,
                            color = contentColor,
                            fontSize = contentSize,
                            lineHeight = contentLineHeight
                        )
                    } else if (selectionMode && message.content.length < 2000) {
                        SelectionContainer {
                            Text(
                                text = message.content,
                                color = contentColor,
                                fontSize = contentSize,
                                lineHeight = contentLineHeight
                            )
                        }
                    } else {
                        Text(
                            text = message.content,
                            color = contentColor,
                            fontSize = contentSize,
                            lineHeight = contentLineHeight
                        )
                    }
                }
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                Text(
                    text = formattedDate,
                    color = SynapticColors.Text3,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
                HorizontalDivider(color = SynapticColors.Border)
                DropdownMenuItem(
                    text = { Text("Copy message") },
                    onClick = {
                        clipboardManager.setText(AnnotatedString(message.content))
                        Toast.makeText(context, "Pesan disalin", Toast.LENGTH_SHORT).show()
                        showMenu = false
                    }
                )
                if (message.content.length < 2000) {
                    DropdownMenuItem(
                        text = { Text("Select text") },
                        onClick = {
                            selectionMode = true
                            showMenu = false
                        }
                    )
                }
                if (isUser) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = {
                            isEditing = true
                            showMenu = false
                        }
                    )
                }
            }
        }

        if (!isUser && !isStreaming && message.content.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(message.content))
                        Toast.makeText(context, "Pesan disalin", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, null, tint = SynapticColors.Text3, modifier = Modifier.size(16.dp))
                }

                IconButton(
                    onClick = { onRegenerate?.invoke() },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(Icons.Default.Refresh, null, tint = SynapticColors.Text3, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
@Composable
private fun ConfirmBar(command: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Synaptic akan menjalankan:", 
                color = MaterialTheme.colorScheme.onSurfaceVariant, 
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = command,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(12.dp),
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onConfirm,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text("Jalankan", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onCancel,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text("Batal", fontSize = 13.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatInputBar(
    isGenerating: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    val context = LocalContext.current

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            IconButton(
                onClick = {
                    Toast.makeText(context, "Fitur Lampiran segera hadir", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Default.Add, 
                    null, 
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            TextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp, max = 120.dp),
                placeholder = { 
                    Text(
                        "Tanya Synaptic...", 
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        fontSize = 15.sp
                    ) 
                },
                textStyle = LocalTextStyle.current.copy(fontSize = 15.sp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary
                )
            )

            if (isGenerating) {
                Surface(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable { onStop() },
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Stop, 
                            null, 
                            tint = MaterialTheme.colorScheme.onErrorContainer, 
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            } else {
                Surface(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(enabled = text.isNotBlank()) {
                            onSend(text)
                            text = ""
                        },
                    color = if (text.isNotBlank()) MaterialTheme.colorScheme.primary 
                            else MaterialTheme.colorScheme.surface
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.ArrowUpward, 
                            null, 
                            tint = if (text.isNotBlank()) MaterialTheme.colorScheme.onPrimary 
                                   else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(
    name = "Synaptic chat preview",
    showBackground = true,
    widthDp = 360,
    heightDp = 800
)
@Composable
private fun ChatPreview() {
    SynapticTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(SynapticColors.Background)
        ) {
            TopAppBar(
                title = {
                    Text(
                        text = "Synaptic",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { }) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = null,
                            tint = SynapticColors.Text2,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { }) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            tint = SynapticColors.Text2,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )

            ChatDemoBody()

            PreviewBottomNav()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewBottomNav() {
    Column(modifier = Modifier.navigationBarsPadding()) {
        HorizontalDivider(color = SynapticColors.Border, thickness = 0.5.dp)
        NavigationBar(
            containerColor = Color.Transparent,
            modifier = Modifier.height(50.dp),
            tonalElevation = 0.dp,
            windowInsets = WindowInsets(0, 0, 0, 0)
        ) {
            listOf(
                Screen.Chat,
                Screen.Shell,
                Screen.Settings
            ).forEach { screen ->
                val selected = screen == Screen.Chat
                NavigationBarItem(
                    selected = selected,
                    onClick = { },
                    icon = {
                        Icon(
                            imageVector = screen.icon,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = if (selected) SynapticColors.Accent else SynapticColors.Text1.copy(alpha = 0.38f)
                        )
                    },
                    label = {
                        Text(
                            text = screen.label,
                            fontSize = 9.sp,
                            maxLines = 1,
                            color = if (selected) SynapticColors.Accent else SynapticColors.Text2.copy(alpha = 0.38f)
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = Color.Transparent,
                        selectedIconColor = SynapticColors.Accent,
                        unselectedIconColor = SynapticColors.Text1.copy(alpha = 0.38f),
                        selectedTextColor = SynapticColors.Accent,
                        unselectedTextColor = SynapticColors.Text2.copy(alpha = 0.38f)
                    )
                )
            }
        }
    }
}
