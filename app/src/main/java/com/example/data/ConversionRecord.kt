package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversion_records")
data class ConversionRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val inputFileName: String,
    val inputFileSize: Long,
    val outputFileName: String,
    val outputFileSize: Long,
    val fromFormat: String,
    val toFormat: String,
    val mode: String, // "CONVERT" or "COMPRESS" or "SPLIT"
    val durationMs: Long,
    val outputPath: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    val savedBytes: Long
        get() = (inputFileSize - outputFileSize).coerceAtLeast(0L)

    val compressionRatioPercent: Int
        get() = if (inputFileSize > 0) {
            val ratio = (outputFileSize.toDouble() / inputFileSize.toDouble()) * 100
            ratio.toInt().coerceIn(0, 100)
        } else 100

    val savingsPercent: Int
        get() = (100 - compressionRatioPercent).coerceAtLeast(0)
}
