package com.synaptic.ai.ui.settings

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.synaptic.ai.AppPreferences
import com.synaptic.ai.ui.SynapticColors

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    var useGpu by remember { mutableStateOf(prefs.useGpuBackend) }
    var confirmExec by remember { mutableStateOf(prefs.isConfirmBeforeExec) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SynapticColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text("Pengaturan", color = SynapticColors.Text1, fontWeight = FontWeight.Black, fontSize = 24.sp)

        Section("Kecerdasan Buatan (LLM)") {
            SettingToggle(
                "Gunakan Akselerasi GPU",
                "Mempercepat jawaban menggunakan Vulkan",
                useGpu,
                onCheckedChange = { useGpu = it; prefs.useGpuBackend = it }
            )
            SettingItem("Path Model GGUF", prefs.modelPath, Icons.Default.Terminal) {}
        }

        Section("Keamanan & Sistem") {
            SettingToggle(
                "Konfirmasi Sebelum Eksekusi",
                "Tanya sebelum menjalankan perintah shell",
                confirmExec,
                onCheckedChange = { confirmExec = it; prefs.isConfirmBeforeExec = it }
            )
            SettingItem("Bersihkan Riwayat Chat", "Hapus semua pesan lokal", Icons.Default.DeleteForever) {}
        }

        Section("Informasi") {
            SettingItem("Versi Aplikasi", "0.6.0 (Stable)", Icons.Default.Info) {}
            SettingItem("Lisensi", "Apache License 2.0", Icons.Default.Description) {}
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = SynapticColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = SynapticColors.Surface1),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun SettingToggle(title: String, desc: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.padding(16.dp).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = SynapticColors.Text1, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(desc, color = SynapticColors.Text3, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = SynapticColors.Accent, checkedTrackColor = SynapticColors.Accent.copy(alpha = 0.5f))
        )
    }
}

@Composable
private fun SettingItem(title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier.clickable { onClick() }.padding(16.dp).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(icon, null, tint = SynapticColors.Text3, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = SynapticColors.Text1, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(value, color = SynapticColors.Text3, fontSize = 12.sp, maxLines = 1)
        }
        Icon(Icons.Default.ChevronRight, null, tint = SynapticColors.Text3)
    }
}
