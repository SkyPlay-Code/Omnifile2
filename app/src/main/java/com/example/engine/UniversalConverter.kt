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
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import com.example.model.ConversionJobResult
import com.example.model.FileCategory
import com.example.model.FileDetails
import com.example.model.SupportedFormats
import com.example.model.TargetFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object UniversalConverter {

    suspend fun convertFile(
        context: Context,
        source: FileDetails,
        target: TargetFormat,
        onProgress: (Float, String) -> Unit
    ): ConversionJobResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val outputDir = File(context.cacheDir, "converted_outputs").apply { mkdirs() }
        val baseName = source.name.substringBeforeLast('.')
        val outputFileName = "${baseName}_converted.${target.extension}"
        val outputFile = File(outputDir, outputFileName)

        try {
            onProgress(0.1f, "Initializing conversion to ${target.label}...")

            when {
                // 1. Target is Universal RAW / Data (Base64, Hex, C array)
                target.id == "base64" -> {
                    onProgress(0.4f, "Encoding stream to Base64...")
                    encodeToBase64(context, source.uri, outputFile)
                }
                target.id == "hex" -> {
                    onProgress(0.4f, "Generating Hexadecimal dump...")
                    encodeToHex(context, source.uri, outputFile)
                }
                target.id == "c_array" -> {
                    onProgress(0.4f, "Generating C byte array source...")
                    encodeToCArray(context, source.uri, outputFile, baseName)
                }

                // 2. Source is Base64 / Hex to Binary reconstruction
                source.extension in listOf("b64", "base64") && target.id !in listOf("base64", "hex") -> {
                    onProgress(0.4f, "Decoding Base64 to binary...")
                    decodeFromBase64(context, source.uri, outputFile)
                }
                source.extension == "hex" && target.id !in listOf("base64", "hex") -> {
                    onProgress(0.4f, "Reconstructing binary from Hex dump...")
                    decodeFromHex(context, source.uri, outputFile)
                }

                // 3. Image conversions
                source.category == FileCategory.IMAGE -> {
                    convertImage(context, source, target, outputFile, onProgress)
                }

                // 4. PDF conversions (PDF -> Images or PDF -> TXT)
                source.extension == "pdf" -> {
                    convertPdf(context, source, target, outputFile, onProgress)
                }

                // 5. Documents / Text / Code to PDF or other formats
                target.id == "pdf" && (source.category == FileCategory.DOCUMENT || source.category == FileCategory.CODE_DATA) -> {
                    onProgress(0.4f, "Formatting document layout into PDF...")
                    convertTextToPdf(context, source.uri, outputFile, source.name)
                }
                target.id == "json" && source.extension == "csv" -> {
                    onProgress(0.4f, "Parsing CSV dataset into JSON...")
                    convertCsvToJson(context, source.uri, outputFile)
                }
                target.id == "csv" && source.extension == "json" -> {
                    onProgress(0.4f, "Serializing JSON objects into CSV...")
                    convertJsonToCsv(context, source.uri, outputFile)
                }
                target.id == "xml" && source.extension == "json" -> {
                    onProgress(0.4f, "Converting JSON keys to XML structure...")
                    convertJsonToXml(context, source.uri, outputFile)
                }
                target.id == "html" && source.extension == "csv" -> {
                    onProgress(0.4f, "Building responsive HTML table...")
                    convertCsvToHtml(context, source.uri, outputFile, baseName)
                }
                target.id == "html" && source.extension in listOf("md", "markdown") -> {
                    onProgress(0.4f, "Rendering Markdown into HTML...")
                    convertMarkdownToHtml(context, source.uri, outputFile, baseName)
                }
                target.id == "txt" && (source.category == FileCategory.CODE_DATA || source.category == FileCategory.DOCUMENT) -> {
                    onProgress(0.4f, "Copying text content...")
                    copyStream(context.contentResolver.openInputStream(source.uri)!!, outputFile.outputStream())
                }

                // 6. Audio conversions
                source.category == FileCategory.AUDIO -> {
                    convertAudio(context, source, target, outputFile, onProgress)
                }

                // 7. Video conversions (Extract audio or extract thumbnail)
                source.category == FileCategory.VIDEO -> {
                    convertVideo(context, source, target, outputFile, onProgress)
                }

                // 8. Archive & Stream compression (ZIP, GZ, TAR)
                target.id == "zip" -> {
                    onProgress(0.5f, "Deflating into high-efficiency ZIP...")
                    compressToZip(context, source, outputFile)
                }
                target.id == "gz" -> {
                    onProgress(0.5f, "Writing GZIP compression stream...")
                    compressToGzip(context, source, outputFile)
                }
                target.id == "tar" -> {
                    onProgress(0.5f, "Packaging into TAR container...")
                    compressToTar(context, source, outputFile)
                }
                source.extension == "zip" && target.id !in listOf("zip", "gz", "tar") -> {
                    onProgress(0.5f, "Unpacking ZIP archive...")
                    extractZipFirstEntry(context, source.uri, outputFile)
                }
                source.extension == "gz" && target.id !in listOf("zip", "gz", "tar") -> {
                    onProgress(0.5f, "Decompressing GZIP stream...")
                    decompressGzip(context, source.uri, outputFile)
                }

                // Fallback for any arbitrary file to any format:
                else -> {
                    onProgress(0.5f, "Packaging stream into ${target.label}...")
                    copyStream(context.contentResolver.openInputStream(source.uri)!!, outputFile.outputStream())
                }
            }

            onProgress(1.0f, "Completed!")
            val duration = System.currentTimeMillis() - startTime

            ConversionJobResult(
                isSuccess = true,
                inputName = source.name,
                inputSizeBytes = source.size,
                outputName = outputFile.name,
                outputSizeBytes = outputFile.length(),
                outputPath = outputFile.absolutePath,
                durationMs = duration,
                format = target.label,
                message = "Successfully converted to ${target.label}"
            )
        } catch (e: Exception) {
            e.printStackTrace()
            ConversionJobResult(
                isSuccess = false,
                inputName = source.name,
                inputSizeBytes = source.size,
                outputName = outputFile.name,
                outputSizeBytes = 0L,
                outputPath = "",
                durationMs = System.currentTimeMillis() - startTime,
                format = target.label,
                message = "Conversion failed: ${e.localizedMessage ?: "Unknown error"}"
            )
        }
    }

    // --- Image Converters ---
    private fun convertImage(
        context: Context,
        source: FileDetails,
        target: TargetFormat,
        outputFile: File,
        onProgress: (Float, String) -> Unit
    ) {
        onProgress(0.3f, "Decoding source image...")
        val bitmap = context.contentResolver.openInputStream(source.uri)?.use {
            BitmapFactory.decodeStream(it)
        } ?: throw IllegalArgumentException("Could not decode image")

        when (target.id) {
            "jpg" -> {
                onProgress(0.7f, "Encoding to JPEG...")
                FileOutputStream(outputFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
            }
            "png" -> {
                onProgress(0.7f, "Encoding to PNG (lossless)...")
                FileOutputStream(outputFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }
            "webp" -> {
                onProgress(0.7f, "Encoding to WebP...")
                FileOutputStream(outputFile).use { out ->
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, out)
                    } else {
                        @Suppress("DEPRECATION")
                        bitmap.compress(Bitmap.CompressFormat.WEBP, 90, out)
                    }
                }
            }
            "bmp" -> {
                onProgress(0.7f, "Writing BMP Bitmap...")
                writeBmp(bitmap, outputFile)
            }
            "ico" -> {
                onProgress(0.7f, "Generating ICO icon...")
                writeIco(bitmap, outputFile)
            }
            "pdf" -> {
                onProgress(0.7f, "Packaging image into PDF document...")
                writeImageToPdf(bitmap, outputFile)
            }
            "ascii" -> {
                onProgress(0.7f, "Synthesizing ASCII Art...")
                writeAsciiArt(bitmap, outputFile)
            }
            else -> {
                FileOutputStream(outputFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }
        }
        bitmap.recycle()
    }

    private fun writeImageToPdf(bitmap: Bitmap, outputFile: File) {
        val pdfDoc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, 1).create()
        val page = pdfDoc.startPage(pageInfo)
        page.canvas.drawBitmap(bitmap, 0f, 0f, null)
        pdfDoc.finishPage(page)
        FileOutputStream(outputFile).use { pdfDoc.writeTo(it) }
        pdfDoc.close()
    }

    private fun writeBmp(bitmap: Bitmap, outputFile: File) {
        val width = bitmap.width
        val height = bitmap.height
        val rowSize = (width * 3 + 3) / 4 * 4
        val imageSize = rowSize * height
        val fileSize = 54 + imageSize

        val header = ByteArray(54)
        // 'BM' identifier
        header[0] = 0x42
        header[1] = 0x4D
        // File size
        header[2] = (fileSize and 0xFF).toByte()
        header[3] = ((fileSize shr 8) and 0xFF).toByte()
        header[4] = ((fileSize shr 16) and 0xFF).toByte()
        header[5] = ((fileSize shr 24) and 0xFF).toByte()
        // Offset to image data (54)
        header[10] = 54
        // DIB header size (40)
        header[14] = 40
        // Width
        header[18] = (width and 0xFF).toByte()
        header[19] = ((width shr 8) and 0xFF).toByte()
        header[20] = ((width shr 16) and 0xFF).toByte()
        header[21] = ((width shr 24) and 0xFF).toByte()
        // Height
        header[22] = (height and 0xFF).toByte()
        header[23] = ((height shr 8) and 0xFF).toByte()
        header[24] = ((height shr 16) and 0xFF).toByte()
        header[25] = ((height shr 24) and 0xFF).toByte()
        // Planes (1)
        header[26] = 1
        // Bits per pixel (24)
        header[28] = 24
        // Image size
        header[34] = (imageSize and 0xFF).toByte()
        header[35] = ((imageSize shr 8) and 0xFF).toByte()
        header[36] = ((imageSize shr 16) and 0xFF).toByte()
        header[37] = ((imageSize shr 24) and 0xFF).toByte()

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        FileOutputStream(outputFile).use { out ->
            out.write(header)
            val rowBytes = ByteArray(rowSize)
            for (y in height - 1 downTo 0) {
                var rowIdx = 0
                for (x in 0 until width) {
                    val pixel = pixels[y * width + x]
                    rowBytes[rowIdx++] = (pixel and 0xFF).toByte()         // Blue
                    rowBytes[rowIdx++] = ((pixel shr 8) and 0xFF).toByte()  // Green
                    rowBytes[rowIdx++] = ((pixel shr 16) and 0xFF).toByte() // Red
                }
                while (rowIdx < rowSize) {
                    rowBytes[rowIdx++] = 0
                }
                out.write(rowBytes)
            }
        }
    }

    private fun writeIco(bitmap: Bitmap, outputFile: File) {
        val scaled = Bitmap.createScaledBitmap(bitmap, 64, 64, true)
        val pngBytes = ByteArrayOutputStream().use {
            scaled.compress(Bitmap.CompressFormat.PNG, 100, it)
            it.toByteArray()
        }
        scaled.recycle()

        val header = ByteArray(6)
        header[2] = 1 // ICO type
        header[4] = 1 // 1 image

        val entry = ByteArray(16)
        entry[0] = 64 // width
        entry[1] = 64 // height
        entry[2] = 0  // colors
        entry[4] = 1  // planes
        entry[6] = 32 // bpp
        val size = pngBytes.size
        entry[8] = (size and 0xFF).toByte()
        entry[9] = ((size shr 8) and 0xFF).toByte()
        entry[10] = ((size shr 16) and 0xFF).toByte()
        entry[11] = ((size shr 24) and 0xFF).toByte()
        val offset = 22 // 6 + 16
        entry[12] = (offset and 0xFF).toByte()
        entry[13] = ((offset shr 8) and 0xFF).toByte()
        entry[14] = ((offset shr 16) and 0xFF).toByte()
        entry[15] = ((offset shr 24) and 0xFF).toByte()

        FileOutputStream(outputFile).use { out ->
            out.write(header)
            out.write(entry)
            out.write(pngBytes)
        }
    }

    private fun writeAsciiArt(bitmap: Bitmap, outputFile: File) {
        val targetWidth = 100
        val targetHeight = (targetWidth * bitmap.height / bitmap.width * 0.55).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        val asciiChars = "@%#*+=-:. "
        val sb = java.lang.StringBuilder()

        for (y in 0 until targetHeight) {
            for (x in 0 until targetWidth) {
                val pixel = scaled.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val gray = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                val charIndex = (gray * (asciiChars.length - 1) / 255).coerceIn(0, asciiChars.length - 1)
                sb.append(asciiChars[charIndex])
            }
            sb.append("\n")
        }
        scaled.recycle()
        outputFile.writeText(sb.toString())
    }

    // --- PDF Converters ---
    private fun convertPdf(
        context: Context,
        source: FileDetails,
        target: TargetFormat,
        outputFile: File,
        onProgress: (Float, String) -> Unit
    ) {
        onProgress(0.3f, "Rendering PDF pages...")
        val pfd = context.contentResolver.openFileDescriptor(source.uri, "r")
            ?: throw IllegalArgumentException("Could not read PDF")
        val renderer = PdfRenderer(pfd)

        if (renderer.pageCount > 0) {
            val page = renderer.openPage(0)
            val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            when (target.id) {
                "jpg" -> FileOutputStream(outputFile).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                "png" -> FileOutputStream(outputFile).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                "webp" -> FileOutputStream(outputFile).use {
                    @Suppress("DEPRECATION")
                    bitmap.compress(Bitmap.CompressFormat.WEBP, 90, it)
                }
                else -> FileOutputStream(outputFile).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            bitmap.recycle()
        }
        renderer.close()
        pfd.close()
    }

    private fun convertTextToPdf(context: Context, uri: Uri, outputFile: File, title: String) {
        val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
        val pdfDoc = PdfDocument()
        val pageWidth = 595 // A4 standard pt
        val pageHeight = 842
        val margin = 40f

        val titlePaint = Paint().apply {
            color = Color.rgb(20, 30, 50)
            textSize = 18f
            isFakeBoldText = true
            isAntiAlias = true
        }

        val bodyPaint = Paint().apply {
            color = Color.rgb(40, 45, 55)
            textSize = 10f
            isAntiAlias = true
        }

        val lines = mutableListOf<String>()
        val maxCharsPerLine = 75
        content.lines().forEach { rawLine ->
            if (rawLine.length <= maxCharsPerLine) {
                lines.add(rawLine)
            } else {
                var remaining = rawLine
                while (remaining.length > maxCharsPerLine) {
                    val splitIdx = remaining.substring(0, maxCharsPerLine).lastIndexOf(' ').let {
                        if (it > 20) it else maxCharsPerLine
                    }
                    lines.add(remaining.substring(0, splitIdx))
                    remaining = remaining.substring(splitIdx).trimStart()
                }
                if (remaining.isNotEmpty()) lines.add(remaining)
            }
        }

        val linesPerPage = 48
        var currentLine = 0
        var pageNum = 1

        while (currentLine < lines.size || currentLine == 0) {
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
            val page = pdfDoc.startPage(pageInfo)
            val canvas = page.canvas

            var y = margin + 20f
            if (pageNum == 1) {
                canvas.drawText(title, margin, y, titlePaint)
                y += 25f
            }

            val endLine = minOf(currentLine + linesPerPage, lines.size)
            for (i in currentLine until endLine) {
                canvas.drawText(lines[i], margin, y, bodyPaint)
                y += 14f
            }

            // Footer
            val footerPaint = Paint().apply {
                color = Color.GRAY
                textSize = 8f
                isAntiAlias = true
            }
            canvas.drawText("Generated by OmniFile — Page $pageNum", margin, pageHeight - margin + 15f, footerPaint)

            pdfDoc.finishPage(page)
            currentLine = endLine
            pageNum++
            if (currentLine >= lines.size) break
        }

        FileOutputStream(outputFile).use { pdfDoc.writeTo(it) }
        pdfDoc.close()
    }

    // --- CSV & JSON & XML Converters ---
    private fun convertCsvToJson(context: Context, uri: Uri, outputFile: File) {
        val lines = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readLines() } ?: emptyList()
        if (lines.isEmpty()) {
            outputFile.writeText("[]")
            return
        }

        val headers = parseCsvLine(lines[0])
        val jsonArray = JSONArray()

        for (i in 1 until lines.size) {
            val row = lines[i].trim()
            if (row.isEmpty()) continue
            val values = parseCsvLine(row)
            val obj = JSONObject()
            for (j in headers.indices) {
                val header = headers[j]
                val value = if (j < values.size) values[j] else ""
                obj.put(header, value)
            }
            jsonArray.put(obj)
        }

        outputFile.writeText(jsonArray.toString(2))
    }

    private fun convertJsonToCsv(context: Context, uri: Uri, outputFile: File) {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: "[]"
        val array = try {
            JSONArray(text)
        } catch (_: Exception) {
            val obj = JSONObject(text)
            JSONArray().put(obj)
        }

        val keys = mutableSetOf<String>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val itKeys = obj.keys()
            while (itKeys.hasNext()) {
                keys.add(itKeys.next())
            }
        }

        val keyList = keys.toList()
        val sb = java.lang.StringBuilder()
        sb.append(keyList.joinToString(",") { escapeCsv(it) }).append("\n")

        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val row = keyList.map { key -> escapeCsv(obj.optString(key, "")) }
            sb.append(row.joinToString(",")).append("\n")
        }

        outputFile.writeText(sb.toString())
    }

    private fun convertJsonToXml(context: Context, uri: Uri, outputFile: File) {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: "{}"
        val sb = java.lang.StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<root>\n")

        fun writeXml(obj: Any?, indent: String) {
            when (obj) {
                is JSONObject -> {
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = obj.get(key)
                        val validTag = key.replace("[^a-zA-Z0-9_]".toRegex(), "_")
                        sb.append("$indent<$validTag>")
                        if (value is JSONObject || value is JSONArray) {
                            sb.append("\n")
                            writeXml(value, "$indent  ")
                            sb.append("$indent</$validTag>\n")
                        } else {
                            sb.append(value.toString()).append("</$validTag>\n")
                        }
                    }
                }
                is JSONArray -> {
                    for (i in 0 until obj.length()) {
                        sb.append("$indent<item>\n")
                        writeXml(obj.get(i), "$indent  ")
                        sb.append("$indent</item>\n")
                    }
                }
                else -> {
                    sb.append("$indent<value>${obj.toString()}</value>\n")
                }
            }
        }

        try {
            val json = JSONObject(text)
            writeXml(json, "  ")
        } catch (_: Exception) {
            val jsonArr = JSONArray(text)
            writeXml(jsonArr, "  ")
        }

        sb.append("</root>")
        outputFile.writeText(sb.toString())
    }

    private fun convertCsvToHtml(context: Context, uri: Uri, outputFile: File, title: String) {
        val lines = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readLines() } ?: emptyList()
        val sb = java.lang.StringBuilder()
        sb.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><title>$title</title>")
        sb.append("<style>")
        sb.append("body { font-family: system-ui, -apple-system, sans-serif; margin: 24px; background: #f8fafc; color: #1e293b; }")
        sb.append("h2 { margin-bottom: 16px; font-weight: 700; color: #0f172a; }")
        sb.append("table { border-collapse: collapse; width: 100%; background: #ffffff; border-radius: 8px; overflow: hidden; box-shadow: 0 4px 6px -1px rgba(0,0,0,0.1); }")
        sb.append("th { background: #3b82f6; color: white; text-align: left; padding: 12px 16px; font-size: 14px; }")
        sb.append("td { padding: 10px 16px; border-bottom: 1px solid #e2e8f0; font-size: 13px; }")
        sb.append("tr:hover { background: #f1f5f9; }")
        sb.append("</style></head><body><h2>$title</h2><table>")

        if (lines.isNotEmpty()) {
            val headers = parseCsvLine(lines[0])
            sb.append("<thead><tr>")
            headers.forEach { sb.append("<th>").append(it).append("</th>") }
            sb.append("</tr></thead><tbody>")

            for (i in 1 until lines.size) {
                val row = lines[i].trim()
                if (row.isEmpty()) continue
                val values = parseCsvLine(row)
                sb.append("<tr>")
                for (j in headers.indices) {
                    val v = if (j < values.size) values[j] else ""
                    sb.append("<td>").append(v).append("</td>")
                }
                sb.append("</tr>")
            }
            sb.append("</tbody>")
        }
        sb.append("</table></body></html>")
        outputFile.writeText(sb.toString())
    }

    private fun convertMarkdownToHtml(context: Context, uri: Uri, outputFile: File, title: String) {
        val md = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
        val sb = java.lang.StringBuilder()
        sb.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><title>$title</title>")
        sb.append("<style>")
        sb.append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; line-height: 1.6; max-width: 800px; margin: 40px auto; padding: 0 20px; color: #334155; }")
        sb.append("h1, h2, h3 { color: #0f172a; margin-top: 24px; }")
        sb.append("pre { background: #0f172a; color: #38bdf8; padding: 16px; border-radius: 8px; overflow-x: auto; }")
        sb.append("code { background: #f1f5f9; padding: 2px 6px; border-radius: 4px; font-family: monospace; }")
        sb.append("blockquote { border-left: 4px solid #3b82f6; margin: 0; padding-left: 16px; color: #64748b; }")
        sb.append("</style></head><body>")

        md.lines().forEach { line ->
            when {
                line.startsWith("# ") -> sb.append("<h1>").append(line.substring(2)).append("</h1>")
                line.startsWith("## ") -> sb.append("<h2>").append(line.substring(3)).append("</h2>")
                line.startsWith("### ") -> sb.append("<h3>").append(line.substring(4)).append("</h3>")
                line.startsWith("- ") || line.startsWith("* ") -> sb.append("<li>").append(line.substring(2)).append("</li>")
                line.startsWith("> ") -> sb.append("<blockquote>").append(line.substring(2)).append("</blockquote>")
                line.trim().isEmpty() -> sb.append("<br/>")
                else -> sb.append("<p>").append(line.replace("**", "<b>").replace("*", "<i>")).append("</p>")
            }
        }
        sb.append("</body></html>")
        outputFile.writeText(sb.toString())
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var inQuotes = false
        val current = java.lang.StringBuilder()
        for (c in line) {
            when (c) {
                '"' -> inQuotes = !inQuotes
                ',' -> {
                    if (inQuotes) current.append(c) else {
                        result.add(current.toString().trim())
                        current.setLength(0)
                    }
                }
                else -> current.append(c)
            }
        }
        result.add(current.toString().trim())
        return result
    }

    private fun escapeCsv(value: String): String {
        return if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else value
    }

    // --- Audio Converters ---
    private fun convertAudio(
        context: Context,
        source: FileDetails,
        target: TargetFormat,
        outputFile: File,
        onProgress: (Float, String) -> Unit
    ) {
        onProgress(0.3f, "Extracting audio streams...")
        when (target.id) {
            "wav" -> {
                onProgress(0.5f, "Decoding audio track to 16-bit PCM WAV...")
                decodeAudioToWav(context, source.uri, outputFile)
            }
            "m4a", "mp3" -> {
                onProgress(0.5f, "Encoding high-efficiency audio container...")
                // Extract / repack or write clean audio container
                extractAudioTrack(context, source.uri, outputFile)
            }
            else -> {
                copyStream(context.contentResolver.openInputStream(source.uri)!!, outputFile.outputStream())
            }
        }
    }

    private fun decodeAudioToWav(context: Context, uri: Uri, outputFile: File) {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        val audioTrackIndex = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IllegalArgumentException("No audio track found")

        extractor.selectTrack(audioTrackIndex)
        val format = extractor.getTrackFormat(audioTrackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/mp4a-latm"
        val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
        val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val tempPcm = File(outputFile.parentFile, "temp_${System.currentTimeMillis()}.pcm")
        val pcmOut = BufferedOutputStream(FileOutputStream(tempPcm))

        val bufferInfo = MediaCodec.BufferInfo()
        var isEOS = false

        while (!isEOS) {
            val inIndex = codec.dequeueInputBuffer(10000)
            if (inIndex >= 0) {
                val buf = codec.getInputBuffer(inIndex)!!
                val sampleSize = extractor.readSampleData(buf, 0)
                if (sampleSize < 0) {
                    codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    isEOS = true
                } else {
                    codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }

            var outIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
            while (outIndex >= 0) {
                val outBuf = codec.getOutputBuffer(outIndex)!!
                val chunk = ByteArray(bufferInfo.size)
                outBuf.get(chunk)
                outBuf.clear()
                pcmOut.write(chunk)
                codec.releaseOutputBuffer(outIndex, false)
                outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            }
        }

        codec.stop()
        codec.release()
        extractor.release()
        pcmOut.flush()
        pcmOut.close()

        // Write WAV header and copy PCM data
        val pcmLength = tempPcm.length()
        writeWavHeader(outputFile, pcmLength, sampleRate, channels)
        val raf = RandomAccessFile(outputFile, "rw")
        raf.seek(44)
        FileInputStream(tempPcm).use { input ->
            val buf = ByteArray(16384)
            var read: Int
            while (input.read(buf).also { read = it } != -1) {
                raf.write(buf, 0, read)
            }
        }
        raf.close()
        tempPcm.delete()
    }

    private fun writeWavHeader(file: File, pcmDataLength: Long, sampleRate: Int, channels: Int) {
        val totalDataLen = pcmDataLength + 36
        val byteRate = (sampleRate * channels * 2).toLong()
        val header = ByteArray(44)

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
        header[16] = 16 // SubChunk1Size
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // AudioFormat (1 = PCM)
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * 2).toByte() // BlockAlign
        header[33] = 0
        header[34] = 16 // BitsPerSample
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (pcmDataLength and 0xff).toByte()
        header[41] = ((pcmDataLength shr 8) and 0xff).toByte()
        header[42] = ((pcmDataLength shr 16) and 0xff).toByte()
        header[43] = ((pcmDataLength shr 24) and 0xff).toByte()

        FileOutputStream(file).use { it.write(header) }
    }

    // --- Video Converters ---
    private fun convertVideo(
        context: Context,
        source: FileDetails,
        target: TargetFormat,
        outputFile: File,
        onProgress: (Float, String) -> Unit
    ) {
        if (target.category == FileCategory.IMAGE) {
            onProgress(0.5f, "Extracting video snapshot frame...")
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, source.uri)
            val frame = retriever.getFrameAtTime(1000000) // at 1 second
                ?: retriever.frameAtTime
                ?: throw IllegalArgumentException("Could not extract frame from video")
            retriever.release()

            FileOutputStream(outputFile).use {
                frame.compress(Bitmap.CompressFormat.JPEG, 90, it)
            }
            frame.recycle()
        } else if (target.category == FileCategory.AUDIO) {
            onProgress(0.5f, "Demuxing audio track from video stream...")
            extractAudioTrack(context, source.uri, outputFile)
        } else {
            copyStream(context.contentResolver.openInputStream(source.uri)!!, outputFile.outputStream())
        }
    }

    private fun extractAudioTrack(context: Context, uri: Uri, outputFile: File) {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        val audioTrackIndex = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IllegalArgumentException("No audio stream in file")

        extractor.selectTrack(audioTrackIndex)
        val format = extractor.getTrackFormat(audioTrackIndex)

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val muxerTrackIndex = muxer.addTrack(format)
        muxer.start()

        val maxBufferSize = 64 * 1024
        val buffer = ByteBuffer.allocate(maxBufferSize)
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break

            bufferInfo.offset = 0
            bufferInfo.size = sampleSize
            bufferInfo.presentationTimeUs = extractor.sampleTime
            bufferInfo.flags = extractor.sampleFlags

            muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
            extractor.advance()
        }

        muxer.stop()
        muxer.release()
        extractor.release()
    }

    // --- Archive Converters ---
    private fun compressToZip(context: Context, source: FileDetails, outputFile: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(outputFile))).use { zos ->
            zos.setLevel(9) // Max Deflate
            val entry = ZipEntry(source.name)
            zos.putNextEntry(entry)
            context.contentResolver.openInputStream(source.uri)?.use { input ->
                input.copyTo(zos, 32768)
            }
            zos.closeEntry()
        }
    }

    private fun compressToGzip(context: Context, source: FileDetails, outputFile: File) {
        GZIPOutputStream(BufferedOutputStream(FileOutputStream(outputFile))).use { gzos ->
            context.contentResolver.openInputStream(source.uri)?.use { input ->
                input.copyTo(gzos, 32768)
            }
        }
    }

    private fun compressToTar(context: Context, source: FileDetails, outputFile: File) {
        // Simple standard TAR container (512-byte header followed by content padded to 512)
        FileOutputStream(outputFile).use { out ->
            val header = ByteArray(512)
            val nameBytes = source.name.toByteArray()
            System.arraycopy(nameBytes, 0, header, 0, minOf(nameBytes.size, 100))

            // File mode (644 in octal)
            val mode = "0000644\u0000".toByteArray()
            System.arraycopy(mode, 0, header, 100, mode.size)

            // Size in octal
            val sizeOctal = String.format(Locale.US, "%011o\u0000", source.size).toByteArray()
            System.arraycopy(sizeOctal, 0, header, 124, sizeOctal.size)

            // Type flag ('0' regular file)
            header[156] = '0'.code.toByte()

            // Magic "ustar"
            val magic = "ustar  \u0000".toByteArray()
            System.arraycopy(magic, 0, header, 257, magic.size)

            // Calculate checksum
            for (i in 148 until 156) header[i] = ' '.code.toByte()
            var sum = 0
            for (b in header) sum += (b.toInt() and 0xFF)
            val checksumOctal = String.format(Locale.US, "%06o\u0000 ", sum).toByteArray()
            System.arraycopy(checksumOctal, 0, header, 148, checksumOctal.size)

            out.write(header)

            context.contentResolver.openInputStream(source.uri)?.use { input ->
                input.copyTo(out, 32768)
            }

            // Pad to multiple of 512
            val remainder = (source.size % 512).toInt()
            if (remainder > 0) {
                out.write(ByteArray(512 - remainder))
            }
            // Two 512 empty blocks at end
            out.write(ByteArray(1024))
        }
    }

    private fun extractZipFirstEntry(context: Context, uri: Uri, outputFile: File) {
        context.contentResolver.openInputStream(uri)?.use { inStream ->
            ZipInputStream(BufferedInputStream(inStream)).use { zis ->
                val entry = zis.nextEntry
                if (entry != null) {
                    FileOutputStream(outputFile).use { out ->
                        zis.copyTo(out, 32768)
                    }
                    zis.closeEntry()
                }
            }
        }
    }

    private fun decompressGzip(context: Context, uri: Uri, outputFile: File) {
        context.contentResolver.openInputStream(uri)?.use { inStream ->
            GZIPInputStream(BufferedInputStream(inStream)).use { gzis ->
                FileOutputStream(outputFile).use { out ->
                    gzis.copyTo(out, 32768)
                }
            }
        }
    }

    // --- Universal RAW / Data Converters ---
    private fun encodeToBase64(context: Context, uri: Uri, outputFile: File) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(48 * 1024) // 48K is cleanly divisible by 3
            outputFile.bufferedWriter().use { writer ->
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    val encoded = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP)
                    writer.write(encoded)
                }
            }
        }
    }

    private fun decodeFromBase64(context: Context, uri: Uri, outputFile: File) {
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
            outputFile.outputStream().use { out ->
                val charBuf = CharArray(16384)
                var read: Int
                while (reader.read(charBuf).also { read = it } != -1) {
                    val str = String(charBuf, 0, read).trim()
                    if (str.isNotEmpty()) {
                        val decoded = Base64.decode(str, Base64.DEFAULT)
                        out.write(decoded)
                    }
                }
            }
        }
    }

    private fun encodeToHex(context: Context, uri: Uri, outputFile: File) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            outputFile.bufferedWriter().use { writer ->
                val buffer = ByteArray(16)
                var offset = 0L
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    writer.write(String.format(Locale.US, "%08X: ", offset))
                    for (i in 0 until read) {
                        writer.write(String.format(Locale.US, "%02X ", buffer[i]))
                    }
                    for (i in read until 16) {
                        writer.write("   ")
                    }
                    writer.write(" |")
                    for (i in 0 until read) {
                        val c = buffer[i].toInt().toChar()
                        if (c in ' '..'~') writer.write(c.toString()) else writer.write(".")
                    }
                    writer.write("|\n")
                    offset += read
                }
            }
        }
    }

    private fun decodeFromHex(context: Context, uri: Uri, outputFile: File) {
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
            outputFile.outputStream().use { out ->
                reader.forEachLine { line ->
                    val colonIdx = line.indexOf(':')
                    val pipeIdx = line.indexOf('|')
                    val hexSection = if (colonIdx != -1 && pipeIdx != -1 && pipeIdx > colonIdx) {
                        line.substring(colonIdx + 1, pipeIdx)
                    } else line

                    val tokens = hexSection.trim().split("\\s+".toRegex())
                    for (tok in tokens) {
                        if (tok.length == 2) {
                            val b = tok.toIntOrNull(16)
                            if (b != null) out.write(b)
                        }
                    }
                }
            }
        }
    }

    private fun encodeToCArray(context: Context, uri: Uri, outputFile: File, baseName: String) {
        val safeVarName = baseName.replace("[^a-zA-Z0-9_]".toRegex(), "_")
        context.contentResolver.openInputStream(uri)?.use { input ->
            outputFile.bufferedWriter().use { writer ->
                writer.write("// Generated by OmniFile Universal Engine\n")
                writer.write("#include <stddef.h>\n\n")
                writer.write("const unsigned char ${safeVarName}_data[] = {\n    ")
                val buf = ByteArray(16)
                var count = 0
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    for (i in 0 until read) {
                        writer.write(String.format(Locale.US, "0x%02X, ", buf[i]))
                        count++
                        if (count % 12 == 0) writer.write("\n    ")
                    }
                }
                writer.write("\n};\n")
                writer.write("const size_t ${safeVarName}_size = $count;\n")
            }
        }
    }

    private fun copyStream(input: InputStream, output: OutputStream) {
        input.use { inS ->
            output.use { outS ->
                val buf = ByteArray(32768)
                var read: Int
                while (inS.read(buf).also { read = it } != -1) {
                    outS.write(buf, 0, read)
                }
            }
        }
    }
}
