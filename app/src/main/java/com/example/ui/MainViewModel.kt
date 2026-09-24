package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.ConversionRecord
import com.example.data.ConversionRepository
import com.example.engine.FileInspector
import com.example.engine.SampleFilesGenerator
import com.example.engine.UniversalCompressor
import com.example.engine.UniversalConverter
import com.example.model.BatchJobSummary
import com.example.model.CompressionConfig
import com.example.model.CompressionMode
import com.example.model.ConversionJobResult
import com.example.model.FileDetails
import com.example.model.MetadataConfig
import com.example.model.QualityProfile
import com.example.model.SupportedFormats
import com.example.model.TargetFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    val historyRecords: StateFlow<List<ConversionRecord>> = repository.allRecords
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalSavingsBytes: StateFlow<Long> = repository.totalBytesSaved
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val totalOperationsCount: StateFlow<Int> = repository.totalCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

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

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _progressStatus = MutableStateFlow("")
    val progressStatus: StateFlow<String> = _progressStatus.asStateFlow()

    private val _lastJobResult = MutableStateFlow<ConversionJobResult?>(null)
    val lastJobResult: StateFlow<ConversionJobResult?> = _lastJobResult.asStateFlow()

    private val _batchSummary = MutableStateFlow<BatchJobSummary?>(null)
    val batchSummary: StateFlow<BatchJobSummary?> = _batchSummary.asStateFlow()

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

    fun selectUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val detailsList = withContext(Dispatchers.IO) {
                uris.map { uri -> FileInspector.inspectUri(getApplication(), uri) }
            }
            _selectedFiles.value = detailsList
            _selectedFile.value = detailsList.firstOrNull()
            _lastJobResult.value = null
            _batchSummary.value = null

            // Recommend format based on the first file
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
            _lastJobResult.value = null
            _batchSummary.value = null
        }
    }

    fun removeFile(file: FileDetails) {
        val current = _selectedFiles.value.toMutableList()
        current.remove(file)
        _selectedFiles.value = current
        _selectedFile.value = current.firstOrNull()
        if (current.isEmpty()) {
            _lastJobResult.value = null
            _batchSummary.value = null
        }
    }

    fun selectUri(uri: Uri) {
        selectUris(listOf(uri))
    }

    fun selectSampleFile(sample: FileDetails) {
        _selectedFiles.value = listOf(sample)
        _selectedFile.value = sample
        _lastJobResult.value = null
        _batchSummary.value = null

        val recommended = SupportedFormats.getRecommendedFor(sample.category, sample.extension)
        if (recommended.isNotEmpty()) {
            _targetFormat.value = recommended.first()
        }
        val halfSize = (sample.size / 2).coerceAtLeast(100 * 1024L)
        _compressionConfig.value = _compressionConfig.value.copy(
            targetSizeBytes = halfSize,
            percentageReduction = 50
        )
    }

    fun clearSelection() {
        _selectedFiles.value = emptyList()
        _selectedFile.value = null
        _lastJobResult.value = null
        _batchSummary.value = null
    }

    fun setTargetFormat(format: TargetFormat) {
        _targetFormat.value = format
    }

    fun setCompressionMode(mode: CompressionMode) {
        _compressionConfig.value = _compressionConfig.value.copy(mode = mode)
    }

    fun setTargetSizeBytes(bytes: Long) {
        _compressionConfig.value = _compressionConfig.value.copy(
            mode = CompressionMode.EXACT_TARGET_SIZE,
            targetSizeBytes = bytes
        )
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

    fun setMetadataConfig(config: MetadataConfig) {
        _metadataConfig.value = config
    }

    fun showPreview(filePath: String) {
        _previewFilePath.value = filePath
    }

    fun dismissPreview() {
        _previewFilePath.value = null
    }

    fun openDownloadsFolder(context: Context) {
        com.example.engine.DownloadStorageHelper.openDownloadsFolder(context)
    }

    fun executeConversion() {
        val files = _selectedFiles.value
        if (files.isEmpty()) return
        val target = _targetFormat.value
        val metaConfig = _metadataConfig.value

        viewModelScope.launch {
            _isProcessing.value = true
            _progress.value = 0.02f
            _progressStatus.value = "Starting conversion of ${files.size} file(s)..."
            _lastJobResult.value = null
            _batchSummary.value = null

            val batchStartTime = System.currentTimeMillis()
            val resultsList = mutableListOf<ConversionJobResult>()

            for (i in files.indices) {
                val file = files[i]
                val baseProgress = i.toFloat() / files.size.toFloat()

                val result = UniversalConverter.convertFile(
                    context = getApplication(),
                    source = file,
                    target = target,
                    metadataConfig = metaConfig,
                    onProgress = { p, status ->
                        val overall = baseProgress + (p / files.size.toFloat())
                        _progress.value = overall
                        _progressStatus.value = "File ${i + 1}/${files.size} (${file.name}): $status"
                    }
                )

                resultsList.add(result)

                if (result.isSuccess) {
                    repository.insert(
                        ConversionRecord(
                            inputFileName = result.inputName,
                            inputFileSize = result.inputSizeBytes,
                            outputFileName = result.outputName,
                            outputFileSize = result.outputSizeBytes,
                            fromFormat = file.extension.uppercase(),
                            toFormat = target.extension.uppercase(),
                            mode = "CONVERT",
                            durationMs = result.durationMs,
                            outputPath = result.outputPath
                        )
                    )
                }
            }

            _lastJobResult.value = resultsList.lastOrNull()
            _batchSummary.value = BatchJobSummary(
                results = resultsList,
                totalDurationMs = System.currentTimeMillis() - batchStartTime,
                mode = "CONVERT"
            )
            _progress.value = 1.0f
            _progressStatus.value = "Batch conversion complete! ${resultsList.count { it.isSuccess }}/${files.size} succeeded."
            _isProcessing.value = false
        }
    }

    fun executeCompression() {
        val files = _selectedFiles.value
        if (files.isEmpty()) return
        val config = _compressionConfig.value.copy(metadataConfig = _metadataConfig.value)

        viewModelScope.launch {
            _isProcessing.value = true
            _progress.value = 0.02f
            _progressStatus.value = "Starting compression of ${files.size} file(s)..."
            _lastJobResult.value = null
            _batchSummary.value = null

            val batchStartTime = System.currentTimeMillis()
            val resultsList = mutableListOf<ConversionJobResult>()

            for (i in files.indices) {
                val file = files[i]
                val baseProgress = i.toFloat() / files.size.toFloat()

                val result = UniversalCompressor.compressFile(
                    context = getApplication(),
                    source = file,
                    config = config,
                    onProgress = { p, status ->
                        val overall = baseProgress + (p / files.size.toFloat())
                        _progress.value = overall
                        _progressStatus.value = "File ${i + 1}/${files.size} (${file.name}): $status"
                    }
                )

                resultsList.add(result)

                if (result.isSuccess) {
                    repository.insert(
                        ConversionRecord(
                            inputFileName = result.inputName,
                            inputFileSize = result.inputSizeBytes,
                            outputFileName = result.outputName,
                            outputFileSize = result.outputSizeBytes,
                            fromFormat = file.extension.uppercase(),
                            toFormat = file.extension.uppercase(),
                            mode = "COMPRESS",
                            durationMs = result.durationMs,
                            outputPath = result.outputPath
                        )
                    )
                }
            }

            _lastJobResult.value = resultsList.lastOrNull()
            _batchSummary.value = BatchJobSummary(
                results = resultsList,
                totalDurationMs = System.currentTimeMillis() - batchStartTime,
                mode = "COMPRESS"
            )
            _progress.value = 1.0f
            _progressStatus.value = "Batch compression complete! ${resultsList.count { it.isSuccess }}/${files.size} succeeded."
            _isProcessing.value = false
        }
    }

    fun deleteHistoryItem(record: ConversionRecord) {
        viewModelScope.launch {
            repository.delete(record)
            try {
                val f = File(record.outputPath)
                if (f.exists()) f.delete()
            } catch (_: Exception) {}
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            repository.clearAll()
        }
    }

    fun shareFile(context: Context, filePath: String) {
        val file = File(filePath)
        if (!file.exists()) return
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share converted file"))
        } catch (_: Exception) {}
    }

    fun shareAllResults(context: Context, results: List<ConversionJobResult>) {
        val uris = ArrayList<Uri>()
        for (r in results) {
            if (r.isSuccess) {
                val f = File(r.outputPath)
                if (f.exists()) {
                    try {
                        val u = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            f
                        )
                        uris.add(u)
                    } catch (_: Exception) {}
                }
            }
        }
        if (uris.isEmpty()) return
        try {
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share all processed files (${uris.size})"))
        } catch (_: Exception) {}
    }

    fun openFile(context: Context, filePath: String) {
        val file = File(filePath)
        if (!file.exists()) return
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }
}
