package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import com.example.model.FileDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

object SampleFilesGenerator {

    suspend fun getOrCreateSampleFiles(context: Context): List<FileDetails> = withContext(Dispatchers.IO) {
        val sampleDir = File(context.cacheDir, "sample_files").apply { mkdirs() }
        val results = mutableListOf<FileDetails>()

        // 1. High-Res Image (1600x1200 Photo-like canvas)
        val imageFile = File(sampleDir, "sample_mountain_sunset.jpg")
        if (!imageFile.exists() || imageFile.length() < 1000) {
            val bmp = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            // Sky gradient
            paint.shader = LinearGradient(0f, 0f, 0f, 800f, Color.rgb(255, 94, 77), Color.rgb(255, 160, 0), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, 1600f, 800f, paint)

            // Sun
            paint.shader = null
            paint.color = Color.rgb(255, 240, 180)
            canvas.drawCircle(800f, 400f, 160f, paint)

            // Mountains
            paint.color = Color.rgb(40, 20, 60)
            val path = android.graphics.Path().apply {
                moveTo(0f, 800f)
                lineTo(400f, 450f)
                lineTo(800f, 750f)
                lineTo(1200f, 380f)
                lineTo(1600f, 800f)
                close()
            }
            canvas.drawPath(path, paint)

            // Foreground hills
            paint.color = Color.rgb(20, 10, 35)
            canvas.drawRect(0f, 800f, 1600f, 1200f, paint)

            FileOutputStream(imageFile).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            bmp.recycle()
        }
        results.add(FileInspector.inspectFile(imageFile).copy(isSample = true))

        // 2. Audio Tone (Stereo 44.1kHz WAV synth tone ~350KB)
        val audioFile = File(sampleDir, "sample_synth_chime.wav")
        if (!audioFile.exists() || audioFile.length() < 1000) {
            generateSampleWav(audioFile, durationSec = 2.0, sampleRate = 44100)
        }
        results.add(FileInspector.inspectFile(audioFile).copy(isSample = true))

        // 3. Sales Dataset CSV
        val csvFile = File(sampleDir, "sample_sales_q3.csv")
        if (!csvFile.exists() || csvFile.length() < 100) {
            csvFile.writeText(buildSampleCsv())
        }
        results.add(FileInspector.inspectFile(csvFile).copy(isSample = true))

        // 4. Config JSON
        val jsonFile = File(sampleDir, "sample_app_manifest.json")
        if (!jsonFile.exists() || jsonFile.length() < 100) {
            jsonFile.writeText(buildSampleJson())
        }
        results.add(FileInspector.inspectFile(jsonFile).copy(isSample = true))

        // 5. Tech Specification Markdown
        val mdFile = File(sampleDir, "sample_architecture_spec.md")
        if (!mdFile.exists() || mdFile.length() < 100) {
            mdFile.writeText(buildSampleMarkdown())
        }
        results.add(FileInspector.inspectFile(mdFile).copy(isSample = true))

        results
    }

    private fun generateSampleWav(file: File, durationSec: Double, sampleRate: Int) {
        val numSamples = (durationSec * sampleRate).toInt()
        val numChannels = 2
        val bytesPerSample = 2 // 16-bit
        val pcmDataLength = numSamples * numChannels * bytesPerSample

        val header = ByteArray(44)
        val totalDataLen = pcmDataLength + 36
        val byteRate = sampleRate * numChannels * bytesPerSample

        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[20] = 1 // PCM
        header[22] = numChannels.toByte()
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (numChannels * bytesPerSample).toByte()
        header[34] = 16
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (pcmDataLength and 0xff).toByte()
        header[41] = ((pcmDataLength shr 8) and 0xff).toByte()
        header[42] = ((pcmDataLength shr 16) and 0xff).toByte()
        header[43] = ((pcmDataLength shr 24) and 0xff).toByte()

        FileOutputStream(file).use { out ->
            out.write(header)
            val buffer = ByteBuffer.allocate(numSamples * numChannels * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until numSamples) {
                val t = i.toDouble() / sampleRate
                val freqL = 440.0 + 20.0 * sin(2.0 * Math.PI * 4.0 * t)
                val freqR = 554.37 // C#5
                val env = (1.0 - (t / durationSec)).coerceAtLeast(0.0)
                val sampleL = (sin(2.0 * Math.PI * freqL * t) * 16000.0 * env).toInt().toShort()
                val sampleR = (sin(2.0 * Math.PI * freqR * t) * 16000.0 * env).toInt().toShort()
                buffer.putShort(sampleL)
                buffer.putShort(sampleR)
            }
            out.write(buffer.array())
        }
    }

    private fun buildSampleCsv(): String {
        val sb = StringBuilder()
        sb.append("id,transaction_code,region,product_category,units_sold,unit_price,revenue,satisfaction_rating\n")
        val categories = listOf("Quantum Chip", "Neural Engine", "Optical Sensor", "Graphene Battery", "Cryo Cooler")
        val regions = listOf("North America", "Europe", "Asia Pacific", "Latin America")

        for (i in 1..80) {
            val cat = categories[i % categories.size]
            val reg = regions[i % regions.size]
            val units = (i * 13) % 250 + 10
            val price = (i * 37) % 800 + 49.99
            val revenue = units * price
            val score = 4.0 + ((i % 10) / 10.0)
            sb.append(String.format(java.util.Locale.US, "%d,TXN-%05d,%s,%s,%d,%.2f,%.2f,%.1f\n", i, 10000 + i, reg, cat, units, price, revenue, score))
        }
        return sb.toString()
    }

    private fun buildSampleJson(): String {
        return """
        {
          "system": {
            "name": "OmniEngine",
            "version": "4.2.0",
            "cluster_nodes": 12,
            "compression_acceleration": "NEON_VFPV4",
            "memory_limit_mb": 4096
          },
          "pipelines": [
            {
              "id": "pipe_image_ultra",
              "codec": "WEBP_LOSSY_V2",
              "quality_range": [10, 95],
              "downscale_enabled": true,
              "color_space": "BT2020"
            },
            {
              "id": "pipe_audio_aac",
              "sample_rate": 48000,
              "channels": 2,
              "bitrates": [32, 64, 96, 128, 192, 256]
            },
            {
              "id": "pipe_deflate_extreme",
              "algorithm": "DEFLATE_L9",
              "window_bits": 15,
              "memory_level": 9
            }
          ],
          "performance_benchmarks": {
            "throughput_mb_sec": 64.8,
            "latency_ms": 12.4,
            "entropy_efficiency": 0.94
          }
        }
        """.trimIndent()
    }

    private fun buildSampleMarkdown(): String {
        return """
        # OmniFile Engine Architecture Specification
        
        ## 1. Universal Processing Model
        OmniFile processes all file types locally on the device using memory-mapped zero-copy streaming buffers.
        
        ### Key Subsystems:
        - **Adaptive Quality Targeter**: Binary search heuristic across continuous compression domains.
        - **Polyglot Transcoder**: Cross-encoding across Raster, Vector, Audio PCM, Document, and Binary formats.
        - **Deflate L9 Accelerator**: Maximum information entropy reduction with multi-part packaging.
        
        ## 2. Performance Characteristics
        - **Local Execution**: 100% offline, zero network latency, zero cloud upload limits.
        - **Throughput**: 40-80 MB/s sustained streaming throughput.
        - **Memory Footprint**: Strict 32KB-64KB circular buffer paging to prevent out-of-memory errors on large multi-megabyte payloads.
        
        ## 3. Supported Target Dimensions
        All standard MIME groups: Image, Video frame, Audio PCM/AAC, Document PDF, CSV, JSON, XML, Markdown, Base64, and Hex.
        """.trimIndent()
    }
}
