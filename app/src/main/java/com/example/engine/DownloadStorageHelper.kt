package com.example.engine

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

data class SavedFileInfo(
    val fileName: String,
    val relativeFolder: String,
    val absolutePath: String,
    val contentUri: Uri?,
    val sizeBytes: Long
)

object DownloadStorageHelper {

    private const val SUBFOLDER = "OmniFile"

    /**
     * Saves ONLY the final output file to the public Downloads/OmniFile directory.
     * Uses MediaStore exclusively on API 29+ and direct public directory on older APIs
     * to prevent creating duplicate files or saving inputs.
     */
    suspend fun saveToDownloads(
        context: Context,
        outputFile: File,
        targetFileName: String,
        mimeType: String
    ): SavedFileInfo = withContext(Dispatchers.IO) {
        var savedUri: Uri? = null
        var finalAbsolutePath = ""
        var finalSize = outputFile.length()
        var finalName = targetFileName

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ (API 29+): Use MediaStore exclusively
            try {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, targetFileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val collectionUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val itemUri = resolver.insert(collectionUri, values)

                if (itemUri != null) {
                    resolver.openOutputStream(itemUri)?.use { outStream ->
                        outputFile.inputStream().use { inStream ->
                            inStream.copyTo(outStream, 32768)
                        }
                    }

                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(itemUri, values, null, null)
                    savedUri = itemUri

                    // Query actual display name and size if MediaStore renamed to avoid collision
                    resolver.query(itemUri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                            val sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                            if (nameIdx != -1) finalName = cursor.getString(nameIdx)
                            if (sizeIdx != -1) finalSize = cursor.getLong(sizeIdx)
                        }
                    }
                }
            } catch (_: Exception) {}

            // Physical path reference for file provider fallback
            finalAbsolutePath = File(
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SUBFOLDER),
                finalName
            ).absolutePath
        } else {
            // Android 9 and below: Direct write to public Downloads directory
            try {
                val publicDownloadsDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    SUBFOLDER
                ).apply { mkdirs() }

                var candidate = File(publicDownloadsDir, targetFileName)
                val base = targetFileName.substringBeforeLast('.')
                val ext = targetFileName.substringAfterLast('.', "")
                var c = 1
                while (candidate.exists()) {
                    val n = if (ext.isNotEmpty()) "${base}_$c.$ext" else "${base}_$c"
                    candidate = File(publicDownloadsDir, n)
                    c++
                }

                outputFile.inputStream().use { input ->
                    FileOutputStream(candidate).use { output ->
                        input.copyTo(output, 32768)
                    }
                }

                finalName = candidate.name
                finalAbsolutePath = candidate.absolutePath
                finalSize = candidate.length()

                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(finalAbsolutePath),
                    arrayOf(mimeType),
                    null
                )

                savedUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    candidate
                )
            } catch (_: Exception) {}
        }

        // Clean up temporary cache file so it does not waste internal storage
        try {
            if (outputFile.exists() && outputFile.absolutePath.contains(context.cacheDir.absolutePath)) {
                outputFile.delete()
            }
        } catch (_: Exception) {}

        if (savedUri == null && finalAbsolutePath.isNotEmpty()) {
            val f = File(finalAbsolutePath)
            if (f.exists()) {
                try {
                    savedUri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        f
                    )
                } catch (_: Exception) {
                    savedUri = Uri.fromFile(f)
                }
            }
        }

        SavedFileInfo(
            fileName = finalName,
            relativeFolder = "Downloads/$SUBFOLDER",
            absolutePath = finalAbsolutePath,
            contentUri = savedUri,
            sizeBytes = finalSize
        )
    }

    /**
     * Opens the public Downloads app / folder directly.
     */
    fun openDownloadsFolder(context: Context) {
        try {
            val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    val publicDir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        SUBFOLDER
                    )
                    setDataAndType(Uri.fromFile(publicDir), "*/*")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {}
        }
    }
}
