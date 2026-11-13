package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.AppDatabase
import com.example.data.ConversionRecord
import com.example.data.ConversionRepository
import com.example.engine.FileInspector
import com.example.engine.UniversalCompressor
import com.example.engine.UniversalConverter
import com.example.model.CompressionConfig
import com.example.model.CompressionMode
import com.example.model.MetadataConfig
import com.example.model.SupportedFormats
import com.example.model.TargetFormat
import com.example.ui.screens.extractPartIndex
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
@Config(sdk = [28]) // Uses real filesystem public Downloads path in Robolectric for output verification
class EngineSuiteTest {

    private lateinit var context: Context
    private lateinit var tempInputDir: File
    private lateinit var database: AppDatabase
    private lateinit var repository: ConversionRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        tempInputDir = File(context.cacheDir, "test_inputs").apply { mkdirs() }
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ConversionRepository(database.conversionDao())
    }

    @After
    fun teardown() {
        database.close()
        tempInputDir.deleteRecursively()
    }

    private fun targetById(id: String): TargetFormat =
        SupportedFormats.ALL_TARGETS.first { it.id == id }

    private fun createTestFile(name: String, content: String): File {
        val f = File(tempInputDir, name)
        f.writeText(content)
        return f
    }

    private fun createTestPng(name: String, width: Int = 200, height: Int = 200): File {
        val f = File(tempInputDir, name)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.rgb(245, 158, 11)) // Cozy Amber
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return f
    }

    // =========================================================================
    // 1. DATA & DOCUMENT CONVERSIONS
    // =========================================================================

    @Test
    fun `csv to json produces valid parseable json array`() = runTest {
        val csv = createTestFile("users.csv", "name,role,age\nAlex,Developer,24\n\"Doe, Jane\",Designer,29")
        val details = FileInspector.inspectUri(context, Uri.fromFile(csv))

        val res = UniversalConverter.convertFile(context, details, targetById("json"), MetadataConfig()) { _, _ -> }
        assertTrue(res.isSuccess)

        val outText = File(res.outputPath).readText()
        val parsed = JSONArray(outText)
        assertEquals(2, parsed.length())
        assertEquals("Alex", parsed.getJSONObject(0).getString("name"))
        assertEquals("Doe, Jane", parsed.getJSONObject(1).getString("name"))
    }

    @Test
    fun `json to xml sanitizes digit keys and escapes special characters`() = runTest {
        val json = createTestFile("data.json", """{"123id": 42, "note": "A & B < C"}""")
        val details = FileInspector.inspectUri(context, Uri.fromFile(json))

        val res = UniversalConverter.convertFile(context, details, targetById("xml"), MetadataConfig()) { _, _ -> }
        assertTrue(res.isSuccess)

        val xml = File(res.outputPath).readText()
        assertTrue(xml.startsWith("<?xml"))
        assertTrue(xml.contains("<_123id>42</_123id>"))
        assertTrue(xml.contains("A &amp; B &lt; C"))
    }

    @Test
    fun `markdown to html and text to pdf succeed`() = runTest {
        val md = createTestFile("readme.md", "# Title\n\n- Item 1\n- Item 2\n\nLong paragraph testing layout wrapping.")
        val details = FileInspector.inspectUri(context, Uri.fromFile(md))

        val htmlRes = UniversalConverter.convertFile(context, details, targetById("html"), MetadataConfig()) { _, _ -> }
        assertTrue(htmlRes.isSuccess)
        assertTrue(File(htmlRes.outputPath).readText().contains("<h1>Title</h1>"))

        val pdfRes = UniversalConverter.convertFile(context, details, targetById("pdf"), MetadataConfig()) { _, _ -> }
        assertTrue(pdfRes.isSuccess)
        assertTrue(pdfRes.outputSizeBytes > 0)
    }

    // =========================================================================
    // 2. ROUND-TRIP BINARY INTEGRITY (BASE64, HEX, ZIP, GZIP, TAR)
    // =========================================================================

    @Test
    fun `base64 encode and decode round-trip matches original text`() = runTest {
        val secret = "OmniFile Local Engine Verification 12345!"
        val raw = createTestFile("secret.txt", secret)
        val rawDetails = FileInspector.inspectUri(context, Uri.fromFile(raw))

        val encodedRes = UniversalConverter.convertFile(context, rawDetails, targetById("base64"), MetadataConfig()) { _, _ -> }
        assertTrue(encodedRes.isSuccess)

        val encodedDetails = FileInspector.inspectUri(context, Uri.fromFile(File(encodedRes.outputPath)))
        val decodedRes = UniversalConverter.convertFile(context, encodedDetails, targetById("txt"), MetadataConfig()) { _, _ -> }
        assertTrue(decodedRes.isSuccess)
        assertEquals(secret, File(decodedRes.outputPath).readText())
    }

    @Test
    fun `zip and gzip packaging and extraction preserve exact bytes`() = runTest {
        val payload = "Archive payload content check!".repeat(20)
        val src = createTestFile("payload.txt", payload)
        val srcDetails = FileInspector.inspectUri(context, Uri.fromFile(src))

        // ZIP round-trip
        val zipRes = UniversalConverter.convertFile(context, srcDetails, targetById("zip"), MetadataConfig()) { _, _ -> }
        assertTrue(zipRes.isSuccess)
        val zipDetails = FileInspector.inspectUri(context, Uri.fromFile(File(zipRes.outputPath)))
        val unzipRes = UniversalConverter.convertFile(context, zipDetails, targetById("txt"), MetadataConfig()) { _, _ -> }
        assertTrue(unzipRes.isSuccess)
        assertEquals(payload, File(unzipRes.outputPath).readText())

        // GZIP round-trip
        val gzRes = UniversalConverter.convertFile(context, srcDetails, targetById("gz"), MetadataConfig()) { _, _ -> }
        assertTrue(gzRes.isSuccess)
        val gzDetails = FileInspector.inspectUri(context, Uri.fromFile(File(gzRes.outputPath)))
        val ungzRes = UniversalConverter.convertFile(context, gzDetails, targetById("txt"), MetadataConfig()) { _, _ -> }
        assertTrue(ungzRes.isSuccess)
        assertEquals(payload, File(ungzRes.outputPath).readText())
    }

    // =========================================================================
    // 3. IMAGE CONVERSION & COMPRESSION PIPELINE
    // =========================================================================

    @Test
    fun `image converts to bmp, ico, ascii, and pdf cleanly`() = runTest {
        val png = createTestPng("sample.png", 128, 128)
        val details = FileInspector.inspectUri(context, Uri.fromFile(png))

        for (targetId in listOf("bmp", "ico", "ascii", "pdf", "jpg", "webp")) {
            val res = UniversalConverter.convertFile(context, details, targetById(targetId), MetadataConfig()) { _, _ -> }
            assertTrue("Failed converting PNG to $targetId: ${res.message}", res.isSuccess)
            assertTrue("Output empty for $targetId", res.outputSizeBytes > 0)
        }
    }

    @Test
    fun `lossless json compression shrinks file without corrupting syntax`() = runTest {
        val prettyJson = """
            {
                "app": "OmniFile",
                "features": [
                    "Convert",
                    "Compress",
                    "Split"
                ]
            }
        """.trimIndent()
        val jsonFile = createTestFile("config.json", prettyJson)
        val details = FileInspector.inspectUri(context, Uri.fromFile(jsonFile))
        val cfg = CompressionConfig().copy(mode = CompressionMode.EXACT_TARGET_SIZE, targetSizeBytes = 10L)

        val res = UniversalCompressor.compressFile(context, details, cfg) { _, _ -> }
        assertTrue(res.isSuccess)
        assertTrue(res.outputSizeBytes < prettyJson.length)

        // Must still be valid JSON (never truncated!)
        val parsed = JSONObject(File(res.outputPath).readText())
        assertEquals("OmniFile", parsed.getString("app"))
    }

    @Test
    fun `splitter part index sorts naturally above part 10`() {
        val names = listOf("movie.mp4.part010", "movie.mp4.part002", "movie.mp4.part001")
        val sorted = names.sortedBy { extractPartIndex(it) }
        assertEquals(listOf("movie.mp4.part001", "movie.mp4.part002", "movie.mp4.part010"), sorted)
    }

    // =========================================================================
    // 4. ROOM DATABASE & REPOSITORY INTEGRITY
    // =========================================================================

    @Test
    fun `repository batch insert, search, savings sum, and trim work atomically`() = runTest {
        val records = listOf(
            ConversionRecord(
                mode = "COMPRESS",
                inputFileName = "holiday_photo.jpg",
                inputFileSize = 5_000_000L,
                outputFileName = "holiday_photo_compressed.jpg",
                outputFileSize = 500_000L,
                fromFormat = "JPG",
                toFormat = "JPG",
                durationMs = 120L,
                outputPath = "/tmp/1.jpg"
            ),
            ConversionRecord(
                mode = "CONVERT",
                inputFileName = "invoice.csv",
                inputFileSize = 20_000L,
                outputFileName = "invoice_converted.json",
                outputFileSize = 25_000L,
                fromFormat = "CSV",
                toFormat = "JSON",
                durationMs = 40L,
                outputPath = "/tmp/2.json"
            )
        )

        repository.insertBatch(records)

        assertEquals(2, repository.totalCount.first())
        assertEquals(4_500_000L, repository.totalBytesSaved.first()) // Only counts positive savings

        val searchResult = repository.searchRecords("holiday").first()
        assertEquals(1, searchResult.size)
        assertEquals("holiday_photo.jpg", searchResult.first().inputFileName)

        repository.trimHistory(keepCount = 1)
        assertEquals(1, repository.allRecords.first().size)
    }
}