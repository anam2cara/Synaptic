package com.synaptic.ai.ui.features

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.synaptic.ai.ui.SynapticColors

@Composable
fun FeaturesScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SynapticColors.Background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Fitur Utama", color = SynapticColors.Text1, fontWeight = FontWeight.Black, fontSize = 24.sp)
        
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item { FeatureItem("Bersihkan Cache", "Hapus file sampah sistem", Icons.Default.DeleteSweep, Color(0xFF64B5F6)) }
            item { FeatureItem("Analisis Detail", "Diagnosa kesehatan HP", Icons.Default.Analytics, Color(0xFFBA68C8)) }
            item { FeatureItem("Logcat Viewer", "Baca log sistem real-time", Icons.Default.BugReport, Color(0xFFFF8A65)) }
            item { FeatureItem("Tool Registry", "Daftar kemampuan AI", Icons.Default.List, Color(0xFF81C784)) }
            item { FeatureItem("Pengaturan", "Konfigurasi aplikasi", Icons.Default.Settings, Color(0xFF90A4AE)) }
        }
    }
}

@Composable
private fun FeatureItem(title: String, desc: String, icon: ImageVector, color: Color) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SynapticColors.Surface1),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().height(140.dp).clickable { }
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(color.copy(alpha = 0.1f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
            }
            Text(title, color = SynapticColors.Text1, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(desc, color = SynapticColors.Text3, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}
