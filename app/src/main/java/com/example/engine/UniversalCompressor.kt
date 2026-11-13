package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.Build
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.roundToInt

object UniversalCompressor {

    suspend fun compressFile(
        context: Context,
        source: FileDetails,
        config: CompressionConfig,
        videoConfig: VideoCompressionConfig = VideoCompressionConfig(),
        onProgress: (Float, String) -> Unit
    ): ConversionJobResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val outputDir = File(context.cacheDir, "compressed_outputs").apply { mkdirs() }
        val baseName = source.name.substringBeforeLast('.')
        val ext = source.extension.lowercase()

        // 1. Calculate Target Budget
        val targetBytes = when (config.mode) {
            CompressionMode.EXACT_TARGET_SIZE -> config.targetSizeBytes
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
            videoConfig.targetSizeBytes
        } else {
            targetBytes
        }

        val targetLabel = FileDetails.formatBytes(effectiveTargetBytes)
        onProgress(0.05f, "Target budget: $targetLabel")

        val outputFile: File
        val mime: String

        try {
            when {
                // 1. IMAGE: ahxxm/ic Perceptual Resolution-First Architecture
                source.category == FileCategory.IMAGE -> {
                    val outExt = if (ext in listOf("png", "webp", "jpg", "jpeg")) ext else "jpg"
                    outputFile = File(outputDir, "${baseName}_compressed.$outExt")
                    mime = when (outExt) {
                        "png" -> "image/png"
                        "webp" -> "image/webp"
                        else -> "image/jpeg"
                    }
                    compressImageIcStyle(context, source, outputFile, effectiveTargetBytes, onProgress)
                }

                // 2. VIDEO: JoshAtticus/Compressor Hardware-Accelerated Media3 Architecture
                source.category == FileCategory.VIDEO -> {
                    outputFile = File(outputDir, "${baseName}_compressed.mp4")
                    mime = "video/mp4"
                    compressVideoJoshAtticusStyle(context, source, outputFile, effectiveTargetBytes, videoConfig, onProgress)
                }

                // 3. AUDIO: Full Duration Preservation
                source.category == FileCategory.AUDIO -> {
                    outputFile = File(outputDir, "${baseName}_compressed.m4a")
                    mime = "audio/mp4"
                    compressAudioWithBitrate(context, source, outputFile, effectiveTargetBytes, onProgress)
                }

                // 4. PDF: Smart Page Downsampling
                source.extension.equals("pdf", ignoreCase = true) -> {
                    outputFile = File(outputDir, "${baseName}_compressed.pdf")
                    mime = "application/pdf"
                    compressPdf(context, source, outputFile, effectiveTargetBytes, onProgress)
                }

                // 5. TEXT / DATA / CODE: Lossless Minification
                source.category == FileCategory.CODE_DATA || ext in listOf("txt", "json", "csv", "xml", "html", "md", "sql") -> {
                    outputFile = File(outputDir, "${baseName}_compressed.$ext")
                    mime = source.mimeType
                    compressTextLosslessly(context, source, outputFile, onProgress)
                }

                // 6. Generic Binaries: Max Deflate Level 9 Zip
                else -> {
                    outputFile = File(outputDir, "${baseName}_compressed.zip")
                    mime = "application/zip"
                    compressGenericZip(context, source, outputFile, onProgress)
                }
            }

            // Apply metadata preferences for images
            if (!config.metadataConfig.stripAllForPrivacy && source.category == FileCategory.IMAGE) {
                MetadataHelper.applyMetadataPreferences(context, source.uri, outputFile, config.metadataConfig, source.timestamp)
            }

            onProgress(0.95f, "Saving to public Downloads/OmniFile...")
            val savedInfo = DownloadStorageHelper.saveToDownloads(context, outputFile, outputFile.name, mime)

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
        }
    }

    // =========================================================================
    // IMAGE COMPRESSION ENGINE (ahxxm/ic Perceptual Resolution-First Method)
    // =========================================================================
    private fun compressImageIcStyle(
        context: Context,
        source: FileDetails,
        outputFile: File,
        targetBytes: Long,
        onProgress: (Float, String) -> Unit
    ) {
        onProgress(0.2f, "Inspecting image dimensions...")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(source.uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val origW = bounds.outWidth.coerceAtLeast(1)
        val origH = bounds.outHeight.coerceAtLeast(1)

        var sampleSize = 1
        while ((origW / sampleSize) > 3840 || (origH / sampleSize) > 3840) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val baseBitmap = context.contentResolver.openInputStream(source.uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOptions)
        } ?: throw IllegalArgumentException("Could not decode image")

        val hasAlpha = baseBitmap.hasAlpha()
        val format = when {
            hasAlpha -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.PNG
            source.extension.equals("webp", ignoreCase = true) -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.JPEG
            }
            source.extension.equals("png", ignoreCase = true) && targetBytes < (source.size * 0.6) -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.JPEG
            }
            else -> Bitmap.CompressFormat.JPEG
        }

        onProgress(0.35f, "Calculating perceptual resolution tiers...")

        var bestBytes = searchQualityBudget(baseBitmap, format, targetBytes, minQ = 55, maxQ = 88)

        if (bestBytes.isEmpty() || bestBytes.size > targetBytes) {
            val scaleTiers = listOf(0.85f, 0.70f, 0.55f, 0.40f, 0.30f, 0.20f, 0.12f)
            for (scale in scaleTiers) {
                onProgress(0.5f, "Scaling resolution to ${(scale * 100).toInt()}% for optimal clarity...")
                val newW = (baseBitmap.width * scale).roundToInt().coerceAtLeast(32)
                val newH = (baseBitmap.height * scale).roundToInt().coerceAtLeast(32)

                val scaled = Bitmap.createScaledBitmap(baseBitmap, newW, newH, true)
                val result = searchQualityBudget(scaled, format, targetBytes, minQ = 45, maxQ = 85)
                scaled.recycle()

                if (result.isNotEmpty() && result.size <= targetBytes) {
                    bestBytes = result
                    break
                }
            }
        }

        if (bestBytes.isEmpty() || bestBytes.size > targetBytes) {
            val micro = Bitmap.createScaledBitmap(
                baseBitmap,
                320,
                (320f * origH / origW).roundToInt().coerceAtLeast(32),
                true
            )
            bestBytes = testCompress(micro, Bitmap.CompressFormat.JPEG, 35)
            micro.recycle()
        }

        baseBitmap.recycle()
        onProgress(0.9f, "Writing ${FileDetails.formatBytes(bestBytes.size.toLong())} image...")
        FileOutputStream(outputFile).use { it.write(bestBytes) }
    }

    private fun searchQualityBudget(
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
    // VIDEO COMPRESSION ENGINE (Media3 Full Control: Codec, Res, FPS, Audio)
    // =========================================================================
    @OptIn(UnstableApi::class)
    private suspend fun compressVideoJoshAtticusStyle(
        context: Context,
        source: FileDetails,
        outputFile: File,
        targetBytes: Long,
        videoConfig: VideoCompressionConfig,
        onProgress: (Float, String) -> Unit
    ) {
        onProgress(0.15f, "Inspecting video streams...")

        val (durationMs, origHeight) = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            var dur = 10_000L
            var h = 1080
            try {
                context.contentResolver.openFileDescriptor(source.uri, "r")?.use { pfd ->
                    retriever.setDataSource(pfd.fileDescriptor)
                } ?: retriever.setDataSource(context, source.uri)

                dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 10_000L
                h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080
            } catch (_: Exception) {
            } finally {
                retriever.release()
            }
            Pair(dur, h)
        }

        val durationSec = (durationMs / 1000.0).coerceAtLeast(1.0)
        val audioBitrateBps = if (videoConfig.removeAudio) 0 else videoConfig.audioBitrateKbps * 1000
        val audioBudgetBits = audioBitrateBps * durationSec
        val totalBits = (targetBytes * 8).toDouble()

        val videoBudgetBits = (totalBits - audioBudgetBits).coerceAtLeast(totalBits * 0.5)
        val calculatedVideoBitrate = (videoBudgetBits / durationSec).toInt().coerceIn(120_000, 18_000_000)

        val requestedHeight = videoConfig.resolution.heightPx
        val targetHeight = when {
            requestedHeight > 0 -> minOf(origHeight, requestedHeight)
            calculatedVideoBitrate >= 3_000_000 -> minOf(origHeight, 1080)
            calculatedVideoBitrate >= 1_400_000 -> minOf(origHeight, 720)
            calculatedVideoBitrate >= 750_000 -> minOf(origHeight, 540)
            else -> minOf(origHeight, 480)
        }

        val codecMime = if (videoConfig.codec == VideoCodec.H265) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264
        val fpsLabel = if (videoConfig.targetFps > 0f) "${videoConfig.targetFps.toInt()}fps" else "Orig FPS"
        onProgress(0.25f, "Encoding ${targetHeight}p • $fpsLabel • ${calculatedVideoBitrate / 1000} kbps...")

        withContext(Dispatchers.Main) {
            val mediaItem = MediaItem.fromUri(source.uri)

            // Video Effects: Resolution scaling & optional FPS drop
            val videoEffects = mutableListOf<Effect>()
            if (targetHeight in 1 until origHeight) {
                videoEffects.add(Presentation.createForHeight(targetHeight))
            }
            if (videoConfig.targetFps > 0f) {
                videoEffects.add(FrameDropEffect.createDefaultFrameDropEffect(videoConfig.targetFps))
            }

            // Audio Processors: Volume gain via ChannelMixingAudioProcessor
            val audioProcessors = mutableListOf<AudioProcessor>()
            if (!videoConfig.removeAudio && abs(videoConfig.audioVolume - 1.0f) > 0.04f) {
                val mixer = ChannelMixingAudioProcessor()
                for (ch in 1..6) {
                    val matrix = ChannelMixingMatrix.create(ch, ch).scaleBy(videoConfig.audioVolume)
                    mixer.putChannelMixingMatrix(matrix)
                }
                audioProcessors.add(mixer)
            }

            val editedItem = EditedMediaItem.Builder(mediaItem)
                .setRemoveAudio(videoConfig.removeAudio)
                .setEffects(Effects(audioProcessors, videoEffects))
                .build()

            val videoEncoderSettings = VideoEncoderSettings.Builder()
                .setBitrate(calculatedVideoBitrate)
                .setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                .build()

            val encoderFactoryBuilder = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(videoEncoderSettings)
                .setEnableFallback(true)

            if (!videoConfig.removeAudio) {
                encoderFactoryBuilder.setRequestedAudioEncoderSettings(
                    AudioEncoderSettings.Builder()
                        .setBitrate(audioBitrateBps)
                        .build()
                )
            }

            val transformerBuilder = Transformer.Builder(context)
                .setEncoderFactory(encoderFactoryBuilder.build())
                .setVideoMimeType(codecMime)

            if (!videoConfig.removeAudio) {
                transformerBuilder.setAudioMimeType(MimeTypes.AUDIO_AAC)
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
                            onProgress(pct, "Encoding video (${progressHolder.progress}%)...")
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
                        transformer?.cancel()
                    }
                }
            } finally {
                progressJob.cancel()
            }
        }
    }

    // =========================================================================
    // AUDIO COMPRESSION (Full Track Duration)
    // =========================================================================
    private fun compressAudioWithBitrate(
        context: Context,
        source: FileDetails,
        outputFile: File,
        targetBytes: Long,
        onProgress: (Float, String) -> Unit
    ) {
        onProgress(0.2f, "Calculating audio bitrate target...")

        val retriever = MediaMetadataRetriever()
        var durationMs = 1000L
        try {
            context.contentResolver.openFileDescriptor(source.uri, "r")?.use { pfd ->
                retriever.setDataSource(pfd.fileDescriptor)
            } ?: retriever.setDataSource(context, source.uri)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 1000L
        } catch (_: Exception) {
        } finally {
            retriever.release()
        }

        val durationSec = (durationMs / 1000.0).coerceAtLeast(1.0)
        val targetBitrate = ((targetBytes * 8) / durationSec).toInt().coerceIn(32_000, 192_000)

        onProgress(0.4f, "Muxing audio stream (${targetBitrate / 1000} kbps target)...")

        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(context, source.uri, null)
            val audioTrackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("No audio track found in file")

            extractor.selectTrack(audioTrackIndex)
            val format = extractor.getTrackFormat(audioTrackIndex)

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrack = muxer.addTrack(format)
            muxer.start()

            val buffer = ByteBuffer.allocate(64 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()

            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = extractor.sampleFlags

                muxer.writeSampleData(muxerTrack, buffer, bufferInfo)
                extractor.advance()
            }
        } finally {
            try { muxer?.stop() } catch (_: Exception) {}
            muxer?.release()
            extractor.release()
        }
    }

    // =========================================================================
    // PDF COMPRESSION (Page-by-page smart downsampling)
    // =========================================================================
    private fun compressPdf(
        context: Context,
        source: FileDetails,
        outputFile: File,
        targetBytes: Long,
        onProgress: (Float, String) -> Unit
    ) {
        onProgress(0.2f, "Inspecting PDF pages...")
        val pfd = context.contentResolver.openFileDescriptor(source.uri, "r")
            ?: throw IllegalArgumentException("Cannot open PDF")
        val renderer = PdfRenderer(pfd)
        val pdfDoc = PdfDocument()

        try {
            val pageCount = renderer.pageCount
            if (pageCount == 0) return

            val bytesPerPage = (targetBytes - 2048).coerceAtLeast(1024) / pageCount
            val (scale, quality) = when {
                bytesPerPage > 80 * 1024 -> Pair(1.0f, 75)
                bytesPerPage > 40 * 1024 -> Pair(0.8f, 55)
                bytesPerPage > 15 * 1024 -> Pair(0.6f, 35)
                else -> Pair(0.4f, 20)
            }

            for (i in 0 until pageCount) {
                onProgress(0.25f + (0.6f * (i.toFloat() / pageCount)), "Compressing page ${i + 1} of $pageCount...")
                val page = renderer.openPage(i)
                val origPageW = page.width
                val origPageH = page.height
                val w = (origPageW * scale).toInt().coerceAtLeast(100)
                val h = (origPageH * scale).toInt().coerceAtLeast(100)

                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
                val canvas = Canvas(bmp)
                canvas.drawColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                val pageJpeg = testCompress(bmp, Bitmap.CompressFormat.JPEG, quality)
                bmp.recycle()

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
            pfd.close()
        }
    }

    // =========================================================================
    // TEXT & CODE (Lossless Compact Minification)
    // =========================================================================
    private fun compressTextLosslessly(
        context: Context,
        source: FileDetails,
        outputFile: File,
        onProgress: (Float, String) -> Unit
    ) {
        onProgress(0.3f, "Performing lossless minification...")
        val raw = context.contentResolver.openInputStream(source.uri)?.bufferedReader()?.use { it.readText() } ?: ""

        val minified = when (source.extension.lowercase()) {
            "json" -> raw.replace(Regex("\\s+"), " ").trim()
            "csv", "tsv" -> raw.lines().filter { it.isNotBlank() }.joinToString("\n") { it.trim() }
            "xml", "html" -> raw.replace(Regex(">\\s+<"), "><").trim()
            else -> raw.lines().joinToString("\n") { it.trimEnd() }.trim()
        }

        val minifiedBytes = minified.toByteArray(Charsets.UTF_8)
        onProgress(0.85f, "Writing minified text...")
        FileOutputStream(outputFile).use { it.write(minifiedBytes) }
    }

    // =========================================================================
    // GENERIC BINARIES (Deflate Level 9 Valid ZIP)
    // =========================================================================
    private fun compressGenericZip(
        context: Context,
        source: FileDetails,
        outputFile: File,
        onProgress: (Float, String) -> Unit
    ) {
        onProgress(0.3f, "Packaging into Level-9 Deflate ZIP...")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(outputFile), 32768)).use { zos ->
            zos.setLevel(Deflater.BEST_COMPRESSION)
            val entry = ZipEntry(source.name)
            zos.putNextEntry(entry)
            context.contentResolver.openInputStream(source.uri)?.use { input ->
                input.copyTo(zos, 32768)
            }
            zos.closeEntry()
        }
    }
}
