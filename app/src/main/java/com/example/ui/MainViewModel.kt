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
import com.example.model.CompressionConfig
import com.example.model.CompressionMode
import com.example.model.ConversionJobResult
import com.example.model.FileDetails
import com.example.model.QualityProfile
import com.example.model.SupportedFormats
import com.example.model.TargetFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

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

    fun selectUri(uri: Uri) {
        viewModelScope.launch {
            val details = FileInspector.inspectUri(getApplication(), uri)
            _selectedFile.value = details
            _lastJobResult.value = null

            // Smart recommend target format based on category
            val recommended = SupportedFormats.getRecommendedFor(details.category, details.extension)
            if (recommended.isNotEmpty()) {
                _targetFormat.value = recommended.first()
            }

            // Also configure smart target size (e.g. 50% of original file or 500KB)
            val halfSize = (details.size / 2).coerceAtLeast(100 * 1024L)
            _compressionConfig.value = _compressionConfig.value.copy(
                targetSizeBytes = halfSize,
                percentageReduction = 50
            )
        }
    }

    fun selectSampleFile(sample: FileDetails) {
        _selectedFile.value = sample
        _lastJobResult.value = null
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
        _selectedFile.value = null
        _lastJobResult.value = null
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

    fun executeConversion() {
        val file = _selectedFile.value ?: return
        val target = _targetFormat.value
        viewModelScope.launch {
            _isProcessing.value = true
            _progress.value = 0.05f
            _progressStatus.value = "Starting conversion..."
            _lastJobResult.value = null

            val result = UniversalConverter.convertFile(
                context = getApplication(),
                source = file,
                target = target,
                onProgress = { p, status ->
                    _progress.value = p
                    _progressStatus.value = status
                }
            )

            _lastJobResult.value = result
            _isProcessing.value = false

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
    }

    fun executeCompression() {
        val file = _selectedFile.value ?: return
        val config = _compressionConfig.value
        viewModelScope.launch {
            _isProcessing.value = true
            _progress.value = 0.05f
            _progressStatus.value = "Starting intelligent compression engine..."
            _lastJobResult.value = null

            val result = UniversalCompressor.compressFile(
                context = getApplication(),
                source = file,
                config = config,
                onProgress = { p, status ->
                    _progress.value = p
                    _progressStatus.value = status
                }
            )

            _lastJobResult.value = result
            _isProcessing.value = false

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
