package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.Charset
import java.util.Locale
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

    private val cachedCodecInfos: Array<MediaCodecInfo> by lazy {
        try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        } catch (_: Exception) {
            emptyArray()
        }
    }

    fun isSoftwareCodec(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return info.isSoftwareOnly
        }
        val name = info.name.lowercase(Locale.US)
        return name.startsWith("omx.google.") ||
            name.startsWith("c2.android.") ||
            name.startsWith("c2.google.") ||
            name.startsWith("omx.ffmpeg.") ||
            name.contains(".sw.") ||
            name.endsWith(".sw")
    }

    fun isCbrSupportedForHardware(mimeType: String): Boolean {
        val primaryHardwareEncoder = cachedCodecInfos.firstOrNull { info ->
            info.isEncoder &&
                !isSoftwareCodec(info) &&
                info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
        } ?: return false

        return try {
            val matchedType = primaryHardwareEncoder.supportedTypes
                .first { it.equals(mimeType, ignoreCase = true) }
            primaryHardwareEncoder.getCapabilitiesForType(matchedType)
                ?.encoderCapabilities
                ?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true
        } catch (_: Exception) {
            false
        }
    }

    @OptIn(UnstableApi::class)
    fun hasEncoderForMime(mimeType: String, allowSoftware: Boolean): Boolean {
        return try {
            val encoders = EncoderUtil.getSupportedEncoders(mimeType)
            if (allowSoftware) encoders.isNotEmpty() else encoders.any { !isSoftwareCodec(it) }
        } catch (_: Exception) {
            cachedCodecInfos.any { info ->
                info.isEncoder &&
                    (allowSoftware || !isSoftwareCodec(info)) &&
                    info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
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

        return targets.map { (mime, label) ->
            val matching = cachedCodecInfos.filter { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
            val hw = matching.filterNot { isSoftwareCodec(it) }
            val sw = matching.filter { isSoftwareCodec(it) }
            val cbr = hw.firstOrNull()?.let { info ->
                try {
                    val matchedType = info.supportedTypes.first { it.equals(mime, ignoreCase = true) }
                    info.getCapabilitiesForType(matchedType)
                        ?.encoderCapabilities
                        ?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true
                } catch (_: Exception) {
                    false
                }
            } ?: false

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
        val hasAudio: Boolean,
        val audioChannelCount: Int,
        val sourceTotalBitrateBps: Int,
        val sourceAudioBitrateBps: Int
    )

    private data class AudioProbeResult(
        val durationMs: Long,
        val channelCount: Int,
        val sourceBitrateBps: Int
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

    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var bytesWritten: Long = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            bytesWritten++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            bytesWritten += len
        }
    }

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
        val baseName = source.name.substringBeforeLast('.').ifBlank { source.name.ifBlank { "output" } }
        val ext = source.extension.lowercase(Locale.US)
        val actualSourceSize = resolveSourceSize(context, source)

        val targetBytes = computeTargetBytes(source.category, actualSourceSize, config, videoConfig)
        val targetLabel = FileDetails.formatBytes(targetBytes)
        onProgress(0.05f, "Target budget: $targetLabel")

        var tempOutputFile: File? = null
        var finalExt: String
        var finalMime: String

        try {
            val isAnimatedOrVectorImage = source.category == FileCategory.IMAGE &&
                (ext == "svg" || isAnimatedImage(context, source.uri))

            when {
                isAnimatedOrVectorImage -> {
                    finalExt = "zip"
                    finalMime = "application/zip"
                    tempOutputFile = File.createTempFile("comp_zip_", ".zip", outputDir)
                    compressGenericZip(context, source, actualSourceSize, tempOutputFile, onProgress)
                }

                source.category == FileCategory.IMAGE -> {
                    val willApplyMetadata = !config.metadataConfig.stripAllForPrivacy
                    // Reserve modest space for EXIF tags if metadata preservation is enabled
                    val imageEncodeBudget = if (willApplyMetadata && targetBytes > 16_384L) {
                        targetBytes - 4_096L
                    } else {
                        targetBytes
                    }

                    val (encodedFile, plan, rotationApplied) = compressImageIcStyle(
                        context = context,
                        source = source,
                        sourceSize = actualSourceSize,
                        outputDir = outputDir,
                        targetBytes = imageEncodeBudget,
                        onProgress = onProgress
                    )
                    tempOutputFile = encodedFile
                    finalExt = plan.extension
                    finalMime = plan.mimeType

                    if (willApplyMetadata && finalExt in listOf("jpg", "jpeg", "webp")) {
                        applyExifSafely(
                            context = context,
                            sourceUri = source.uri,
                            outputFile = encodedFile,
                            finalExt = finalExt,
                            rotationApplied = rotationApplied,
                            targetBytes = targetBytes,
                            sourceSize = actualSourceSize,
                            config = config,
                            timestamp = source.timestamp
                        )
                    }
                }

                source.category == FileCategory.VIDEO -> {
                    finalExt = "mp4"
                    finalMime = "video/mp4"
                    tempOutputFile = File.createTempFile("comp_vid_", ".mp4", outputDir)
                    compressVideoWithFallback(
                        context = context,
                        source = source,
                        sourceSize = actualSourceSize,
                        outputFile = tempOutputFile,
                        targetBytes = targetBytes,
                        videoConfig = videoConfig,
                        audioCodecMode = audioCodecMode,
                        enableAllCodecs = enableAllCodecs,
                        onProgress = onProgress
                    )
                }

                source.category == FileCategory.AUDIO -> {
                    finalExt = "m4a"
                    finalMime = "audio/mp4"
                    tempOutputFile = File.createTempFile("comp_aud_", ".m4a", outputDir)
                    compressAudioWithMedia3(
                        context = context,
                        source = source,
                        sourceSize = actualSourceSize,
                        outputFile = tempOutputFile,
                        targetBytes = targetBytes,
                        onProgress = onProgress
                    )
                }

                ext == "pdf" -> {
                    finalExt = "pdf"
                    finalMime = "application/pdf"
                    tempOutputFile = File.createTempFile("comp_pdf_", ".pdf", outputDir)
                    compressPdf(
                        context = context,
                        source = source,
                        sourceSize = actualSourceSize,
                        outputFile = tempOutputFile,
                        targetBytes = targetBytes,
                        onProgress = onProgress
                    )
                }

                ext == "json" -> {
                    finalExt = "json"
                    finalMime = "application/json"
                    tempOutputFile = File.createTempFile("comp_json_", ".json", outputDir)
                    val minifiedOk = compressJsonLosslessly(
                        context = context,
                        source = source,
                        sourceSize = actualSourceSize,
                        outputFile = tempOutputFile,
                        onProgress = onProgress
                    )
                    if (!minifiedOk) {
                        tempOutputFile.delete()
                        finalExt = "zip"
                        finalMime = "application/zip"
                        tempOutputFile = File.createTempFile("comp_zip_", ".zip", outputDir)
                        compressGenericZip(context, source, actualSourceSize, tempOutputFile, onProgress)
                    }
                }

                else -> {
                    finalExt = "zip"
                    finalMime = "application/zip"
                    tempOutputFile = File.createTempFile("comp_zip_", ".zip", outputDir)
                    compressGenericZip(context, source, actualSourceSize, tempOutputFile, onProgress)
                }
            }

            val finalTempFile = tempOutputFile
            if (finalTempFile == null || !finalTempFile.exists() || finalTempFile.length() <= 0L) {
                throw IllegalStateException("Compression produced an empty output file")
            }

            val finalFileName = if (finalExt.isNotEmpty()) {
                "${baseName}_compressed.$finalExt"
            } else {
                "${baseName}_compressed"
            }

            onProgress(0.95f, "Saving to public Downloads/OmniFile...")
            val savedInfo = DownloadStorageHelper.saveToDownloads(context, finalTempFile, finalFileName, finalMime)

            val duration = System.currentTimeMillis() - startTime
            val metaSummary = if (config.metadataConfig.stripAllForPrivacy) "Metadata stripped" else "Preserved"

            val statusMessage = if (actualSourceSize > 0L && savedInfo.sizeBytes >= actualSourceSize) {
                "Saved at ${FileDetails.formatBytes(savedInfo.sizeBytes)} (source was already near optimal)"
            } else {
                "Compressed to ${FileDetails.formatBytes(savedInfo.sizeBytes)} (Target: $targetLabel)"
            }

            ConversionJobResult(
                isSuccess = true,
                inputName = source.name,
                inputSizeBytes = actualSourceSize,
                outputName = savedInfo.fileName,
                outputSizeBytes = savedInfo.sizeBytes,
                outputPath = savedInfo.absolutePath,
                savedRelativePath = savedInfo.relativeFolder,
                durationMs = duration,
                format = "Compressed",
                message = statusMessage,
                metadataPreservedSummary = metaSummary
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            e.printStackTrace()
            ConversionJobResult(
                isSuccess = false,
                inputName = source.name,
                inputSizeBytes = actualSourceSize,
                outputName = "${baseName}_compressed",
                outputSizeBytes = 0L,
                outputPath = "",
                durationMs = System.currentTimeMillis() - startTime,
                format = "Compression",
                message = "Compression failed: Out of memory while processing ${source.name}"
            )
        } catch (e: Exception) {
            e.printStackTrace()
            ConversionJobResult(
                isSuccess = false,
                inputName = source.name,
                inputSizeBytes = actualSourceSize,
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

    private fun resolveSourceSize(context: Context, source: FileDetails): Long {
        if (source.size > 0L) return source.size
        return try {
            context.contentResolver.openAssetFileDescriptor(source.uri, "r")?.use { afd ->
                if (afd.length > 0L) afd.length else 0L
            } ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun computeTargetBytes(
        category: FileCategory,
        sourceSize: Long,
        config: CompressionConfig,
        videoConfig: VideoCompressionConfig
    ): Long {
        val rawTarget = when (config.mode) {
            CompressionMode.EXACT_TARGET_SIZE -> {
                if (category == FileCategory.VIDEO && videoConfig.targetSizeBytes > 0L) {
                    videoConfig.targetSizeBytes
                } else {
                    config.targetSizeBytes
                }
            }
            CompressionMode.PERCENTAGE_REDUCTION -> {
                val baseSize = if (sourceSize > 0L) sourceSize else defaultAssumedSize(category)
                val reduction = config.percentageReduction.coerceIn(5, 95)
                val fraction = (100 - reduction) / 100.0
                (baseSize * fraction).toLong()
            }
            CompressionMode.QUALITY_PRESET -> {
                val baseSize = if (sourceSize > 0L) sourceSize else defaultAssumedSize(category)
                val fraction = when (config.qualityProfile) {
                    QualityProfile.EXTREME_DEFLATE -> 0.15
                    QualityProfile.ULTRA_COMPACT -> 0.25
                    QualityProfile.BALANCED -> 0.50
                    QualityProfile.HIGH_FIDELITY -> 0.75
                }
                (baseSize * fraction).toLong()
            }
        }

        val minFloor = if (sourceSize in 1L..1023L) sourceSize else 1024L
        return if (sourceSize > 0L) {
            rawTarget.coerceIn(minFloor, sourceSize)
        } else {
            rawTarget.coerceAtLeast(1024L)
        }
    }

    private fun defaultAssumedSize(category: FileCategory): Long = when (category) {
        FileCategory.VIDEO -> 10L * 1024L * 1024L
        FileCategory.AUDIO -> 3L * 1024L * 1024L
        FileCategory.IMAGE -> 2L * 1024L * 1024L
        else -> 1L * 1024L * 1024L
    }

    // =========================================================================
    // IMAGE COMPRESSION ENGINE
    // =========================================================================
    private data class ImageCompressionResult(
        val file: File,
        val plan: ImageEncodePlan,
        val rotationApplied: Boolean
    )

    private fun isAnimatedImage(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { raw ->
                val buf = ByteArray(16_384)
                val read = raw.read(buf)
                if (read < 12) return false
                val header = String(buf, 0, min(read, 12), Charsets.US_ASCII)
                if (header.startsWith("GIF87a") || header.startsWith("GIF89a")) {
                    // Check for > 1 Graphic Control Extension (0x21 0xF9) or Image Descriptor blocks
                    var gceCount = 0
                    for (i in 0 until read - 1) {
                        if (buf[i] == 0x21.toByte() && buf[i + 1] == 0xF9.toByte()) {
                            gceCount++
                            if (gceCount > 1) return true
                        }
                    }
                    return false
                }
                if (header.startsWith("RIFF") && header.endsWith("WEBP")) {
                    // Look for ANIM chunk in RIFF/WEBP header
                    val ascii = String(buf, 0, read, Charsets.US_ASCII)
                    return ascii.contains("ANIM")
                }
                false
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun compressImageIcStyle(
        context: Context,
        source: FileDetails,
        sourceSize: Long,
        outputDir: File,
        targetBytes: Long,
        onProgress: (Float, String) -> Unit
    ): ImageCompressionResult {
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
        while ((origW / sampleSize) > 3840 || (origH / sampleSize) > 3840 ||
            ((origW.toLong() / sampleSize) * (origH.toLong() / sampleSize) * 4L) > 48L * 1024L * 1024L
        ) {
            sampleSize *= 2
        }

        // Decode & rotate with OOM fallback doubling inSampleSize
        var baseBitmap: Bitmap? = null
        var rotationApplied = false
        var decodeAttempt = 0
        while (baseBitmap == null) {
            currentCoroutineContext().ensureActive()
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            var decoded: Bitmap? = null
            try {
                decoded = context.contentResolver.openInputStream(source.uri)?.use {
                    BitmapFactory.decodeStream(it, null, decodeOptions)
                } ?: throw IllegalArgumentException("Could not decode image")

                val (rotated, didRotate) = applyExifRotation(decoded, exifOrientation)
                baseBitmap = rotated
                rotationApplied = didRotate
            } catch (oom: OutOfMemoryError) {
                decoded?.recycle()
                sampleSize *= 2
                decodeAttempt++
                if (decodeAttempt >= 4 || sampleSize > 64) {
                    throw oom
                }
            } finally {
                if (decoded != null && decoded !== baseBitmap) {
                    decoded.recycle()
                }
            }
        }

        try {
            val hasRealAlpha = hasRealTransparency(baseBitmap)
            val sourceExt = source.extension.lowercase(Locale.US)
            val plan = chooseImageEncodePlan(hasRealAlpha, sourceExt, sourceSize, targetBytes)

            onProgress(0.35f, "Calculating perceptual resolution tiers (${plan.extension.uppercase()})...")

            val streamBuffer = ByteArrayOutputStream(65_536)
            var bestBytes = if (plan.supportsQuality) {
                searchQualityBudget(baseBitmap, plan.format, targetBytes, minQ = 40, maxQ = 88, stream = streamBuffer)
            } else {
                testCompress(baseBitmap, plan.format, 100, streamBuffer)
            }

            if (bestBytes.isEmpty() || bestBytes.size > targetBytes) {
                val scaleTiers = listOf(0.85f, 0.70f, 0.55f, 0.40f, 0.30f, 0.20f, 0.12f)
                for (scale in scaleTiers) {
                    currentCoroutineContext().ensureActive()
                    onProgress(0.50f, "Scaling resolution to ${(scale * 100).toInt()}% for optimal clarity...")
                    val newW = (baseBitmap.width * scale).roundToInt().coerceAtLeast(min(32, baseBitmap.width))
                    val newH = (baseBitmap.height * scale).roundToInt().coerceAtLeast(min(32, baseBitmap.height))

                    val scaled = Bitmap.createScaledBitmap(baseBitmap, newW, newH, true)
                    val result = try {
                        if (plan.supportsQuality) {
                            searchQualityBudget(scaled, plan.format, targetBytes, minQ = 15, maxQ = 85, stream = streamBuffer)
                        } else {
                            testCompress(scaled, plan.format, 100, streamBuffer)
                        }
                    } finally {
                        if (scaled !== baseBitmap) scaled.recycle()
                    }

                    if (result.isNotEmpty() && (bestBytes.isEmpty() || result.size < bestBytes.size)) {
                        bestBytes = result
                    }
                    if (bestBytes.isNotEmpty() && bestBytes.size <= targetBytes) {
                        break
                    }
                }
            }

            // Micro fallback for extreme budgets: shrink down further if still over budget
            var finalPlan = plan
            if (bestBytes.isEmpty() || bestBytes.size > targetBytes) {
                currentCoroutineContext().ensureActive()
                val fallbackFormat = chooseLossyFallbackPlan(hasRealAlpha, plan)
                val maxDims = listOf(240, 128, 64)
                for (capSide in maxDims) {
                    currentCoroutineContext().ensureActive()
                    val longest = max(baseBitmap.width, baseBitmap.height)
                    val micro = if (longest > capSide) {
                        val ratio = capSide.toFloat() / longest.toFloat()
                        val mW = (baseBitmap.width * ratio).roundToInt().coerceAtLeast(1)
                        val mH = (baseBitmap.height * ratio).roundToInt().coerceAtLeast(1)
                        Bitmap.createScaledBitmap(baseBitmap, mW, mH, true)
                    } else {
                        baseBitmap
                    }
                    val candidate = try {
                        if (fallbackFormat.supportsQuality) {
                            searchQualityBudget(micro, fallbackFormat.format, targetBytes, minQ = 5, maxQ = 45, stream = streamBuffer)
                        } else {
                            testCompress(micro, fallbackFormat.format, 100, streamBuffer)
                        }
                    } finally {
                        if (micro !== baseBitmap) micro.recycle()
                    }

                    if (candidate.isNotEmpty() && (bestBytes.isEmpty() || candidate.size < bestBytes.size)) {
                        bestBytes = candidate
                        finalPlan = fallbackFormat
                    }
                    if (bestBytes.isNotEmpty() && bestBytes.size <= targetBytes) {
                        break
                    }
                }
            }

            if (bestBytes.isEmpty()) {
                throw IllegalStateException("Image encoder failed to produce output bytes")
            }

            // Bloat guard: if source image was already smaller than our encoded output and targetBytes allows it
            val outFile = File.createTempFile("comp_img_", ".${finalPlan.extension}", outputDir)
            try {
                if (sourceSize > 0L && bestBytes.size >= sourceSize &&
                    (finalPlan.extension == sourceExt || (finalPlan.extension == "jpg" && sourceExt == "jpeg")) &&
                    exifOrientation == ExifInterface.ORIENTATION_NORMAL
                ) {
                    context.contentResolver.openInputStream(source.uri)?.use { input ->
                        FileOutputStream(outFile).use { output -> input.copyTo(output, 32_768) }
                    } ?: FileOutputStream(outFile).use { it.write(bestBytes) }
                } else {
                    FileOutputStream(outFile).use { it.write(bestBytes) }
                }
            } catch (t: Throwable) {
                if (outFile.exists()) outFile.delete()
                throw t
            }

            onProgress(0.90f, "Writing ${FileDetails.formatBytes(outFile.length())} ${finalPlan.extension.uppercase(Locale.US)}...")
            return ImageCompressionResult(outFile, finalPlan, rotationApplied)
        } finally {
            baseBitmap.recycle()
        }
    }

    private fun hasRealTransparency(bmp: Bitmap): Boolean {
        if (!bmp.hasAlpha()) return false
        val w = bmp.width
        val h = bmp.height
        val stepX = max(1, w / 128)
        val stepY = max(1, h / 128)
        val row = IntArray(w)
        var y = 0
        while (y < h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) {
                if ((row[x] ushr 24) < 250) return true
                x += stepX
            }
            y += stepY
        }
        return false
    }

    private fun chooseImageEncodePlan(
        hasRealAlpha: Boolean,
        sourceExt: String,
        sourceSize: Long,
        targetBytes: Long
    ): ImageEncodePlan {
        return when {
            hasRealAlpha -> lossyWebpOrPngPlan()
            sourceExt == "webp" -> lossyWebpOrJpegPlan()
            sourceExt == "png" && (sourceSize <= 0L || targetBytes < (sourceSize * 0.7)) -> lossyWebpOrJpegPlan()
            sourceExt == "png" -> ImageEncodePlan(Bitmap.CompressFormat.PNG, "png", "image/png", false)
            else -> ImageEncodePlan(Bitmap.CompressFormat.JPEG, "jpg", "image/jpeg", true)
        }
    }

    private fun chooseLossyFallbackPlan(hasRealAlpha: Boolean, current: ImageEncodePlan): ImageEncodePlan {
        return if (hasRealAlpha) {
            lossyWebpOrPngPlan().takeIf { it.supportsQuality } ?: current
        } else {
            ImageEncodePlan(Bitmap.CompressFormat.JPEG, "jpg", "image/jpeg", true)
        }
    }

    @Suppress("DEPRECATION")
    private fun lossyWebpOrPngPlan(): ImageEncodePlan {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ImageEncodePlan(Bitmap.CompressFormat.WEBP_LOSSY, "webp", "image/webp", true)
        } else {
            ImageEncodePlan(Bitmap.CompressFormat.WEBP, "webp", "image/webp", true)
        }
    }

    @Suppress("DEPRECATION")
    private fun lossyWebpOrJpegPlan(): ImageEncodePlan {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ImageEncodePlan(Bitmap.CompressFormat.WEBP_LOSSY, "webp", "image/webp", true)
        } else {
            ImageEncodePlan(Bitmap.CompressFormat.WEBP, "webp", "image/webp", true)
        }
    }

    private fun readExifOrientation(context: Context, uri: Uri): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                } ?: ExifInterface.ORIENTATION_NORMAL
            } else {
                val path = uri.path ?: return ExifInterface.ORIENTATION_NORMAL
                if (uri.scheme == "file") {
                    ExifInterface(path).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                } else {
                    ExifInterface.ORIENTATION_NORMAL
                }
            }
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    private fun applyExifRotation(bitmap: Bitmap, orientation: Int): Pair<Bitmap, Boolean> {
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
            else -> return Pair(bitmap, false)
        }
        return try {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            Pair(rotated, true)
        } catch (_: Exception) {
            Pair(bitmap, false)
        }
    }

    private fun applyExifSafely(
        context: Context,
        sourceUri: Uri,
        outputFile: File,
        finalExt: String,
        rotationApplied: Boolean,
        targetBytes: Long,
        sourceSize: Long,
        config: CompressionConfig,
        timestamp: Long
    ) {
        // Framework ExifInterface write support on WebP is unreliable prior to API 30
        if (finalExt == "webp" && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        val backupBytes = outputFile.readBytes()
        try {
            MetadataHelper.applyMetadataPreferences(
                context,
                sourceUri,
                outputFile,
                config.metadataConfig,
                timestamp
            )
            if (rotationApplied) {
                val exif = ExifInterface(outputFile.absolutePath)
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                exif.saveAttributes()
            }
            // Guard against EXIF injection blowing the budget or bloating above source
            val maxAllowed = if (sourceSize > 0L) min(targetBytes, sourceSize) else targetBytes
            if (outputFile.length() > maxAllowed && backupBytes.size <= maxAllowed) {
                FileOutputStream(outputFile).use { it.write(backupBytes) }
            }
        } catch (_: Exception) {
            // Revert to clean compressed image if metadata writing failed or risked double-rotation
            try {
                FileOutputStream(outputFile).use { it.write(backupBytes) }
            } catch (_: Exception) {}
        }
    }

    private suspend fun searchQualityBudget(
        bmp: Bitmap,
        format: Bitmap.CompressFormat,
        targetBytes: Long,
        minQ: Int,
        maxQ: Int,
        stream: ByteArrayOutputStream
    ): ByteArray {
        var low = minQ
        var high = maxQ
        var bestUnderBudget = ByteArray(0)
        var smallestOverall = ByteArray(0)

        while (low <= high) {
            currentCoroutineContext().ensureActive()
            val mid = (low + high) / 2
            val bytes = testCompress(bmp, format, mid, stream)
            if (bytes.isEmpty()) {
                high = mid - 1
                continue
            }
            if (smallestOverall.isEmpty() || bytes.size < smallestOverall.size) {
                smallestOverall = bytes
            }
            if (bytes.size <= targetBytes) {
                bestUnderBudget = bytes
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return if (bestUnderBudget.isNotEmpty()) bestUnderBudget else smallestOverall
    }

    private fun testCompress(
        bmp: Bitmap,
        format: Bitmap.CompressFormat,
        quality: Int,
        stream: ByteArrayOutputStream = ByteArrayOutputStream()
    ): ByteArray {
        stream.reset()
        val ok = bmp.compress(format, quality.coerceIn(1, 100), stream)
        return if (ok) stream.toByteArray() else ByteArray(0)
    }

    // =========================================================================
    // VIDEO COMPRESSION ENGINE (Cross-Device Multi-Tier Media3 Pipeline)
    // =========================================================================
    private suspend fun probeVideo(context: Context, uri: Uri, sourceSize: Long): VideoProbeResult =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            var durMs = 0L
            var rawW = 0
            var rawH = 0
            var rotation = 0
            var hasAudio = false
            var audioChecked = false
            var totalBitrate = 0

            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    retriever.setDataSource(pfd.fileDescriptor)
                } ?: retriever.setDataSource(context, uri)

                durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                rawW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                rawH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                totalBitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
                val audioMeta = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
                if (audioMeta != null) {
                    hasAudio = audioMeta.equals("yes", ignoreCase = true)
                    audioChecked = true
                }
            } catch (_: Exception) {
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {}
            }

            var audioChannels = 2
            var audioBitrate = 0
            var videoBitrate = 0

            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                var foundAudio = false
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/")) {
                        if (rawW <= 0 && format.containsKey(MediaFormat.KEY_WIDTH)) {
                            rawW = format.getInteger(MediaFormat.KEY_WIDTH)
                        }
                        if (rawH <= 0 && format.containsKey(MediaFormat.KEY_HEIGHT)) {
                            rawH = format.getInteger(MediaFormat.KEY_HEIGHT)
                        }
                        if (rotation == 0 && format.containsKey(MediaFormat.KEY_ROTATION)) {
                            rotation = format.getInteger(MediaFormat.KEY_ROTATION)
                        }
                        if (durMs <= 0L && format.containsKey(MediaFormat.KEY_DURATION)) {
                            durMs = format.getLong(MediaFormat.KEY_DURATION) / 1000L
                        }
                        if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                            videoBitrate = format.getInteger(MediaFormat.KEY_BIT_RATE)
                        }
                    } else if (mime.startsWith("audio/")) {
                        foundAudio = true
                        if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            audioChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        }
                        if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                            audioBitrate = format.getInteger(MediaFormat.KEY_BIT_RATE)
                        }
                        if (durMs <= 0L && format.containsKey(MediaFormat.KEY_DURATION)) {
                            durMs = format.getLong(MediaFormat.KEY_DURATION) / 1000L
                        }
                    }
                }
                if (!audioChecked) {
                    hasAudio = foundAudio
                }
            } catch (_: Exception) {
                if (!audioChecked) hasAudio = true
            } finally {
                try {
                    extractor.release()
                } catch (_: Exception) {}
            }

            // Last-resort duration estimate from sourceSize if both retriever & extractor failed
            if (durMs <= 0L) {
                durMs = if (sourceSize > 0L) {
                    ((sourceSize * 8L) / 2_000_000L * 1000L).coerceIn(1_000L, 3_600_000L)
                } else {
                    10_000L
                }
            }
            if (rawW <= 0 || rawH <= 0) {
                rawW = 1280
                rawH = 720
            }

            val (dispW, dispH) = if (rotation == 90 || rotation == 270) {
                Pair(rawH, rawW)
            } else {
                Pair(rawW, rawH)
            }

            val derivedTotalBps = when {
                totalBitrate > 0 -> totalBitrate
                videoBitrate > 0 -> videoBitrate + audioBitrate
                sourceSize > 0L -> ((sourceSize * 8.0) / (durMs / 1000.0)).toInt().coerceAtLeast(32_000)
                else -> 0
            }

            VideoProbeResult(
                durationMs = durMs.coerceAtLeast(500L),
                displayWidth = dispW.coerceAtLeast(64),
                displayHeight = dispH.coerceAtLeast(64),
                hasAudio = hasAudio,
                audioChannelCount = audioChannels,
                sourceTotalBitrateBps = derivedTotalBps,
                sourceAudioBitrateBps = if (audioBitrate > 0) audioBitrate else 128_000
            )
        }

    private fun computeAlignedDimensions(
        displayW: Int,
        displayH: Int,
        requestedShortSide: Int,
        alignMultiple: Int
    ): Pair<Int, Int>? {
        val currentShort = min(displayW, displayH)
        val needsDownscale = requestedShortSide > 0 && currentShort > requestedShortSide
        val needsAlignment = (displayW % alignMultiple != 0) || (displayH % alignMultiple != 0)

        if (!needsDownscale && !needsAlignment) {
            return null
        }

        val scale = if (needsDownscale) {
            requestedShortSide.toFloat() / currentShort.toFloat()
        } else {
            1.0f
        }

        val rawOutW = (displayW * scale).roundToInt()
        val rawOutH = (displayH * scale).roundToInt()

        val minFloor = min(min(displayW, displayH), 128)
        val minAligned = max(alignMultiple, ((minFloor + alignMultiple - 1) / alignMultiple) * alignMultiple)

        fun roundToMultiple(value: Int): Int {
            val rounded = ((value + alignMultiple / 2) / alignMultiple) * alignMultiple
            return max(minAligned, rounded)
        }

        // Align the short side first, then compute the long side preserving exact aspect ratio
        val alignedW: Int
        val alignedH: Int
        if (rawOutW <= rawOutH) {
            alignedW = roundToMultiple(rawOutW)
            val idealH = (alignedW.toDouble() * displayH.toDouble() / displayW.toDouble()).roundToInt()
            alignedH = roundToMultiple(idealH)
        } else {
            alignedH = roundToMultiple(rawOutH)
            val idealW = (alignedH.toDouble() * displayW.toDouble() / displayH.toDouble()).roundToInt()
            alignedW = roundToMultiple(idealW)
        }

        return if (alignedW == displayW && alignedH == displayH) null else Pair(alignedW, alignedH)
    }

    @OptIn(UnstableApi::class)
    private suspend fun compressVideoWithFallback(
        context: Context,
        source: FileDetails,
        sourceSize: Long,
        outputFile: File,
        targetBytes: Long,
        videoConfig: VideoCompressionConfig,
        audioCodecMode: String,
        enableAllCodecs: Boolean,
        onProgress: (Float, String) -> Unit
    ) {
        onProgress(0.12f, "Inspecting device codecs & video streams...")
        val probe = probeVideo(context, source.uri, sourceSize)

        val durationSec = (probe.durationMs / 1000.0).coerceAtLeast(0.5)
        // Reserve ~5% for MP4 container/moov/mdat overhead
        val mediaBudgetBytes = (targetBytes * 0.95).coerceAtLeast(512.0)
        val totalBps = ((mediaBudgetBytes * 8.0) / durationSec).toInt().coerceAtLeast(24_000)

        val needsVolumeAdjust = abs(videoConfig.audioVolume - 1.0f) > 0.04f
        val needsDownmix = probe.audioChannelCount > 2
        val requestedMute = !probe.hasAudio || videoConfig.removeAudio || audioCodecMode == "MUTE" || totalBps < 40_000

        // Only allow COPY if no audio effects are applied and source audio fits inside budget
        val canCopyAudio = !requestedMute &&
            audioCodecMode == "COPY" &&
            !needsVolumeAdjust &&
            !needsDownmix &&
            probe.sourceAudioBitrateBps <= (totalBps * 0.45).toInt()

        val requestedAudioBps = videoConfig.audioBitrateKbps.coerceIn(16, 320) * 1000
        val effectiveAudioBps = when {
            requestedMute -> 0
            canCopyAudio -> probe.sourceAudioBitrateBps.coerceAtMost((totalBps * 0.45).toInt())
            else -> min(requestedAudioBps, (totalBps * 0.25).toInt().coerceIn(16_000, 192_000))
        }

        val rawVideoBitrate = (totalBps - effectiveAudioBps).coerceIn(24_000, 18_000_000)
        val calculatedVideoBitrate = if (probe.sourceTotalBitrateBps > 0 && sourceSize > 0L && targetBytes < sourceSize) {
            min(rawVideoBitrate, max(24_000, probe.sourceTotalBitrateBps - effectiveAudioBps))
        } else {
            rawVideoBitrate
        }

        val requestedShortSide = videoConfig.resolution.maxShortSidePx ?: 0
        val smartShortSide = when {
            requestedShortSide > 0 -> requestedShortSide
            calculatedVideoBitrate >= 3_000_000 -> 1080
            calculatedVideoBitrate >= 1_400_000 -> 720
            calculatedVideoBitrate >= 750_000 -> 540
            calculatedVideoBitrate >= 300_000 -> 480
            else -> 360
        }

        val primaryVideoMime = when (videoConfig.codec) {
            VideoCodec.H265 -> MimeTypes.VIDEO_H265
            VideoCodec.H264 -> MimeTypes.VIDEO_H264
            VideoCodec.VP9 -> MimeTypes.VIDEO_VP9
            VideoCodec.AV1 -> MimeTypes.VIDEO_AV1
        }

        val primaryAudioMime = when {
            requestedMute -> "MUTE"
            canCopyAudio -> null
            audioCodecMode == "OPUS" -> MimeTypes.AUDIO_OPUS
            else -> MimeTypes.AUDIO_AAC
        }

        val attempts = mutableListOf<VideoAttemptConfig>()

        // Tier 1: Requested Studio Settings (skip if primary codec is completely absent on device)
        if (DeviceCodecInspector.hasEncoderForMime(primaryVideoMime, allowSoftware = enableAllCodecs)) {
            attempts.add(
                VideoAttemptConfig(
                    tierLabel = "Hardware Studio",
                    videoMime = primaryVideoMime,
                    audioMime = primaryAudioMime,
                    targetShortSide = smartShortSide,
                    targetFps = videoConfig.targetFps ?: 0f,
                    useCbrIfSupported = true,
                    allowAllCodecs = enableAllCodecs,
                    preferSoftware = false,
                    alignMultiple = 4
                )
            )
        }

        // Intermediate Audio Rescue Tier: swap OPUS/COPY -> AAC while keeping requested video codec
        if (primaryAudioMime != "MUTE" && primaryAudioMime != MimeTypes.AUDIO_AAC &&
            DeviceCodecInspector.hasEncoderForMime(primaryVideoMime, allowSoftware = enableAllCodecs)
        ) {
            attempts.add(
                VideoAttemptConfig(
                    tierLabel = "Studio (AAC Audio)",
                    videoMime = primaryVideoMime,
                    audioMime = MimeTypes.AUDIO_AAC,
                    targetShortSide = smartShortSide,
                    targetFps = videoConfig.targetFps ?: 0f,
                    useCbrIfSupported = true,
                    allowAllCodecs = enableAllCodecs,
                    preferSoftware = false,
                    alignMultiple = 4
                )
            )
        }

        // Tier 2: Safe Hardware Compatibility Mode (VBR, H.264, AAC, 16-aligned)
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

        // Tier 3: Universal Software Encoder Fallback
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
                if (!outputFile.exists() || outputFile.length() <= 0L) {
                    throw IllegalStateException("Transformer finished with an empty video file")
                }
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

        val resLabel = scaledDims?.let { "${min(it.first, it.second)}p" }
            ?: "${min(probe.displayWidth, probe.displayHeight)}p"
        val fpsLabel = if (attempt.targetFps > 0f && !attempt.targetFps.isNaN()) {
            "${attempt.targetFps.toInt()}fps"
        } else {
            "Orig FPS"
        }
        onProgress(0.25f, "${attempt.tierLabel}: $resLabel • $fpsLabel • ${videoBitrateBps / 1000} kbps...")

        val supportsCbr = attempt.useCbrIfSupported &&
            !attempt.preferSoftware &&
            DeviceCodecInspector.isCbrSupportedForHardware(attempt.videoMime)

        val isMute = attempt.audioMime == "MUTE" || !probe.hasAudio
        val needsVolume = !isMute && abs(audioVolume - 1.0f) > 0.04f
        val needsDownmix = !isMute && probe.audioChannelCount > 2
        val effectiveAudioMime = if (!isMute && (needsVolume || needsDownmix) && attempt.audioMime == null) {
            MimeTypes.AUDIO_AAC
        } else {
            attempt.audioMime
        }
        val isAudioCopy = effectiveAudioMime == null && !needsVolume && !needsDownmix

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
            if (attempt.targetFps > 0f && !attempt.targetFps.isNaN()) {
                videoEffects.add(FrameDropEffect.createDefaultFrameDropEffect(attempt.targetFps))
            }

            val audioProcessors = mutableListOf<AudioProcessor>()
            if (!isMute && (needsVolume || needsDownmix)) {
                val mixer = ChannelMixingAudioProcessor()
                val clampedVolume = audioVolume.coerceIn(0f, 1.5f)
                for (ch in 1..8) {
                    val outCh = if (needsDownmix) min(ch, 2) else ch
                    try {
                        val matrix = ChannelMixingMatrix.createForConstantPower(ch, outCh)
                        mixer.putChannelMixingMatrix(if (needsVolume) matrix.scaleBy(clampedVolume) else matrix)
                        if (outCh == 2 && ch > 2) {
                            val mono = ChannelMixingMatrix.createForConstantPower(ch, 1)
                            mixer.putChannelMixingMatrix(if (needsVolume) mono.scaleBy(clampedVolume) else mono)
                        }
                    } catch (_: UnsupportedOperationException) {
                        // Skip unsupported channel mapping
                    }
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

            if (!isMute && !isAudioCopy && effectiveAudioMime != null) {
                transformerBuilder.setAudioMimeType(effectiveAudioMime)
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
            } catch (e: CancellationException) {
                withContext(NonCancellable + Dispatchers.Main) {
                    transformer?.cancel()
                }
                throw e
            } finally {
                withContext(NonCancellable) {
                    progressJob.cancelAndJoin()
                }
            }
        }
    }

    // =========================================================================
    // AUDIO COMPRESSION (Real Media3 AAC Transcoding to Target Bitrate)
    // =========================================================================
    private suspend fun probeAudio(context: Context, uri: Uri, sourceSize: Long): AudioProbeResult =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            var durMs = 0L
            var bitrate = 0
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    retriever.setDataSource(pfd.fileDescriptor)
                } ?: retriever.setDataSource(context, uri)
                durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
            } catch (_: Exception) {
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {}
            }

            var channels = 2
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) {
                        if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        }
                        if (bitrate <= 0 && format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                            bitrate = format.getInteger(MediaFormat.KEY_BIT_RATE)
                        }
                        if (durMs <= 0L && format.containsKey(MediaFormat.KEY_DURATION)) {
                            durMs = format.getLong(MediaFormat.KEY_DURATION) / 1000L
                        }
                        break
                    }
                }
            } catch (_: Exception) {
            } finally {
                try {
                    extractor.release()
                } catch (_: Exception) {}
            }

            if (durMs <= 0L) {
                durMs = if (sourceSize > 0L) {
                    ((sourceSize * 8L) / 128_000L * 1000L).coerceIn(1_000L, 3_600_000L)
                } else {
                    10_000L
                }
            }
            if (bitrate <= 0 && sourceSize > 0L) {
                bitrate = ((sourceSize * 8.0) / (durMs / 1000.0)).toInt().coerceAtLeast(16_000)
            }

            AudioProbeResult(
                durationMs = durMs.coerceAtLeast(500L),
                channelCount = channels,
                sourceBitrateBps = bitrate
            )
        }

    @OptIn(UnstableApi::class)
    private suspend fun compressAudioWithMedia3(
        context: Context,
        source: FileDetails,
        sourceSize: Long,
        outputFile: File,
        targetBytes: Long,
        onProgress: (Float, String) -> Unit
    ) {
        onProgress(0.15f, "Calculating audio bitrate target...")

        val probe = probeAudio(context, source.uri, sourceSize)
        val durationSec = (probe.durationMs / 1000.0).coerceAtLeast(0.5)
        val mediaBudgetBytes = (targetBytes * 0.95).coerceAtLeast(512.0)
        val rawTargetBitrate = ((mediaBudgetBytes * 8.0) / durationSec).toInt().coerceIn(16_000, 192_000)

        // Never target a bitrate higher than the source audio when trying to shrink it
        val targetBitrate = if (probe.sourceBitrateBps > 0 && sourceSize > 0L && targetBytes < sourceSize) {
            min(rawTargetBitrate, max(16_000, (probe.sourceBitrateBps * 0.90).toInt()))
        } else {
            rawTargetBitrate
        }

        onProgress(0.25f, "Transcoding audio to ${targetBitrate / 1000} kbps AAC...")

        withContext(Dispatchers.Main) {
            val mediaItem = MediaItem.fromUri(source.uri)
            val audioProcessors = mutableListOf<AudioProcessor>()
            if (probe.channelCount > 2) {
                val mixer = ChannelMixingAudioProcessor()
                for (ch in 3..8) {
                    try {
                        mixer.putChannelMixingMatrix(ChannelMixingMatrix.createForConstantPower(ch, 2))
                        mixer.putChannelMixingMatrix(ChannelMixingMatrix.createForConstantPower(ch, 1))
                    } catch (_: UnsupportedOperationException) {}
                }
                audioProcessors.add(mixer)
            }

            val editedItem = EditedMediaItem.Builder(mediaItem)
                .setRemoveVideo(true)
                .setEffects(Effects(audioProcessors, emptyList()))
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
            } catch (e: CancellationException) {
                withContext(NonCancellable + Dispatchers.Main) {
                    transformer?.cancel()
                }
                throw e
            } finally {
                withContext(NonCancellable) {
                    progressJob.cancelAndJoin()
                }
            }
        }
    }

    // =========================================================================
    // PDF COMPRESSION (Streaming Direct-JPEG PDF 1.4 Writer + ARGB_8888 + Bloat Guard)
    // =========================================================================
    private suspend fun compressPdf(
        context: Context,
        source: FileDetails,
        sourceSize: Long,
        outputFile: File,
        targetBytes: Long,
        onProgress: (Float, String) -> Unit
    ) {
        onProgress(0.20f, "Inspecting PDF pages...")

        val pfd = context.contentResolver.openFileDescriptor(source.uri, "r")
            ?: throw IllegalArgumentException("Cannot open PDF")

        pfd.use { descriptor ->
            val renderer = PdfRenderer(descriptor)
            try {
                val pageCount = renderer.pageCount
                if (pageCount == 0) {
                    throw IllegalArgumentException("PDF contains 0 pages")
                }

                // Reserve ~280 bytes per page for PDF object structure & xref overhead
                val overheadBudget = 256L + (pageCount * 280L)
                val perPageBudget = ((targetBytes - overheadBudget).coerceAtLeast(pageCount * 1024L)) / pageCount

                val (baseScale, baseQuality) = when {
                    perPageBudget > 100 * 1024 -> Pair(1.0f, 80)
                    perPageBudget > 50 * 1024 -> Pair(0.85f, 65)
                    perPageBudget > 25 * 1024 -> Pair(0.70f, 45)
                    else -> Pair(0.50f, 28)
                }

                CountingOutputStream(BufferedOutputStream(FileOutputStream(outputFile), 32_768)).use { out ->
                    fun writeAscii(text: String) {
                        out.write(text.toByteArray(Charsets.US_ASCII))
                    }

                    val objectOffsets = LinkedHashMap<Int, Long>()
                    val pageObjIds = ArrayList<Int>(pageCount)
                    val streamBuffer = ByteArrayOutputStream(65_536)

                    // PDF 1.4 header + binary marker
                    writeAscii("%PDF-1.4\n%")
                    out.write(byteArrayOf(0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))

                    // Object 1: Catalog
                    objectOffsets[1] = out.bytesWritten
                    writeAscii("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")

                    for (i in 0 until pageCount) {
                        currentCoroutineContext().ensureActive()
                        onProgress(0.25f + (0.60f * (i.toFloat() / pageCount)), "Compressing page ${i + 1} of $pageCount...")

                        var page: PdfRenderer.Page? = null
                        var bmp: Bitmap? = null
                        val origPageW: Int
                        val origPageH: Int
                        val bmpW: Int
                        val bmpH: Int
                        val pageJpeg: ByteArray

                        try {
                            page = renderer.openPage(i)
                            origPageW = page.width.coerceAtLeast(1)
                            origPageH = page.height.coerceAtLeast(1)

                            val maxDim = max(origPageW, origPageH)
                            val safeScale = min(baseScale, 1600f / maxDim.toFloat())
                            bmpW = (origPageW * safeScale).roundToInt().coerceAtLeast(100)
                            bmpH = (origPageH * safeScale).roundToInt().coerceAtLeast(100)

                            // PdfRenderer strictly requires ARGB_8888
                            bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                            val canvas = Canvas(bmp)
                            canvas.drawColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                            pageJpeg = searchQualityBudget(
                                bmp = bmp,
                                format = Bitmap.CompressFormat.JPEG,
                                targetBytes = perPageBudget,
                                minQ = 12,
                                maxQ = baseQuality,
                                stream = streamBuffer
                            )
                            if (pageJpeg.isEmpty()) {
                                throw IllegalStateException("Failed to encode PDF page ${i + 1}")
                            }
                        } finally {
                            try {
                                page?.close()
                            } catch (_: Exception) {}
                            bmp?.recycle()
                        }

                        val imgObjId = 3 + i * 3
                        val contentObjId = 4 + i * 3
                        val pageObjId = 5 + i * 3
                        pageObjIds.add(pageObjId)

                        // Image XObject embedding raw DCTDecode JPEG bytes directly (zero re-encoding bloat)
                        objectOffsets[imgObjId] = out.bytesWritten
                        writeAscii(
                            "$imgObjId 0 obj\n<< /Type /XObject /Subtype /Image /Width $bmpW /Height $bmpH " +
                                "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode " +
                                "/Length ${pageJpeg.size} >>\nstream\n"
                        )
                        out.write(pageJpeg)
                        writeAscii("\nendstream\nendobj\n")

                        // Content stream drawing the image stretched to original page dimensions
                        val contentBytes = "q $origPageW 0 0 $origPageH 0 0 cm /Im0 Do Q\n".toByteArray(Charsets.US_ASCII)
                        objectOffsets[contentObjId] = out.bytesWritten
                        writeAscii("$contentObjId 0 obj\n<< /Length ${contentBytes.size} >>\nstream\n")
                        out.write(contentBytes)
                        writeAscii("endstream\nendobj\n")

                        // Page object
                        objectOffsets[pageObjId] = out.bytesWritten
                        writeAscii(
                            "$pageObjId 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $origPageW $origPageH] " +
                                "/Resources << /XObject << /Im0 $imgObjId 0 R >> >> " +
                                "/Contents $contentObjId 0 R >>\nendobj\n"
                        )
                    }

                    // Object 2: Pages node
                    objectOffsets[2] = out.bytesWritten
                    val kids = pageObjIds.joinToString(" ") { "$it 0 R" }
                    writeAscii("2 0 obj\n<< /Type /Pages /Kids [ $kids ] /Count $pageCount >>\nendobj\n")

                    val totalObjects = 2 + pageCount * 3
                    val xrefOffset = out.bytesWritten
                    writeAscii("xref\n0 ${totalObjects + 1}\n")
                    writeAscii("0000000000 65535 f \n")
                    for (objId in 1..totalObjects) {
                        val offset = objectOffsets[objId] ?: 0L
                        writeAscii(String.format(Locale.US, "%010d %05d n \n", offset, 0))
                    }

                    writeAscii(
                        "trailer\n<< /Size ${totalObjects + 1} /Root 1 0 R >>\n" +
                            "startxref\n$xrefOffset\n%%EOF\n"
                    )
                    out.flush()
                }
            } finally {
                try {
                    renderer.close()
                } catch (_: Exception) {}
            }
        }

        // Bloat guard: if rasterization produced a larger PDF than original, preserve original
        if (sourceSize > 0L && outputFile.length() >= sourceSize) {
            onProgress(0.92f, "Original PDF is already optimal; preserving original...")
            context.contentResolver.openInputStream(source.uri)?.use { input ->
                FileOutputStream(outputFile).use { out ->
                    input.copyTo(out, 32_768)
                }
            }
        }
    }

    // =========================================================================
    // JSON (BOM-Aware Streaming Lossless Minification)
    // =========================================================================
    private suspend fun compressJsonLosslessly(
        context: Context,
        source: FileDetails,
        sourceSize: Long,
        outputFile: File,
        onProgress: (Float, String) -> Unit
    ): Boolean {
        onProgress(0.30f, "Losslessly minifying JSON...")
        val rawStream = context.contentResolver.openInputStream(source.uri)
            ?: throw IllegalStateException("Cannot open input stream for ${source.name}")

        val buffered = BufferedInputStream(rawStream, 32_768)
        buffered.mark(4)
        val bom = ByteArray(4)
        val bomRead = buffered.read(bom)
        buffered.reset()

        val charset: Charset = when {
            bomRead >= 3 && bom[0] == 0xEF.toByte() && bom[1] == 0xBB.toByte() && bom[2] == 0xBF.toByte() -> {
                buffered.skip(3)
                Charsets.UTF_8
            }
            bomRead >= 4 && bom[0] == 0x00.toByte() && bom[1] == 0x00.toByte() &&
                bom[2] == 0xFE.toByte() && bom[3] == 0xFF.toByte() -> {
                buffered.skip(4)
                Charsets.UTF_32BE
            }
            bomRead >= 4 && bom[0] == 0xFF.toByte() && bom[1] == 0xFE.toByte() &&
                bom[2] == 0x00.toByte() && bom[3] == 0x00.toByte() -> {
                buffered.skip(4)
                Charsets.UTF_32LE
            }
            bomRead >= 2 && bom[0] == 0xFE.toByte() && bom[1] == 0xFF.toByte() -> {
                buffered.skip(2)
                Charsets.UTF_16BE
            }
            bomRead >= 2 && bom[0] == 0xFF.toByte() && bom[1] == 0xFE.toByte() -> {
                buffered.skip(2)
                Charsets.UTF_16LE
            }
            else -> Charsets.UTF_8
        }

        var abortedForComments = false
        BufferedReader(InputStreamReader(buffered, charset), 32_768).use { reader ->
            BufferedWriter(OutputStreamWriter(FileOutputStream(outputFile), Charsets.UTF_8), 32_768).use { writer ->
                var inString = false
                var escaped = false
                var prevCharOutsideString = '\u0000'
                var chInt: Int
                var count = 0L

                while (reader.read().also { chInt = it } != -1) {
                    if (++count % 8192L == 0L) {
                        currentCoroutineContext().ensureActive()
                        if (sourceSize > 0L) {
                            val pct = (0.30f + 0.55f * (count.toFloat() / sourceSize.toFloat())).coerceAtMost(0.88f)
                            onProgress(pct, "Minifying JSON...")
                        }
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
                            prevCharOutsideString = '"'
                        }
                    } else {
                        // Detect non-standard JSONC/JSON5 comments before stripping newlines
                        if (prevCharOutsideString == '/' && (c == '/' || c == '*')) {
                            abortedForComments = true
                            break
                        }
                        // Only strip RFC 8259 JSON whitespace
                        if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                            writer.write(chInt)
                            if (c == '"') {
                                inString = true
                                prevCharOutsideString = '\u0000'
                            } else {
                                prevCharOutsideString = c
                            }
                        }
                    }
                }
            }
        }

        // If JSON contains comments or minification didn't shrink it, let caller fall back to ZIP
        if (abortedForComments) return false
        if (sourceSize > 0L && outputFile.length() >= sourceSize) return false
        return true
    }

    // =========================================================================
    // LOSSLESS LEVEL-9 DEFLATE ZIP (Text, Code & Generic Binaries)
    // =========================================================================
    private suspend fun compressGenericZip(
        context: Context,
        source: FileDetails,
        sourceSize: Long,
        outputFile: File,
        onProgress: (Float, String) -> Unit
    ) {
        onProgress(0.30f, "Packaging losslessly into Level-9 ZIP...")
        val input = context.contentResolver.openInputStream(source.uri)
            ?: throw IllegalStateException("Cannot open input stream for ${source.name}")

        val safeEntryName = sanitizeZipEntryName(source.name)
        input.use { stream ->
            ZipOutputStream(BufferedOutputStream(FileOutputStream(outputFile), 32_768)).use { zos ->
                zos.setLevel(Deflater.BEST_COMPRESSION)
                zos.putNextEntry(ZipEntry(safeEntryName))
                val buf = ByteArray(32_768)
                var read: Int
                var totalRead = 0L
                var lastReported = 0L
                while (stream.read(buf).also { read = it } != -1) {
                    currentCoroutineContext().ensureActive()
                    zos.write(buf, 0, read)
                    totalRead += read
                    if (sourceSize > 0L && totalRead - lastReported >= 262_144L) {
                        lastReported = totalRead
                        val pct = (0.30f + 0.58f * (totalRead.toFloat() / sourceSize.toFloat())).coerceAtMost(0.90f)
                        onProgress(pct, "Compressing (${(pct * 100).toInt()}%)...")
                    }
                }
                zos.closeEntry()
            }
        }
    }

    private fun sanitizeZipEntryName(rawName: String): String {
        val base = rawName.substringAfterLast('/').substringAfterLast('\\').trim()
        return when {
            base.isEmpty() || base == "." || base == ".." -> "compressed_file"
            else -> base
        }
    }
}
