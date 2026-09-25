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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

object SampleFilesGenerator {

    suspend fun getOrCreateSampleFiles(context: Context): List<FileDetails> = withContext(Dispatchers.IO) {
        val sampleDir = File(context.cacheDir, "sample_files").apply { mkdirs() }
        val results = mutableListOf<FileDetails>()

        // 1. High-Res Organic Photo (Forest & Sunlit Canopy)
        val imageFile = File(sampleDir, "sample_forest_canopy.jpg")
        if (!imageFile.exists() || imageFile.length() < 1000) {
            val bmp = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            // Warm sunlit mist sky
            paint.shader = LinearGradient(0f, 0f, 0f, 700f, Color.rgb(230, 242, 233), Color.rgb(180, 218, 195), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, 1600f, 700f, paint)

            // Morning Sun Glow
            paint.shader = null
            paint.color = Color.rgb(255, 238, 190)
            canvas.drawCircle(800f, 320f, 180f, paint)

            // Distant Mountains
            paint.color = Color.rgb(55, 95, 75)
            val path = android.graphics.Path().apply {
                moveTo(0f, 700f)
                lineTo(450f, 400f)
                lineTo(900f, 620f)
                lineTo(1300f, 350f)
                lineTo(1600f, 700f)
                close()
            }
            canvas.drawPath(path, paint)

            // Forest Foreground
            paint.color = Color.rgb(28, 55, 40)
            canvas.drawRect(0f, 700f, 1600f, 1200f, paint)

            FileOutputStream(imageFile).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            bmp.recycle()
        }
        results.add(FileInspector.inspectFile(imageFile).copy(isSample = true))

        // 2. Audio Ambience (Binaural Harmonic Tone WAV)
        val audioFile = File(sampleDir, "sample_nature_tone.wav")
        if (!audioFile.exists() || audioFile.length() < 1000) {
            generateSampleWav(audioFile, durationSec = 2.5, sampleRate = 44100)
        }
        results.add(FileInspector.inspectFile(audioFile).copy(isSample = true))

        // 3. Botanical Species Dataset CSV
        val csvFile = File(sampleDir, "sample_botanical_catalog.csv")
        if (!csvFile.exists() || csvFile.length() < 100) {
            csvFile.writeText(buildSampleCsv())
        }
        results.add(FileInspector.inspectFile(csvFile).copy(isSample = true))

        // 4. Ecosystem Config JSON
        val jsonFile = File(sampleDir, "sample_ecosystem_manifest.json")
        if (!jsonFile.exists() || jsonFile.length() < 100) {
            jsonFile.writeText(buildSampleJson())
        }
        results.add(FileInspector.inspectFile(jsonFile).copy(isSample = true))

        // 5. Permaculture Guide Markdown
        val mdFile = File(sampleDir, "sample_permaculture_guide.md")
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
                val freqL = 432.0 // Natural healing A
                val freqR = 528.0 // Solfeggio C
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
        sb.append("id,botanical_name,common_name,climate_zone,canopy_layer,soil_ph_optimum,moisture_demand\n")
        val flora = listOf(
            Triple("Quercus alba", "White Oak", "Temperate"),
            Triple("Acer saccharum", "Sugar Maple", "Boreal"),
            Triple("Lavandula angustifolia", "English Lavender", "Mediterranean"),
            Triple("Salvia rosmarinus", "Rosemary", "Arid"),
            Triple("Monstera deliciosa", "Swiss Cheese Plant", "Tropical Rainforest")
        )

        for (i in 1..80) {
            val item = flora[i % flora.size]
            val layer = if (i % 3 == 0) "Overstory" else if (i % 3 == 1) "Understory" else "Herbaceous"
            val ph = 5.5 + ((i % 25) / 10.0)
            val moisture = if (i % 2 == 0) "Moderate" else "High"
            sb.append(String.format(java.util.Locale.US, "%d,%s,%s,%s,%s,%.1f,%s\n", i, item.first, item.second, item.third, layer, ph, moisture))
        }
        return sb.toString()
    }

    private fun buildSampleJson(): String {
        return """
        {
          "ecosystem": {
            "name": "OmniForest Sanctuary",
            "biome": "Temperate Evergreen & Meadow",
            "biodiversity_index": 0.94,
            "soil_organic_matter_pct": 14.8,
            "canopy_coverage_pct": 78
          },
          "flora_layers": [
            {
              "layer": "Emergent & Canopy",
              "keystone_species": ["Quercus robur", "Pinus sylvestris"],
              "carbon_sequestration_tons_yr": 124.5
            },
            {
              "layer": "Shrub & Herbaceous",
              "keystone_species": ["Vaccinium myrtillus", "Dryopteris filix-mas"],
              "pollinator_support_rating": "Exceptional"
            }
          ],
          "local_processing": {
            "zero_cloud_footprint": true,
            "energy_efficiency_multiplier": 4.2
          }
        }
        """.trimIndent()
    }

    private fun buildSampleMarkdown(): String {
        return """
        # Permaculture & Regenerative Design Guide
        
        ## 1. Principles of Natural Systems
        Working with local biological and thermodynamic patterns rather than forced unnatural resistance.
        
        ### Core Tenets:
        - **Catch and Store Energy**: Passive harvesting of solar and organic matter.
        - **Produce No Waste**: Every output becomes the nutrient input for the next cycle.
        - **Integrate Rather than Segregate**: High density companion guilds creating resilience.
        
        ## 2. Soil Micro-Biome & Fungal Networks
        Mycelial mycorrhizal networks share trace minerals, nitrogen, and moisture across diverse canopy layers.
        
        ## 3. Local Offline Computing
        100% on-device processing minimizes datacenter heat and transmission footprints, delivering instantaneous privacy and longevity.
        """.trimIndent()
    }
}
