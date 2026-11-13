package com.example.ui.screens

import android.media.MediaMetadataRetriever
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
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoSettings
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.BatchJobSummary
import com.example.model.CompressionConfig
import com.example.model.CompressionMode
import com.example.model.ConversionJobResult
import com.example.model.FileCategory
import com.example.model.FileDetails
import com.example.model.MetadataConfig
import com.example.model.QualityProfile
import com.example.model.VideoCodec
import com.example.model.VideoCompressionConfig
import com.example.model.VideoPresetStore
import com.example.model.VideoQualityPreset
import com.example.model.VideoResolution
import com.example.model.VideoTargetPreset
import com.example.ui.MainViewModel
import com.example.ui.components.BatchJobResultCard
import com.example.ui.components.JobResultCard
import com.example.ui.components.MetadataPreferenceCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    val videoConfig by viewModel.videoConfig.collectAsStateWithLifecycle()

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
    var customUnitMb by remember { mutableStateOf(false) }
    var showAdvancedSettings by remember { mutableStateOf(false) }

    // Video Studio State
    val isVideoSelected = selectedFiles.any { it.category == FileCategory.VIDEO }
    var videoStudioTab by remember { mutableIntStateOf(0) } // 0=Presets, 1=Video, 2=Audio
    var customPresets by remember { mutableStateOf(VideoPresetStore.loadPresets(context)) }
    var showPresetEditor by remember { mutableStateOf(false) }
    var videoDurationMs by remember { mutableLongStateOf(15_000L) }

    // Inspect actual video duration for impossible-size prediction
    LaunchedEffect(selectedFiles) {
        val firstVideo = selectedFiles.firstOrNull { it.category == FileCategory.VIDEO }
        if (firstVideo != null) {
            videoDurationMs = withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                var dur = 15_000L
                try {
                    context.contentResolver.openFileDescriptor(firstVideo.uri, "r")?.use { pfd ->
                        retriever.setDataSource(pfd.fileDescriptor)
                    } ?: retriever.setDataSource(context, firstVideo.uri)
                    dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 15_000L
                } catch (_: Exception) {
                } finally {
                    retriever.release()
                }
                dur
            }
        }
    }

    val accentSavings = Color(0xFF10B981)
    val warningAmber = Color(0xFFF59E0B)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // =========================================================================
        // 1. FILE PICKER CARD
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
                        text = "Video Studio, Perceptual Images, Audio & PDF",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { filePicker.launch(arrayOf("*/*")) },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
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
                                    Icon(Icons.Default.Description, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
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
                                        modifier = Modifier.size(14.dp).clickable { viewModel.removeFile(file) },
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
        // 2A. 3-TAB VIDEO COMPRESSION STUDIO (JoshAtticus Style)
        // =========================================================================
        if (selectedFiles.isNotEmpty() && isVideoSelected) {
            val totalOrigBytes = selectedFiles.sumOf { it.size }
            val durationSec = (videoDurationMs / 1000.0).coerceAtLeast(1.0)
            val audioBps = if (videoConfig.removeAudio) 0.0 else videoConfig.audioBitrateKbps * 1000.0
            val estTotalBps = (videoConfig.targetSizeBytes * 8.0) / durationSec
            val estVideoKbps = ((estTotalBps - audioBps) / 1000.0).toInt()

            val isImpossible = estVideoKbps < 140
            val isLowQualityForRes = (videoConfig.resolution == VideoResolution.P1080 && estVideoKbps < 900) ||
                (videoConfig.resolution == VideoResolution.P720 && estVideoKbps < 400)

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // 3 Studio Tabs: Presets | Video | Audio
                    val studioTabs = listOf("Presets", "Video", "Audio")
                    TabRow(
                        selectedTabIndex = videoStudioTab,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        contentColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp))
                    ) {
                        for (idx in studioTabs.indices) {
                            Tab(
                                selected = videoStudioTab == idx,
                                onClick = { videoStudioTab = idx },
                                icon = {
                                    val ic = when (idx) {
                                        0 -> Icons.Default.Tune
                                        1 -> Icons.Default.VideoSettings
                                        else -> Icons.Default.Audiotrack
                                    }
                                    Icon(ic, null, modifier = Modifier.size(16.dp))
                                },
                                text = {
                                    Text(
                                        text = studioTabs[idx],
                                        fontSize = 12.sp,
                                        fontWeight = if (videoStudioTab == idx) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // -----------------------------------------------------------------
                    // TAB A: PRESETS (Quality + Customizable Platform Target Sizes)
                    // -----------------------------------------------------------------
                    if (videoStudioTab == 0) {
                        Text(
                            text = "QUALITY PRESET",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (qp in listOf(VideoQualityPreset.HIGH, VideoQualityPreset.MEDIUM, VideoQualityPreset.LOW)) {
                                val selected = videoConfig.qualityPreset == qp
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .border(
                                            width = if (selected) 2.dp else 1.dp,
                                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                                        )
                                        .clickable {
                                            val newRes = when (qp) {
                                                VideoQualityPreset.HIGH -> VideoResolution.ORIGINAL
                                                VideoQualityPreset.MEDIUM -> VideoResolution.P1080
                                                VideoQualityPreset.LOW -> VideoResolution.P720
                                                VideoQualityPreset.CUSTOM -> videoConfig.resolution
                                            }
                                            viewModel.setVideoConfig(
                                                videoConfig.copy(
                                                    qualityPreset = qp,
                                                    resolution = newRes,
                                                    targetFps = qp.targetFps
                                                )
                                            )
                                        }
                                        .padding(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                text = qp.label,
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = qp.subtitle,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (selected) {
                                            Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "TARGET SIZE PRESETS",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
                                color = MaterialTheme.colorScheme.primary
                            )
                            TextButton(onClick = { showPresetEditor = true }) {
                                Icon(Icons.Default.Edit, null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Customize", fontSize = 11.sp)
                            }
                        }

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for (preset in customPresets) {
                                val selected = videoConfig.targetSizeBytes == preset.sizeBytes
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        viewModel.setVideoConfig(videoConfig.copy(targetSizeBytes = preset.sizeBytes))
                                    },
                                    label = {
                                        Text("${preset.name} (${FileDetails.formatBytes(preset.sizeBytes)})", fontSize = 11.sp)
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                )
                            }
                        }
                    }

                    // -----------------------------------------------------------------
                    // TAB B: ADVANCED VIDEO (Slider, Codec, Resolution, FPS)
                    // -----------------------------------------------------------------
                    if (videoStudioTab == 1) {
                        val maxSliderMb = (totalOrigBytes / (1024f * 1024f)).coerceAtLeast(10f)
                        val currentMb = (videoConfig.targetSizeBytes / (1024f * 1024f)).coerceIn(1f, maxSliderMb)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Target File Size", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = FileDetails.formatBytes(videoConfig.targetSizeBytes),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Slider(
                            value = currentMb,
                            onValueChange = { mb ->
                                viewModel.setVideoConfig(
                                    videoConfig.copy(
                                        qualityPreset = VideoQualityPreset.CUSTOM,
                                        targetSizeBytes = (mb.toLong() * 1024L * 1024L).coerceAtLeast(512 * 1024L)
                                    )
                                )
                            },
                            valueRange = 1f..maxSliderMb
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        Text("VIDEO ENCODING CODEC", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(6.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (codec in VideoCodec.entries) {
                                val selected = videoConfig.codec == codec
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .border(
                                            if (selected) 2.dp else 1.dp,
                                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                            RoundedCornerShape(10.dp)
                                        )
                                        .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface)
                                        .clickable { viewModel.setVideoConfig(videoConfig.copy(codec = codec)) }
                                        .padding(10.dp)
                                ) {
                                    Column {
                                        Text(codec.label, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                                        Text(codec.description, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Text("RESOLUTION", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (res in VideoResolution.entries) {
                                FilterChip(
                                    selected = videoConfig.resolution == res,
                                    onClick = {
                                        viewModel.setVideoConfig(
                                            videoConfig.copy(qualityPreset = VideoQualityPreset.CUSTOM, resolution = res)
                                        )
                                    },
                                    label = { Text(res.label, fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Text("FRAME RATE (FPS)", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val fpsChoices = listOf(Pair("Original", 0f), Pair("60 FPS", 60f), Pair("30 FPS", 30f), Pair("24 FPS", 24f), Pair("15 FPS", 15f))
                            for ((label, fps) in fpsChoices) {
                                FilterChip(
                                    selected = videoConfig.targetFps == fps,
                                    onClick = {
                                        viewModel.setVideoConfig(
                                            videoConfig.copy(qualityPreset = VideoQualityPreset.CUSTOM, targetFps = fps)
                                        )
                                    },
                                    label = { Text(label, fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                )
                            }
                        }
                    }

                    // -----------------------------------------------------------------
                    // TAB C: ADVANCED AUDIO (Remove, Bitrate, Volume Gain)
                    // -----------------------------------------------------------------
                    if (videoStudioTab == 2) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.VolumeOff, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text("Remove Audio Track", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                    Text("Saves space by stripping audio completely", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Switch(
                                checked = videoConfig.removeAudio,
                                onCheckedChange = { viewModel.setVideoConfig(videoConfig.copy(removeAudio = it)) }
                            )
                        }

                        if (!videoConfig.removeAudio) {
                            Spacer(modifier = Modifier.height(14.dp))
                            Text("AUDIO BITRATE", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(6.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (kbps in listOf(48, 64, 96, 128, 192)) {
                                    FilterChip(
                                        selected = videoConfig.audioBitrateKbps == kbps,
                                        onClick = { viewModel.setVideoConfig(videoConfig.copy(audioBitrateKbps = kbps)) },
                                        label = { Text("$kbps kbps", fontSize = 11.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                        )
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Audio Volume Gain", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    text = "${(videoConfig.audioVolume * 100).toInt()}%",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Slider(
                                value = videoConfig.audioVolume,
                                onValueChange = { viewModel.setVideoConfig(videoConfig.copy(audioVolume = it)) },
                                valueRange = 0.25f..2.0f,
                                steps = 6
                            )
                        }
                    }

                    // -----------------------------------------------------------------
                    // IMPOSSIBLE SIZE WARNING & ONE-TAP SUGGESTIONS
                    // -----------------------------------------------------------------
                    if (isImpossible || isLowQualityForRes) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = warningAmber.copy(alpha = 0.14f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, warningAmber.copy(alpha = 0.4f))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Warning, null, tint = warningAmber, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isImpossible) "Target size too tight (~${estVideoKbps.coerceAtLeast(1)} kbps)"
                                               else "Low bitrate for ${videoConfig.resolution.label} (~$estVideoKbps kbps)",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                        color = warningAmber
                                    )
                                }
                                Text(
                                    text = "Suggestions to keep video watchable:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
                                )
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (videoConfig.resolution != VideoResolution.P480 && videoConfig.resolution != VideoResolution.P360) {
                                        OutlinedButton(
                                            onClick = { viewModel.setVideoConfig(videoConfig.copy(resolution = VideoResolution.P480, qualityPreset = VideoQualityPreset.CUSTOM)) },
                                            shape = RoundedCornerShape(8.dp)
                                        ) { Text("Drop to 480p", fontSize = 11.sp) }
                                    }
                                    if (videoConfig.targetFps == 0f || videoConfig.targetFps > 24f) {
                                        OutlinedButton(
                                            onClick = { viewModel.setVideoConfig(videoConfig.copy(targetFps = 24f, qualityPreset = VideoQualityPreset.CUSTOM)) },
                                            shape = RoundedCornerShape(8.dp)
                                        ) { Text("Cap 24 FPS", fontSize = 11.sp) }
                                    }
                                    if (videoConfig.codec != VideoCodec.H265) {
                                        OutlinedButton(
                                            onClick = { viewModel.setVideoConfig(videoConfig.copy(codec = VideoCodec.H265)) },
                                            shape = RoundedCornerShape(8.dp)
                                        ) { Text("Use H.265", fontSize = 11.sp) }
                                    }
                                    if (!videoConfig.removeAudio) {
                                        OutlinedButton(
                                            onClick = { viewModel.setVideoConfig(videoConfig.copy(removeAudio = true)) },
                                            shape = RoundedCornerShape(8.dp)
                                        ) { Text("Strip Audio", fontSize = 11.sp) }
                                    }
                                }
                            }
                        }
                    }

                    // Forecast Summary Pill
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("ORIGINAL", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(FileDetails.formatBytes(totalOrigBytes), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                            }
                            Text("➔", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                            Column(horizontalAlignment = Alignment.End) {
                                Text("${videoConfig.codec.label.substringBefore(" ")} • ${videoConfig.resolution.label}", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = accentSavings)
                                Text(FileDetails.formatBytes(videoConfig.targetSizeBytes), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = accentSavings))
                            }
                        }
                    }
                }
            }
        } else if (selectedFiles.isNotEmpty()) {
            // =========================================================================
            // 2B. STANDARD COMPRESSION CONFIG (Images, Audio, PDF, Code)
            // =========================================================================
            val totalBatchBytes = selectedFiles.sumOf { it.size }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "COMPRESSION TARGET",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(10.dp))

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
                        for (idx in modes.indices) {
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
                                text = { Text(modes[idx], fontSize = 12.sp, fontWeight = if (selectedTabIndex == idx) FontWeight.Bold else FontWeight.Normal) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    if (compressionConfig.mode == CompressionMode.EXACT_TARGET_SIZE) {
                        val quickSizes = listOf(
                            Pair("100 KB", 100 * 1024L),
                            Pair("500 KB", 500 * 1024L),
                            Pair("1 MB", 1024 * 1024L),
                            Pair("2 MB", 2 * 1024 * 1024L),
                            Pair("10 MB (GitHub)", 10 * 1024 * 1024L),
                            Pair("25 MB (Email)", 25 * 1024 * 1024L)
                        )

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for ((label, bytes) in quickSizes) {
                                FilterChip(
                                    selected = compressionConfig.targetSizeBytes == bytes,
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
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                modifier = Modifier.weight(1f).testTag("custom_target_size_input"),
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
                                Text(if (customUnitMb) "MB" else "KB", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }

                    if (compressionConfig.mode == CompressionMode.PERCENTAGE_REDUCTION) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Target Reduction:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("-${compressionConfig.percentageReduction}%", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = accentSavings))
                        }

                        Slider(
                            value = compressionConfig.percentageReduction.toFloat(),
                            onValueChange = { viewModel.setPercentageReduction(it.toInt()) },
                            valueRange = 10f..90f,
                            steps = 15,
                            modifier = Modifier.testTag("percentage_slider")
                        )

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            for (pct in listOf(25, 50, 75, 90)) {
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

                    if (compressionConfig.mode == CompressionMode.QUALITY_PRESET) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (profile in QualityProfile.entries) {
                                val isSelected = compressionConfig.qualityProfile == profile
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .border(if (isSelected) 2.dp else 1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                                        .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                        .clickable { viewModel.setQualityProfile(profile) }
                                        .padding(12.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (isSelected) {
                                            Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                        }
                                        Column {
                                            Text(profile.label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                            Text(profile.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // =========================================================================
        // 3. COLLAPSIBLE METADATA OPTIONS & ACTION BUTTON
        // =========================================================================
        if (selectedFiles.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth().animateContentSize(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { showAdvancedSettings = !showAdvancedSettings },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Tune, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Advanced Options & Privacy", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                        }
                        Icon(if (showAdvancedSettings) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    AnimatedVisibility(
                        visible = showAdvancedSettings,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column(modifier = Modifier.padding(top = 10.dp)) {
                            MetadataPreferenceCard(config = metadataConfig, onConfigChange = { viewModel.setMetadataConfig(it) })
                        }
                    }
                }
            }

            if (isProcessing) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(progressStatus, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
                    }
                }
            } else {
                Button(
                    onClick = { viewModel.executeCompression() },
                    modifier = Modifier.fillMaxWidth().height(54.dp).testTag("compress_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.Compress, null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (selectedFiles.size > 1) "Compress Batch (${selectedFiles.size} Files)" else "Compress to Target",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }

        // =========================================================================
        // 4. RESULTS
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

    // =========================================================================
    // CUSTOM PRESET EDITOR DIALOG
    // =========================================================================
    if (showPresetEditor) {
        var newName by remember { mutableStateOf("") }
        var newMb by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showPresetEditor = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Customize Target Presets", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    IconButton(onClick = {
                        customPresets = VideoPresetStore.resetDefaults(context)
                    }) {
                        Icon(Icons.Default.Refresh, "Reset defaults")
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = { Text("Name", fontSize = 11.sp) },
                            modifier = Modifier.weight(1.3f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = newMb,
                            onValueChange = { newMb = it.filter { c -> c.isDigit() } },
                            label = { Text("MB", fontSize = 11.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(0.9f),
                            singleLine = true
                        )
                        IconButton(onClick = {
                            val mbVal = newMb.toLongOrNull() ?: 0L
                            if (newName.isNotBlank() && mbVal > 0) {
                                val updated = customPresets + VideoTargetPreset(
                                    id = "custom_${System.currentTimeMillis()}",
                                    name = newName.trim(),
                                    sizeBytes = mbVal * 1024L * 1024L,
                                    isDefault = false
                                )
                                customPresets = updated
                                VideoPresetStore.savePresets(context, updated)
                                newName = ""
                                newMb = ""
                            }
                        }) {
                            Icon(Icons.Default.Add, "Add", tint = MaterialTheme.colorScheme.primary)
                        }
                    }

                    Column(
                        modifier = Modifier.height(220.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        for (p in customPresets) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("${p.name} • ${FileDetails.formatBytes(p.sizeBytes)}", style = MaterialTheme.typography.bodySmall)
                                IconButton(
                                    onClick = {
                                        val updated = customPresets.filterNot { it.id == p.id }
                                        customPresets = updated
                                        VideoPresetStore.savePresets(context, updated)
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.Delete, "Delete", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPresetEditor = false }) { Text("Done") }
            }
        )
    }
}