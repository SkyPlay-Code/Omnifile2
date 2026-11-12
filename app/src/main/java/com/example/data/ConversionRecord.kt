package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversion_records",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["mode", "timestamp"]),
        Index(value = ["inputFileName"])
    ]
)
data class ConversionRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val mode: String, // "CONVERT" or "COMPRESS"
    val inputFileName: String,
    val inputFileSize: Long,
    val outputFileName: String,
    val outputFileSize: Long,
    val fromFormat: String = "",
    val toFormat: String = "",
    val durationMs: Long = 0,
    val isSuccess: Boolean = true,
    val outputPath: String = "",
    val metadataSummary: String = ""
)