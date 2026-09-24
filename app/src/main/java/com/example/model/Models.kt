package com.example.model

import android.net.Uri

enum class FileCategory(val title: String, val iconName: String) {
    IMAGE("Images", "Image"),
    DOCUMENT("Documents & PDF", "Description"),
    AUDIO("Audio & Music", "Audiotrack"),
    VIDEO("Video & Media", "VideoFile"),
    ARCHIVE("Archives & Bundles", "Archive"),
    CODE_DATA("Code & Data", "DataObject"),
    RAW_BINARY("Universal & Raw", "SettingsEthernet")
}

data class MetadataConfig(
    val preserveExif: Boolean = true,
    val preserveGps: Boolean = true,
    val preserveTimestamps: Boolean = true,
    val stripAllForPrivacy: Boolean = false,
    val customAuthor: String = "",
    val customCopyright: String = ""
)

data class FileDetails(
    val uri: Uri,
    val name: String,
    val size: Long,
    val mimeType: String,
    val extension: String,
    val category: FileCategory,
    val dimensions: String? = null,
    val durationText: String? = null,
    val lineCount: Int? = null,
    val hexSnippet: String? = null,
    val textSnippet: String? = null,
    val isSample: Boolean = false,
    val timestamp: Long? = null,
    val exifMakeModel: String? = null,
    val exifDate: String? = null,
    val hasGps: Boolean = false
) {
    val formattedSize: String
        get() = formatBytes(size)

    companion object {
        fun formatBytes(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
            val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
            return if (digitGroups == 0) {
                "$bytes B"
            } else {
                String.format(java.util.Locale.US, "%.2f %s", value, units[digitGroups])
            }
        }
    }
}

enum class CompressionMode {
    EXACT_TARGET_SIZE,
    PERCENTAGE_REDUCTION,
    QUALITY_PRESET
}

enum class QualityProfile(val label: String, val description: String) {
    ULTRA_COMPACT("Ultra Compact", "Maximum size reduction, suitable for low-bandwidth"),
    BALANCED("Balanced", "Great balance between clarity and file size reduction"),
    HIGH_FIDELITY("High Fidelity", "Preserves sharp details while shedding excess data"),
    EXTREME_DEFLATE("Extreme Deflate", "Maximum algorithmic entropy compression (L9)")
}

data class CompressionConfig(
    val mode: CompressionMode = CompressionMode.EXACT_TARGET_SIZE,
    val targetSizeBytes: Long = 500 * 1024, // 500 KB default
    val percentageReduction: Int = 50, // 50% default
    val qualityProfile: QualityProfile = QualityProfile.BALANCED,
    val scaleResolution: Float = 1.0f,
    val preserveAudioPitch: Boolean = true,
    val splitIfExceeds: Boolean = true,
    val metadataConfig: MetadataConfig = MetadataConfig()
)

data class TargetFormat(
    val id: String,
    val extension: String,
    val label: String,
    val mimeType: String,
    val category: FileCategory,
    val description: String,
    val isRecommendedFor: (FileCategory, String) -> Boolean = { _, _ -> false }
)

object SupportedFormats {
    // Images
    val JPG = TargetFormat("jpg", "jpg", "JPEG Image", "image/jpeg", FileCategory.IMAGE, "Universal lossy photo format", { cat, _ -> cat == FileCategory.IMAGE })
    val PNG = TargetFormat("png", "png", "PNG Image", "image/png", FileCategory.IMAGE, "Lossless graphics with transparency", { cat, _ -> cat == FileCategory.IMAGE })
    val WEBP = TargetFormat("webp", "webp", "WebP Image", "image/webp", FileCategory.IMAGE, "Ultra modern web-optimized format", { cat, _ -> cat == FileCategory.IMAGE })
    val BMP = TargetFormat("bmp", "bmp", "BMP Bitmap", "image/bmp", FileCategory.IMAGE, "Raw uncompressed raster bitmap")
    val ICO = TargetFormat("ico", "ico", "ICO Icon", "image/x-icon", FileCategory.IMAGE, "Favicon and application icon")
    val ASCII_ART = TargetFormat("ascii", "txt", "ASCII Art Text", "text/plain", FileCategory.IMAGE, "Visual rendered using monochrome characters")

    // Documents & PDF
    val PDF = TargetFormat("pdf", "pdf", "PDF Document", "application/pdf", FileCategory.DOCUMENT, "Universal formatted vector/raster document", { cat, _ -> cat == FileCategory.IMAGE || cat == FileCategory.DOCUMENT || cat == FileCategory.CODE_DATA })
    val TXT = TargetFormat("txt", "txt", "Plain Text", "text/plain", FileCategory.DOCUMENT, "Standard UTF-8 plain text file", { cat, _ -> cat == FileCategory.DOCUMENT || cat == FileCategory.CODE_DATA })
    val HTML = TargetFormat("html", "html", "HTML Document", "text/html", FileCategory.DOCUMENT, "Styled web document / responsive table")
    val MD = TargetFormat("md", "md", "Markdown", "text/markdown", FileCategory.DOCUMENT, "Lightweight markup language")

    // Audio
    val WAV = TargetFormat("wav", "wav", "WAV Audio (PCM)", "audio/wav", FileCategory.AUDIO, "Lossless studio PCM uncompressed audio", { cat, _ -> cat == FileCategory.AUDIO || cat == FileCategory.VIDEO })
    val M4A_AAC = TargetFormat("m4a", "m4a", "M4A (AAC Audio)", "audio/mp4", FileCategory.AUDIO, "High-efficiency modern compressed audio", { cat, _ -> cat == FileCategory.AUDIO || cat == FileCategory.VIDEO })
    val MP3_CONTAINER = TargetFormat("mp3", "mp3", "MP3 Audio Container", "audio/mpeg", FileCategory.AUDIO, "Ubiquitous audio format", { cat, _ -> cat == FileCategory.AUDIO })

    // Archives & Compression Containers
    val ZIP = TargetFormat("zip", "zip", "ZIP Archive", "application/zip", FileCategory.ARCHIVE, "Standard Deflate multi-file archive", { _, _ -> true })
    val GZ = TargetFormat("gz", "gz", "GZIP Stream (.gz)", "application/gzip", FileCategory.ARCHIVE, "Single-file high-ratio gzip stream", { _, _ -> true })
    val TAR = TargetFormat("tar", "tar", "TAR Tape Archive", "application/x-tar", FileCategory.ARCHIVE, "Unix archive container")
    val CHUNKS = TargetFormat("chunks", "zip", "Target Chunk Splitter", "application/octet-stream", FileCategory.ARCHIVE, "Split into exact size parts for transfer")

    // Code & Data
    val JSON = TargetFormat("json", "json", "JSON Structure", "application/json", FileCategory.CODE_DATA, "Standard serialized object format", { _, ext -> ext == "csv" || ext == "xml" || ext == "txt" })
    val CSV = TargetFormat("csv", "csv", "CSV Spreadsheet", "text/csv", FileCategory.CODE_DATA, "Comma-separated tabular rows", { _, ext -> ext == "json" || ext == "txt" })
    val XML = TargetFormat("xml", "xml", "XML Markup", "application/xml", FileCategory.CODE_DATA, "Extensible markup language")
    val SQL_INSERTS = TargetFormat("sql", "sql", "SQL Insert Script", "text/plain", FileCategory.CODE_DATA, "Relational database insert statements")

    // Universal / Raw Binary
    val BASE64 = TargetFormat("base64", "b64", "Base64 Text", "text/plain", FileCategory.RAW_BINARY, "ASCII text-encoded binary payload", { _, _ -> true })
    val HEX_DUMP = TargetFormat("hex", "hex", "Hexadecimal Dump", "text/plain", FileCategory.RAW_BINARY, "Raw hex byte view (00..FF)", { _, _ -> true })
    val C_BYTE_ARRAY = TargetFormat("c_array", "c", "C / Byte Array Source", "text/plain", FileCategory.RAW_BINARY, "Embedded byte array code definition", { _, _ -> true })

    val ALL_TARGETS: List<TargetFormat> = listOf(
        JPG, PNG, WEBP, BMP, ICO, ASCII_ART,
        PDF, TXT, HTML, MD,
        WAV, M4A_AAC, MP3_CONTAINER,
        ZIP, GZ, TAR, CHUNKS,
        JSON, CSV, XML, SQL_INSERTS,
        BASE64, HEX_DUMP, C_BYTE_ARRAY
    )

    fun getRecommendedFor(category: FileCategory, extension: String): List<TargetFormat> {
        val lowerExt = extension.lowercase()
        return ALL_TARGETS.filter { it.isRecommendedFor(category, lowerExt) }
    }
}

data class ConversionJobResult(
    val isSuccess: Boolean,
    val inputName: String,
    val inputSizeBytes: Long,
    val outputName: String,
    val outputSizeBytes: Long,
    val outputPath: String,
    val savedRelativePath: String = "Downloads/OmniFile",
    val durationMs: Long,
    val format: String,
    val message: String,
    val metadataPreservedSummary: String? = null
) {
    val speedMegaBytesPerSec: Double
        get() = if (durationMs > 0) {
            val mb = inputSizeBytes.toDouble() / (1024.0 * 1024.0)
            val sec = durationMs.toDouble() / 1000.0
            mb / sec
        } else 0.0

    val savingsPercent: Int
        get() = if (inputSizeBytes > 0) {
            val diff = inputSizeBytes - outputSizeBytes
            ((diff.toDouble() / inputSizeBytes.toDouble()) * 100).toInt()
        } else 0
}

data class BatchJobSummary(
    val results: List<ConversionJobResult>,
    val totalDurationMs: Long,
    val mode: String // "CONVERT" or "COMPRESS"
) {
    val totalInputBytes: Long get() = results.sumOf { it.inputSizeBytes }
    val totalOutputBytes: Long get() = results.sumOf { it.outputSizeBytes }
    val totalSavedBytes: Long get() = (totalInputBytes - totalOutputBytes).coerceAtLeast(0L)
    val successCount: Int get() = results.count { it.isSuccess }
    val failureCount: Int get() = results.count { !it.isSuccess }
    val overallSavingsPercent: Int get() = if (totalInputBytes > 0) {
        ((totalSavedBytes.toDouble() / totalInputBytes.toDouble()) * 100).toInt()
    } else 0
}

