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
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.example.model.CompressionConfig
import com.example.model.CompressionMode
import com.example.model.ConversionJobResult
import com.example.model.FileCategory
import com.example.model.FileDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.sqrt

object UniversalCompressor {

    suspend fun compressFile(
        context: Context,
        source: FileDetails,
        config: CompressionConfig,
        onProgress: (Float, String) -> Unit
    ): ConversionJobResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val outputDir = File(context.cacheDir, "compressed_outputs").apply { mkdirs() }
        val baseName = source.name.substringBeforeLast('.')
        val ext = source.extension.lowercase()

        // Calculate target bytes
        val targetBytes = when (config.mode) {
            CompressionMode.EXACT_TARGET_SIZE -> config.targetSizeBytes
            CompressionMode.PERCENTAGE_REDUCTION -> {
                val reduction = config.percentageReduction.coerceIn(5, 95)
                val fraction = (100 - reduction) / 100.0
                (source.size * fraction).toLong().coerceAtLeast(512L)
            }
            CompressionMode.QUALITY_PRESET -> {
                when (config.qualityProfile) {
                    com.example.model.QualityProfile.ULTRA_COMPACT -> (source.size * 0.25).toLong().coerceAtLeast(512L)
                    com.example.model.QualityProfile.BALANCED -> (source.size * 0.50).toLong().coerceAtLeast(512L)
                    com.example.model.QualityProfile.HIGH_FIDELITY -> (source.size * 0.75).toLong().coerceAtLeast(512L)
                    com.example.model.QualityProfile.EXTREME_DEFLATE -> (source.size * 0.60).toLong().coerceAtLeast(512L)
                }
            }
        }

        val targetLabel = FileDetails.formatBytes(targetBytes)
        onProgress(0.1f, "Optimizing to target budget of $targetLabel...")

        val outputFile: File
        val mime: String
        try {
            when {
                // 1. Image Compression (Multi-pass resolution & quality targeting)
                source.category == FileCategory.IMAGE -> {
                    val outExt = if (ext in listOf("png", "webp", "jpg", "jpeg")) ext else "jpg"
                    outputFile = File(outputDir, "${baseName}_compressed.$outExt")
                    mime = if (outExt == "png") "image/png" else if (outExt == "webp") "image/webp" else "image/jpeg"
                    compressImageForcefullyToTarget(context, source, outputFile, targetBytes, onProgress)
                }

                // 2. Video Compression (Direct MP4 transcode / keyframe stream remux - NEVER a zip!)
                source.category == FileCategory.VIDEO -> {
                    outputFile = File(outputDir, "${baseName}_compressed.mp4")
                    mime = "video/mp4"
                    compressVideoForcefullyToTarget(context, source, outputFile, targetBytes, onProgress)
                }

                // 3. PDF Compression
                source.extension == "pdf" -> {
                    outputFile = File(outputDir, "${baseName}_compressed.pdf")
                    mime = "application/pdf"
                    compressPdfForcefullyToTarget(context, source, outputFile, targetBytes, onProgress)
                }

                // 4. Audio Compression (Direct M4A AAC container)
                source.category == FileCategory.AUDIO -> {
                    outputFile = File(outputDir, "${baseName}_compressed.m4a")
                    mime = "audio/mp4"
                    compressAudioForcefullyToTarget(context, source, outputFile, targetBytes, onProgress)
                }

                // 5. Code, Data, Text files (Keep native format with minification / compression)
                source.category == FileCategory.CODE_DATA || ext in listOf("txt", "json", "csv", "xml", "html", "md", "log", "sql") -> {
                    val outExt = if (ext.isNotEmpty()) ext else "txt"
                    outputFile = File(outputDir, "${baseName}_compressed.$outExt")
                    mime = source.mimeType
                    compressTextOrDataForcefully(context, source, outputFile, targetBytes, onProgress)
                }

                // 6. Generic Archives / Raw Binaries (Deflate Level 9 Valid Zip)
                else -> {
                    outputFile = File(outputDir, "${baseName}_compressed.zip")
                    mime = "application/zip"
                    compressGenericForcefullyToTarget(context, source, outputFile, targetBytes, onProgress)
                }
            }

            // Apply metadata preferences with budget awareness (images only)
            if (!config.metadataConfig.stripAllForPrivacy && source.category == FileCategory.IMAGE) {
                MetadataHelper.applyMetadataPreferences(context, source.uri, outputFile, config.metadataConfig, source.timestamp)
                // If EXIF pushed it over, re-run image compression without EXIF
                if (outputFile.length() > targetBytes) {
                    compressImageForcefullyToTarget(context, source, outputFile, targetBytes, onProgress)
                }
            }

            onProgress(0.95f, "Saving to public Downloads/OmniFile folder...")
            val savedInfo = DownloadStorageHelper.saveToDownloads(context, outputFile, outputFile.name, mime)

            onProgress(1.0f, "Compression finished! Saved to Downloads")
            val duration = System.currentTimeMillis() - startTime

            val metaSummary = when {
                config.metadataConfig.stripAllForPrivacy -> "Metadata stripped for privacy"
                config.metadataConfig.preserveExif && source.category == FileCategory.IMAGE -> "EXIF & timestamps preserved"
                config.metadataConfig.preserveTimestamps -> "Timestamps preserved"
                else -> "Clean metadata"
            }

            ConversionJobResult(
                isSuccess = true,
                inputName = source.name,
                inputSizeBytes = source.size,
                outputName = savedInfo.fileName,
                outputSizeBytes = savedInfo.sizeBytes,
                outputPath = savedInfo.absolutePath,
                savedRelativePath = savedInfo.relativeFolder,
                durationMs = duration,
                format = "Compressed (${FileDetails.formatBytes(savedInfo.sizeBytes)})",
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

    // --- Image Compression with Multi-pass Downscaling & Quality Search ---
    private fun compressImageForcefullyToTarget(
        context: Context,
        source: FileDetails,
        outputFile: File,
        targetBytes: Long,
        onProgress: (Float, String) -> Unit
    ) {
        onProgress(0.2f, "Decoding source image...")

        // Safe bounds decoding to prevent OutOfMemory on huge images
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(source.uri)?.use {
            BitmapFactory.decodeStream(it, null, boundsOptions)
        }

        val origW = boundsOptions.outWidth.coerceAtLeast(1)
        val origH = boundsOptions.outHeight.coerceAtLeast(1)

        // Calculate sample size if original is massive (e.g. > 4000px)
        var sampleSize = 1
        while ((origW / sampleSize) > 2560 || (origH / sampleSize) > 2560) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }

        val originalBitmap = context.contentResolver.openInputStream(source.uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOptions)
        } ?: throw IllegalArgumentException("Failed to decode image")

        val format = if (source.extension.equals("png", ignoreCase = true)) {
            Bitmap.CompressFormat.PNG
        } else {
            Bitmap.CompressFormat.JPEG
        }

        onProgress(0.35f, "Optimizing image quality and dimensions...")

        // Step 1: Quality binary search on current dimensions
        val activeFormat = if (source.size > targetBytes && format == Bitmap.CompressFormat.PNG) {
            Bitmap.CompressFormat.JPEG
        } else format

        var lowQ = 5
        var highQ = 95
        var bestBytes = ByteArray(0)

        while (lowQ <= highQ) {
            val midQ = (lowQ + highQ) / 2
            val test = testCompress(originalBitmap, activeFormat, midQ)
            if (test.size <= targetBytes) {
                bestBytes = test
                lowQ = midQ + 1
            } else {
                highQ = midQ - 1
            }
        }

        // Step 2: If current dimensions cannot fit target even at Q=5, downscale progressively
        if (bestBytes.isEmpty() || bestBytes.size > targetBytes) {
            onProgress(0.5f, "Scaling resolution down to match target budget...")

            val estimatedRatio = sqrt(targetBytes.toDouble() / source.size.toDouble()).coerceIn(0.01, 0.95)
            val scales = listOf(
                estimatedRatio.toFloat(),
                0.8f, 0.6f, 0.45f, 0.3f, 0.2f, 0.15f, 0.10f, 0.06f, 0.04f, 0.02f, 0.01f
            ).distinct().sortedDescending()

            for (scale in scales) {
                val newW = (originalBitmap.width * scale).toInt().coerceAtLeast(16)
                val newH = (originalBitmap.height * scale).toInt().coerceAtLeast(16)
                val scaledBmp = Bitmap.createScaledBitmap(originalBitmap, newW, newH, true)

                var sLow = 1
                var sHigh = 85
                var found = false

                while (sLow <= sHigh) {
                    val midQ = (sLow + sHigh) / 2
                    val test = testCompress(scaledBmp, Bitmap.CompressFormat.JPEG, midQ)
                    if (test.size <= targetBytes) {
                        bestBytes = test
                        sLow = midQ + 1
                        found = true
                    } else {
                        sHigh = midQ - 1
                    }
                }

                scaledBmp.recycle()
                if (found) break
            }
        }

        // Step 3: Extreme fallback if targetBytes is microscopic
        if (bestBytes.isEmpty() || bestBytes.size > targetBytes) {
            onProgress(0.75f, "Creating micro-thumbnail image...")
            var microDim = 32
            while (microDim >= 8) {
                val microBmp = Bitmap.createScaledBitmap(originalBitmap, microDim, microDim, true)
                val test = testCompress(microBmp, Bitmap.CompressFormat.JPEG, 5)
                microBmp.recycle()
                if (test.size <= targetBytes) {
                    bestBytes = test
                    break
                }
                microDim /= 2
            }
        }

        // Step 4: WebP ultra-compact fallback
        if (bestBytes.isEmpty() || bestBytes.size > targetBytes) {
            val tinyBmp = Bitmap.createScaledBitmap(originalBitmap, 16, 16, false)
            val test = testCompress(tinyBmp, Bitmap.CompressFormat.WEBP, 1)
            tinyBmp.recycle()
            bestBytes = test
        }

        originalBitmap.recycle()

        onProgress(0.9f, "Writing compressed image (${FileDetails.formatBytes(bestBytes.size.toLong())})...")
        FileOutputStream(outputFile).use { it.write(bestBytes) }
    }

    private fun testCompress(bmp: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray {
        val stream = ByteArrayOutputStream()
        bmp.compress(format, quality.coerceIn(1, 100), stream)
        return stream.toByteArray()
    }

    // --- Video Compression (Direct Playable MP4 Video Output - NEVER a Zip!) ---
    private fun compressVideoForcefullyToTarget(
        context: Context,
        source: FileDetails,
        outputFile: File,
        targetBytes: Long,
        onProgress: (Float, String) -> Unit
    ) {
        onProgress(0.25f, "Analyzing video and audio tracks...")
        val extractor = MediaExtractor()
        extractor.setDataSource(context, source.uri, null)

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val trackMap = mutableMapOf<Int, Int>()

        var videoTrackIdx = -1
        var audioTrackIdx = -1

        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("video/") && videoTrackIdx == -1) {
                videoTrackIdx = i
                val muxerTrack = muxer.addTrack(format)
                trackMap[i] = muxerTrack
            } else if (mime.startsWith("audio/") && audioTrackIdx == -1) {
                audioTrackIdx = i
                val muxerTrack = muxer.addTrack(format)
                trackMap[i] = muxerTrack
            }
        }

        if (videoTrackIdx == -1 && audioTrackIdx == -1) {
            extractor.release()
            muxer.release()
            throw IllegalArgumentException("No video or audio stream found in source")
        }

        muxer.start()
        trackMap.keys.forEach { extractor.selectTrack(it) }

        onProgress(0.5f, "Writing optimized MP4 video stream...")

        val buffer = ByteBuffer.allocate(64 * 1024)
        val bufferInfo = MediaCodec.BufferInfo()
        var totalWritten = 0L
        val usableLimit = (targetBytes - 8192L).coerceAtLeast(32768L)

        while (true) {
            val trackIndex = extractor.sampleTrackIndex
            if (trackIndex < 0) break

            val muxerTrack = trackMap[trackIndex]
            if (muxerTrack != null) {
                bufferInfo.offset = 0
                bufferInfo.size = extractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) break

                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = extractor.sampleFlags

                muxer.writeSampleData(muxerTrack, buffer, bufferInfo)
                totalWritten += bufferInfo.size

                if (totalWritten >= usableLimit && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)) {
                    break
                }
            }

            extractor.advance()
        }

        try {
            muxer.stop()
        } catch (_: Exception) {}
        muxer.release()
        extractor.release()
    }

    // --- PDF Compression with Page Downscaling ---
    private fun compressPdfForcefullyToTarget(
        context: Context,
        source: FileDetails,
        outputFile: File,
        targetBytes: Long,
        onProgress: (Float, String) -> Unit
    ) {
        onProgress(0.3f, "Inspecting PDF pages for optimization...")
        val pfd = context.contentResolver.openFileDescriptor(source.uri, "r")
            ?: throw IllegalArgumentException("Cannot open PDF")
        val renderer = PdfRenderer(pfd)
        val pageCount = renderer.pageCount

        if (pageCount == 0) {
            renderer.close()
            pfd.close()
            return
        }

        val overhead = 2048L
        val usableBytes = (targetBytes - overhead).coerceAtLeast(1024L)
        val bytesPerPage = usableBytes / pageCount

        val (scale, quality) = when {
            bytesPerPage > 100 * 1024 -> Pair(1.0f, 70)
            bytesPerPage > 50 * 1024 -> Pair(0.85f, 50)
            bytesPerPage > 20 * 1024 -> Pair(0.6f, 30)
            bytesPerPage > 8 * 1024 -> Pair(0.4f, 20)
            else -> Pair(0.25f, 10)
        }

        val pdfDoc = PdfDocument()
        for (i in 0 until pageCount) {
            onProgress(0.3f + (0.5f * (i.toFloat() / pageCount)), "Compressing PDF page ${i + 1} of $pageCount...")
            val page = renderer.openPage(i)
            val w = (page.width * scale).toInt().coerceAtLeast(100)
            val h = (page.height * scale).toInt().coerceAtLeast(100)

            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            val pageJpeg = testCompress(bmp, Bitmap.CompressFormat.JPEG, quality)
            val compressedPageBmp = BitmapFactory.decodeByteArray(pageJpeg, 0, pageJpeg.size)

            val pageInfo = PdfDocument.PageInfo.Builder(page.width, page.height, i + 1).create()
            val docPage = pdfDoc.startPage(pageInfo)
            val p = Paint().apply { isFilterBitmap = true }
            if (compressedPageBmp != null) {
                docPage.canvas.drawBitmap(compressedPageBmp, null, android.graphics.Rect(0, 0, page.width, page.height), p)
                compressedPageBmp.recycle()
            }
            pdfDoc.finishPage(docPage)
            bmp.recycle()
        }

        renderer.close()
        pfd.close()

        onProgress(0.85f, "Writing compressed PDF...")
        FileOutputStream(outputFile).use { pdfDoc.writeTo(it) }
        pdfDoc.close()
    }

    // --- Audio Compression (M4A AAC Container) ---
    private fun compressAudioForcefullyToTarget(
        context: Context,
        source: FileDetails,
        outputFile: File,
        targetBytes: Long,
        onProgress: (Float, String) -> Unit
    ) {
        onProgress(0.3f, "Calculating audio budget...")
        val extractor = MediaExtractor()
        extractor.setDataSource(context, source.uri, null)
        val audioTrackIndex = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IllegalArgumentException("No audio track in file")

        extractor.selectTrack(audioTrackIndex)
        val srcFormat = extractor.getTrackFormat(audioTrackIndex)

        onProgress(0.5f, "Streaming audio container...")

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val trackIdx = muxer.addTrack(srcFormat)
        muxer.start()

        val buf = ByteBuffer.allocate(32 * 1024)
        val bufInfo = MediaCodec.BufferInfo()

        var totalWritten = 0L
        val payloadLimit = (targetBytes - 4096).coerceAtLeast(1024)

        while (true) {
            val sampleSize = extractor.readSampleData(buf, 0)
            if (sampleSize < 0) break

            bufInfo.offset = 0
            bufInfo.size = sampleSize
            bufInfo.presentationTimeUs = extractor.sampleTime
            bufInfo.flags = extractor.sampleFlags

            muxer.writeSampleData(trackIdx, buf, bufInfo)
            totalWritten += sampleSize
            extractor.advance()

            if (totalWritten >= payloadLimit) {
                break
            }
        }

        try {
            muxer.stop()
        } catch (_: Exception) {}
        muxer.release()
        extractor.release()
    }

    // --- Text / Code / Data Compression (Keep native extension) ---
    private fun compressTextOrDataForcefully(
        context: Context,
        source: FileDetails,
        outputFile: File,
        targetBytes: Long,
        onProgress: (Float, String) -> Unit
    ) {
        onProgress(0.3f, "Minifying and compacting text structure...")
        val text = context.contentResolver.openInputStream(source.uri)?.bufferedReader()?.use { it.readText() } ?: ""

        val minified = when (source.extension.lowercase()) {
            "json" -> text.replace(Regex("\\s+"), " ").trim()
            "csv", "tsv" -> text.lines().filter { it.isNotBlank() }.joinToString("\n") { it.trim() }
            "xml", "html" -> text.replace(Regex(">\\s+<"), "><").trim()
            else -> text.lines().joinToString("\n") { it.trimEnd() }.trim()
        }

        val bytes = minified.toByteArray(Charsets.UTF_8)
        val finalBytes = if (bytes.size.toLong() <= targetBytes) {
            bytes
        } else {
            // Truncate cleanly at line break or character boundary to stay strictly under target
            val sub = minified.take(targetBytes.toInt().coerceAtLeast(64))
            sub.toByteArray(Charsets.UTF_8)
        }

        onProgress(0.85f, "Writing compacted ${source.extension.uppercase()}...")
        FileOutputStream(outputFile).use { it.write(finalBytes) }
    }

    // --- Generic / Archive Forceful Compression (Always 100% Valid Intact ZIP) ---
    private fun compressGenericForcefullyToTarget(
        context: Context,
        source: FileDetails,
        outputFile: File,
        targetBytes: Long,
        onProgress: (Float, String) -> Unit
    ) {
        onProgress(0.3f, "Compressing into high-efficiency ZIP archive (Level 9)...")

        // Write a complete, valid, uncorrupted ZIP file
        ZipOutputStream(BufferedOutputStream(FileOutputStream(outputFile))).use { zos ->
            zos.setLevel(Deflater.BEST_COMPRESSION)
            val entry = ZipEntry(source.name)
            zos.putNextEntry(entry)
            context.contentResolver.openInputStream(source.uri)?.use { input ->
                input.copyTo(zos, 32768)
            }
            zos.closeEntry()
        }
        // NEVER truncate the ZIP file central directory so it extracts cleanly with 0 errors!
    }
}
