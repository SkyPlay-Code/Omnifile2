package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.EncoderSelector
import androidx.media3.transformer.EncoderUtil
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.example.model.CompressionConfig
import com.example.model.CompressionMode
import com.example.model.ConversionJobResult
import com.example.model.FileCategory
import com.example.model.FileDetails
import com.example.model.QualityProfile
import com.example.model.VideoCodec
import com.example.model.VideoCompressionConfig
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class DeviceCodecCapability(
    val mimeType: String,
    val label: String,
    val hasHardware: Boolean,
    val hasSoftware: Boolean,
    val supportsCbr: Boolean
)

object DeviceCodecInspector {

    fun isSoftwareCodec(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return info.isSoftwareOnly
        }
        val name = info.name.lowercase()
        return name.startsWith("omx.google.") ||
            name.startsWith("c2.android.") ||
            (!name.startsWith("omx.") && !name.startsWith("c2."))
    }

    fun isCbrSupportedForHardware(mimeType: String): Boolean {
        val codecs = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        } catch (_: Exception) {
            return false
        }
        return codecs.any { info ->
            try {
                info.isEncoder &&
                    !isSoftwareCodec(info) &&
                    info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) } &&
                    info.getCapabilitiesForType(mimeType).encoderCapabilities
                        ?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true
            } catch (_: Exception) {
                false
            }
        }
    }

    fun probeDeviceVideoEncoders(): List<DeviceCodecCapability> {
        val targets = listOf(
            MimeTypes.VIDEO_H264 to "H.264 (AVC)",
            MimeTypes.VIDEO_H265 to "H.265 (HEVC)",
            MimeTypes.VIDEO_VP9 to "VP9",
            MimeTypes.VIDEO_AV1 to "AV1"
        )
        val codecs = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        } catch (_: Exception) {
            emptyArray()
        }

        return targets.map { (mime, label) ->
            val matching = codecs.filter { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
            val hw = matching.filterNot { isSoftwareCodec(it) }
            val sw = matching.filter { isSoftwareCodec(it) }
            val cbr = hw.any { info ->
                try {
                    info.getCapabilitiesForType(mime).encoderCapabilities
                        ?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true
                } catch (_: Exception) {
                    false
                }
            }
            DeviceCodecCapability(
                mimeType = mime,
                label = label,
                hasHardware = hw.isNotEmpty(),
                hasSoftware = sw.isNotEmpty(),
                supportsCbr = cbr
            )
        }
    }
}

object UniversalCompressor {

    private val mainHandler = Handler(Looper.getMainLooper())

    private data class VideoProbeResult(
        val durationMs: Long,
        val displayWidth: Int,
        val displayHeight: Int,
        val hasAudio: Boolean
    )

    private data class VideoAttemptConfig(
        val tierLabel: String,
        val videoMime: String,
        val audioMime: String?, // null = passthrough copy; "MUTE" = strip audio
        val targetShortSide: Int,
        val targetFps: Float,
        val useCbrIfSupported: Boolean,
        val allowAllCodecs: Boolean,
        val preferSoftware: Boolean,
        val alignMultiple: Int
    )

    private data class ImageEncodePlan(
        val format: Bitmap.CompressFormat,
        val extension: String,
        val mimeType: String,
        val supportsQuality: Boolean
    )

    suspend fun compressFile(
        context: Context,
        source: FileDetails,
        config: CompressionConfig,
        videoConfig: VideoCompressionConfig = VideoCompressionConfig(),
        audioCodecMode: String = "AAC", // "AAC", "OPUS", "COPY", "MUTE"
        enableAllCodecs: Boolean = false,
        onProgress: (Float, String) -> Unit
    ): ConversionJobResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val outputDir = File(context.cacheDir, "compressed_outputs").apply { mkdirs() }
        val baseName = source.name.substringBeforeLast('.')
        val ext = source.extension.lowercase()

        // 1. Calculate Target Budget (enforce >= 1024B across all modes)
        val targetBytes = when (config.mode) {
            CompressionMode.EXACT_TARGET_SIZE -> config.targetSizeBytes.coerceAtLeast(1024L)
            CompressionMode.PERCENTAGE_REDUCTION -> {
                val reduction = config.percentageReduction.coerceIn(5, 95)
                val fraction = (100 - reduction) / 100.0
                (source.size * fraction).toLong().coerceAtLeast(1024L)
            }
            CompressionMode.QUALITY_PRESET -> {
                when (config.qualityProfile) {
                    QualityProfile.ULTRA_COMPACT -> (source.size * 0.25).toLong().coerceAtLeast(1024L)
                    QualityProfile.BALANCED -> (source.size * 0.50).toLong().coerceAtLeast(1024L)
                    QualityProfile.HIGH_FIDELITY -> (source.size * 0.75).toLong().coerceAtLeast(1024L)
                    QualityProfile.EXTREME_DEFLATE -> (source.size * 0.60).toLong().coerceAtLeast(1024L)
                }
            }
        }

        val effectiveTargetBytes = if (source.category == FileCategory.VIDEO && config.mode == CompressionMode.EXACT_TARGET_SIZE) {
            videoConfig.targetSizeBytes.coerceAtLeast(1024L)
        } else {
            targetBytes
        }

        val targetLabel = FileDetails.formatBytes(effectiveTargetBytes)
        onProgress(0.05f, "Target budget: $targetLabel")

        var tempOutputFile: File? = null
        var finalExt: String
        var finalMime: String

        try {
            when {
                // 1. IMAGE: Determine exact output format/extension before encoding
                source.category == FileCategory.IMAGE -> {
                    val result = compressImageIcStyle(
                        context = context,
                        source = source,
                        outputDir = outputDir,
                        targetBytes = effectiveTargetBytes,
                        onProgress = onProgress
                    )
                    tempOutputFile = result.first
                    finalExt = result.second.extension
                    finalMime = result.second.mimeType
                }

                // 2. VIDEO: Multi-tier Cross-Device Media3 Pipeline
                source.category == FileCategory.VIDEO -> {
                    finalExt = "mp4"
                    finalMime = "video/mp4"
                    tempOutputFile = File.createTempFile("comp_vid_", ".mp4", outputDir)
                    compressVideoWithFallback(
                        context = context,
                        source = source,
                        outputFile = tempOutputFile,
                        targetBytes = effectiveTargetBytes,
                        videoConfig = videoConfig,
                        audioCodecMode = audioCodecMode,
                        enableAllCodecs = enableAllCodecs,
                        onProgress = onProgress
                    )
                }

                // 3. AUDIO: Real Media3 Transcoding to Target Bitrate AAC
                source.category == FileCategory.AUDIO -> {
                    finalExt = "m4a"
                    finalMime = "audio/mp4"
                    tempOutputFile = File.createTempFile("comp_aud_", ".m4a", outputDir)
                    compressAudioWithMedia3(context, source, tempOutputFile, effectiveTargetBytes, onProgress)
                }

                // 4. PDF: Safe File Descriptor, Size-Capped Rendering & Bloat Guard
                source.extension.equals("pdf", ignoreCase = true) -> {
                    finalExt = "pdf"
                    finalMime = "application/pdf"
                    tempOutputFile = File.createTempFile("comp_pdf_", ".pdf", outputDir)
                    compressPdf(context, source, tempOutputFile, effectiveTargetBytes, onProgress)
                }

                // 5. JSON: True Lossless Streaming State-Machine Minifier
                ext == "json" -> {
                    finalExt = "json"
                    finalMime = "application/json"
                    tempOutputFile = File.createTempFile("comp_json_", ".json", outputDir)
                    compressJsonLosslessly(context, source, tempOutputFile, onProgress)
                }

                // 6. Other Text / Data / Code & Generic Binaries: True Lossless Deflate Level-9 ZIP
                else -> {
                    finalExt = "zip"
                    finalMime = "application/zip"
                    tempOutputFile = File.createTempFile("comp_zip_", ".zip", outputDir)
                    compressGenericZip(context, source, tempOutputFile, onProgress)
                }
            }

            val finalFileName = if (finalExt.isNotEmpty()) {
                "${baseName}_compressed.$finalExt"
            } else {
                "${baseName}_compressed"
            }

            if (source.category == FileCategory.IMAGE && finalExt in listOf("jpg", "jpeg", "webp")) {
                if (!config.metadataConfig.stripAllForPrivacy) {
                    MetadataHelper.applyMetadataPreferences(
                        context,
                        source.uri,
                        tempOutputFile!!,
                        config.metadataConfig,
                        source.timestamp
                    )
                    // Pixels were already rotated physically upright; prevent double-rotation by viewers
                    resetExifOrientation(tempOutputFile)
                }
            }

            onProgress(0.95f, "Saving to public Downloads/OmniFile...")
            val savedInfo = DownloadStorageHelper.saveToDownloads(context, tempOutputFile!!, finalFileName, finalMime)

            val duration = System.currentTimeMillis() - startTime
            val metaSummary = if (config.metadataConfig.stripAllForPrivacy) "Metadata stripped" else "Preserved"

            ConversionJobResult(
                isSuccess = true,
                inputName = source.name,
                inputSizeBytes = source.size,
                outputName = savedInfo.fileName,
                outputSizeBytes = savedInfo.sizeBytes,
                outputPath = savedInfo.absolutePath,
                savedRelativePath = savedInfo.relativeFolder,
                durationMs = duration,
                format = "Compressed",
                message = "Compressed to ${FileDetails.formatBytes(savedInfo.sizeBytes)} (Target: $targetLabel)",
                metadataPreservedSummary = metaSummary
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            ConversionJobResult(
                isSuccess = false,
                inputName = source.name,
                inputSizeBytes = source.size,
                outputName = "${baseName}_compressed",
                outputSizeBytes = 0L,
                outputPath = "",
                durationMs = System.currentTimeMillis() - startTime,
                format = "Compression",
                message = "Compression failed: ${e.localizedMessage ?: "Unknown error"}"
            )
        } finally {
            try {
                tempOutputFile?.let { if (it.exists()) it.delete() }
            } catch (_: Exception) {}
        }
    }

    // =========================================================================
    // IMAGE COMPRESSION ENGINE (EXIF-Aware + Matching Extension/MIME)
    // =========================================================================
    private suspend fun compressImageIcStyle(
        context: Context,
        source: FileDetails,
        outputDir: File,
        targetBytes: Long,
        onProgress: (Float, String) -> Unit
    ): Pair<File, ImageEncodePlan> {
        onProgress(0.18f, "Inspecting image dimensions & EXIF...")

        val exifOrientation = readExifOrientation(context, source.uri)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(source.uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: throw IllegalStateException("Cannot open image input stream")

        val origW = bounds.outWidth
        val origH = bounds.outHeight
        if (origW <= 0 || origH <= 0) throw IllegalArgumentException("Invalid or unsupported image")

        var sampleSize = 1
        while ((origW / sampleSize) > 3840 || (origH / sampleSize) > 3840) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val decodedBitmap = context.contentResolver.openInputStream(source.uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOptions)
        } ?: throw IllegalArgumentException("Could not decode image")

        val baseBitmap = applyExifRotation(decodedBitmap, exifOrientation)
        if (baseBitmap !== decodedBitmap) decodedBitmap.recycle()

        try {
            val hasAlpha = baseBitmap.hasAlpha()
            val plan = chooseImageEncodePlan(hasAlpha, source.extension.lowercase(), source.size, targetBytes)

            onProgress(0.35f, "Calculating perceptual resolution tiers (${plan.extension.uppercase()})...")

            var bestBytes = if (plan.supportsQuality) {
                searchQualityBudget(baseBitmap, plan.format, targetBytes, minQ = 55, maxQ = 88)
            } else {
                testCompress(baseBitmap, plan.format, 100)
            }

            if (bestBytes.isEmpty() || bestBytes.size > targetBytes) {
                val scaleTiers = listOf(0.85f, 0.70f, 0.55f, 0.40f, 0.30f, 0.20f, 0.12f)
                for (scale in scaleTiers) {
                    currentCoroutineContext().ensureActive()
                    onProgress(0.5f, "Scaling resolution to ${(scale * 100).toInt()}% for optimal clarity...")
                    val newW = (baseBitmap.width * scale).roundToInt().coerceAtLeast(min(32, baseBitmap.width))
                    val newH = (baseBitmap.height * scale).roundToInt().coerceAtLeast(min(32, baseBitmap.height))

                    val scaled = Bitmap.createScaledBitmap(baseBitmap, newW, newH, true)
                    val result = try {
                        if (plan.supportsQuality) {
                            searchQualityBudget(scaled, plan.format, targetBytes, minQ = 45, maxQ = 85)
                        } else {
                            testCompress(scaled, plan.format, 100)
                        }
                    } finally {
                        if (scaled !== baseBitmap) scaled.recycle()
                    }

                    if (result.isNotEmpty() && (bestBytes.isEmpty() || result.size < bestBytes.size)) {
                        bestBytes = result
                    }
                    if (result.isNotEmpty() && result.size <= targetBytes) {
                        bestBytes = result
                        break
                    }
                }
            }

            // Bounded micro fallback: cap longest side to 320px (never upscale or blow up panoramas)
            var finalPlan = plan
            if (bestBytes.isEmpty() || bestBytes.size > targetBytes) {
                currentCoroutineContext().ensureActive()
                val longest = max(baseBitmap.width, baseBitmap.height)
                val micro = if (longest > 320) {
                    val ratio = 320f / longest.toFloat()
                    val mW = (baseBitmap.width * ratio).roundToInt().coerceAtLeast(1)
                    val mH = (baseBitmap.height * ratio).roundToInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(baseBitmap, mW, mH, true)
                } else {
                    baseBitmap
                }
                try {
                    val fallbackFormat = if (hasAlpha) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            ImageEncodePlan(Bitmap.CompressFormat.WEBP_LOSSY, "webp", "image/webp", true)
                        } else {
                            plan
                        }
                    } else {
                        ImageEncodePlan(Bitmap.CompressFormat.JPEG, "jpg", "image/jpeg", true)
                    }
                    val candidate = if (fallbackFormat.supportsQuality) {
                        testCompress(micro, fallbackFormat.format, 35)
                    } else {
                        testCompress(micro, fallbackFormat.format, 100)
                    }
                    if (bestBytes.isEmpty() || candidate.size < bestBytes.size) {
                        bestBytes = candidate
                        finalPlan = fallbackFormat
                    }
                } finally {
                    if (micro !== baseBitmap) micro.recycle()
                }
            }

            onProgress(0.9f, "Writing ${FileDetails.formatBytes(bestBytes.size.toLong())} ${finalPlan.extension.uppercase()}...")
            val outFile = File.createTempFile("comp_img_", ".${finalPlan.extension}", outputDir)
            FileOutputStream(outFile).use { it.write(bestBytes) }
            return Pair(outFile, finalPlan)
        } finally {
            baseBitmap.recycle()
        }
    }

    private fun chooseImageEncodePlan(
        hasAlpha: Boolean,
        sourceExt: String,
        sourceSize: Long,
        targetBytes: Long
    ): ImageEncodePlan {
        return when {
            hasAlpha -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ImageEncodePlan(Bitmap.CompressFormat.WEBP_LOSSY, "webp", "image/webp", true)
                } else {
                    ImageEncodePlan(Bitmap.CompressFormat.PNG, "png", "image/png", false)
                }
            }
            sourceExt == "webp" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ImageEncodePlan(Bitmap.CompressFormat.WEBP_LOSSY, "webp", "image/webp", true)
                } else {
                    ImageEncodePlan(Bitmap.CompressFormat.JPEG, "jpg", "image/jpeg", true)
                }
            }
            sourceExt == "png" && targetBytes < (sourceSize * 0.6) -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ImageEncodePlan(Bitmap.CompressFormat.WEBP_LOSSY, "webp", "image/webp", true)
                } else {
                    ImageEncodePlan(Bitmap.CompressFormat.JPEG, "jpg", "image/jpeg", true)
                }
            }
            sourceExt == "png" -> ImageEncodePlan(Bitmap.CompressFormat.PNG, "png", "image/png", false)
            else -> ImageEncodePlan(Bitmap.CompressFormat.JPEG, "jpg", "image/jpeg", true)
        }
    }

    private fun readExifOrientation(context: Context, uri: Uri): Int {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    private fun applyExifRotation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.preScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (_: Exception) {
            bitmap
        }
    }

    private fun resetExifOrientation(file: File) {
        try {
            val exif = ExifInterface(file.absolutePath)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            exif.saveAttributes()
        } catch (_: Exception) {}
    }

    private suspend fun searchQualityBudget(
        bmp: Bitmap,
        format: Bitmap.CompressFormat,
        targetBytes: Long,
        minQ: Int,
        maxQ: Int
    ): ByteArray {
        var low = minQ
        var high = maxQ
        var best = ByteArray(0)

        while (low <= high) {
            currentCoroutineContext().ensureActive()
            val mid = (low + high) / 2
            val bytes = testCompress(bmp, format, mid)
            if (bytes.size <= targetBytes) {
                best = bytes
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return best
    }

    private fun testCompress(bmp: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray {
        val stream = ByteArrayOutputStream()
        bmp.compress(format, quality.coerceIn(1, 100), stream)
        return stream.toByteArray()
    }

    // =========================================================================
    // VIDEO COMPRESSION ENGINE (Cross-Device Multi-Tier Media3 Pipeline)
    // =========================================================================
    private suspend fun probeVideo(context: Context, uri: Uri): VideoProbeResult = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        var dur = 10_000L
        var rawW = 1920
        var rawH = 1080
        var rotation = 0
        var hasAudio = false
        var audioChecked = false

        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                retriever.setDataSource(pfd.fileDescriptor)
            } ?: retriever.setDataSource(context, uri)

            dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 10_000L
            rawW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1920
            rawH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080
            rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val audioMeta = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            if (audioMeta != null) {
                hasAudio = audioMeta.equals("yes", ignoreCase = true)
                audioChecked = true
            }
        } catch (_: Exception) {
        } finally {
            retriever.release()
        }

        if (!audioChecked) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                hasAudio = (0 until extractor.trackCount).any {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                }
            } catch (_: Exception) {
                hasAudio = true
            } finally {
                extractor.release()
            }
        }

        val (dispW, dispH) = if (rotation == 90 || rotation == 270) {
            Pair(rawH, rawW)
        } else {
            Pair(rawW, rawH)
        }

        VideoProbeResult(
            durationMs = dur.coerceAtLeast(500L),
            displayWidth = dispW.coerceAtLeast(64),
            displayHeight = dispH.coerceAtLeast(64),
            hasAudio = hasAudio
        )
    }

    private fun computeAlignedDimensions(
        displayW: Int,
        displayH: Int,
        requestedShortSide: Int,
        alignMultiple: Int
    ): Pair<Int, Int>? {
        val currentShort = min(displayW, displayH)
        if (requestedShortSide <= 0 || currentShort <= requestedShortSide) {
            return null
        }
        val scale = requestedShortSide.toFloat() / currentShort.toFloat()
        val rawOutW = (displayW * scale).roundToInt()
        val rawOutH = (displayH * scale).roundToInt()

        val alignedW = max(64, (rawOutW / alignMultiple) * alignMultiple)
        val alignedH = max(64, (rawOutH / alignMultiple) * alignMultiple)
        return Pair(alignedW, alignedH)
    }

    @OptIn(UnstableApi::class)
    private suspend fun compressVideoWithFallback(
        context: Context,
        source: FileDetails,
        outputFile: File,
        targetBytes: Long,
        videoConfig: VideoCompressionConfig,
        audioCodecMode: String,
        enableAllCodecs: Boolean,
        onProgress: (Float, String) -> Unit
    ) {
        onProgress(0.12f, "Inspecting device codecs & video streams...")
        val probe = probeVideo(context, source.uri)

        val durationSec = (probe.durationMs / 1000.0).coerceAtLeast(1.0)
        val totalBps = ((targetBytes * 8.0) / durationSec).toInt().coerceAtLeast(32_000)

        val requestedMute = !probe.hasAudio || videoConfig.removeAudio || audioCodecMode == "MUTE" || totalBps < 36_000
        val requestedAudioBps = videoConfig.audioBitrateKbps * 1000
        // Cap audio to <= 25% of total budget on tight targets so audio doesn't blow the budget
        val effectiveAudioBps = if (requestedMute) 0 else min(requestedAudioBps, (totalBps * 0.25).toInt().coerceIn(24_000, 192_000))
        val calculatedVideoBitrate = (totalBps - effectiveAudioBps).coerceIn(80_000, 18_000_000)

        val requestedShortSide = videoConfig.resolution.heightPx
        val smartShortSide = when {
            requestedShortSide > 0 -> requestedShortSide
            calculatedVideoBitrate >= 3_000_000 -> 1080
            calculatedVideoBitrate >= 1_400_000 -> 720
            calculatedVideoBitrate >= 750_000 -> 540
            else -> 480
        }

        val primaryVideoMime = when (videoConfig.codec) {
            VideoCodec.H265 -> MimeTypes.VIDEO_H265
            VideoCodec.H264 -> MimeTypes.VIDEO_H264
        }

        val primaryAudioMime = when {
            requestedMute -> "MUTE"
            audioCodecMode == "COPY" -> null
            audioCodecMode == "OPUS" -> MimeTypes.AUDIO_OPUS
            else -> MimeTypes.AUDIO_AAC
        }

        val attempts = mutableListOf(
            // Tier 1: Requested Studio Settings (Hardware prioritized first even when enableAllCodecs=true)
            VideoAttemptConfig(
                tierLabel = "Hardware Studio",
                videoMime = primaryVideoMime,
                audioMime = primaryAudioMime,
                targetShortSide = smartShortSide,
                targetFps = videoConfig.targetFps,
                useCbrIfSupported = true,
                allowAllCodecs = enableAllCodecs,
                preferSoftware = false,
                alignMultiple = 4
            )
        )

        // Intermediate Audio Rescue Tier: If OPUS/COPY failed in MP4, transcode audio to AAC while KEEPING H.265/Res/FPS
        if (primaryAudioMime != "MUTE" && primaryAudioMime != MimeTypes.AUDIO_AAC) {
            attempts.add(
                VideoAttemptConfig(
                    tierLabel = "Hardware Studio (AAC Audio)",
                    videoMime = primaryVideoMime,
                    audioMime = MimeTypes.AUDIO_AAC,
                    targetShortSide = smartShortSide,
                    targetFps = videoConfig.targetFps,
                    useCbrIfSupported = true,
                    allowAllCodecs = enableAllCodecs,
                    preferSoftware = false,
                    alignMultiple = 4
                )
            )
        }

        // Tier 2: Safe Hardware Compatibility Mode (VBR, H.264, AAC, 16-aligned, no FPS drop)
        attempts.add(
            VideoAttemptConfig(
                tierLabel = "Compatible Hardware Mode",
                videoMime = MimeTypes.VIDEO_H264,
                audioMime = if (requestedMute) "MUTE" else MimeTypes.AUDIO_AAC,
                targetShortSide = min(smartShortSide, 720),
                targetFps = 0f,
                useCbrIfSupported = false,
                allowAllCodecs = false,
                preferSoftware = false,
                alignMultiple = 16
            )
        )

        // Tier 3: Universal Software Encoder Fallback (c2.android / OMX.google)
        attempts.add(
            VideoAttemptConfig(
                tierLabel = "Universal Software Encoder",
                videoMime = MimeTypes.VIDEO_H264,
                audioMime = if (requestedMute) "MUTE" else MimeTypes.AUDIO_AAC,
                targetShortSide = min(smartShortSide, 720),
                targetFps = 0f,
                useCbrIfSupported = false,
                allowAllCodecs = true,
                preferSoftware = true,
                alignMultiple = 16
            )
        )

        var lastError: Exception? = null
        for ((index, attempt) in attempts.withIndex()) {
            currentCoroutineContext().ensureActive()
            try {
                if (outputFile.exists()) outputFile.delete()
                if (index > 0) {
                    onProgress(0.20f, "Switching to ${attempt.tierLabel}...")
                }
                runTransformerAttempt(
                    context = context,
                    sourceUri = source.uri,
                    probe = probe,
                    outputFile = outputFile,
                    videoBitrateBps = calculatedVideoBitrate,
                    audioBitrateBps = effectiveAudioBps,
                    audioVolume = videoConfig.audioVolume,
                    attempt = attempt,
                    onProgress = onProgress
                )
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }

        throw lastError ?: IllegalStateException("Video encoding failed across all compatibility tiers")
    }

    @OptIn(UnstableApi::class)
    private suspend fun runTransformerAttempt(
        context: Context,
        sourceUri: Uri,
        probe: VideoProbeResult,
        outputFile: File,
        videoBitrateBps: Int,
        audioBitrateBps: Int,
        audioVolume: Float,
        attempt: VideoAttemptConfig,
        onProgress: (Float, String) -> Unit
    ) {
        val scaledDims = computeAlignedDimensions(
            displayW = probe.displayWidth,
            displayH = probe.displayHeight,
            requestedShortSide = attempt.targetShortSide,
            alignMultiple = attempt.alignMultiple
        )

        val resLabel = scaledDims?.let { "${min(it.first, it.second)}p" } ?: "${min(probe.displayWidth, probe.displayHeight)}p"
        val fpsLabel = if (attempt.targetFps > 0f) "${attempt.targetFps.toInt()}fps" else "Orig FPS"
        onProgress(0.25f, "${attempt.tierLabel}: $resLabel • $fpsLabel • ${videoBitrateBps / 1000} kbps...")

        val supportsCbr = attempt.useCbrIfSupported &&
            !attempt.preferSoftware &&
            DeviceCodecInspector.isCbrSupportedForHardware(attempt.videoMime)

        val isMute = attempt.audioMime == "MUTE" || !probe.hasAudio
        val isAudioCopy = attempt.audioMime == null && abs(audioVolume - 1.0f) <= 0.04f

        withContext(Dispatchers.Main) {
            val mediaItem = MediaItem.fromUri(sourceUri)

            val videoEffects = mutableListOf<Effect>()
            if (scaledDims != null) {
                videoEffects.add(
                    Presentation.createForWidthAndHeight(
                        scaledDims.first,
                        scaledDims.second,
                        Presentation.LAYOUT_SCALE_TO_FIT
                    )
                )
            }
            if (attempt.targetFps > 0f) {
                videoEffects.add(FrameDropEffect.createDefaultFrameDropEffect(attempt.targetFps))
            }

            val audioProcessors = mutableListOf<AudioProcessor>()
            if (!isMute && abs(audioVolume - 1.0f) > 0.04f) {
                val mixer = ChannelMixingAudioProcessor()
                for (ch in 1..8) {
                    val matrix = ChannelMixingMatrix.createForConstantPower(ch, ch).scaleBy(audioVolume)
                    mixer.putChannelMixingMatrix(matrix)
                }
                audioProcessors.add(mixer)
            }

            val editedItem = EditedMediaItem.Builder(mediaItem)
                .setRemoveAudio(isMute)
                .setEffects(Effects(audioProcessors, videoEffects))
                .build()

            val videoSettingsBuilder = VideoEncoderSettings.Builder()
                .setBitrate(videoBitrateBps)
            if (supportsCbr) {
                videoSettingsBuilder.setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }

            val encoderFactoryBuilder = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(videoSettingsBuilder.build())
                .setEnableFallback(true)

            if (attempt.allowAllCodecs || attempt.preferSoftware) {
                encoderFactoryBuilder.setVideoEncoderSelector(
                    EncoderSelector { mime ->
                        val all = EncoderUtil.getSupportedEncoders(mime)
                        val sw = all.filter { DeviceCodecInspector.isSoftwareCodec(it) }
                        val hw = all.filterNot { DeviceCodecInspector.isSoftwareCodec(it) }
                        if (attempt.preferSoftware) {
                            ImmutableList.copyOf(sw + hw)
                        } else {
                            ImmutableList.copyOf(hw + sw)
                        }
                    }
                )
            }

            if (!isMute && !isAudioCopy && audioBitrateBps > 0) {
                encoderFactoryBuilder.setRequestedAudioEncoderSettings(
                    AudioEncoderSettings.Builder()
                        .setBitrate(audioBitrateBps)
                        .build()
                )
            }

            val transformerBuilder = Transformer.Builder(context)
                .setEncoderFactory(encoderFactoryBuilder.build())
                .setVideoMimeType(attempt.videoMime)

            if (!isMute && !isAudioCopy && attempt.audioMime != null) {
                transformerBuilder.setAudioMimeType(attempt.audioMime)
            }

            var transformer: Transformer? = null
            val progressJob = launch {
                val progressHolder = ProgressHolder()
                while (isActive) {
                    val t = transformer
                    if (t != null) {
                        val state = t.getProgress(progressHolder)
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                            val pct = 0.25f + (progressHolder.progress / 100f) * 0.65f
                            val msg = "${attempt.tierLabel} (${progressHolder.progress}%)..."
                            withContext(Dispatchers.IO) {
                                onProgress(pct, msg)
                            }
                        }
                    }
                    delay(250)
                }
            }

            try {
                suspendCancellableCoroutine<Unit> { continuation ->
                    transformer = transformerBuilder
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (continuation.isActive) continuation.resume(Unit)
                            }

                            override fun onError(
                                composition: Composition,
                                exportResult: ExportResult,
                                exportException: ExportException
                            ) {
                                if (continuation.isActive) continuation.resumeWithException(exportException)
                            }
                        })
                        .build()

                    transformer!!.start(editedItem, outputFile.absolutePath)

                    continuation.invokeOnCancellation {
                        if (Looper.myLooper() == Looper.getMainLooper()) {
                            transformer?.cancel()
                        } else {
                            mainHandler.post { transformer?.cancel() }
                        }
                    }
                }
            } finally {
                progressJob.cancel()
            }
        }
    }

    // =========================================================================
    // AUDIO COMPRESSION (Real Media3 AAC Transcoding to Target Bitrate)
    // =========================================================================
    @OptIn(UnstableApi::class)
    private suspend fun compressAudioWithMedia3(
        context: Context,
        source: FileDetails,
        outputFile: File,
        targetBytes: Long,
        onProgress: (Float, String) -> Unit
    ) {
        onProgress(0.15f, "Calculating audio bitrate target...")

        val durationMs = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            var dur = 10_000L
            try {
                context.contentResolver.openFileDescriptor(source.uri, "r")?.use { pfd ->
                    retriever.setDataSource(pfd.fileDescriptor)
                } ?: retriever.setDataSource(context, source.uri)
                dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 10_000L
            } catch (_: Exception) {
            } finally {
                retriever.release()
            }
            dur.coerceAtLeast(1000L)
        }

        val durationSec = durationMs / 1000.0
        val targetBitrate = ((targetBytes * 8.0) / durationSec).toInt().coerceIn(24_000, 192_000)

        onProgress(0.25f, "Transcoding audio to ${targetBitrate / 1000} kbps AAC...")

        withContext(Dispatchers.Main) {
            val mediaItem = MediaItem.fromUri(source.uri)
            val editedItem = EditedMediaItem.Builder(mediaItem)
                .setRemoveVideo(true)
                .build()

            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .setRequestedAudioEncoderSettings(
                    AudioEncoderSettings.Builder()
                        .setBitrate(targetBitrate)
                        .build()
                )
                .setEnableFallback(true)
                .build()

            var transformer: Transformer? = null
            val progressJob = launch {
                val progressHolder = ProgressHolder()
                while (isActive) {
                    val t = transformer
                    if (t != null) {
                        val state = t.getProgress(progressHolder)
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                            val pct = 0.25f + (progressHolder.progress / 100f) * 0.65f
                            val msg = "Transcoding audio (${progressHolder.progress}%)..."
                            withContext(Dispatchers.IO) {
                                onProgress(pct, msg)
                            }
                        }
                    }
                    delay(200)
                }
            }

            try {
                suspendCancellableCoroutine<Unit> { continuation ->
                    transformer = Transformer.Builder(context)
                        .setEncoderFactory(encoderFactory)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (continuation.isActive) continuation.resume(Unit)
                            }

                            override fun onError(
                                composition: Composition,
                                exportResult: ExportResult,
                                exportException: ExportException
                            ) {
                                if (continuation.isActive) continuation.resumeWithException(exportException)
                            }
                        })
                        .build()

                    transformer!!.start(editedItem, outputFile.absolutePath)

                    continuation.invokeOnCancellation {
                        if (Looper.myLooper() == Looper.getMainLooper()) {
                            transformer?.cancel()
                        } else {
                            mainHandler.post { transformer?.cancel() }
                        }
                    }
                }
            } finally {
                progressJob.cancel()
            }
        }
    }

    // =========================================================================
    // PDF COMPRESSION (Safe FD Lifecycle, Bounded Dimensions & Bloat Guard)
    // =========================================================================
    private suspend fun compressPdf(
        context: Context,
        source: FileDetails,
        outputFile: File,
        targetBytes: Long,
        onProgress: (Float, String) -> Unit
    ) {
        onProgress(0.2f, "Inspecting PDF pages...")

        val pfd = context.contentResolver.openFileDescriptor(source.uri, "r")
            ?: throw IllegalArgumentException("Cannot open PDF")

        pfd.use { descriptor ->
            val renderer = PdfRenderer(descriptor)
            val pdfDoc = PdfDocument()
            try {
                val pageCount = renderer.pageCount
                if (pageCount == 0) {
                    throw IllegalArgumentException("PDF contains 0 pages")
                }

                val bytesPerPage = (targetBytes - 2048).coerceAtLeast(1024) / pageCount
                val (baseScale, quality) = when {
                    bytesPerPage > 80 * 1024 -> Pair(1.0f, 75)
                    bytesPerPage > 40 * 1024 -> Pair(0.8f, 55)
                    bytesPerPage > 15 * 1024 -> Pair(0.6f, 35)
                    else -> Pair(0.4f, 20)
                }

                for (i in 0 until pageCount) {
                    currentCoroutineContext().ensureActive()
                    onProgress(0.25f + (0.6f * (i.toFloat() / pageCount)), "Compressing page ${i + 1} of $pageCount...")
                    val page = renderer.openPage(i)
                    val origPageW = page.width
                    val origPageH = page.height

                    // Cap render dimensions to max 1600px on longest side to prevent OOM on CAD/poster PDFs
                    val maxDim = max(origPageW, origPageH).coerceAtLeast(1)
                    val safeScale = min(baseScale, 1600f / maxDim.toFloat())
                    val w = (origPageW * safeScale).toInt().coerceAtLeast(100)
                    val h = (origPageH * safeScale).toInt().coerceAtLeast(100)

                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
                    val pageJpeg: ByteArray
                    try {
                        val canvas = Canvas(bmp)
                        canvas.drawColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        pageJpeg = testCompress(bmp, Bitmap.CompressFormat.JPEG, quality)
                    } finally {
                        page.close()
                        bmp.recycle()
                    }

                    val compressedPageBmp = BitmapFactory.decodeByteArray(pageJpeg, 0, pageJpeg.size)
                    val pageInfo = PdfDocument.PageInfo.Builder(origPageW, origPageH, i + 1).create()
                    val docPage = pdfDoc.startPage(pageInfo)
                    if (compressedPageBmp != null) {
                        docPage.canvas.drawBitmap(
                            compressedPageBmp,
                            null,
                            android.graphics.Rect(0, 0, origPageW, origPageH),
                            Paint(Paint.FILTER_BITMAP_FLAG)
                        )
                        compressedPageBmp.recycle()
                    }
                    pdfDoc.finishPage(docPage)
                }

                onProgress(0.9f, "Finalizing compressed PDF...")
                FileOutputStream(outputFile).use { pdfDoc.writeTo(it) }
            } finally {
                pdfDoc.close()
                renderer.close()
            }
        }

        // Bloat guard: if rasterization produced a larger PDF than original, return original untouched
        if (source.size > 0 && outputFile.length() > source.size) {
            onProgress(0.92f, "Original PDF is already optimal; preserving original...")
            context.contentResolver.openInputStream(source.uri)?.use { input ->
                FileOutputStream(outputFile).use { out ->
                    input.copyTo(out, 32768)
                }
            }
        }
    }

    // =========================================================================
    // JSON (Streaming State-Machine Lossless Minification)
    // =========================================================================
    private suspend fun compressJsonLosslessly(
        context: Context,
        source: FileDetails,
        outputFile: File,
        onProgress: (Float, String) -> Unit
    ) {
        onProgress(0.3f, "Losslessly minifying JSON...")
        val input = context.contentResolver.openInputStream(source.uri)
            ?: throw IllegalStateException("Cannot open input stream for ${source.name}")

        BufferedReader(InputStreamReader(input, Charsets.UTF_8), 32768).use { reader ->
            BufferedWriter(OutputStreamWriter(FileOutputStream(outputFile), Charsets.UTF_8), 32768).use { writer ->
                var inString = false
                var escaped = false
                var chInt: Int
                var count = 0

                while (reader.read().also { chInt = it } != -1) {
                    if (++count % 8192 == 0) {
                        currentCoroutineContext().ensureActive()
                    }
                    val c = chInt.toChar()
                    if (inString) {
                        writer.write(chInt)
                        if (escaped) {
                            escaped = false
                        } else if (c == '\\') {
                            escaped = true
                        } else if (c == '"') {
                            inString = false
                        }
                    } else {
                        if (!c.isWhitespace()) {
                            writer.write(chInt)
                            if (c == '"') {
                                inString = true
                            }
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // LOSSLESS LEVEL-9 DEFLATE ZIP (Text, Code & Generic Binaries)
    // =========================================================================
    private suspend fun compressGenericZip(
        context: Context,
        source: FileDetails,
        outputFile: File,
        onProgress: (Float, String) -> Unit
    ) {
        onProgress(0.3f, "Packaging losslessly into Level-9 ZIP...")
        val input = context.contentResolver.openInputStream(source.uri)
            ?: throw IllegalStateException("Cannot open input stream for ${source.name}")

        input.use { stream ->
            ZipOutputStream(BufferedOutputStream(FileOutputStream(outputFile), 32768)).use { zos ->
                zos.setLevel(Deflater.BEST_COMPRESSION)
                val entry = ZipEntry(source.name)
                zos.putNextEntry(entry)
                val buf = ByteArray(32768)
                var read: Int
                while (stream.read(buf).also { read = it } != -1) {
                    currentCoroutineContext().ensureActive()
                    zos.write(buf, 0, read)
                }
                zos.closeEntry()
            }
        }
    }
}
