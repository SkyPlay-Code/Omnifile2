package com.example.engine

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

sealed class ProcessingTaskRequest {
    abstract val files: List<FileDetails>

    data class Convert(
        override val files: List<FileDetails>,
        val target: TargetFormat,
        val metadataConfig: MetadataConfig
    ) : ProcessingTaskRequest()

    data class Compress(
        override val files: List<FileDetails>,
        val compressionConfig: CompressionConfig,
        val videoConfig: VideoCompressionConfig
    ) : ProcessingTaskRequest()
}

internal data class PendingProcessingJob(
    val jobId: Long,
    val request: ProcessingTaskRequest
)

object ProcessingJobCoordinator {

    private val lock = Any()
    private var nextJobId = 0L
    private var activeJobId = 0L
    private var lastOwnedJobId = 0L
    private var cancelledJobId = 0L
    private var pendingJob: PendingProcessingJob? = null
    private var serviceCancelHook: ((Long) -> Unit)? = null

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
        val safeRequest = when (request) {
            is ProcessingTaskRequest.Convert -> request.copy(files = request.files.toList())
            is ProcessingTaskRequest.Compress -> request.copy(files = request.files.toList())
        }

        if (safeRequest.files.isEmpty()) {
            synchronized(lock) {
                if (!_isProcessing.value) {
                    _lastJobResult.value = null
                    _batchSummary.value = null
                    _progress.value = 0f
                    _progressStatus.value = "No files selected to process"
                }
            }
            return
        }

        val jobId: Long
        synchronized(lock) {
            if (_isProcessing.value) return
            jobId = ++nextJobId
            activeJobId = jobId
            lastOwnedJobId = jobId
            pendingJob = PendingProcessingJob(jobId, safeRequest)
            _isProcessing.value = true
            _progress.value = 0.02f
            _progressStatus.value = "Starting background processor..."
            _lastJobResult.value = null
            _batchSummary.value = null
        }

        val appContext = context.applicationContext
        val intent = Intent(appContext, ProcessingForegroundService::class.java).apply {
            action = ProcessingForegroundService.ACTION_START
        }

        try {
            ContextCompat.startForegroundService(appContext, intent)
        } catch (e: Exception) {
            e.printStackTrace()
            synchronized(lock) {
                if (activeJobId == jobId) {
                    pendingJob = null
                    activeJobId = 0L
                    _isProcessing.value = false
                    _progress.value = 0f
                    _progressStatus.value =
                        "Unable to start background processor: ${e.localizedMessage ?: "Start blocked"}"
                }
            }
        }
    }

    fun cancel(context: Context) {
        val jobIdToCancel: Long
        val hook: ((Long) -> Unit)?
        synchronized(lock) {
            if (!_isProcessing.value || activeJobId == 0L) return
            jobIdToCancel = activeJobId
            cancelledJobId = jobIdToCancel

            if (pendingJob?.jobId == jobIdToCancel) {
                // Service has not consumed the job yet; cancel immediately in memory.
                pendingJob = null
                activeJobId = 0L
                _isProcessing.value = false
                _progressStatus.value = "Operation cancelled by user"
                return
            }

            // Transition coordinator state synchronously; service hook cancels activeJob without startService().
            activeJobId = 0L
            _isProcessing.value = false
            _progressStatus.value = "Operation cancelled by user"
            hook = serviceCancelHook
        }

        if (hook != null) {
            hook.invoke(jobIdToCancel)
        } else {
            // Fallback broadcast only if service hasn't registered its hook yet.
            val appContext = context.applicationContext
            val cancelIntent = Intent(ProcessingForegroundService.ACTION_CANCEL).apply {
                setPackage(appContext.packageName)
            }
            appContext.sendBroadcast(cancelIntent)
        }
    }

    fun clearResults() {
        synchronized(lock) {
            if (!_isProcessing.value) {
                _lastJobResult.value = null
                _batchSummary.value = null
                _progress.value = 0f
                _progressStatus.value = ""
            }
        }
    }

    internal fun consumePendingJob(): PendingProcessingJob? = synchronized(lock) {
        val pending = pendingJob
        pendingJob = null
        pending
    }

    internal fun registerServiceCancelHook(hook: (Long) -> Unit) {
        synchronized(lock) {
            serviceCancelHook = hook
        }
    }

    internal fun unregisterServiceCancelHook(hook: (Long) -> Unit) {
        synchronized(lock) {
            if (serviceCancelHook === hook) {
                serviceCancelHook = null
            }
        }
    }

    internal fun isJobActive(jobId: Long): Boolean = synchronized(lock) {
        activeJobId == jobId && cancelledJobId != jobId
    }

    internal fun updateProgress(jobId: Long, p: Float, status: String) {
        synchronized(lock) {
            if (activeJobId != jobId || cancelledJobId == jobId) return
            if (p.isFinite()) {
                _progress.value = p.coerceIn(0f, 1f)
            }
            _progressStatus.value = status
        }
    }

    internal fun complete(
        jobId: Long,
        last: ConversionJobResult?,
        summary: BatchJobSummary?,
        finalStatus: String
    ) {
        synchronized(lock) {
            if (activeJobId != jobId || cancelledJobId == jobId) return
            _lastJobResult.value = last
            _batchSummary.value = summary
            _progress.value = 1f
            _progressStatus.value = finalStatus
            _isProcessing.value = false
            activeJobId = 0L
        }
    }

    internal fun attachPartialResultsIfCurrent(
        jobId: Long,
        last: ConversionJobResult?,
        summary: BatchJobSummary?
    ) {
        synchronized(lock) {
            if (lastOwnedJobId != jobId) return
            if (last != null) _lastJobResult.value = last
            if (summary != null) _batchSummary.value = summary
        }
    }

    internal fun markStopped(
        jobId: Long,
        status: String,
        last: ConversionJobResult? = null,
        summary: BatchJobSummary? = null
    ) {
        synchronized(lock) {
            if (lastOwnedJobId != jobId) return
            if (last != null) _lastJobResult.value = last
            if (summary != null) _batchSummary.value = summary
            _progressStatus.value = status
            if (activeJobId == jobId) {
                _isProcessing.value = false
                activeJobId = 0L
            }
        }
    }

    internal fun markCancelledByService(jobId: Long, status: String) {
        synchronized(lock) {
            cancelledJobId = jobId
            if (pendingJob?.jobId == jobId) {
                pendingJob = null
            }
            if (activeJobId == jobId) {
                activeJobId = 0L
                _isProcessing.value = false
            }
            if (lastOwnedJobId == jobId) {
                _progressStatus.value = status
            }
        }
    }

    internal fun markInterruptedIfActive(jobId: Long) {
        synchronized(lock) {
            if (pendingJob?.jobId == jobId) {
                pendingJob = null
            }
            if (activeJobId == jobId) {
                activeJobId = 0L
                _isProcessing.value = false
                _progressStatus.value = "Background processing interrupted"
            }
        }
    }
}

class ProcessingForegroundService : Service() {

    private enum class StopReason {
        USER_CANCELLED,
        TIMEOUT,
        INTERRUPTED
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        throwable.printStackTrace()
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)

    private val wakeLockGuard = Any()
    private val notificationGuard = Any()
    private val stopReasons = ConcurrentHashMap<Long, StopReason>()

    @Volatile
    private var activeJob: Job? = null

    @Volatile
    private var currentJobId: Long = 0L

    @Volatile
    private var latestStartId: Int = 0

    @Volatile
    private var lastNotifyTimeMs: Long = 0L

    @Volatile
    private var lastProgressNotification: Notification? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var receiverRegistered = false

    private val cancelHook: (Long) -> Unit = { jobId ->
        mainHandler.post {
            handleCancelRequest(expectedJobId = jobId, stopReason = StopReason.USER_CANCELLED)
        }
    }

    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_CANCEL) {
                val activeId = currentJobId
                if (activeId != 0L) {
                    ProcessingJobCoordinator.markCancelledByService(activeId, "Operation cancelled by user")
                    handleCancelRequest(expectedJobId = activeId, stopReason = StopReason.USER_CANCELLED)
                }
            }
        }
    }

    private val openPendingIntent: PendingIntent by lazy {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )
    }

    private val cancelPendingIntent: PendingIntent by lazy {
        val cancelIntent = Intent(ACTION_CANCEL).apply {
            setPackage(packageName)
        }
        PendingIntent.getBroadcast(
            this,
            1,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannels()
        ProcessingJobCoordinator.registerServiceCancelHook(cancelHook)
        ContextCompat.registerReceiver(
            this,
            cancelReceiver,
            IntentFilter(ACTION_CANCEL),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId

        when (intent?.action) {
            ACTION_CANCEL -> {
                val activeId = currentJobId
                if (activeId != 0L) {
                    ProcessingJobCoordinator.markCancelledByService(activeId, "Operation cancelled by user")
                    handleCancelRequest(expectedJobId = activeId, stopReason = StopReason.USER_CANCELLED)
                } else {
                    stopSelf(latestStartId)
                }
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val pending = ProcessingJobCoordinator.consumePendingJob()

                if (pending == null) {
                    // If a valid job is already running, preserve it and refresh its current foreground notification.
                    if (activeJob?.isActive == true && currentJobId != 0L) {
                        val existingNotif = lastProgressNotification
                            ?: buildProgressNotification("OmniFile processing...", 0, true)
                        startForegroundCompat(existingNotif)
                        return START_NOT_STICKY
                    }

                    // Must satisfy startForegroundService() contract before stopping.
                    val fallbackNotif = buildProgressNotification("Preparing OmniFile engine...", 0, true)
                    startForegroundCompat(fallbackNotif)
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf(latestStartId)
                    return START_NOT_STICKY
                }

                val initialNotif = buildProgressNotification("Preparing OmniFile engine...", 2, true)
                synchronized(notificationGuard) {
                    lastNotifyTimeMs = 0L
                    lastProgressNotification = initialNotif
                    startForegroundCompat(initialNotif)
                }

                currentJobId = pending.jobId
                stopReasons.remove(pending.jobId)
                acquireOrRenewWakeLock()

                val previousJob = activeJob
                activeJob = serviceScope.launch {
                    previousJob?.join()
                    executeTask(pending.jobId, pending.request)
                }
                return START_NOT_STICKY
            }

            else -> {
                if (activeJob?.isActive == true && currentJobId != 0L) {
                    return START_NOT_STICKY
                }
                val fallbackNotif = buildProgressNotification("Preparing OmniFile engine...", 0, true)
                startForegroundCompat(fallbackNotif)
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf(latestStartId)
                return START_NOT_STICKY
            }
        }
    }

    private suspend fun executeTask(jobId: Long, request: ProcessingTaskRequest) {
        val repository = ConversionRepository(AppDatabase.getDatabase(applicationContext).conversionDao())
        val batchStartTime = SystemClock.elapsedRealtime()
        val resultsList = mutableListOf<ConversionJobResult>()
        val mode = when (request) {
            is ProcessingTaskRequest.Convert -> "CONVERT"
            is ProcessingTaskRequest.Compress -> "COMPRESS"
        }

        try {
            if (!ProcessingJobCoordinator.isJobActive(jobId)) {
                throw CancellationException("Job $jobId cancelled before start")
            }

            val files = request.files
            val totalFiles = files.size

            for (i in files.indices) {
                coroutineContext.ensureActive()
                if (!ProcessingJobCoordinator.isJobActive(jobId)) {
                    throw CancellationException("Job $jobId cancelled")
                }

                // Refresh WakeLock per file so >60-minute batches do not stall mid-run.
                acquireOrRenewWakeLock()

                val file = files[i]
                val baseProgress = i.toFloat() / totalFiles.toFloat()

                val result = when (request) {
                    is ProcessingTaskRequest.Convert -> {
                        UniversalConverter.convertFile(
                            context = applicationContext,
                            source = file,
                            target = request.target,
                            metadataConfig = request.metadataConfig,
                            onProgress = { p, status ->
                                if (!ProcessingJobCoordinator.isJobActive(jobId)) return@convertFile
                                val safeFileP = if (p.isFinite()) p.coerceIn(0f, 1f) else 0f
                                val overall = ((i.toFloat() + safeFileP) / totalFiles.toFloat()).coerceIn(0f, 1f)
                                val msg = "File ${i + 1}/$totalFiles (${file.name}): $status"
                                ProcessingJobCoordinator.updateProgress(jobId, overall, msg)
                                postThrottledNotification(jobId, msg, (overall * 100f).toInt())
                            }
                        )
                    }

                    is ProcessingTaskRequest.Compress -> {
                        val vidConfig = request.videoConfig
                        UniversalCompressor.compressFile(
                            context = applicationContext,
                            source = file,
                            config = request.compressionConfig,
                            videoConfig = vidConfig,
                            audioCodecMode = vidConfig.effectiveAudioMode,
                            enableAllCodecs = vidConfig.enableAllCodecs,
                            onProgress = { p, status ->
                                if (!ProcessingJobCoordinator.isJobActive(jobId)) return@compressFile
                                val safeFileP = if (p.isFinite()) p.coerceIn(0f, 1f) else 0f
                                val overall = ((i.toFloat() + safeFileP) / totalFiles.toFloat()).coerceIn(0f, 1f)
                                val msg = "File ${i + 1}/$totalFiles (${file.name}): $status"
                                ProcessingJobCoordinator.updateProgress(jobId, overall, msg)
                                postThrottledNotification(jobId, msg, (overall * 100f).toInt())
                            }
                        )
                    }
                }

                // Guard against libraries that catch CancellationException internally and return normally.
                if (!ProcessingJobCoordinator.isJobActive(jobId)) {
                    throw CancellationException("Job $jobId cancelled during file processing")
                }

                resultsList.add(result)

                if (result.isSuccess) {
                    val toFormat = when (request) {
                        is ProcessingTaskRequest.Convert -> request.target.extension.uppercase()
                        is ProcessingTaskRequest.Compress -> {
                            result.outputName
                                .substringAfterLast('.', "")
                                .ifBlank { file.extension }
                                .uppercase()
                        }
                    }

                    val record = ConversionRecord(
                        inputFileName = result.inputName,
                        inputFileSize = result.inputSizeBytes,
                        outputFileName = result.outputName,
                        outputFileSize = result.outputSizeBytes,
                        fromFormat = file.extension.uppercase(),
                        toFormat = toFormat,
                        mode = mode,
                        durationMs = result.durationMs,
                        outputPath = result.outputPath ?: "",
                        metadataSummary = result.metadataPreservedSummary ?: ""
                    )

                    withContext(NonCancellable) {
                        repository.insertBatch(listOf(record))
                    }
                }

                val stepProgress = ((i + 1).toFloat() / totalFiles.toFloat()).coerceIn(0f, 1f)
                val stepMsg = "Completed ${i + 1}/$totalFiles (${file.name})"
                ProcessingJobCoordinator.updateProgress(jobId, stepProgress, stepMsg)
                postThrottledNotification(jobId, stepMsg, (stepProgress * 100f).toInt())
            }

            if (!ProcessingJobCoordinator.isJobActive(jobId)) {
                throw CancellationException("Job $jobId cancelled")
            }

            val totalDurationMs = (SystemClock.elapsedRealtime() - batchStartTime).coerceAtLeast(0L)
            val summary = BatchJobSummary(
                results = resultsList.toList(),
                totalDurationMs = totalDurationMs,
                mode = mode
            )
            val successCount = resultsList.count { it.isSuccess }
            val actionLabel = if (mode == "CONVERT") "Conversion" else "Compression"
            val msg = "$actionLabel complete: $successCount/$totalFiles succeeded"

            releaseWakeLock()
            showCompletionNotification(msg)
            ProcessingJobCoordinator.complete(jobId, resultsList.lastOrNull(), summary, msg)
        } catch (_: CancellationException) {
            val reason = stopReasons.remove(jobId) ?: StopReason.INTERRUPTED
            val partialSummary = if (resultsList.isNotEmpty()) {
                BatchJobSummary(
                    results = resultsList.toList(),
                    totalDurationMs = (SystemClock.elapsedRealtime() - batchStartTime).coerceAtLeast(0L),
                    mode = mode
                )
            } else {
                null
            }

            when (reason) {
                StopReason.USER_CANCELLED -> {
                    ProcessingJobCoordinator.attachPartialResultsIfCurrent(
                        jobId = jobId,
                        last = resultsList.lastOrNull(),
                        summary = partialSummary
                    )
                    ProcessingJobCoordinator.markStopped(
                        jobId = jobId,
                        status = "Operation cancelled by user"
                    )
                }

                StopReason.TIMEOUT -> {
                    val timeoutMsg = "Background timeout reached; please reopen app to continue."
                    ProcessingJobCoordinator.markStopped(
                        jobId = jobId,
                        status = timeoutMsg,
                        last = resultsList.lastOrNull(),
                        summary = partialSummary
                    )
                }

                StopReason.INTERRUPTED -> {
                    ProcessingJobCoordinator.markStopped(
                        jobId = jobId,
                        status = "Background processing interrupted",
                        last = resultsList.lastOrNull(),
                        summary = partialSummary
                    )
                }
            }
        } catch (t: Throwable) {
            t.printStackTrace()
            val errMsg = "Error: ${t.localizedMessage ?: t::class.java.simpleName ?: "Processing halted"}"
            val partialSummary = if (resultsList.isNotEmpty()) {
                BatchJobSummary(
                    results = resultsList.toList(),
                    totalDurationMs = (SystemClock.elapsedRealtime() - batchStartTime).coerceAtLeast(0L),
                    mode = mode
                )
            } else {
                null
            }
            ProcessingJobCoordinator.markStopped(
                jobId = jobId,
                status = errMsg,
                last = resultsList.lastOrNull(),
                summary = partialSummary
            )
            showCompletionNotification(errMsg)
        } finally {
            stopReasons.remove(jobId)
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                finishServiceIfOwningJob(jobId)
            }
        }
    }

    // Android 15+ (API 35+) foreground service timeout safeguard
    override fun onTimeout(startId: Int, fgsType: Int) {
        latestStartId = startId
        val timeoutMsg = "Background timeout reached; please reopen app to continue."
        val activeId = currentJobId
        if (activeId != 0L) {
            ProcessingJobCoordinator.markStopped(activeId, timeoutMsg)
            showCompletionNotification(timeoutMsg)
            handleCancelRequest(expectedJobId = activeId, stopReason = StopReason.TIMEOUT)
        } else {
            releaseWakeLock()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(latestStartId)
        }
    }

    override fun onDestroy() {
        ProcessingJobCoordinator.unregisterServiceCancelHook(cancelHook)
        if (receiverRegistered) {
            try {
                unregisterReceiver(cancelReceiver)
            } catch (_: IllegalArgumentException) {
            }
            receiverRegistered = false
        }

        val activeId = currentJobId
        currentJobId = 0L
        if (activeId != 0L) {
            stopReasons.putIfAbsent(activeId, StopReason.INTERRUPTED)
            ProcessingJobCoordinator.markInterruptedIfActive(activeId)
        }

        activeJob?.cancel()
        activeJob = null
        serviceScope.cancel()
        releaseWakeLock()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun handleCancelRequest(expectedJobId: Long, stopReason: StopReason) {
        if (currentJobId != expectedJobId || expectedJobId == 0L) return

        stopReasons[expectedJobId] = stopReason
        synchronized(notificationGuard) {
            currentJobId = 0L
            lastProgressNotification = null
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        activeJob?.cancel()
        releaseWakeLock()
        stopSelf(latestStartId)
    }

    private fun finishServiceIfOwningJob(jobId: Long) {
        val ownsService = synchronized(notificationGuard) {
            if (currentJobId == jobId) {
                currentJobId = 0L
                lastProgressNotification = null
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                true
            } else {
                false
            }
        }
        if (ownsService) {
            releaseWakeLock()
            stopSelf(latestStartId)
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        val fgsType = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
    }

    private fun acquireOrRenewWakeLock() {
        synchronized(wakeLockGuard) {
            try {
                if (wakeLock == null) {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    wakeLock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "OmniFile::ProcessingWakeLock"
                    ).apply {
                        setReferenceCounted(false)
                    }
                }
                wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)
            } catch (_: Exception) {
            }
        }
    }

    private fun releaseWakeLock() {
        synchronized(wakeLockGuard) {
            try {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun ensureNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val progressChannel = NotificationChannel(
                PROGRESS_CHANNEL_ID,
                "OmniFile Background Processing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress for local video compression and file conversions"
                setShowBadge(false)
            }

            val completionChannel = NotificationChannel(
                COMPLETION_CHANNEL_ID,
                "OmniFile Job Results",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies when background compression or conversion jobs finish"
                setShowBadge(true)
            }

            nm.createNotificationChannel(progressChannel)
            nm.createNotificationChannel(completionChannel)
        }
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            nm.areNotificationsEnabled()
        } else {
            true
        }
    }

    private fun postThrottledNotification(jobId: Long, status: String, percent: Int) {
        val clampedPercent = percent.coerceIn(0, 100)
        val now = SystemClock.elapsedRealtime()

        synchronized(notificationGuard) {
            if (currentJobId != jobId || !ProcessingJobCoordinator.isJobActive(jobId)) return
            if (now - lastNotifyTimeMs < NOTIFY_THROTTLE_MS) return
            lastNotifyTimeMs = now

            val notif = buildProgressNotification(status, clampedPercent, indeterminate = false)
            lastProgressNotification = notif

            if (!canPostNotifications()) return
            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, notif)
            } catch (_: Exception) {
            }
        }
    }

    private fun buildProgressNotification(
        status: String,
        percent: Int,
        indeterminate: Boolean
    ): Notification {
        val safePercent = percent.coerceIn(0, 100)
        val title = if (indeterminate) {
            "OmniFile Processing"
        } else {
            "OmniFile Processing ($safePercent%)"
        }

        return NotificationCompat.Builder(this, PROGRESS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText(status))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .setProgress(100, safePercent, indeterminate)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                cancelPendingIntent
            )
            .build()
    }

    private fun showCompletionNotification(message: String) {
        if (!canPostNotifications()) return
        try {
            val notif = NotificationCompat.Builder(this, COMPLETION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("OmniFile")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .setContentIntent(openPendingIntent)
                .build()

            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(COMPLETION_NOTIFICATION_ID, notif)
        } catch (_: Exception) {
        }
    }

    private fun immutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    companion object {
        const val ACTION_START = "com.example.action.START_PROCESSING"
        const val ACTION_CANCEL = "com.example.action.CANCEL_PROCESSING"

        private const val PROGRESS_CHANNEL_ID = "omnifile_processing_channel"
        private const val COMPLETION_CHANNEL_ID = "omnifile_completion_channel"
        private const val NOTIFICATION_ID = 1001
        private const val COMPLETION_NOTIFICATION_ID = 1002
        private const val NOTIFY_THROTTLE_MS = 350L
        private const val WAKELOCK_TIMEOUT_MS = 60 * 60 * 1000L
    }
}
