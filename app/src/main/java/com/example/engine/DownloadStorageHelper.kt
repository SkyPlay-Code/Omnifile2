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
     * Copies a file to the public Downloads/OmniFile directory using MediaStore on API 29+
     * and direct public external directory with MediaScanner on all APIs.
     */
    suspend fun saveToDownloads(
        context: Context,
        sourceFile: File,
        targetFileName: String,
        mimeType: String
    ): SavedFileInfo = withContext(Dispatchers.IO) {
        var savedUri: Uri? = null
        var finalFile: File? = null

        // 1. Direct file write to public Downloads directory
        try {
            val publicDownloadsDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                SUBFOLDER
            )
            if (!publicDownloadsDir.exists()) {
                publicDownloadsDir.mkdirs()
            }
            finalFile = File(publicDownloadsDir, targetFileName)

            // Avoid collisions by renaming if already exists
            var counter = 1
            val baseName = targetFileName.substringBeforeLast('.')
            val ext = targetFileName.substringAfterLast('.', "")
            while (finalFile!!.exists()) {
                val newName = if (ext.isNotEmpty()) "${baseName}_$counter.$ext" else "${baseName}_$counter"
                finalFile = File(publicDownloadsDir, newName)
                counter++
            }

            sourceFile.inputStream().use { input ->
                FileOutputStream(finalFile).use { output ->
                    input.copyTo(output, 32768)
                }
            }

            // Run media scanner so Downloads & Gallery index it immediately
            MediaScannerConnection.scanFile(
                context,
                arrayOf(finalFile.absolutePath),
                arrayOf(mimeType),
                null
            )
        } catch (_: Exception) {}

        // 2. Also insert into MediaStore.Downloads on Android 10+ (API 29+) for guaranteed public visibility
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, finalFile?.name ?: targetFileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val collectionUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val itemUri = resolver.insert(collectionUri, values)

                if (itemUri != null) {
                    resolver.openOutputStream(itemUri)?.use { outStream ->
                        sourceFile.inputStream().use { inStream ->
                            inStream.copyTo(outStream, 32768)
                        }
                    }

                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(itemUri, values, null, null)
                    savedUri = itemUri
                }
            } catch (_: Exception) {}
        }

        val actualFile = finalFile ?: sourceFile
        val actualUri = savedUri ?: try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                actualFile
            )
        } catch (_: Exception) {
            Uri.fromFile(actualFile)
        }

        SavedFileInfo(
            fileName = actualFile.name,
            relativeFolder = "Downloads/$SUBFOLDER",
            absolutePath = actualFile.absolutePath,
            contentUri = actualUri,
            sizeBytes = actualFile.length()
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
