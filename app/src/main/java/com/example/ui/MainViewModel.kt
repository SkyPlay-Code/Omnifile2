package com.example.ui

import android.app.Application
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayList

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: ConversionRepository

    init {
        val database = AppDatabase.getDatabase(application)
        repository = ConversionRepository(database.conversionDao())
    }

    // --- History & Stats Flows ---
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val historyRecords: StateFlow<List<ConversionRecord>> = _searchQuery
        .flatMapLatest { query ->
            if (query.isBlank()) repository.allRecords
            else repository.searchRecords(query)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalSavingsBytes: StateFlow<Long> = repository.totalBytesSaved
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val totalOperationsCount: StateFlow<Int> = repository.totalCount
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

    init {
        loadSampleFiles()
    }

    private fun loadSampleFiles() {
        viewModelScope.launch {
            val samples = SampleFilesGenerator.getOrCreateSampleFiles(getApplication())
            _sampleFiles.value = samples
        }
    }

    // --- File Selection Handlers ---
    fun selectUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val detailsList = withContext(Dispatchers.IO) {
                uris.map { uri -> FileInspector.inspectUri(getApplication(), uri) }
            }
            _selectedFiles.value = detailsList
            _selectedFile.value = detailsList.firstOrNull()
            ProcessingJobCoordinator.clearResults()

            detailsList.firstOrNull()?.let { first ->
                val recommended = SupportedFormats.getRecommendedFor(first.category, first.extension)
                if (recommended.isNotEmpty()) {
                    _targetFormat.value = recommended.first()
                }
                val halfSize = (first.size / 2).coerceAtLeast(100 * 1024L)
                _compressionConfig.value = _compressionConfig.value.copy(
                    targetSizeBytes = halfSize,
                    percentageReduction = 50
                )
                _videoConfig.value = _videoConfig.value.withTargetSizeBytes(
                    minOf(20_000_000L, halfSize)
                )
            }
        }
    }

    fun addUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val newDetails = withContext(Dispatchers.IO) {
                uris.map { uri -> FileInspector.inspectUri(getApplication(), uri) }
            }
            val current = _selectedFiles.value.toMutableList()
            current.addAll(newDetails)
            _selectedFiles.value = current
            _selectedFile.value = current.firstOrNull()
            ProcessingJobCoordinator.clearResults()
        }
    }

    fun removeFile(file: FileDetails) {
        val current = _selectedFiles.value.toMutableList()
        current.remove(file)
        _selectedFiles.value = current
        _selectedFile.value = current.firstOrNull()
        if (current.isEmpty()) {
            ProcessingJobCoordinator.clearResults()
        }
    }

    fun selectUri(uri: Uri) = selectUris(listOf(uri))

    fun selectSampleFile(sample: FileDetails) {
        _selectedFiles.value = listOf(sample)
        _selectedFile.value = sample
        ProcessingJobCoordinator.clearResults()

        val recommended = SupportedFormats.getRecommendedFor(sample.category, sample.extension)
        if (recommended.isNotEmpty()) {
            _targetFormat.value = recommended.first()
        }
        val halfSize = (sample.size / 2).coerceAtLeast(100 * 1024L)
        _compressionConfig.value = _compressionConfig.value.copy(
            targetSizeBytes = halfSize,
            percentageReduction = 50
        )
        _videoConfig.value = _videoConfig.value.withTargetSizeBytes(
            minOf(20_000_000L, halfSize)
        )
    }

    fun clearSelection() {
        _selectedFiles.value = emptyList()
        _selectedFile.value = null
        ProcessingJobCoordinator.clearResults()
    }

    // --- Configuration Setters ---
    fun setTargetFormat(format: TargetFormat) { _targetFormat.value = format }

    fun setCompressionMode(mode: CompressionMode) {
        _compressionConfig.value = _compressionConfig.value.copy(mode = mode)
    }

    fun setTargetSizeBytes(bytes: Long) {
        _compressionConfig.value = _compressionConfig.value.copy(
            mode = CompressionMode.EXACT_TARGET_SIZE,
            targetSizeBytes = bytes
        )
        _videoConfig.value = _videoConfig.value.withTargetSizeBytes(bytes)
    }

    fun setPercentageReduction(percent: Int) {
        _compressionConfig.value = _compressionConfig.value.copy(
            mode = CompressionMode.PERCENTAGE_REDUCTION,
            percentageReduction = percent
        )
    }

    fun setQualityProfile(profile: QualityProfile) {
        _compressionConfig.value = _compressionConfig.value.copy(
            mode = CompressionMode.QUALITY_PRESET,
            qualityProfile = profile
        )
    }

    fun setVideoConfig(config: VideoCompressionConfig) {
        _videoConfig.value = config
        _compressionConfig.value = _compressionConfig.value.copy(
            mode = CompressionMode.EXACT_TARGET_SIZE,
            targetSizeBytes = config.targetSizeBytes
        )
    }

    fun setMetadataConfig(config: MetadataConfig) { _metadataConfig.value = config }
    fun setSearchQuery(query: String) { _searchQuery.value = query }

    fun showPreview(filePath: String) { _previewFilePath.value = filePath }
    fun dismissPreview() { _previewFilePath.value = null }
    fun openDownloadsFolder(context: Context) { DownloadStorageHelper.openDownloadsFolder(context) }

    fun cancelActiveJob() {
        ProcessingJobCoordinator.cancel(getApplication())
    }

    // --- Background ForegroundService Execution ---
    fun executeConversion() {
        val files = _selectedFiles.value
        if (files.isEmpty() || ProcessingJobCoordinator.isProcessing.value) return
        ProcessingJobCoordinator.start(
            context = getApplication(),
            request = ProcessingTaskRequest.Convert(
                files = files.toList(),
                target = _targetFormat.value,
                metadataConfig = _metadataConfig.value
            )
        )
    }

    fun executeCompression() {
        val files = _selectedFiles.value
        if (files.isEmpty() || ProcessingJobCoordinator.isProcessing.value) return
        val config = _compressionConfig.value.copy(metadataConfig = _metadataConfig.value)
        ProcessingJobCoordinator.start(
            context = getApplication(),
            request = ProcessingTaskRequest.Compress(
                files = files.toList(),
                compressionConfig = config,
                videoConfig = _videoConfig.value
            )
        )
    }

    // --- History Deletion Handlers ---
    fun deleteHistoryItem(record: ConversionRecord) {
        viewModelScope.launch {
            repository.delete(record)
            withContext(Dispatchers.IO) {
                try {
                    if (!record.outputPath.startsWith("content://")) {
                        val f = File(record.outputPath)
                        if (f.exists()) f.delete()
                    }
                } catch (_: Exception) {}
            }
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            repository.clearAll()
        }
    }

    // --- Safe File Sharing & Opening ---
    private fun resolveShareableUri(context: Context, pathOrUri: String): Uri? {
        if (pathOrUri.startsWith("content://")) return Uri.parse(pathOrUri)
        val file = File(pathOrUri)
        if (!file.exists()) return null
        return try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (_: Exception) {
            Uri.fromFile(file)
        }
    }

    private fun inferMimeType(filePath: String): String {
        val ext = filePath.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    fun shareFile(context: Context, filePath: String?) {
        val path = filePath ?: return
        val uri = resolveShareableUri(context, path) ?: return
        val mime = inferMimeType(path)
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, "Share file").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (_: Exception) {}
    }

    fun shareAllResults(context: Context, results: List<ConversionJobResult>) {
        val uris = ArrayList<Uri>()
        for (r in results) {
            if (r.isSuccess && r.outputPath.isNotEmpty()) {
                resolveShareableUri(context, r.outputPath)?.let { uris.add(it) }
            }
        }
        if (uris.isEmpty()) return
        try {
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, "Share all files (${uris.size})").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (_: Exception) {}
    }

    fun openFile(context: Context, filePath: String?) {
        val path = filePath ?: return
        val uri = resolveShareableUri(context, path) ?: return
        val mime = inferMimeType(path)
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }
}
