package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.BatchJobSummary
import com.example.model.CompressionConfig
import com.example.model.CompressionMode
import com.example.model.ConversionJobResult
import com.example.model.FileDetails
import com.example.model.MetadataConfig
import com.example.model.QualityProfile
import com.example.ui.MainViewModel
import com.example.ui.components.BatchJobResultCard
import com.example.ui.components.JobResultCard
import com.example.ui.components.MetadataPreferenceCard

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CompressScreen(
    viewModel: MainViewModel,
    selectedFiles: List<FileDetails>,
    compressionConfig: CompressionConfig,
    metadataConfig: MetadataConfig,
    isProcessing: Boolean,
    progress: Float,
    progressStatus: String,
    lastJobResult: ConversionJobResult?,
    batchSummary: BatchJobSummary?,
    sampleFiles: List<FileDetails>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) viewModel.selectUris(uris)
    }

    val addMorePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) viewModel.addUris(uris)
    }

    val visualPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) viewModel.selectUris(uris)
    }

    var customSizeInput by remember { mutableStateOf("500") }
    var customUnitMb by remember { mutableStateOf(false) } // false = KB, true = MB
    var showAdvancedSettings by remember { mutableStateOf(false) }

    val accentSavings = Color(0xFF10B981) // Crisp savings green

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // =========================================================================
        // 1. FILE PICKER CARD (Empty Hero vs Compact Active Tray)
        // =========================================================================
        if (selectedFiles.isEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { filePicker.launch(arrayOf("*/*")) },
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Compress,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Choose Files to Compress",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Shrink images, video, audio & PDF to your exact target",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { filePicker.launch(arrayOf("*/*")) },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(Icons.Default.InsertDriveFile, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Documents", fontSize = 13.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                visualPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                )
                            },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.PhotoLibrary, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Media", fontSize = 13.sp)
                        }
                    }
                }
            }
        } else {
            // COMPACT FILE TRAY
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "${selectedFiles.size} QUEUED",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Total: ${FileDetails.formatBytes(selectedFiles.sumOf { it.size })}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row {
                            IconButton(onClick = { addMorePicker.launch(arrayOf("*/*")) }) {
                                Icon(Icons.Default.Add, "Add Files", tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = { viewModel.clearSelection() }) {
                                Icon(Icons.Default.Close, "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(selectedFiles.size) { index ->
                            val file = selectedFiles[index]
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Description,
                                        null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = file.name,
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.width(140.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        Icons.Default.Close,
                                        "Remove",
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clickable { viewModel.removeFile(file) },
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // =========================================================================
        // 2. COMPRESSION CONFIGURATION (Only when files selected)
        // =========================================================================
        if (selectedFiles.isNotEmpty()) {
            val totalBatchBytes = selectedFiles.sumOf { it.size }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "COMPRESSION TARGET",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        ),
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
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
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

                    Spacer(modifier = Modifier.height(14.dp))

                    // MODE 0: EXACT TARGET SIZE
                    if (compressionConfig.mode == CompressionMode.EXACT_TARGET_SIZE) {
                        val quickSizes = listOf(
                            Pair("100 KB", 100 * 1024L),
                            Pair("500 KB", 500 * 1024L),
                            Pair("1 MB", 1024 * 1024L),
                            Pair("2 MB", 2 * 1024 * 1024L),
                            Pair("8 MB (Discord)", 8 * 1024 * 1024L),
                            Pair("25 MB (Email)", 25 * 1024 * 1024L)
                        )

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            quickSizes.forEach { (label, bytes) ->
                                val isSelected = compressionConfig.targetSizeBytes == bytes
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { viewModel.setTargetSizeBytes(bytes) },
                                    label = { Text(label, fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

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
                                label = { Text("Custom Size", fontSize = 12.sp) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("custom_target_size_input"),
                                singleLine = true
                            )

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
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
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

                    // MODE 1: PERCENTAGE REDUCTION
                    if (compressionConfig.mode == CompressionMode.PERCENTAGE_REDUCTION) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Target Reduction:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "-${compressionConfig.percentageReduction}%",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = accentSavings
                                )
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
                                    label = { Text("-$pct%", fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                )
                            }
                        }
                    }

                    // MODE 2: QUALITY PROFILE
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
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                        )
                                        .clickable { viewModel.setQualityProfile(profile) }
                                        .padding(12.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (isSelected) {
                                            Icon(
                                                Icons.Default.Check,
                                                null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                        }
                                        Column {
                                            Text(
                                                text = profile.label,
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = profile.description,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // LIVE BEFORE -> AFTER SAVINGS PROJECTION PILL
                    Spacer(modifier = Modifier.height(14.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        val projectedTargetBytes = when (compressionConfig.mode) {
                            CompressionMode.EXACT_TARGET_SIZE -> compressionConfig.targetSizeBytes * selectedFiles.size
                            CompressionMode.PERCENTAGE_REDUCTION -> (totalBatchBytes * (100 - compressionConfig.percentageReduction) / 100.0).toLong()
                            CompressionMode.QUALITY_PRESET -> when (compressionConfig.qualityProfile) {
                                QualityProfile.ULTRA_COMPACT -> (totalBatchBytes * 0.25).toLong()
                                QualityProfile.BALANCED -> (totalBatchBytes * 0.50).toLong()
                                QualityProfile.HIGH_FIDELITY -> (totalBatchBytes * 0.75).toLong()
                                QualityProfile.EXTREME_DEFLATE -> (totalBatchBytes * 0.60).toLong()
                            }
                        }
                        val estSavings = if (totalBatchBytes > 0) {
                            val diff = totalBatchBytes - projectedTargetBytes
                            ((diff.toDouble() / totalBatchBytes.toDouble()) * 100).toInt().coerceIn(0, 99)
                        } else 0

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "ORIGINAL",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = FileDetails.formatBytes(totalBatchBytes),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                            }
                            Text(
                                text = "➔",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "FORECAST (~$estSavings% saved)",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    color = accentSavings
                                )
                                Text(
                                    text = FileDetails.formatBytes(projectedTargetBytes),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = accentSavings
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // =========================================================================
            // 3. COLLAPSIBLE ADVANCED SETTINGS (Metadata & EXIF)
            // =========================================================================
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAdvancedSettings = !showAdvancedSettings },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Tune,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Advanced Options & Privacy",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Icon(
                            imageVector = if (showAdvancedSettings) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    AnimatedVisibility(
                        visible = showAdvancedSettings,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column(modifier = Modifier.padding(top = 10.dp)) {
                            MetadataPreferenceCard(
                                config = metadataConfig,
                                onConfigChange = { viewModel.setMetadataConfig(it) }
                            )
                        }
                    }
                }
            }

            // =========================================================================
            // 4. ACTION BUTTON / PROGRESS
            // =========================================================================
            if (isProcessing) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = progressStatus,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${(progress * 100).toInt()}%",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )
                    }
                }
            } else {
                Button(
                    onClick = { viewModel.executeCompression() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("compress_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Default.Compress, null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (selectedFiles.size > 1) {
                            "Compress Batch (${selectedFiles.size} Files)"
                        } else {
                            "Compress to Target"
                        },
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }

        // =========================================================================
        // 5. JOB RESULTS (Clean Card with Preview)
        // =========================================================================
        if (batchSummary != null && batchSummary.results.size > 1) {
            BatchJobResultCard(
                summary = batchSummary,
                onOpenClick = { viewModel.openFile(context, it) },
                onShareClick = { viewModel.shareFile(context, it) },
                onPreviewClick = { viewModel.showPreview(it) },
                onShareAllClick = { viewModel.shareAllResults(context, it) },
                onOpenFolderClick = { viewModel.openDownloadsFolder(context) }
            )
        } else if (lastJobResult != null) {
            JobResultCard(
                result = lastJobResult,
                onOpenClick = { viewModel.openFile(context, it) },
                onShareClick = { viewModel.shareFile(context, it) },
                onPreviewClick = { viewModel.showPreview(it) },
                onOpenFolderClick = { viewModel.openDownloadsFolder(context) }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}