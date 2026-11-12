package com.example.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class ConversionRepository(private val dao: ConversionDao) {

    // Hot Flows mapped onto Dispatchers.IO to guarantee zero Main-Thread stalls
    val allRecords: Flow<List<ConversionRecord>> = dao.getAllRecords().flowOn(Dispatchers.IO)
    val totalBytesSaved: Flow<Long> = dao.getTotalBytesSaved().flowOn(Dispatchers.IO)
    val totalCount: Flow<Int> = dao.getTotalOperationsCount().flowOn(Dispatchers.IO)

    fun getRecordsByMode(mode: String): Flow<List<ConversionRecord>> =
        dao.getRecordsByMode(mode).flowOn(Dispatchers.IO)

    fun getRecentRecords(limit: Int = 20): Flow<List<ConversionRecord>> =
        dao.getRecentRecords(limit).flowOn(Dispatchers.IO)

    fun searchRecords(query: String): Flow<List<ConversionRecord>> =
        dao.searchRecords(query).flowOn(Dispatchers.IO)

    // Single Insert
    suspend fun insert(record: ConversionRecord): Long = withContext(Dispatchers.IO) {
        dao.insertRecord(record)
    }

    // Atomic Batch Insert for batch operations
    suspend fun insertBatch(records: List<ConversionRecord>): List<Long> = withContext(Dispatchers.IO) {
        if (records.isEmpty()) emptyList() else dao.insertRecords(records)
    }

    suspend fun delete(record: ConversionRecord) = withContext(Dispatchers.IO) {
        dao.deleteRecord(record)
    }

    suspend fun deleteById(id: Long) = withContext(Dispatchers.IO) {
        dao.deleteById(id)
    }

    suspend fun deleteBatch(ids: List<Long>) = withContext(Dispatchers.IO) {
        dao.deleteByIds(ids)
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        dao.clearAll()
    }

    suspend fun trimHistory(keepCount: Int = 200) = withContext(Dispatchers.IO) {
        dao.trimHistory(keepCount)
    }
}