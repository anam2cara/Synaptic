package com.synaptic.ai.ui.chat

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.synaptic.ai.SynapticApp
import com.synaptic.ai.tools.ShizukuHelper
import com.synaptic.ai.ui.SynapticColors
import com.synaptic.ai.ui.SynapticTheme
import com.synaptic.ai.ui.logcat.LogcatActivity
import com.synaptic.ai.ui.shell.ShellScreen
import com.synaptic.ai.ui.settings.SettingsScreen
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import kotlinx.coroutines.launch

class ChatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val demoMode = intent.getBooleanExtra(EXTRA_DEMO_MODE, false)
        setContent {
            SynapticTheme {
                MainNavigation(demoMode = demoMode)
            }
        }
    }
}

const val EXTRA_DEMO_MODE = "demoMode"

sealed class Screen(val label: String, val icon: ImageVector) {
    object Chat : Screen("Chat", Icons.Default.ChatBubble)
    object Shell : Screen("Shell", Icons.Default.Terminal)
    object Settings : Screen("Settings", Icons.Default.Settings)
}

@Composable
fun LlmStatusBadge(viewModel: ChatViewModel) {
    val info by viewModel.llmInfo.collectAsState()
    val uiState by viewModel.uiState.observeAsState(ChatViewModel.UiState.IDLE)

    val isLoading = uiState == ChatViewModel.UiState.LOADING_MODEL
    
    // Logika "Toaster": Tampil melayang dengan background tegas ala System Toast
    if (info != null || isLoading) {
        Surface(
            color = if (isLoading) Color(0xFF1B5E20) // Hijau Tua (Loading)
                    else Color(0xFF121212).copy(alpha = 0.95f), // Hitam Gahar (Ready)
            shape = RoundedCornerShape(12.dp), // Sudut lebih tegas
            border = BorderStroke(1.5.dp, if (isLoading) Color(0xFF4CAF50) else Color(0xFF37474F)),
            shadowElevation = 10.dp,
            modifier = Modifier
                .height(42.dp) // Lebih tinggi agar font bisa besar
                .padding(vertical = 2.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 3.dp,
                        color = Color.White
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "SEDANG MEMUAT...", 
                        fontSize = 14.sp, // Ukuran font lebih besar
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        letterSpacing = 1.2.sp
                    )
                } else info?.let {
                    // Ikon Status Dinamis (Lebih besar)
                    Icon(
                        imageVector = if (it.useGpu) Icons.Default.Bolt else Icons.Default.Memory,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (it.useGpu) Color(0xFFFFEA00) else Color(0xFF03A9F4)
                    )
                    
                    Spacer(Modifier.width(12.dp))
                    
                    // Teks Model (Font BESAR & TEBAL)
                    val modelDisplayName = it.name.substringAfterLast("/")
                                           .replace(".gguf", "", ignoreCase = true)
                                           .replace("-", " ")
                                           .uppercase()
                    
                    Text(
                        text = modelDisplayName,
                        fontSize = 15.sp, // Font jauh lebih besar
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    // Pemisah Vertikal
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .width(2.dp)
                            .height(20.dp)
                            .background(Color.White.copy(alpha = 0.2f))
                    )
                    
                    // Backend Tag (Font Besar)
                    Text(
                        text = if (it.useGpu) "VULKAN" else "CPU",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (it.useGpu) Color(0xFFFFEA00) else Color(0xFF03A9F4)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MainNavigation(viewModel: ChatViewModel = viewModel(), demoMode: Boolean = false) {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Chat) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    
    var isImporting by remember { mutableStateOf(false) }
    
    val modelPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                isImporting = true
                val fileName = it.lastPathSegment?.substringAfterLast('/') ?: "imported_model.gguf"
                val result = ImportModelHelper.importModel(context, it, fileName)
                isImporting = false
                
                result.onSuccess { path ->
                    Toast.makeText(context, "Model berhasil diimpor: ${File(path).name}", Toast.LENGTH_LONG).show()
                    viewModel.initModel() // Force reload after import
                }.onFailure { e ->
                    Toast.makeText(context, "Gagal impor: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Auto-sync model status when returning to app or changing settings
    LaunchedEffect(currentScreen) {
        if (currentScreen == Screen.Chat) {
            viewModel.initModel() 
        }
    }

    val sessions by viewModel.sessionSummaries.observeAsState(emptyList())
    val isKeyboardVisible = WindowInsets.isImeVisible

    var sessionToRename by remember { mutableStateOf<String?>(null) }
    var newSessionName by remember { mutableStateOf("") }
    
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                ShizukuHelper.updateState()
                if (ShizukuHelper.isShizukuAvailable() && !ShizukuHelper.hasPermission()) {
                    ShizukuHelper.requestPermission()
                }
                // Refresh model info on resume
                viewModel.initModel()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    if (sessionToRename != null) {
        AlertDialog(
            onDismissRequest = { sessionToRename = null },
            title = { Text("Ubah Nama Chat", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newSessionName,
                    onValueChange = { newSessionName = it },
                    placeholder = { Text("Masukkan nama baru...") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            },
            confirmButton = {
                Button(onClick = {
                    sessionToRename?.let { viewModel.renameSession(it, newSessionName) }
                    sessionToRename = null
                }) { Text("Simpan") }
            },
            dismissButton = {
                TextButton(onClick = { sessionToRename = null }) { Text("Batal") }
            }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
                modifier = Modifier.width(300.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Spacer(Modifier.height(32.dp))
                    
                    Surface(
                        onClick = { 
                            viewModel.startNewSession()
                            currentScreen = Screen.Chat
                            scope.launch { drawerState.close() }
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "Chat Baru", 
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                    Text(
                        "RIWAYAT", 
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp), 
                        fontSize = 12.sp, 
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (sessions.isEmpty()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("Belum ada riwayat", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(sessions, key = { it.sessionId }) { msg ->
                            var showMenu by remember { mutableStateOf(false) }
                            NavigationDrawerItem(
                                label = { 
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = msg.sessionTitle ?: msg.content, 
                                                fontSize = 14.sp, 
                                                maxLines = 1, 
                                                overflow = TextOverflow.Ellipsis, 
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                        IconButton(onClick = { showMenu = true }) {
                                            Icon(Icons.Default.MoreVert, null, modifier = Modifier.size(18.dp))
                                        }
                                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                            DropdownMenuItem(
                                                text = { Text("Ubah Nama") },
                                                onClick = { 
                                                    sessionToRename = msg.sessionId
                                                    newSessionName = msg.sessionTitle ?: msg.content
                                                    showMenu = false 
                                                },
                                                leadingIcon = { Icon(Icons.Default.Edit, null) }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Hapus") },
                                                onClick = { 
                                                    viewModel.deleteSession(msg.sessionId)
                                                    showMenu = false 
                                                },
                                                leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
                                            )
                                        }
                                    }
                                },
                                selected = false,
                                onClick = { 
                                    viewModel.loadSession(msg.sessionId)
                                    currentScreen = Screen.Chat
                                    scope.launch { drawerState.close() } 
                                },
                                icon = { Icon(Icons.AutoMirrored.Filled.Message, null, modifier = Modifier.size(20.dp)) },
                                shape = RoundedCornerShape(12.dp),
                                colors = NavigationDrawerItemDefaults.colors(
                                    unselectedContainerColor = Color.Transparent,
                                    selectedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            )
                        }
                    }
                }
                
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                
                NavigationDrawerItem(
                    label = { 
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (isImporting) "Mengimpor..." else "Import Model GGUF", fontSize = 14.sp)
                            if (isImporting) {
                                Spacer(Modifier.width(12.dp))
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            }
                        }
                    },
                    selected = false,
                    onClick = { 
                        if (!isImporting) {
                            scope.launch { drawerState.close() }
                            modelPicker.launch("application/octet-stream")
                        }
                    },
                    icon = { Icon(Icons.Default.CloudUpload, null, modifier = Modifier.size(20.dp)) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(8.dp),
                    colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent)
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Synaptic", fontSize = 20.sp, fontWeight = FontWeight.Black)
                            Spacer(Modifier.width(12.dp))
                            LlmStatusBadge(viewModel)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    },
                    actions = {
                        if (currentScreen == Screen.Chat) {
                            IconButton(onClick = { viewModel.startNewSession() }) {
                                Icon(Icons.Default.Edit, contentDescription = "New Chat")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        scrolledContainerColor = MaterialTheme.colorScheme.background
                    )
                )
            },
            bottomBar = {
                if (!isKeyboardVisible) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 8.dp
                    ) {
                        NavigationBar(
                            containerColor = Color.Transparent,
                            modifier = Modifier.navigationBarsPadding().height(64.dp),
                            windowInsets = WindowInsets(0, 0, 0, 0)
                        ) {
                            listOf(Screen.Chat, Screen.Shell, Screen.Settings).forEach { screen ->
                                val selected = currentScreen == screen
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { currentScreen = screen },
                                    icon = {
                                        Icon(
                                            imageVector = screen.icon,
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = screen.label,
                                            fontSize = 11.sp,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                )
                            }
                        }
                    }
                }
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                when (currentScreen) {
                    Screen.Chat -> ChatScreen(viewModel, demoMode = demoMode)
                    Screen.Shell -> ShellScreen()
                    Screen.Settings -> SettingsScreen()
                }
            }
        }
    }
}
