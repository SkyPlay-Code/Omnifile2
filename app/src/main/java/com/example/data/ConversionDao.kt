package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversionDao {
    @Query("SELECT * FROM conversion_records ORDER BY timestamp DESC")
    fun getAllRecords(): Flow<List<ConversionRecord>>

    @Query("SELECT * FROM conversion_records WHERE mode = :mode ORDER BY timestamp DESC")
    fun getRecordsByMode(mode: String): Flow<List<ConversionRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecord(record: ConversionRecord): Long

    @Delete
    suspend fun deleteRecord(record: ConversionRecord)

    @Query("DELETE FROM conversion_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM conversion_records")
    suspend fun clearAll()

    @Query("SELECT COALESCE(SUM(inputFileSize - outputFileSize), 0) FROM conversion_records WHERE inputFileSize > outputFileSize")
    fun getTotalBytesSaved(): Flow<Long>

    @Query("SELECT COUNT(*) FROM conversion_records")
    fun getTotalOperationsCount(): Flow<Int>
}
