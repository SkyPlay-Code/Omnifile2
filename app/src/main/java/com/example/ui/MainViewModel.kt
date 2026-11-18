package com.example.ui

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.ConversionRecord
import com.example.data.ConversionRepository
import com.example.engine.DownloadStorageHelper
import com.example.engine.FileInspector
import com.example.engine.ProcessingJobCoordinator
import com.example.engine.ProcessingTaskRequest
import com.example.engine.SampleFilesGenerator
import com.example.model.BatchJobSummary
import com.example.model.CompressionConfig
import com.example.model.CompressionMode
import com.example.model.ConversionJobResult
import com.example.model.FileDetails
import com.example.model.MetadataConfig
import com.example.model.QualityProfile
import com.example.model.SupportedFormats
import com.example.model.TargetFormat
import com.example.model.VideoCompressionConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: ConversionRepository

    init {
        val database = AppDatabase.getDatabase(application)
        repository = ConversionRepository(database.conversionDao())
    }

    // --- UI Feedback & Inspection State ---
    private val _uiMessage = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val uiMessage: SharedFlow<String> = _uiMessage.asSharedFlow()

    private val _isInspectingFiles = MutableStateFlow(false)
    val isInspectingFiles: StateFlow<Boolean> = _isInspectingFiles.asStateFlow()

    // --- History & Stats Flows ---
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val historyRecords: StateFlow<List<ConversionRecord>> = _searchQuery
        .map { it.trim() }
        .debounce(SEARCH_DEBOUNCE_MS)
        .distinctUntilChanged()
        .flatMapLatest { query ->
            if (query.isBlank()) repository.allRecords
            else repository.searchRecords(query)
        }
        .catch {
            emitError("Failed to load conversion history.")
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalSavingsBytes: StateFlow<Long> = repository.totalBytesSaved
        .catch { emit(0L) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val totalOperationsCount: StateFlow<Int> = repository.totalCount
        .catch { emit(0) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // --- UI Configuration State ---
    private val _metadataConfig = MutableStateFlow(MetadataConfig())
    val metadataConfig: StateFlow<MetadataConfig> = _metadataConfig.asStateFlow()

    private val _previewFilePath = MutableStateFlow<String?>(null)
    val previewFilePath: StateFlow<String?> = _previewFilePath.asStateFlow()

    private val _selectedFiles = MutableStateFlow<List<FileDetails>>(emptyList())
    val selectedFiles: StateFlow<List<FileDetails>> = _selectedFiles.asStateFlow()

    private val _selectedFile = MutableStateFlow<FileDetails?>(null)
    val selectedFile: StateFlow<FileDetails?> = _selectedFile.asStateFlow()

    private val _targetFormat = MutableStateFlow<TargetFormat>(SupportedFormats.WEBP)
    val targetFormat: StateFlow<TargetFormat> = _targetFormat.asStateFlow()

    private val _compressionConfig = MutableStateFlow(CompressionConfig())
    val compressionConfig: StateFlow<CompressionConfig> = _compressionConfig.asStateFlow()

    private val _videoConfig = MutableStateFlow(VideoCompressionConfig())
    val videoConfig: StateFlow<VideoCompressionConfig> = _videoConfig.asStateFlow()

    // --- Processing & Progress State (Driven by Background ForegroundService) ---
    val isProcessing: StateFlow<Boolean> = ProcessingJobCoordinator.isProcessing
    val progress: StateFlow<Float> = ProcessingJobCoordinator.progress
    val progressStatus: StateFlow<String> = ProcessingJobCoordinator.progressStatus
    val lastJobResult: StateFlow<ConversionJobResult?> = ProcessingJobCoordinator.lastJobResult
    val batchSummary: StateFlow<BatchJobSummary?> = ProcessingJobCoordinator.batchSummary

    private val _sampleFiles = MutableStateFlow<List<FileDetails>>(emptyList())
    val sampleFiles: StateFlow<List<FileDetails>> = _sampleFiles.asStateFlow()

    // --- Selection & Job Synchronization State (Main-thread confined) ---
    private var selectionEpoch: Long = 0L
    private val pendingSelectionJobs = mutableListOf<Job>()
    private var activeInspectionCount = 0
    private var hasSupportedFormatForCurrentSelection = true
    private var isTargetFormatManuallySet = false
    private var isLaunchingJob = false

    init {
        loadSampleFiles()
    }

    private fun loadSampleFiles() {
        viewModelScope.launch {
            try {
                val samples = withContext(Dispatchers.IO) {
                    SampleFilesGenerator.getOrCreateSampleFiles(getApplication())
                }
                _sampleFiles.value = samples
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                emitError("Failed to prepare sample files.")
            }
        }
    }

    // --- File Selection Handlers ---
    fun selectUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (ProcessingJobCoordinator.isProcessing.value) {
            emitError("Cannot change files while processing is in progress.")
            return
        }

        val uniqueUris = uris.distinct()
        cancelPendingSelectionJobs()
        val epoch = ++selectionEpoch

        val job = viewModelScope.launch {
            incrementInspectionCount()
            try {
                val (detailsList, failedCount) = withContext(Dispatchers.IO) {
                    inspectUrisSafely(uniqueUris)
                }

                if (epoch != selectionEpoch) return@launch

                if (failedCount > 0) {
                    emitError(
                        if (detailsList.isEmpty()) "Unable to access selected file(s)."
                        else "Skipped $failedCount unreadable file(s)."
                    )
                }
                if (detailsList.isEmpty()) return@launch

                isTargetFormatManuallySet = false
                updateSelectionState(detailsList)
                ProcessingJobCoordinator.clearResults()
                detailsList.firstOrNull()?.let { applyDefaultsForLeadFile(it) }
            } finally {
                decrementInspectionCount()
            }
        }
        trackSelectionJob(job)
    }

    fun addUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (ProcessingJobCoordinator.isProcessing.value) {
            emitError("Cannot add files while processing is in progress.")
            return
        }

        val uniqueUris = uris.distinct()
        val epoch = selectionEpoch

        val job = viewModelScope.launch {
            incrementInspectionCount()
            try {
                val (newDetails, failedCount) = withContext(Dispatchers.IO) {
                    inspectUrisSafely(uniqueUris)
                }

                if (epoch != selectionEpoch) return@launch

                if (failedCount > 0) {
                    emitError("Skipped $failedCount unreadable file(s).")
                }
                if (newDetails.isEmpty()) return@launch

                val current = _selectedFiles.value
                val wasEmpty = current.isEmpty()
                val merged = (current + newDetails).distinct()
                if (merged.size == current.size) {
                    emitError("Selected file(s) are already in the list.")
                    return@launch
                }

                updateSelectionState(merged)
                ProcessingJobCoordinator.clearResults()

                if (wasEmpty) {
                    isTargetFormatManuallySet = false
                    merged.firstOrNull()?.let { applyDefaultsForLeadFile(it) }
                }
            } finally {
                decrementInspectionCount()
            }
        }
        trackSelectionJob(job)
    }

    fun removeFile(file: FileDetails) {
        if (ProcessingJobCoordinator.isProcessing.value) {
            emitError("Cannot modify selection while processing is in progress.")
            return
        }

        val current = _selectedFiles.value
        if (file !in current) return

        val wasLeadFile = current.firstOrNull() == file
        val updated = current.filterNot { it == file }

        updateSelectionState(updated)
        ProcessingJobCoordinator.clearResults()

        if (updated.isEmpty()) {
            hasSupportedFormatForCurrentSelection = true
            isTargetFormatManuallySet = false
        } else if (wasLeadFile) {
            isTargetFormatManuallySet = false
            applyDefaultsForLeadFile(updated.first())
        }
    }

    fun selectUri(uri: Uri) = selectUris(listOf(uri))

    fun selectSampleFile(sample: FileDetails) {
        if (ProcessingJobCoordinator.isProcessing.value) {
            emitError("Cannot change files while processing is in progress.")
            return
        }

        cancelPendingSelectionJobs()
        ++selectionEpoch
        isTargetFormatManuallySet = false

        updateSelectionState(listOf(sample))
        ProcessingJobCoordinator.clearResults()
        applyDefaultsForLeadFile(sample)
    }

    fun clearSelection() {
        if (ProcessingJobCoordinator.isProcessing.value) {
            emitError("Cannot clear selection while processing is in progress.")
            return
        }

        cancelPendingSelectionJobs()
        ++selectionEpoch
        isTargetFormatManuallySet = false
        hasSupportedFormatForCurrentSelection = true

        updateSelectionState(emptyList())
        ProcessingJobCoordinator.clearResults()
    }

    // --- Selection Helpers ---
    private suspend fun inspectUrisSafely(uris: List<Uri>): Pair<List<FileDetails>, Int> {
        val context = getApplication<Application>()
        val results = mutableListOf<FileDetails>()
        var failedCount = 0

        for (uri in uris) {
            try {
                tryTakePersistableReadPermission(context, uri)
                results.add(FileInspector.inspectUri(context, uri))
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                failedCount++
            }
        }
        return results.distinct() to failedCount
    }

    private fun tryTakePersistableReadPermission(context: Context, uri: Uri) {
        if (!uri.scheme.equals("content", ignoreCase = true)) return
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
            // Expected when the source Intent did not grant persistable permissions
        }
    }

    private fun updateSelectionState(files: List<FileDetails>) {
        _selectedFiles.value = files
        _selectedFile.value = files.firstOrNull()
    }

    private fun applyDefaultsForLeadFile(leadFile: FileDetails) {
        val recommended = SupportedFormats.getRecommendedFor(leadFile.category, leadFile.extension)
        if (recommended.isNotEmpty()) {
            _targetFormat.value = recommended.first()
            hasSupportedFormatForCurrentSelection = true
        } else {
            hasSupportedFormatForCurrentSelection = false
        }

        val defaultTargetSize = computeDefaultTargetSizeBytes(leadFile.size)
        _compressionConfig.value = _compressionConfig.value.copy(
            targetSizeBytes = defaultTargetSize,
            percentageReduction = 50,
            metadataConfig = _metadataConfig.value
        )
        _videoConfig.value = _videoConfig.value.withTargetSizeBytes(
            minOf(DEFAULT_VIDEO_MAX_TARGET_BYTES, defaultTargetSize)
        )
    }

    private fun computeDefaultTargetSizeBytes(originalSize: Long): Long {
        if (originalSize <= 0L) return FALLBACK_TARGET_SIZE_BYTES
        if (originalSize == 1L) return 1L
        return (originalSize / 2L).coerceIn(1L, originalSize - 1L)
    }

    private fun trackSelectionJob(job: Job) {
        pendingSelectionJobs.add(job)
        job.invokeOnCompletion {
            viewModelScope.launch {
                pendingSelectionJobs.remove(job)
            }
        }
    }

    private fun cancelPendingSelectionJobs() {
        pendingSelectionJobs.forEach { it.cancel() }
        pendingSelectionJobs.clear()
    }

    private fun incrementInspectionCount() {
        activeInspectionCount++
        _isInspectingFiles.value = true
    }

    private fun decrementInspectionCount() {
        activeInspectionCount = (activeInspectionCount - 1).coerceAtLeast(0)
        _isInspectingFiles.value = activeInspectionCount > 0
    }

    // --- Configuration Setters ---
    fun setTargetFormat(format: TargetFormat) {
        _targetFormat.value = format
        isTargetFormatManuallySet = true
        hasSupportedFormatForCurrentSelection = true
    }

    fun setCompressionMode(mode: CompressionMode) {
        _compressionConfig.value = _compressionConfig.value.copy(mode = mode)
    }

    fun setTargetSizeBytes(bytes: Long) {
        val validBytes = bytes.coerceAtLeast(1L)
        _compressionConfig.value = _compressionConfig.value.copy(
            mode = CompressionMode.EXACT_TARGET_SIZE,
            targetSizeBytes = validBytes
        )
        _videoConfig.value = _videoConfig.value.withTargetSizeBytes(validBytes)
    }

    fun setPercentageReduction(percent: Int) {
        val validPercent = percent.coerceIn(1, 99)
        _compressionConfig.value = _compressionConfig.value.copy(
            mode = CompressionMode.PERCENTAGE_REDUCTION,
            percentageReduction = validPercent
        )
    }

    fun setQualityProfile(profile: QualityProfile) {
        _compressionConfig.value = _compressionConfig.value.copy(
            mode = CompressionMode.QUALITY_PRESET,
            qualityProfile = profile
        )
    }

    fun setVideoConfig(config: VideoCompressionConfig) {
        val validBytes = config.targetSizeBytes.coerceAtLeast(1L)
        val sanitizedConfig = if (validBytes == config.targetSizeBytes) {
            config
        } else {
            config.withTargetSizeBytes(validBytes)
        }
        val sizeChanged = sanitizedConfig.targetSizeBytes != _videoConfig.value.targetSizeBytes
        _videoConfig.value = sanitizedConfig

        if (sizeChanged) {
            _compressionConfig.value = _compressionConfig.value.copy(
                targetSizeBytes = sanitizedConfig.targetSizeBytes
            )
        }
    }

    fun setMetadataConfig(config: MetadataConfig) {
        _metadataConfig.value = config
        _compressionConfig.value = _compressionConfig.value.copy(metadataConfig = config)
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun showPreview(filePath: String) {
        val trimmed = filePath.trim()
        if (trimmed.isEmpty()) return

        if (trimmed.startsWith("content://", ignoreCase = true)) {
            _previewFilePath.value = trimmed
            return
        }

        val normalizedPath = stripFileScheme(trimmed)
        val file = File(normalizedPath)
        if (!file.exists() || !file.isFile) {
            emitError("Preview unavailable: file no longer exists.")
            return
        }
        _previewFilePath.value = normalizedPath
    }

    fun dismissPreview() {
        _previewFilePath.value = null
    }

    fun openDownloadsFolder(context: Context) {
        try {
            DownloadStorageHelper.openDownloadsFolder(context)
        } catch (_: Exception) {
            emitError("Unable to open Downloads folder.")
        }
    }

    fun cancelActiveJob() {
        ProcessingJobCoordinator.cancel(getApplication())
    }

    // --- Background ForegroundService Execution ---
    fun executeConversion() {
        val files = _selectedFiles.value
        if (files.isEmpty()) return
        if (_isInspectingFiles.value) {
            emitError("Please wait until file inspection completes.")
            return
        }
        if (ProcessingJobCoordinator.isProcessing.value || isLaunchingJob) return
        if (!hasSupportedFormatForCurrentSelection && !isTargetFormatManuallySet) {
            emitError("No supported conversion format available for the selected file.")
            return
        }

        launchProcessingJob(
            ProcessingTaskRequest.Convert(
                files = files.toList(),
                target = _targetFormat.value,
                metadataConfig = _metadataConfig.value
            )
        )
    }

    fun executeCompression() {
        val files = _selectedFiles.value
        if (files.isEmpty()) return
        if (_isInspectingFiles.value) {
            emitError("Please wait until file inspection completes.")
            return
        }
        if (ProcessingJobCoordinator.isProcessing.value || isLaunchingJob) return

        val config = _compressionConfig.value.copy(
            targetSizeBytes = _compressionConfig.value.targetSizeBytes.coerceAtLeast(1L),
            percentageReduction = _compressionConfig.value.percentageReduction.coerceIn(1, 99),
            metadataConfig = _metadataConfig.value
        )
        val videoCfg = _videoConfig.value.let {
            if (it.targetSizeBytes <= 0L) it.withTargetSizeBytes(1L) else it
        }

        launchProcessingJob(
            ProcessingTaskRequest.Compress(
                files = files.toList(),
                compressionConfig = config,
                videoConfig = videoCfg
            )
        )
    }

    private fun launchProcessingJob(request: ProcessingTaskRequest) {
        isLaunchingJob = true
        try {
            ProcessingJobCoordinator.start(
                context = getApplication(),
                request = request
            )
        } catch (_: Exception) {
            emitError("Failed to start background processing.")
        } finally {
            isLaunchingJob = false
        }
    }

    // --- History Deletion Handlers ---
    fun deleteHistoryItem(record: ConversionRecord) {
        viewModelScope.launch {
            try {
                repository.delete(record)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                emitError("Failed to delete history record.")
                return@launch
            }

            if (_previewFilePath.value == record.outputPath) {
                _previewFilePath.value = null
            }

            val deleted = withContext(Dispatchers.IO) {
                deleteOutputTargetSafely(record.outputPath)
            }
            if (!deleted && record.outputPath.isNotBlank()) {
                emitError("History entry removed, but the output file could not be deleted.")
            }
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            val allPaths = try {
                repository.allRecords.first().map { it.outputPath }.distinct()
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                emptyList()
            }

            try {
                repository.clearAll()
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                emitError("Failed to clear conversion history.")
                return@launch
            }

            if (_previewFilePath.value in allPaths) {
                _previewFilePath.value = null
            }

            withContext(Dispatchers.IO) {
                allPaths.forEach { deleteOutputTargetSafely(it) }
            }
        }
    }

    private fun deleteOutputTargetSafely(outputPath: String): Boolean {
        val trimmed = outputPath.trim()
        if (trimmed.isEmpty()) return true

        return try {
            if (trimmed.startsWith("content://", ignoreCase = true)) {
                val uri = Uri.parse(trimmed)
                getApplication<Application>().contentResolver.delete(uri, null, null) > 0
            } else {
                val file = File(stripFileScheme(trimmed))
                if (!file.exists()) true else file.isFile && file.delete()
            }
        } catch (_: Exception) {
            false
        }
    }

    // --- Safe File Sharing & Opening ---
    private fun resolveShareableUri(context: Context, pathOrUri: String): Uri? {
        val trimmed = pathOrUri.trim()
        if (trimmed.isEmpty()) return null

        if (trimmed.startsWith("content://", ignoreCase = true)) {
            return Uri.parse(trimmed)
        }

        val normalizedPath = stripFileScheme(trimmed)
        if (normalizedPath.isEmpty()) return null

        val file = File(normalizedPath)
        if (!file.exists() || !file.isFile) return null

        return try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (_: Exception) {
            null
        }
    }

    private fun stripFileScheme(pathOrUri: String): String {
        if (!pathOrUri.startsWith("file://", ignoreCase = true)) return pathOrUri
        return Uri.parse(pathOrUri).path.orEmpty()
    }

    private fun inferMimeType(context: Context, uri: Uri, originalPath: String): String {
        if (uri.scheme.equals("content", ignoreCase = true)) {
            val resolvedType = try {
                context.contentResolver.getType(uri)
            } catch (_: Exception) {
                null
            }
            if (!resolvedType.isNullOrBlank() && resolvedType != "*/*" && resolvedType != "application/octet-stream") {
                return resolvedType
            }
        }

        val candidateName = (
            if (originalPath.startsWith("content://", ignoreCase = true)) uri.lastPathSegment
            else stripFileScheme(originalPath)
            ).orEmpty().substringAfterLast('/')

        val dotIndex = candidateName.lastIndexOf('.')
        if (dotIndex <= 0 || dotIndex >= candidateName.lastIndex) return "*/*"

        val ext = candidateName.substring(dotIndex + 1).lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: EXTRA_MIME_TYPES[ext]
            ?: "*/*"
    }

    fun shareFile(context: Context, filePath: String?) {
        val path = filePath?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val uri = resolveShareableUri(context, path)
        if (uri == null) {
            emitError("File is unavailable for sharing.")
            return
        }
        val mime = inferMimeType(context, uri, path)
        launchSingleShareIntent(context, uri, mime)
    }

    fun shareAllResults(context: Context, results: List<ConversionJobResult>) {
        val resolvedItems = mutableListOf<Pair<Uri, String>>()
        for (result in results) {
            if (result.isSuccess && result.outputPath.isNotBlank()) {
                val uri = resolveShareableUri(context, result.outputPath) ?: continue
                val mime = inferMimeType(context, uri, result.outputPath)
                resolvedItems.add(uri to mime)
            }
        }

        if (resolvedItems.isEmpty()) {
            emitError("No available files to share.")
            return
        }

        if (resolvedItems.size == 1) {
            val (uri, mime) = resolvedItems.first()
            launchSingleShareIntent(context, uri, mime)
            return
        }

        if (resolvedItems.size > MAX_SHARE_URIS) {
            emitError("Too many files to share at once (max $MAX_SHARE_URIS).")
            return
        }

        val uris = ArrayList<Uri>(resolvedItems.size).apply {
            resolvedItems.forEach { add(it.first) }
        }
        val commonMime = computeCommonMimeType(resolvedItems.map { it.second })

        try {
            val clipData = ClipData.newRawUri(null, uris.first()).apply {
                for (i in 1 until uris.size) {
                    addItem(ClipData.Item(uris[i]))
                }
            }
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = commonMime
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                this.clipData = clipData
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, "Share all files (${uris.size})").apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
            emitError("No app available to share these files.")
        } catch (_: Exception) {
            emitError("Failed to share files.")
        }
    }

    private fun launchSingleShareIntent(context: Context, uri: Uri, mime: String) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(null, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, "Share file").apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
            emitError("No app available to share this file.")
        } catch (_: Exception) {
            emitError("Failed to share file.")
        }
    }

    private fun computeCommonMimeType(mimeTypes: List<String>): String {
        val concrete = mimeTypes.filter { it != "*/*" }
        if (concrete.isEmpty()) return "*/*"
        val first = concrete.first()
        if (concrete.all { it == first }) return first
        val topLevel = first.substringBefore('/')
        return if (concrete.all { it.substringBefore('/') == topLevel }) {
            "$topLevel/*"
        } else {
            "*/*"
        }
    }

    fun openFile(context: Context, filePath: String?) {
        val path = filePath?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val uri = resolveShareableUri(context, path)
        if (uri == null) {
            emitError("File does not exist or cannot be opened.")
            return
        }

        val mime = inferMimeType(context, uri, path)
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            emitError("No app installed to open this file type.")
        } catch (_: Exception) {
            emitError("Failed to open file.")
        }
    }

    private fun emitError(message: String) {
        _uiMessage.tryEmit(message)
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 250L
        const val FALLBACK_TARGET_SIZE_BYTES = 100L * 1024L
        const val DEFAULT_VIDEO_MAX_TARGET_BYTES = 20_000_000L
        const val MAX_SHARE_URIS = 100

        val EXTRA_MIME_TYPES = mapOf(
            "webp" to "image/webp",
            "avif" to "image/avif",
            "heic" to "image/heic",
            "heif" to "image/heif",
            "mkv" to "video/x-matroska",
            "opus" to "audio/opus",
            "flac" to "audio/flac",
            "m4a" to "audio/mp4"
        )
    }
}
