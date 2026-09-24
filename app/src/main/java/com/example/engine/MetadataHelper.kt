package com.example.engine

import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import com.example.model.MetadataConfig
import java.io.File

data class ExifSummary(
    val makeModel: String? = null,
    val dateTaken: String? = null,
    val hasGps: Boolean = false,
    val iso: String? = null,
    val exposureTime: String? = null,
    val fNumber: String? = null,
    val orientation: String? = null
)

object MetadataHelper {

    fun extractExifSummary(context: Context, uri: Uri): ExifSummary? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val make = exif.getAttribute(ExifInterface.TAG_MAKE)
                val model = exif.getAttribute(ExifInterface.TAG_MODEL)
                val date = exif.getAttribute(ExifInterface.TAG_DATETIME)
                val lat = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE)
                val iso = exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)
                val exposure = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)
                val fNum = exif.getAttribute(ExifInterface.TAG_F_NUMBER)
                val orient = exif.getAttribute(ExifInterface.TAG_ORIENTATION)

                val makeModel = when {
                    make != null && model != null -> "$make $model"
                    model != null -> model
                    make != null -> make
                    else -> null
                }

                if (makeModel != null || date != null || lat != null) {
                    ExifSummary(
                        makeModel = makeModel,
                        dateTaken = date,
                        hasGps = lat != null,
                        iso = iso,
                        exposureTime = exposure,
                        fNumber = fNum,
                        orientation = orient
                    )
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Preserves or strips metadata on the destination file based on user config.
     */
    fun applyMetadataPreferences(
        context: Context,
        sourceUri: Uri,
        destFile: File,
        config: MetadataConfig,
        sourceTimestamp: Long?
    ) {
        // Timestamp handling
        if (config.preserveTimestamps && sourceTimestamp != null && sourceTimestamp > 0L) {
            try {
                destFile.setLastModified(sourceTimestamp)
            } catch (_: Exception) {}
        }

        // If user chose to strip all metadata, we stop here (do not copy EXIF)
        if (config.stripAllForPrivacy) {
            return
        }

        // If not an image that supports EXIF (e.g. JPEG, WebP), return
        val ext = destFile.extension.lowercase()
        if (ext !in listOf("jpg", "jpeg", "webp")) {
            return
        }

        if (!config.preserveExif) {
            return
        }

        try {
            // Read source EXIF
            val sourceExif = context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                ExifInterface(stream)
            } ?: return

            // Target EXIF
            val destExif = ExifInterface(destFile.absolutePath)

            // Standard tags to copy
            val tagsToCopy = mutableListOf(
                ExifInterface.TAG_MAKE,
                ExifInterface.TAG_MODEL,
                ExifInterface.TAG_DATETIME,
                ExifInterface.TAG_DATETIME_ORIGINAL,
                ExifInterface.TAG_DATETIME_DIGITIZED,
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.TAG_FLASH,
                ExifInterface.TAG_WHITE_BALANCE,
                ExifInterface.TAG_FOCAL_LENGTH,
                ExifInterface.TAG_EXPOSURE_TIME,
                ExifInterface.TAG_F_NUMBER,
                ExifInterface.TAG_ISO_SPEED_RATINGS
            )

            // GPS tags
            if (config.preserveGps) {
                tagsToCopy.addAll(
                    listOf(
                        ExifInterface.TAG_GPS_LATITUDE,
                        ExifInterface.TAG_GPS_LONGITUDE,
                        ExifInterface.TAG_GPS_LATITUDE_REF,
                        ExifInterface.TAG_GPS_LONGITUDE_REF,
                        ExifInterface.TAG_GPS_ALTITUDE,
                        ExifInterface.TAG_GPS_ALTITUDE_REF,
                        ExifInterface.TAG_GPS_DATESTAMP,
                        ExifInterface.TAG_GPS_TIMESTAMP
                    )
                )
            }

            for (tag in tagsToCopy) {
                val value = sourceExif.getAttribute(tag)
                if (value != null) {
                    destExif.setAttribute(tag, value)
                }
            }

            // Custom author / copyright if provided
            if (config.customAuthor.isNotBlank()) {
                destExif.setAttribute(ExifInterface.TAG_ARTIST, config.customAuthor)
            }
            if (config.customCopyright.isNotBlank()) {
                destExif.setAttribute(ExifInterface.TAG_COPYRIGHT, config.customCopyright)
            }

            destExif.saveAttributes()
        } catch (_: Exception) {}
    }
}
