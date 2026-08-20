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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.synaptic.ai.AppPreferences
import com.synaptic.ai.SynapticApp
import com.synaptic.ai.tools.ShizukuHelper
import com.synaptic.ai.ui.DashboardScreen
import com.synaptic.ai.ui.SynapticColors
import com.synaptic.ai.ui.SynapticTheme
import com.synaptic.ai.ui.logcat.LogcatActivity
import com.synaptic.ai.ui.shell.ShellScreen
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
    object Dashboard : Screen("Dashboard", Icons.Default.Dashboard)
    object Shell : Screen("Shell", Icons.Default.Terminal)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MainNavigation(viewModel: ChatViewModel = viewModel(), demoMode: Boolean = false) {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Chat) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val sessions by viewModel.sessionSummaries.observeAsState(emptyList())
    
    val isKeyboardVisible = WindowInsets.isImeVisible

    var sessionToRename by remember { mutableStateOf<String?>(null) }
    var newSessionName by remember { mutableStateOf("") }
    var showSettingsDialog by remember { mutableStateOf(false) }
    
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                ShizukuHelper.updateState()
                if (ShizukuHelper.isShizukuAvailable() && !ShizukuHelper.hasPermission()) {
                    ShizukuHelper.requestPermission()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    LaunchedEffect(demoMode) {
        if (!demoMode) {
            viewModel.initModel()
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

    if (showSettingsDialog) {
        SettingsDialog(onDismiss = { showSettingsDialog = false })
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = SynapticColors.Surface1,
                drawerShape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)
            ) {
                Spacer(Modifier.height(24.dp))
                Text(
                    "RIWAYAT CHAT", 
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp), 
                    fontSize = 13.sp, 
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.2.sp,
                    color = SynapticColors.Accent
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                
                if (sessions.isEmpty()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("Belum ada chat", color = SynapticColors.Text3, fontSize = 15.sp)
                    }
                } else {
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
                        items(sessions, key = { it.sessionId }) { msg ->
                            var showMenu by remember { mutableStateOf(false) }
                            NavigationDrawerItem(
                                label = { 
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = msg.sessionTitle ?: msg.content, 
                                                fontSize = 15.sp, 
                                                maxLines = 1, 
                                                overflow = TextOverflow.Ellipsis, 
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text("ID: ${msg.sessionId}", fontSize = 11.sp, color = SynapticColors.Text3)
                                        }
                                        IconButton(onClick = { showMenu = true }) {
                                            Icon(Icons.Default.MoreVert, null, modifier = Modifier.size(20.dp))
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
                                                leadingIcon = { Icon(Icons.Default.Delete, null, tint = SynapticColors.Danger) }
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
                                colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent)
                            )
                        }
                    }
                }
                
                HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                
                NavigationDrawerItem(
                    label = { Text("Pengaturan Aplikasi", fontSize = 16.sp) },
                    selected = false,
                    onClick = { scope.launch { drawerState.close() }; showSettingsDialog = true },
                    icon = { Icon(Icons.Default.Settings, null, modifier = Modifier.size(24.dp)) },
                    colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent)
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Synaptic", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu", tint = SynapticColors.Text2, modifier = Modifier.size(24.dp))
                        }
                    },
                    actions = {
                        if (currentScreen == Screen.Chat) {
                            IconButton(onClick = { viewModel.startNewSession() }) {
                                Icon(Icons.Default.Edit, contentDescription = "New Chat", tint = SynapticColors.Text2, modifier = Modifier.size(24.dp))
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            },
            bottomBar = {
                if (!isKeyboardVisible) {
                    Column(modifier = Modifier.navigationBarsPadding()) {
                        HorizontalDivider(color = SynapticColors.Border, thickness = 0.5.dp)
                        NavigationBar(
                            containerColor = Color.Transparent,
                            modifier = Modifier.height(50.dp),
                            tonalElevation = 0.dp,
                            windowInsets = WindowInsets(0, 0, 0, 0)
                        ) {
                            listOf(Screen.Chat, Screen.Dashboard, Screen.Shell).forEach { screen ->
                                val selected = currentScreen == screen
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { currentScreen = screen },
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
                                            fontSize = 11.sp,
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
            }
        ) { padding ->
                Box(modifier = Modifier.padding(padding).fillMaxSize().background(SynapticColors.Background)) {
                    when (currentScreen) {
                        Screen.Chat -> ChatScreen(viewModel, demoMode = demoMode)
                        Screen.Dashboard -> DashboardScreen()
                        Screen.Shell -> ShellScreen()
                    }
                }
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    var modelPath by remember { mutableStateOf(prefs.modelPath) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SynapticColors.Surface1,
        title = { Text("Pengaturan Synaptic", color = SynapticColors.Text1, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Path Model GGUF", color = SynapticColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = modelPath, onValueChange = { modelPath = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedBorderColor = SynapticColors.Accent, unfocusedBorderColor = SynapticColors.Text3
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { prefs.modelPath = modelPath; onDismiss() },
                colors = ButtonDefaults.buttonColors(containerColor = SynapticColors.Accent),
                shape = RoundedCornerShape(10.dp)
            ) { Text("Simpan") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal", color = SynapticColors.Text3) } }
    )
}
