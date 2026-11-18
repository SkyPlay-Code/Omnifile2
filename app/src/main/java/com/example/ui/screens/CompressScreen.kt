package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.engine.DeviceCodecInspector
import com.example.model.AndroidVideoPresetRepositoryFactory
import com.example.model.BatchJobSummary
import com.example.model.CompressionConfig
import com.example.model.CompressionMode
import com.example.model.ConversionJobResult
import com.example.model.FileCategory
import com.example.model.FileDetails
import com.example.model.MetadataConfig
import com.example.model.QualityProfile
import com.example.model.VideoAudioCodec
import com.example.model.VideoCodec
import com.example.model.VideoPresetRepository
import com.example.model.VideoQualityPreset
import com.example.model.VideoResolution
import com.example.model.VideoTargetPreset
import com.example.ui.MainViewModel
import com.example.ui.components.BatchJobResultCard
import com.example.ui.components.JobResultCard
import com.example.ui.components.MetadataPreferenceCard
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Suppress("UNUSED_PARAMETER")
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
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val videoConfig by viewModel.videoConfig.collectAsStateWithLifecycle()

    var hasAskedNotificationPerm by rememberSaveable { mutableStateOf(false) }
    var lastCompressClickMs by remember { mutableLongStateOf(0L) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (!isProcessing && uris.isNotEmpty()) viewModel.selectUris(uris)
    }

    val addMorePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (!isProcessing && uris.isNotEmpty()) viewModel.addUris(uris)
    }

    val visualPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris: List<Uri> ->
        if (!isProcessing && uris.isNotEmpty()) viewModel.selectUris(uris)
    }

    val addMoreVisualPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris: List<Uri> ->
        if (!isProcessing && uris.isNotEmpty()) viewModel.addUris(uris)
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        hasAskedNotificationPerm = true
        if (!isProcessing && selectedFiles.isNotEmpty()) {
            viewModel.executeCompression()
        }
    }

    // Standard compression input state (saved across config changes)
    var customSizeInput by rememberSaveable { mutableStateOf("500") }
    var customUnitMb by rememberSaveable { mutableStateOf(false) }
    var customSizeError by rememberSaveable { mutableStateOf<String?>(null) }
    var showAdvancedSettings by rememberSaveable { mutableStateOf(false) }

    // File categorization for mixed-batch support
    val videoFiles = remember(selectedFiles) {
        selectedFiles.filter { it.category == FileCategory.VIDEO }
    }
    val hasVideoFiles = videoFiles.isNotEmpty()
    val hasNonVideoFiles = remember(selectedFiles) {
        selectedFiles.any { it.category != FileCategory.VIDEO }
    }

    // Video Studio state
    var videoStudioTab by rememberSaveable { mutableIntStateOf(0) } // 0=Presets, 1=Video, 2=Audio
    val presetRepo = remember(context) { AndroidVideoPresetRepositoryFactory.create(context) }
    val presetMutex = remember { Mutex() }
    var customPresets by remember { mutableStateOf(VideoPresetRepository.DEFAULT_PRESETS) }
    var presetError by remember { mutableStateOf<String?>(null) }
    var showPresetEditor by rememberSaveable { mutableStateOf(false) }

    // null = loading, 0L = unavailable, >0L = longest video duration in batch
    val videoUrisKey = remember(videoFiles) { videoFiles.map { it.uri } }
    var maxVideoDurationMs by remember(videoUrisKey) { mutableStateOf<Long?>(null) }

    // Load user's saved target presets asynchronously with lock + error handling
    LaunchedEffect(presetRepo) {
        presetMutex.withLock {
            runCatching { presetRepo.loadPresets() }
                .onSuccess {
                    customPresets = it
                    presetError = null
                }
                .onFailure {
                    presetError = "Could not load saved presets"
                }
        }
    }

    // Probe device hardware/software encoders off the main thread
    val deviceCapabilities = rememberAsyncIterable {
        DeviceCodecInspector.probeDeviceVideoEncoders()
    }

    // Probe longest video duration across all queued videos off the main thread
    LaunchedEffect(videoUrisKey) {
        if (videoFiles.isEmpty()) {
            maxVideoDurationMs = null
            return@LaunchedEffect
        }
        maxVideoDurationMs = withContext(Dispatchers.IO) {
            var longestMs = 0L
            for (videoFile in videoFiles) {
                val retriever = MediaMetadataRetriever()
                try {
                    context.contentResolver.openFileDescriptor(videoFile.uri, "r")?.use { pfd ->
                        retriever.setDataSource(pfd.fileDescriptor)
                    } ?: retriever.setDataSource(context, videoFile.uri)
                    val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                    if (dur > longestMs) longestMs = dur
                } catch (_: Exception) {
                    // Ignore unreadable video metadata; handled via 0L sentinel
                } finally {
                    runCatching { retriever.release() }
                }
            }
            longestMs
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
        // 1. FILE PICKER / QUEUE CARD
        // =========================================================================
        if (selectedFiles.isEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(enabled = !isProcessing) { filePicker.launch(arrayOf("*/*")) },
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ),
                border = BorderStroke(
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

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { filePicker.launch(arrayOf("*/*")) },
                            enabled = !isProcessing,
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
                            enabled = !isProcessing,
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row {
                            IconButton(
                                onClick = {
                                    addMoreVisualPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                    )
                                },
                                enabled = !isProcessing
                            ) {
                                Icon(
                                    Icons.Default.PhotoLibrary,
                                    contentDescription = "Add Media",
                                    tint = if (isProcessing) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                    else MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(
                                onClick = { addMorePicker.launch(arrayOf("*/*")) },
                                enabled = !isProcessing
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = "Add Files",
                                    tint = if (isProcessing) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                    else MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(
                                onClick = { viewModel.clearSelection() },
                                enabled = !isProcessing
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Clear",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                        alpha = if (isProcessing) 0.4f else 1f
                                    )
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    val seenUriCounts = HashMap<String, Int>(selectedFiles.size)
                    val stableKeys = selectedFiles.map { file ->
                        val uriStr = file.uri.toString()
                        val occurrence = seenUriCounts.getOrDefault(uriStr, 0)
                        seenUriCounts[uriStr] = occurrence + 1
                        "${uriStr}_$occurrence"
                    }

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(
                            items = selectedFiles,
                            key = { index, _ -> stableKeys[index] }
                        ) { _, file ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Description,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = file.name,
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 140.dp)
                                    )
                                    IconButton(
                                        onClick = { viewModel.removeFile(file) },
                                        enabled = !isProcessing,
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Remove ${file.name}",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                alpha = if (isProcessing) 0.4f else 1f
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // =========================================================================
        // 2A. 3-TAB VIDEO COMPRESSION STUDIO
        // =========================================================================
        if (hasVideoFiles) {
            val totalVideoOrigBytes = videoFiles.sumOf { it.size }
            val referenceVideoOrigBytes = if (videoFiles.size == 1) {
                totalVideoOrigBytes
            } else {
                videoFiles.maxOfOrNull { it.size } ?: totalVideoOrigBytes
            }
            val isTargetLargerThanSource =
                referenceVideoOrigBytes > 0L && videoConfig.targetSizeBytes >= referenceVideoOrigBytes

            val durationKnown = (maxVideoDurationMs ?: 0L) > 0L
            val durationSec = if (durationKnown) (maxVideoDurationMs!! / 1000.0).coerceAtLeast(1.0) else 0.0
            val isMuted = videoConfig.isAudioMuted
            val isAudioCopied = videoConfig.audioCodec == VideoAudioCodec.COPY
            val audioBps = if (isMuted || isAudioCopied) 0.0 else videoConfig.audioBitrateKbps * 1000.0

            val estTotalBps = if (durationKnown) (videoConfig.targetSizeBytes * 8.0) / durationSec else 0.0
            val estVideoKbps = if (durationKnown) ((estTotalBps - audioBps) / 1000.0).toInt() else 0
            val audioExceedsTarget = durationKnown && !isMuted && !isAudioCopied && estTotalBps <= audioBps

            val isImpossible = durationKnown && estVideoKbps < 140
            val isLowQualityForRes = durationKnown && !isImpossible && (
                (videoConfig.resolution == VideoResolution.ORIGINAL && estVideoKbps < 600) ||
                    (videoConfig.resolution == VideoResolution.P1080 && estVideoKbps < 900) ||
                    (videoConfig.resolution == VideoResolution.P720 && estVideoKbps < 400)
                )

            val h265Cap = deviceCapabilities?.firstOrNull {
                it.mimeType.equals(VideoCodec.H265.mimeType, ignoreCase = true)
            }
            val canUseH265 = VideoCodec.H265.isAllowed(videoConfig.enableAllCodecs, Build.VERSION.SDK_INT) &&
                h265Cap != null &&
                (h265Cap.hasHardware || (videoConfig.enableAllCodecs && h265Cap.hasSoftware))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
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
                                enabled = !isProcessing,
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
                    // TAB A: PRESETS
                    // -----------------------------------------------------------------
                    if (videoStudioTab == 0) {
                        Text(
                            text = "QUALITY PRESET",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            ),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (qp in listOf(VideoQualityPreset.HIGH, VideoQualityPreset.MEDIUM, VideoQualityPreset.LOW)) {
                                val selected = videoConfig.qualityPreset == qp
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .alpha(if (isProcessing) 0.6f else 1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .border(
                                            width = if (selected) 2.dp else 1.dp,
                                            color = if (selected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                                        )
                                        .clickable(enabled = !isProcessing, role = Role.RadioButton) {
                                            viewModel.setVideoConfig(videoConfig.withQualityPreset(qp))
                                        }
                                        .padding(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = qp.label,
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                color = if (selected) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = qp.subtitle,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (selected) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
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
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp
                                ),
                                color = MaterialTheme.colorScheme.primary
                            )
                            TextButton(
                                onClick = { showPresetEditor = true },
                                enabled = !isProcessing
                            ) {
                                Icon(Icons.Default.Edit, null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Customize", fontSize = 11.sp)
                            }
                        }

                        if (customPresets.isEmpty()) {
                            Text(
                                text = "No presets available. Tap Customize to add or reset defaults.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                for (preset in customPresets) {
                                    val selected = videoConfig.targetSizeBytes == preset.sizeBytes
                                    FilterChip(
                                        selected = selected,
                                        enabled = !isProcessing,
                                        onClick = {
                                            viewModel.setVideoConfig(videoConfig.withTargetSizeBytes(preset.sizeBytes))
                                        },
                                        label = {
                                            Text(
                                                "${preset.name} (${FileDetails.formatBytes(preset.sizeBytes)})",
                                                fontSize = 11.sp
                                            )
                                        },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                        )
                                    )
                                }
                            }
                        }
                    }

                    // -----------------------------------------------------------------
                    // TAB B: ADVANCED VIDEO (Slider, Enable All Codecs, Res, FPS)
                    // -----------------------------------------------------------------
                    if (videoStudioTab == 1) {
                        val minSliderMb = 0.5f
                        val maxSliderMb = (referenceVideoOrigBytes / 1_000_000f).coerceAtLeast(1f)
                        val currentMb = (videoConfig.targetSizeBytes / 1_000_000f).coerceIn(minSliderMb, maxSliderMb)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (videoFiles.size > 1) "Target Size (Per Video)" else "Target File Size",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = FileDetails.formatBytes(videoConfig.targetSizeBytes),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Slider(
                            value = currentMb,
                            enabled = !isProcessing,
                            onValueChange = { mb ->
                                viewModel.setVideoConfig(
                                    videoConfig.withTargetSizeBytes(
                                        (mb * 1_000_000f).toLong().coerceAtLeast(500_000L)
                                    )
                                )
                            },
                            valueRange = minSliderMb..maxSliderMb
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(
                                        text = "Enable All Codecs",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Text(
                                        text = "Unlock VP9, AV1 & software fallback encoders",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = videoConfig.enableAllCodecs,
                                    enabled = !isProcessing,
                                    onCheckedChange = { enabled ->
                                        viewModel.setVideoConfig(
                                            videoConfig.withEnableAllCodecs(enabled, Build.VERSION.SDK_INT)
                                        )
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            "VIDEO ENCODING CODEC",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        val visibleCodecs = if (videoConfig.enableAllCodecs) {
                            VideoCodec.entries
                        } else {
                            listOf(VideoCodec.H264, VideoCodec.H265)
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (rowCodecs in visibleCodecs.chunked(2)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(IntrinsicSize.Max),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    for (codec in rowCodecs) {
                                        val cap = deviceCapabilities?.firstOrNull {
                                            it.mimeType.equals(codec.mimeType, ignoreCase = true)
                                        }
                                        val isAllowed = codec.isAllowed(
                                            videoConfig.enableAllCodecs,
                                            Build.VERSION.SDK_INT
                                        )
                                        val hasEncoder = when {
                                            deviceCapabilities == null -> codec == VideoCodec.H264
                                            cap == null -> false
                                            else -> cap.hasHardware || (videoConfig.enableAllCodecs && cap.hasSoftware)
                                        }
                                        val isSupported = isAllowed && hasEncoder

                                        val badgeText = when {
                                            deviceCapabilities == null -> "..."
                                            cap?.hasHardware == true -> "⚡ HW"
                                            cap?.hasSoftware == true -> "🧩 SW"
                                            else -> "N/A"
                                        }
                                        val selected = videoConfig.codec == codec

                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                                .alpha(if (isSupported && !isProcessing) 1f else 0.45f)
                                                .clip(RoundedCornerShape(10.dp))
                                                .border(
                                                    if (selected) 2.dp else 1.dp,
                                                    if (selected) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                                    RoundedCornerShape(10.dp)
                                                )
                                                .background(
                                                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                                    else MaterialTheme.colorScheme.surface
                                                )
                                                .clickable(enabled = isSupported && !isProcessing) {
                                                    viewModel.setVideoConfig(
                                                        videoConfig.withCodec(codec, Build.VERSION.SDK_INT)
                                                    )
                                                }
                                                .padding(10.dp)
                                        ) {
                                            Column {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = codec.label,
                                                        style = MaterialTheme.typography.bodySmall.copy(
                                                            fontWeight = FontWeight.Bold
                                                        ),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Surface(
                                                        color = if (cap?.hasHardware == true)
                                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                                        else MaterialTheme.colorScheme.surfaceVariant,
                                                        shape = RoundedCornerShape(4.dp)
                                                    ) {
                                                        Text(
                                                            text = badgeText,
                                                            fontSize = 9.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = if (cap?.hasHardware == true)
                                                                MaterialTheme.colorScheme.primary
                                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = codec.description,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                    if (rowCodecs.size == 1) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "RESOLUTION",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for (res in VideoResolution.entries) {
                                FilterChip(
                                    selected = videoConfig.resolution == res,
                                    enabled = !isProcessing,
                                    onClick = {
                                        viewModel.setVideoConfig(videoConfig.withResolution(res))
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
                        Text(
                            "FRAME RATE (FPS)",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val fpsChoices = listOf(
                                Pair("Original", 0f),
                                Pair("60 FPS", 60f),
                                Pair("30 FPS", 30f),
                                Pair("24 FPS", 24f),
                                Pair("15 FPS", 15f)
                            )
                            val currentFps = videoConfig.targetFps ?: 0f
                            for ((label, fps) in fpsChoices) {
                                FilterChip(
                                    selected = currentFps == fps,
                                    enabled = !isProcessing,
                                    onClick = {
                                        viewModel.setVideoConfig(
                                            videoConfig.withTargetFps(if (fps <= 0f) null else fps)
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
                    // TAB C: ADVANCED AUDIO (Codec Selection, Remove, Bitrate, Volume)
                    // -----------------------------------------------------------------
                    if (videoStudioTab == 2) {
                        Text(
                            "AUDIO ENCODING",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (rowAudioCodecs in VideoAudioCodec.entries.chunked(2)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(IntrinsicSize.Max),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    for (ac in rowAudioCodecs) {
                                        val selected = videoConfig.audioCodec == ac
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                                .alpha(if (isProcessing) 0.55f else 1f)
                                                .clip(RoundedCornerShape(10.dp))
                                                .border(
                                                    if (selected) 2.dp else 1.dp,
                                                    if (selected) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                                    RoundedCornerShape(10.dp)
                                                )
                                                .background(
                                                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                                    else MaterialTheme.colorScheme.surface
                                                )
                                                .clickable(enabled = !isProcessing) {
                                                    viewModel.setVideoConfig(videoConfig.withAudioCodec(ac))
                                                }
                                                .padding(10.dp)
                                        ) {
                                            Column {
                                                Text(
                                                    ac.label,
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                )
                                                Text(
                                                    text = ac.description,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                    if (rowAudioCodecs.size == 1) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        when (videoConfig.audioCodec) {
                            VideoAudioCodec.COPY -> {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                ) {
                                    Text(
                                        text = "Original audio stream will be passed through untouched (fastest, zero quality loss).",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                            }

                            VideoAudioCodec.MUTE -> {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.VolumeOff,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Audio track will be stripped completely, giving maximum bitrate to video.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            else -> {
                                Text(
                                    "AUDIO BITRATE",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    for (kbps in listOf(48, 64, 96, 128, 192)) {
                                        FilterChip(
                                            selected = videoConfig.audioBitrateKbps == kbps,
                                            enabled = !isProcessing,
                                            onClick = {
                                                viewModel.setVideoConfig(videoConfig.withAudioBitrateKbps(kbps))
                                            },
                                            label = { Text("$kbps kbps", fontSize = 11.sp) },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                            )
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "Audio Volume Gain",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${(videoConfig.audioVolume * 100).roundToInt()}%",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Slider(
                                    value = videoConfig.audioVolume,
                                    enabled = !isProcessing,
                                    onValueChange = { viewModel.setVideoConfig(videoConfig.withAudioVolume(it)) },
                                    valueRange = 0.25f..2.0f,
                                    steps = 6
                                )
                            }
                        }
                    }

                    // -----------------------------------------------------------------
                    // TARGET SIZE / BITRATE WARNINGS & ONE-TAP SUGGESTIONS
                    // -----------------------------------------------------------------
                    if (isTargetLargerThanSource) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = warningAmber.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, warningAmber.copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Warning, null, tint = warningAmber, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Target size (${FileDetails.formatBytes(videoConfig.targetSizeBytes)}) is >= source size (${FileDetails.formatBytes(referenceVideoOrigBytes)}).",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                                    color = warningAmber
                                )
                            }
                        }
                    }

                    if (isImpossible || isLowQualityForRes) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = warningAmber.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, warningAmber.copy(alpha = 0.4f))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Warning, null, tint = warningAmber, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    val warningText = when {
                                        audioExceedsTarget ->
                                            "Audio bitrate (${videoConfig.audioBitrateKbps} kbps) exceeds total target budget"
                                        isImpossible && isAudioCopied ->
                                            "Target size too tight (~${estVideoKbps.coerceAtLeast(1)} kbps total with copied audio)"
                                        isImpossible ->
                                            "Target size too tight (~${estVideoKbps.coerceAtLeast(1)} kbps video)"
                                        else ->
                                            "Low bitrate for ${videoConfig.resolution.label} (~$estVideoKbps kbps)"
                                    }
                                    Text(
                                        text = warningText,
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
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (videoConfig.resolution != VideoResolution.P480 &&
                                        videoConfig.resolution != VideoResolution.P360
                                    ) {
                                        OutlinedButton(
                                            enabled = !isProcessing,
                                            onClick = {
                                                viewModel.setVideoConfig(videoConfig.withResolution(VideoResolution.P480))
                                            },
                                            shape = RoundedCornerShape(8.dp)
                                        ) { Text("Drop to 480p", fontSize = 11.sp) }
                                    }
                                    val curFps = videoConfig.targetFps ?: 0f
                                    if (curFps == 0f || curFps > 24f) {
                                        OutlinedButton(
                                            enabled = !isProcessing,
                                            onClick = {
                                                viewModel.setVideoConfig(videoConfig.withTargetFps(24f))
                                            },
                                            shape = RoundedCornerShape(8.dp)
                                        ) { Text("Cap 24 FPS", fontSize = 11.sp) }
                                    }
                                    if (videoConfig.codec == VideoCodec.H264 && canUseH265) {
                                        OutlinedButton(
                                            enabled = !isProcessing,
                                            onClick = {
                                                viewModel.setVideoConfig(
                                                    videoConfig.withCodec(VideoCodec.H265, Build.VERSION.SDK_INT)
                                                )
                                            },
                                            shape = RoundedCornerShape(8.dp)
                                        ) { Text("Use H.265", fontSize = 11.sp) }
                                    }
                                    if (!isMuted) {
                                        OutlinedButton(
                                            enabled = !isProcessing,
                                            onClick = {
                                                viewModel.setVideoConfig(videoConfig.withAudioCodec(VideoAudioCodec.MUTE))
                                            },
                                            shape = RoundedCornerShape(8.dp)
                                        ) { Text("Strip Audio", fontSize = 11.sp) }
                                    }
                                }
                            }
                        }
                    }

                    // Forecast Summary Pill
                    Spacer(modifier = Modifier.height(12.dp))
                    val targetColor = if (isTargetLargerThanSource) warningAmber else accentSavings
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = if (videoFiles.size > 1) "TOTAL VIDEO INPUT (${videoFiles.size})" else "ORIGINAL",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    FileDetails.formatBytes(totalVideoOrigBytes),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                            }
                            Text("➔", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "${videoConfig.codec.label.substringBefore(" ")} • ${videoConfig.resolution.label} • ${videoConfig.effectiveAudioMode}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    color = targetColor
                                )
                                Text(
                                    text = if (videoFiles.size > 1) {
                                        "${FileDetails.formatBytes(videoConfig.targetSizeBytes)} / video"
                                    } else {
                                        FileDetails.formatBytes(videoConfig.targetSizeBytes)
                                    },
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = targetColor
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        // =========================================================================
        // 2B. STANDARD COMPRESSION CONFIG (Images, Audio, PDF, Code, & Mixed Non-Video)
        // =========================================================================
        if (hasNonVideoFiles) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (hasVideoFiles) "COMPRESSION TARGET (NON-VIDEO FILES)" else "COMPRESSION TARGET",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        ),
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
                                enabled = !isProcessing,
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
                                        modes[idx],
                                        fontSize = 12.sp,
                                        fontWeight = if (selectedTabIndex == idx) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    if (compressionConfig.mode == CompressionMode.EXACT_TARGET_SIZE) {
                        val quickSizes = listOf(
                            Triple("100 KB", 100 * 1024L, Pair("100", false)),
                            Triple("500 KB", 500 * 1024L, Pair("500", false)),
                            Triple("1 MB", 1024 * 1024L, Pair("1", true)),
                            Triple("2 MB", 2 * 1024 * 1024L, Pair("2", true)),
                            Triple("10 MB (GitHub)", 10 * 1024 * 1024L, Pair("10", true)),
                            Triple("25 MB (Email)", 25 * 1024 * 1024L, Pair("25", true))
                        )

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for ((label, bytes, displaySync) in quickSizes) {
                                FilterChip(
                                    selected = compressionConfig.targetSizeBytes == bytes,
                                    enabled = !isProcessing,
                                    onClick = {
                                        customSizeInput = displaySync.first
                                        customUnitMb = displaySync.second
                                        customSizeError = null
                                        viewModel.setTargetSizeBytes(bytes)
                                    },
                                    label = { Text(label, fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = customSizeInput,
                                enabled = !isProcessing,
                                isError = customSizeError != null,
                                onValueChange = { newVal ->
                                    if (isValidDecimalInput(newVal)) {
                                        customSizeInput = newVal
                                        val bytes = parsePositiveBytes(newVal, customUnitMb)
                                        if (bytes != null) {
                                            customSizeError = null
                                            viewModel.setTargetSizeBytes(bytes)
                                        } else {
                                            customSizeError = if (newVal.isBlank()) {
                                                "Enter a target size > 0"
                                            } else {
                                                "Invalid size"
                                            }
                                        }
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
                                onClick = {
                                    val toggledMb = !customUnitMb
                                    customUnitMb = toggledMb
                                    val bytes = parsePositiveBytes(customSizeInput, toggledMb)
                                    if (bytes != null) {
                                        customSizeError = null
                                        viewModel.setTargetSizeBytes(bytes)
                                    } else {
                                        customSizeError = "Enter a valid size > 0"
                                    }
                                },
                                enabled = !isProcessing,
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            ) {
                                Text(
                                    text = if (customUnitMb) "MB" else "KB",
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        if (customSizeError != null) {
                            Text(
                                text = customSizeError!!,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                            )
                        }
                    }

                    if (compressionConfig.mode == CompressionMode.PERCENTAGE_REDUCTION) {
                        val sliderPct = compressionConfig.percentageReduction.coerceIn(10, 90)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Target Reduction:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "-${compressionConfig.percentageReduction}%",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = accentSavings
                                )
                            )
                        }

                        Slider(
                            value = sliderPct.toFloat(),
                            enabled = !isProcessing,
                            onValueChange = { viewModel.setPercentageReduction(it.roundToInt()) },
                            valueRange = 10f..90f,
                            steps = 15,
                            modifier = Modifier.testTag("percentage_slider")
                        )

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for (pct in listOf(25, 50, 75, 90)) {
                                FilterChip(
                                    selected = compressionConfig.percentageReduction == pct,
                                    enabled = !isProcessing,
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
                                        .alpha(if (isProcessing) 0.6f else 1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .border(
                                            if (isSelected) 2.dp else 1.dp,
                                            if (isSelected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                            RoundedCornerShape(10.dp)
                                        )
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                        )
                                        .clickable(enabled = !isProcessing, role = Role.RadioButton) {
                                            viewModel.setQualityProfile(profile)
                                        }
                                        .padding(12.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (isSelected) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                        }
                                        Column {
                                            Text(
                                                profile.label,
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontWeight = FontWeight.Bold
                                                ),
                                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                profile.description,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
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
        // 3. COLLAPSIBLE METADATA OPTIONS & COMPRESS ACTION / PROGRESS
        // =========================================================================
        if (selectedFiles.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
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
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Advanced Options & Privacy",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                            )
                        }
                        Icon(
                            if (showAdvancedSettings) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    AnimatedVisibility(
                        visible = showAdvancedSettings,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(top = 10.dp)
                                .alpha(if (isProcessing) 0.6f else 1f)
                        ) {
                            MetadataPreferenceCard(
                                config = metadataConfig,
                                onConfigChange = { updated ->
                                    if (!isProcessing) viewModel.setMetadataConfig(updated)
                                }
                            )
                        }
                    }
                }
            }
        }

        // Always keep active job progress & cancel button visible regardless of selectedFiles
        if (isProcessing) {
            val safeProgress = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
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
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${(safeProgress * 100).roundToInt()}%",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = safeProgress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { viewModel.cancelActiveJob() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Cancel Compression")
                    }
                }
            }
        } else if (selectedFiles.isNotEmpty()) {
            val hasBlockingCustomInputError = hasNonVideoFiles &&
                compressionConfig.mode == CompressionMode.EXACT_TARGET_SIZE &&
                customSizeError != null

            Button(
                enabled = !hasBlockingCustomInputError,
                onClick = {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastCompressClickMs < 500L) return@Button
                    lastCompressClickMs = now

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !hasAskedNotificationPerm &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        viewModel.executeCompression()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("compress_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
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

        // =========================================================================
        // 4. RESULTS
        // =========================================================================
        if (!isProcessing) {
            if (batchSummary != null && batchSummary.results.size != 1) {
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
        }

        Spacer(modifier = Modifier.height(24.dp))
    }

    // =========================================================================
    // CUSTOM PRESET EDITOR DIALOG
    // =========================================================================
    if (showPresetEditor) {
        var newName by rememberSaveable { mutableStateOf("") }
        var newMb by rememberSaveable { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showPresetEditor = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Customize Target Presets",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        scope.launch {
                            presetMutex.withLock {
                                runCatching {
                                    withContext(NonCancellable) { 
										val resetSucceeded = presetRepo.resetDefaults()
										check(resetSucceeded) { "Failed to reset presets" }
										presetRepo.loadPresets() } // or VideoPresetRepository.DEFAULT_PRESETS
                                }.onSuccess { defaults ->
                                    customPresets = defaults
                                    presetError = null
                                }.onFailure {
                                    presetError = "Failed to reset presets"
                                }
                            }
                        }
                    }) {
                        Icon(Icons.Default.Refresh, "Reset defaults")
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { if (it.length <= 32) newName = it },
                            label = { Text("Name", fontSize = 11.sp) },
                            modifier = Modifier.weight(1.3f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = newMb,
                            onValueChange = { input ->
                                newMb = input.filter { c -> c in '0'..'9' }.take(6)
                            },
                            label = { Text("MB", fontSize = 11.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(0.9f),
                            singleLine = true
                        )
                        IconButton(onClick = {
                            val trimmedName = newName.trim()
                            val mbVal = newMb.toLongOrNull() ?: 0L
                            if (trimmedName.isBlank() || mbVal !in 1L..100_000L) {
                                presetError = "Enter a name and 1–100,000 MB"
                                return@IconButton
                            }
                            val newPreset = VideoTargetPreset(
                                id = "custom_${UUID.randomUUID()}",
                                name = trimmedName,
                                sizeBytes = mbVal * 1_000_000L,
                                isDefault = false
                            )
                            scope.launch {
                                presetMutex.withLock {
                                    val updated = customPresets + newPreset
                                    runCatching {
                                        withContext(NonCancellable) { presetRepo.savePresets(updated) }
                                    }.onSuccess {
                                        customPresets = updated
                                        newName = ""
                                        newMb = ""
                                        presetError = null
                                    }.onFailure {
                                        presetError = "Failed to save preset"
                                    }
                                }
                            }
                        }) {
                            Icon(Icons.Default.Add, "Add", tint = MaterialTheme.colorScheme.primary)
                        }
                    }

                    if (presetError != null) {
                        Text(
                            text = presetError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }

                    if (customPresets.isEmpty()) {
                        Text(
                            text = "No presets saved. Add one above or tap Reset.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            for (p in customPresets) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${p.name} • ${FileDetails.formatBytes(p.sizeBytes)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                presetMutex.withLock {
                                                    val updated = customPresets.filterNot { it.id == p.id }
                                                    runCatching {
                                                        withContext(NonCancellable) {
                                                            presetRepo.savePresets(updated)
                                                        }
                                                    }.onSuccess {
                                                        customPresets = updated
                                                        presetError = null
                                                    }.onFailure {
                                                        presetError = "Failed to delete preset"
                                                    }
                                                }
                                            }
                                        },
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Delete ${p.name}",
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    presetError = null
                    showPresetEditor = false
                }) { Text("Done") }
            }
        )
    }
}

@Composable
private fun <T> rememberAsyncIterable(block: suspend () -> Iterable<T>): Iterable<T>? {
    var result by remember { mutableStateOf<Iterable<T>?>(null) }
    LaunchedEffect(Unit) {
        result = withContext(Dispatchers.Default) {
            runCatching { block() }.getOrElse { emptyList() }
        }
    }
    return result
}

private fun isValidDecimalInput(input: String): Boolean {
    if (input.length > 12) return false
    var separatorSeen = false
    for (ch in input) {
        when {
            ch in '0'..'9' -> Unit
            ch == '.' || ch == ',' -> {
                if (separatorSeen) return false
                separatorSeen = true
            }
            else -> return false
        }
    }
    return true
}

private fun parsePositiveBytes(input: String, isMb: Boolean): Long? {
    val normalized = input.replace(',', '.')
    val num = normalized.toDoubleOrNull() ?: return null
    if (!num.isFinite() || num <= 0.0) return null
    val multiplier = if (isMb) 1024.0 * 1024.0 else 1024.0
    val rawBytes = num * multiplier
    if (!rawBytes.isFinite() || rawBytes >= Long.MAX_VALUE.toDouble()) return null
    val bytes = rawBytes.toLong()
    return bytes.takeIf { it > 0L }
}
