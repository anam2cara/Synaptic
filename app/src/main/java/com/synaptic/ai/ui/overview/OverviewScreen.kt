package com.synaptic.ai.ui.overview

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.synaptic.ai.ui.SynapticColors
import com.synaptic.ai.core.model.DeviceSnapshot
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke

@Composable
fun OverviewScreen(viewModel: OverviewViewModel = viewModel()) {
    val snapshot by viewModel.snapshot.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        DeviceHeaderCard()
        CpuCard(snapshot)
        MemoryCard(snapshot)
        GpuCard(snapshot)
        BatteryCard(snapshot)
    }
}

@Composable
private fun DeviceHeaderCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = SynapticColors.Surface1),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .background(SynapticColors.Accent.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("AI", color = SynapticColors.Accent, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            Column {
                Text("Samsung Galaxy SM-A366B", color = SynapticColors.Text1, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("Snapdragon™ 6 Gen 3 | 4 nm", color = SynapticColors.Text3, fontSize = 13.sp)
                Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Badge("arm64-v8a")
                    Badge("Android 16")
                }
            }
        }
    }
}

@Composable
private fun CpuCard(s: DeviceSnapshot) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SynapticColors.Surface1),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("CPU", color = SynapticColors.Text1, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("${s.cpuUsagePercent.toInt()}%", color = SynapticColors.Success, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text("${s.cpuTempCelsius.toInt()}°C", color = Color(0xFFFFB74D), fontWeight = FontWeight.Bold)
            }
            
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                s.cpuPerCoreUsage.chunked(2).forEachIndexed { rowIndex, pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEachIndexed { colIndex, usage ->
                            val coreIdx = rowIndex * 2 + colIndex
                            CoreProgressBar(index = coreIdx, usage = usage, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CoreProgressBar(index: Int, usage: Float, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(index.toString(), color = SynapticColors.Text3, fontSize = 10.sp, modifier = Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.05f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(usage / 100f)
                    .clip(CircleShape)
                    .background(if (usage > 70) Color(0xFFFF7043) else if (usage > 30) Color(0xFFFFD54F) else Color(0xFF81C784))
            )
        }
        Text("${usage.toInt()}%", color = SynapticColors.Text2, fontSize = 10.sp, modifier = Modifier.padding(start = 4.dp).width(25.dp))
    }
}

@Composable
private fun MemoryCard(s: DeviceSnapshot) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SynapticColors.Surface1),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(100.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { s.ramUsedPercent / 100f },
                    modifier = Modifier.fillMaxSize(),
                    color = SynapticColors.Accent,
                    strokeWidth = 10.dp,
                    trackColor = Color.White.copy(alpha = 0.05f),
                    strokeCap = StrokeCap.Round
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${s.ramUsedPercent.toInt()}%", color = SynapticColors.Text1, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text("RAM", color = SynapticColors.Text3, fontSize = 10.sp)
                }
            }
            
            Column(modifier = Modifier.padding(start = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                InfoLine("Physical", "${String.format("%.1f", s.ramTotalBytes / 1e9f)} GB")
                InfoLine("Available", "${String.format("%.1f", s.ramFreeBytes / 1e9f)} GB")
                InfoLine("Used", "${String.format("%.1f", (s.ramTotalBytes - s.ramFreeBytes) / 1e9f)} GB")
            }
        }
    }
}

@Composable
private fun GpuCard(s: DeviceSnapshot) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SynapticColors.Surface1),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("GPU", color = SynapticColors.Text1, fontWeight = FontWeight.Bold)
            Text(s.gpuModel, color = SynapticColors.Text2, fontSize = 14.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f).height(10.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.05f))) {
                    Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(s.gpuBusyPercent.coerceAtLeast(0f) / 100f).background(Color(0xFFBA68C8)))
                }
                Text("${s.gpuBusyPercent.toInt()}%", color = SynapticColors.Text2, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun BatteryCard(s: DeviceSnapshot) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SynapticColors.Surface1),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Baterai", color = SynapticColors.Text1, fontWeight = FontWeight.Bold)
                Text(if (s.isCharging) "Sedang Mengisi Daya" else "Tidak Mengisi Daya", color = SynapticColors.Text3, fontSize = 12.sp)
            }
            Text("${s.batteryLevel}%", color = SynapticColors.Accent, fontWeight = FontWeight.Black, fontSize = 28.sp)
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row {
        Text(label, color = SynapticColors.Text3, fontSize = 13.sp, modifier = Modifier.width(80.dp))
        Text(value, color = SynapticColors.Text1, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Badge(text: String) {
    Surface(
        color = Color.White.copy(alpha = 0.05f),
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.padding(end = 4.dp)
    ) {
        Text(text, color = SynapticColors.Text3, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}
