package com.example.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.data.AppDatabase
import com.example.data.ConversionRecord
import com.example.data.ConversionRepository
import com.example.model.BatchJobSummary
import com.example.model.CompressionConfig
import com.example.model.ConversionJobResult
import com.example.model.FileDetails
import com.example.model.MetadataConfig
import com.example.model.TargetFormat
import com.example.model.VideoCompressionConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class ProcessingTaskRequest {
    data class Convert(
        val files: List<FileDetails>,
        val target: TargetFormat,
        val metadataConfig: MetadataConfig
    ) : ProcessingTaskRequest()

    data class Compress(
        val files: List<FileDetails>,
        val compressionConfig: CompressionConfig,
        val videoConfig: VideoCompressionConfig
    ) : ProcessingTaskRequest()
}

object ProcessingJobCoordinator {
    @Volatile
    var pendingRequest: ProcessingTaskRequest? = null

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

    fun start(context: Context, request: ProcessingTaskRequest) {
        if (_isProcessing.value) return
        pendingRequest = request
        _isProcessing.value = true
        _progress.value = 0.02f
        _progressStatus.value = "Starting background processor..."
        _lastJobResult.value = null
        _batchSummary.value = null

        val intent = Intent(context, ProcessingForegroundService::class.java).apply {
            action = ProcessingForegroundService.ACTION_START
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun cancel(context: Context) {
        val intent = Intent(context, ProcessingForegroundService::class.java).apply {
            action = ProcessingForegroundService.ACTION_CANCEL
        }
        context.startService(intent)
    }

    fun clearResults() {
        if (!_isProcessing.value) {
            _lastJobResult.value = null
            _batchSummary.value = null
            _progress.value = 0f
            _progressStatus.value = ""
        }
    }

    internal fun updateProgress(p: Float, status: String) {
        _progress.value = p.coerceIn(0f, 1f)
        _progressStatus.value = status
    }

    internal fun complete(last: ConversionJobResult?, summary: BatchJobSummary?, finalStatus: String) {
        _lastJobResult.value = last
        _batchSummary.value = summary
        _progress.value = 1f
        _progressStatus.value = finalStatus
        _isProcessing.value = false
    }

    internal fun markStopped(status: String) {
        _progressStatus.value = status
        _isProcessing.value = false
    }
}

class ProcessingForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotifyTimeMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                activeJob?.cancel()
                activeJob = null
                ProcessingJobCoordinator.markStopped("Operation cancelled by user")
                releaseWakeLock()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val initialNotif = buildProgressNotification("Preparing OmniFile engine...", 2, true)
                startForegroundCompat(initialNotif)

                val request = ProcessingJobCoordinator.pendingRequest
                ProcessingJobCoordinator.pendingRequest = null
                if (request == null) {
                    ProcessingJobCoordinator.markStopped("")
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }

                if (activeJob?.isActive == true) {
                    return START_NOT_STICKY
                }

                acquireWakeLock()
                activeJob = serviceScope.launch {
                    executeTask(request)
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun executeTask(request: ProcessingTaskRequest) {
        val repository = ConversionRepository(AppDatabase.getDatabase(applicationContext).conversionDao())
        val batchStartTime = System.currentTimeMillis()
        val resultsList = mutableListOf<ConversionJobResult>()
        val recordsToInsert = mutableListOf<ConversionRecord>()
        var cancelled = false

        try {
            when (request) {
                is ProcessingTaskRequest.Convert -> {
                    val files = request.files
                    for (i in files.indices) {
                        val file = files[i]
                        val baseProgress = i.toFloat() / files.size.toFloat()

                        val result = UniversalConverter.convertFile(
                            context = applicationContext,
                            source = file,
                            target = request.target,
                            metadataConfig = request.metadataConfig,
                            onProgress = { p, status ->
                                val overall = baseProgress + (p / files.size.toFloat())
                                val msg = "File ${i + 1}/${files.size} (${file.name}): $status"
                                ProcessingJobCoordinator.updateProgress(overall, msg)
                                postThrottledNotification(msg, (overall * 100).toInt())
                            }
                        )
                        resultsList.add(result)

                        if (result.isSuccess) {
                            recordsToInsert.add(
                                ConversionRecord(
                                    inputFileName = result.inputName,
                                    inputFileSize = result.inputSizeBytes,
                                    outputFileName = result.outputName,
                                    outputFileSize = result.outputSizeBytes,
                                    fromFormat = file.extension.uppercase(),
                                    toFormat = request.target.extension.uppercase(),
                                    mode = "CONVERT",
                                    durationMs = result.durationMs,
                                    outputPath = result.outputPath ?: "",
                                    metadataSummary = result.metadataPreservedSummary ?: ""
                                )
                            )
                        }
                    }

                    if (recordsToInsert.isNotEmpty()) {
                        repository.insertBatch(recordsToInsert)
                    }

                    val summary = BatchJobSummary(
                        results = resultsList,
                        totalDurationMs = System.currentTimeMillis() - batchStartTime,
                        mode = "CONVERT"
                    )
                    val successCount = resultsList.count { it.isSuccess }
                    val msg = "Conversion complete: $successCount/${files.size} succeeded"
                    ProcessingJobCoordinator.complete(resultsList.lastOrNull(), summary, msg)
                    showCompletionNotification(msg)
                }

                is ProcessingTaskRequest.Compress -> {
                    val files = request.files
                    val vidConfig = request.videoConfig
                    for (i in files.indices) {
                        val file = files[i]
                        val baseProgress = i.toFloat() / files.size.toFloat()

                        val result = UniversalCompressor.compressFile(
                            context = applicationContext,
                            source = file,
                            config = request.compressionConfig,
                            videoConfig = vidConfig,
                            audioCodecMode = vidConfig.effectiveAudioMode,
                            enableAllCodecs = vidConfig.enableAllCodecs,
                            onProgress = { p, status ->
                                val overall = baseProgress + (p / files.size.toFloat())
                                val msg = "File ${i + 1}/${files.size} (${file.name}): $status"
                                ProcessingJobCoordinator.updateProgress(overall, msg)
                                postThrottledNotification(msg, (overall * 100).toInt())
                            }
                        )
                        resultsList.add(result)

                        if (result.isSuccess) {
                            recordsToInsert.add(
                                ConversionRecord(
                                    inputFileName = result.inputName,
                                    inputFileSize = result.inputSizeBytes,
                                    outputFileName = result.outputName,
                                    outputFileSize = result.outputSizeBytes,
                                    fromFormat = file.extension.uppercase(),
                                    toFormat = file.extension.uppercase(),
                                    mode = "COMPRESS",
                                    durationMs = result.durationMs,
                                    outputPath = result.outputPath ?: "",
                                    metadataSummary = result.metadataPreservedSummary ?: ""
                                )
                            )
                        }
                    }

                    if (recordsToInsert.isNotEmpty()) {
                        repository.insertBatch(recordsToInsert)
                    }

                    val summary = BatchJobSummary(
                        results = resultsList,
                        totalDurationMs = System.currentTimeMillis() - batchStartTime,
                        mode = "COMPRESS"
                    )
                    val successCount = resultsList.count { it.isSuccess }
                    val msg = "Compression complete: $successCount/${files.size} succeeded"
                    ProcessingJobCoordinator.complete(resultsList.lastOrNull(), summary, msg)
                    showCompletionNotification(msg)
                }
            }
        } catch (_: CancellationException) {
            cancelled = true
            ProcessingJobCoordinator.markStopped("Operation cancelled by user")
        } catch (e: Exception) {
            e.printStackTrace()
            val errMsg = "Error: ${e.localizedMessage ?: "Processing halted"}"
            ProcessingJobCoordinator.markStopped(errMsg)
            showCompletionNotification(errMsg)
        } finally {
            releaseWakeLock()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            if (!cancelled) {
                stopSelf()
            }
        }
    }

    // Android 15+ (API 35+) dataSync timeout safeguard
    override fun onTimeout(startId: Int, fgsType: Int) {
        activeJob?.cancel()
        ProcessingJobCoordinator.markStopped("Background timeout reached; please reopen app to continue.")
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        activeJob?.cancel()
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OmniFile::ProcessingWakeLock").apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(60 * 60 * 1000L) // 60 min max safety timeout
            }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OmniFile Background Processing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress for local video compression and file conversions"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun postThrottledNotification(status: String, percent: Int) {
        val now = System.currentTimeMillis()
        if (now - lastNotifyTimeMs < 350L && percent < 99) return
        lastNotifyTimeMs = now

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildProgressNotification(status, percent.coerceIn(0, 100), false))
    }

    private fun buildProgressNotification(status: String, percent: Int, indeterminate: Boolean): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, ProcessingForegroundService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPending = PendingIntent.getService(
            this,
            1,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("OmniFile Processing ($percent%)")
            .setContentText(status)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(openPending)
            .setProgress(100, percent, indeterminate)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPending)
            .build()
    }

    private fun showCompletionNotification(message: String) {
        try {
            val openIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openPending = PendingIntent.getActivity(
                this,
                2,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("OmniFile")
                .setContentText(message)
                .setAutoCancel(true)
                .setContentIntent(openPending)
                .build()

            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(COMPLETION_NOTIFICATION_ID, notif)
        } catch (_: Exception) {}
    }

    companion object {
        const val ACTION_START = "com.example.action.START_PROCESSING"
        const val ACTION_CANCEL = "com.example.action.CANCEL_PROCESSING"
        private const val CHANNEL_ID = "omnifile_processing_channel"
        private const val NOTIFICATION_ID = 1001
        private const val COMPLETION_NOTIFICATION_ID = 1002
    }
}
