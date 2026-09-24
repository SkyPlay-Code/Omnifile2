package com.example.engine

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.example.model.FileCategory
import com.example.model.FileDetails
import java.io.File
import java.io.InputStream
import java.util.Locale

object FileInspector {

    fun inspectUri(context: Context, uri: Uri): FileDetails {
        var fileName = "unknown_file"
        var fileSize = 0L

        // Query content resolver for metadata
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex != -1) {
                        fileName = cursor.getString(nameIndex) ?: fileName
                    }
                    if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                        fileSize = cursor.getLong(sizeIndex)
                    }
                }
            }
        } catch (_: Exception) {
        }

        // If file scheme
        if (uri.scheme == "file") {
            uri.path?.let { path ->
                val f = File(path)
                if (f.exists()) {
                    fileName = f.name
                    fileSize = f.length()
                }
            }
        }

        // If size is still 0, determine by reading stream length safely
        if (fileSize <= 0L) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    var total = 0L
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (stream.read(buffer).also { read = it } != -1) {
                        total += read
                    }
                    fileSize = total
                }
            } catch (_: Exception) {
            }
        }

        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        var mimeType = context.contentResolver.getType(uri)
        if (mimeType.isNullOrEmpty()) {
            mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        }

        val category = determineCategory(mimeType, extension)

        var dimensions: String? = null
        var durationText: String? = null
        var lineCount: Int? = null

        // Inspect specialized dimensions / duration
        if (category == FileCategory.IMAGE) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(stream, null, options)
                    if (options.outWidth > 0 && options.outHeight > 0) {
                        dimensions = "${options.outWidth} × ${options.outHeight} px"
                    }
                }
            } catch (_: Exception) {}
        } else if (category == FileCategory.AUDIO || category == FileCategory.VIDEO) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, uri)
                val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                durMs?.let {
                    val sec = (it / 1000) % 60
                    val min = (it / (1000 * 60)) % 60
                    durationText = String.format(Locale.US, "%02d:%02d", min, sec)
                }
                if (category == FileCategory.VIDEO) {
                    val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    if (width != null && height != null) {
                        dimensions = "$width × $height px"
                    }
                }
                retriever.release()
            } catch (_: Exception) {}
        }

        // Snippets: Hex snippet & Text snippet
        var hexSnippet: String? = null
        var textSnippet: String? = null

        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val sampleBuffer = ByteArray(64)
                val read = stream.read(sampleBuffer)
                if (read > 0) {
                    hexSnippet = sampleBuffer.take(read).joinToString(" ") { b ->
                        String.format("%02X", b)
                    }
                }
            }
        } catch (_: Exception) {}

        if (category == FileCategory.DOCUMENT || category == FileCategory.CODE_DATA || extension in listOf("txt", "json", "csv", "xml", "html", "md", "kt", "java", "py", "js", "log")) {
            try {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                    val lines = mutableListOf<String>()
                    var count = 0
                    reader.forEachLine { line ->
                        count++
                        if (lines.size < 15) {
                            lines.add(line)
                        }
                    }
                    lineCount = count
                    textSnippet = lines.joinToString("\n").take(800)
                }
            } catch (_: Exception) {}
        }

        return FileDetails(
            uri = uri,
            name = fileName,
            size = fileSize,
            mimeType = mimeType,
            extension = extension,
            category = category,
            dimensions = dimensions,
            durationText = durationText,
            lineCount = lineCount,
            hexSnippet = hexSnippet,
            textSnippet = textSnippet
        )
    }

    fun inspectFile(file: File): FileDetails {
        val uri = Uri.fromFile(file)
        val ext = file.extension.lowercase(Locale.ROOT)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val category = determineCategory(mime, ext)

        var dimensions: String? = null
        if (category == FileCategory.IMAGE) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            if (options.outWidth > 0 && options.outHeight > 0) {
                dimensions = "${options.outWidth} × ${options.outHeight} px"
            }
        }

        var hexSnippet: String? = null
        try {
            val buf = ByteArray(minOf(64, file.length().toInt().coerceAtLeast(1)))
            file.inputStream().use { it.read(buf) }
            hexSnippet = buf.joinToString(" ") { String.format("%02X", it) }
        } catch (_: Exception) {}

        return FileDetails(
            uri = uri,
            name = file.name,
            size = file.length(),
            mimeType = mime,
            extension = ext,
            category = category,
            dimensions = dimensions,
            hexSnippet = hexSnippet
        )
    }

    private fun determineCategory(mimeType: String, extension: String): FileCategory {
        val ext = extension.lowercase(Locale.ROOT)
        return when {
            mimeType.startsWith("image/") || ext in listOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "ico", "svg", "tiff") -> FileCategory.IMAGE
            mimeType == "application/pdf" || ext in listOf("pdf", "doc", "docx", "txt", "rtf", "odt", "epub") -> FileCategory.DOCUMENT
            mimeType.startsWith("audio/") || ext in listOf("mp3", "wav", "m4a", "aac", "ogg", "flac", "wma", "opus") -> FileCategory.AUDIO
            mimeType.startsWith("video/") || ext in listOf("mp4", "mkv", "webm", "avi", "mov", "3gp") -> FileCategory.VIDEO
            mimeType.contains("zip") || mimeType.contains("tar") || mimeType.contains("gzip") || ext in listOf("zip", "gz", "tar", "7z", "rar", "bz2") -> FileCategory.ARCHIVE
            mimeType.contains("json") || mimeType.contains("xml") || mimeType.contains("csv") || ext in listOf("json", "csv", "xml", "html", "md", "sql", "kt", "java", "py", "js", "yaml", "yml", "tsv", "log") -> FileCategory.CODE_DATA
            else -> FileCategory.RAW_BINARY
        }
    }
}
