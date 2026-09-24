package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.CompressionConfig
import com.example.model.CompressionMode
import com.example.model.ConversionJobResult
import com.example.model.FileDetails
import com.example.model.QualityProfile
import com.example.ui.MainViewModel
import com.example.ui.components.FileDetailsCard
import com.example.ui.components.JobResultCard
import com.example.ui.theme.EmeraldSuccess

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CompressScreen(
    viewModel: MainViewModel,
    selectedFile: FileDetails?,
    compressionConfig: CompressionConfig,
    isProcessing: Boolean,
    progress: Float,
    progressStatus: String,
    lastJobResult: ConversionJobResult?,
    sampleFiles: List<FileDetails>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.selectUri(it) }
    }

    var customSizeInput by remember { mutableStateOf("500") }
    var customUnitMb by remember { mutableStateOf(false) } // false = KB, true = MB

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero Header
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.secondary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Compress,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondary,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Intelligent Size Compressor",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Compress any file to your exact chosen size or ratio",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // File Details / Selector
        FileDetailsCard(
            file = selectedFile,
            onPickFileClick = { filePickerLauncher.launch(arrayOf("*/*")) },
            onClearClick = { viewModel.clearSelection() },
            sampleFiles = sampleFiles,
            onSelectSample = { viewModel.selectSampleFile(it) }
        )

        // Compression Settings (when file selected)
        if (selectedFile != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                ),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "TARGET PROPORTION & SIZE",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    // Mode Tabs
                    val modes = listOf("Exact Size", "Percentage", "Profile")
                    val selectedTabIndex = when (compressionConfig.mode) {
                        CompressionMode.EXACT_TARGET_SIZE -> 0
                        CompressionMode.PERCENTAGE_REDUCTION -> 1
                        CompressionMode.QUALITY_PRESET -> 2
                    }

                    TabRow(
                        selectedTabIndex = selectedTabIndex,
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp))
                    ) {
                        modes.forEachIndexed { idx, label ->
                            Tab(
                                selected = selectedTabIndex == idx,
                                onClick = {
                                    val newMode = when (idx) {
                                        0 -> CompressionMode.EXACT_TARGET_SIZE
                                        1 -> CompressionMode.PERCENTAGE_REDUCTION
                                        else -> CompressionMode.QUALITY_PRESET
                                    }
                                    viewModel.setCompressionMode(newMode)
                                },
                                text = {
                                    Text(
                                        text = label,
                                        fontSize = 12.sp,
                                        fontWeight = if (selectedTabIndex == idx) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Mode 0: EXACT TARGET SIZE
                    if (compressionConfig.mode == CompressionMode.EXACT_TARGET_SIZE) {
                        Text(
                            text = "Choose target size constraint:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        // Quick target chips
                        val quickSizes = listOf(
                            Pair("100 KB", 100 * 1024L),
                            Pair("300 KB", 300 * 1024L),
                            Pair("500 KB", 500 * 1024L),
                            Pair("1 MB", 1024 * 1024L),
                            Pair("2 MB", 2 * 1024 * 1024L),
                            Pair("8 MB (Discord)", 8 * 1024 * 1024L),
                            Pair("25 MB (Email)", 25 * 1024 * 1024L)
                        )

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            quickSizes.forEach { (label, bytes) ->
                                val isSelected = compressionConfig.targetSizeBytes == bytes
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { viewModel.setTargetSizeBytes(bytes) },
                                    label = { Text(text = label, fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Custom Numeric Input
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = customSizeInput,
                                onValueChange = { newVal ->
                                    if (newVal.all { it.isDigit() || it == '.' }) {
                                        customSizeInput = newVal
                                        val num = newVal.toDoubleOrNull() ?: 0.0
                                        val multiplier = if (customUnitMb) 1024 * 1024L else 1024L
                                        val bytes = (num * multiplier).toLong()
                                        if (bytes > 0) viewModel.setTargetSizeBytes(bytes)
                                    }
                                },
                                label = { Text("Custom Target Size") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("custom_target_size_input"),
                                singleLine = true
                            )

                            // Unit toggle button
                            Surface(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        customUnitMb = !customUnitMb
                                        val num = customSizeInput.toDoubleOrNull() ?: 0.0
                                        val multiplier = if (customUnitMb) 1024 * 1024L else 1024L
                                        val bytes = (num * multiplier).toLong()
                                        if (bytes > 0) viewModel.setTargetSizeBytes(bytes)
                                    }
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                                    .padding(horizontal = 16.dp, vertical = 16.dp)
                            ) {
                                Text(
                                    text = if (customUnitMb) "MB" else "KB",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    // Mode 1: PERCENTAGE REDUCTION
                    if (compressionConfig.mode == CompressionMode.PERCENTAGE_REDUCTION) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Reduction Amount:",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${compressionConfig.percentageReduction}% smaller",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = EmeraldSuccess)
                            )
                        }

                        Slider(
                            value = compressionConfig.percentageReduction.toFloat(),
                            onValueChange = { viewModel.setPercentageReduction(it.toInt()) },
                            valueRange = 10f..90f,
                            steps = 15,
                            modifier = Modifier.testTag("percentage_slider")
                        )

                        val presets = listOf(25, 50, 75, 90)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            presets.forEach { pct ->
                                FilterChip(
                                    selected = compressionConfig.percentageReduction == pct,
                                    onClick = { viewModel.setPercentageReduction(pct) },
                                    label = { Text(text = "-$pct%") }
                                )
                            }
                        }
                    }

                    // Mode 2: QUALITY PRESET
                    if (compressionConfig.mode == CompressionMode.QUALITY_PRESET) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            QualityProfile.entries.forEach { profile ->
                                val isSelected = compressionConfig.qualityProfile == profile
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .border(
                                            width = if (isSelected) 2.dp else 1.dp,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                            else MaterialTheme.colorScheme.surface
                                        )
                                        .clickable { viewModel.setQualityProfile(profile) }
                                        .padding(12.dp)
                                ) {
                                    Column {
                                        Text(
                                            text = profile.label,
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = profile.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Live Estimated Output Projection Card
                    Spacer(modifier = Modifier.height(16.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                    ) {
                        val projectedTargetBytes = when (compressionConfig.mode) {
                            CompressionMode.EXACT_TARGET_SIZE -> compressionConfig.targetSizeBytes
                            CompressionMode.PERCENTAGE_REDUCTION -> (selectedFile.size * (100 - compressionConfig.percentageReduction) / 100.0).toLong()
                            CompressionMode.QUALITY_PRESET -> when (compressionConfig.qualityProfile) {
                                QualityProfile.ULTRA_COMPACT -> (selectedFile.size * 0.25).toLong()
                                QualityProfile.BALANCED -> (selectedFile.size * 0.50).toLong()
                                QualityProfile.HIGH_FIDELITY -> (selectedFile.size * 0.75).toLong()
                                QualityProfile.EXTREME_DEFLATE -> (selectedFile.size * 0.60).toLong()
                            }
                        }
                        val estSavings = if (selectedFile.size > 0) {
                            val diff = selectedFile.size - projectedTargetBytes
                            ((diff.toDouble() / selectedFile.size.toDouble()) * 100).toInt().coerceIn(0, 99)
                        } else 0

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(text = "CURRENT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(text = selectedFile.formattedSize, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                            }
                            Text(text = "➔", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                            Column(horizontalAlignment = Alignment.End) {
                                Text(text = "TARGET LIMIT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    text = "${FileDetails.formatBytes(projectedTargetBytes)} (~$estSavings% saved)",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = EmeraldSuccess)
                                )
                            }
                        }
                    }
                }
            }

            // Compress Action Button
            Spacer(modifier = Modifier.height(4.dp))
            if (isProcessing) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = progressStatus,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                }
            } else {
                Button(
                    onClick = { viewModel.executeCompression() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("compress_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Compress,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Compress to Target Size",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }

        // Job Result
        if (lastJobResult != null) {
            JobResultCard(
                result = lastJobResult,
                onOpenClick = { viewModel.openFile(context, it) },
                onShareClick = { viewModel.shareFile(context, it) }
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}
