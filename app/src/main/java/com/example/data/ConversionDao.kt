package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversionDao {

    // --- Queries ---

    @Query("SELECT * FROM conversion_records ORDER BY timestamp DESC")
    fun getAllRecords(): Flow<List<ConversionRecord>>

    @Query("SELECT * FROM conversion_records ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentRecords(limit: Int): Flow<List<ConversionRecord>>

    @Query("SELECT * FROM conversion_records WHERE mode = :mode ORDER BY timestamp DESC")
    fun getRecordsByMode(mode: String): Flow<List<ConversionRecord>>

    @Query("""
        SELECT * FROM conversion_records 
        WHERE inputFileName LIKE '%' || :query || '%' 
           OR outputFileName LIKE '%' || :query || '%' 
        ORDER BY timestamp DESC
    """)
    fun searchRecords(query: String): Flow<List<ConversionRecord>>

    // --- Inserts ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecord(record: ConversionRecord): Long

    // Atomic Batch Insert: Runs in a single SQLite transaction
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecords(records: List<ConversionRecord>): List<Long>

    // --- Deletes ---

    @Delete
    suspend fun deleteRecord(record: ConversionRecord)

    @Query("DELETE FROM conversion_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM conversion_records WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM conversion_records")
    suspend fun clearAll()

    // Trims history to keep only the latest N records to prevent unbounded DB growth
    @Query("""
        DELETE FROM conversion_records 
        WHERE id NOT IN (
            SELECT id FROM conversion_records ORDER BY timestamp DESC LIMIT :keepCount
        )
    """)
    suspend fun trimHistory(keepCount: Int = 200)

    // --- Aggregates (Analytics) ---

    @Query("SELECT COALESCE(SUM(inputFileSize - outputFileSize), 0) FROM conversion_records WHERE inputFileSize > outputFileSize AND isSuccess = 1")
    fun getTotalBytesSaved(): Flow<Long>

    @Query("SELECT COUNT(*) FROM conversion_records WHERE isSuccess = 1")
    fun getTotalOperationsCount(): Flow<Int>
}