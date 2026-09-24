package com.example.data

import kotlinx.coroutines.flow.Flow

class ConversionRepository(private val dao: ConversionDao) {
    val allRecords: Flow<List<ConversionRecord>> = dao.getAllRecords()
    val totalBytesSaved: Flow<Long> = dao.getTotalBytesSaved()
    val totalCount: Flow<Int> = dao.getTotalOperationsCount()

    fun getRecordsByMode(mode: String): Flow<List<ConversionRecord>> = dao.getRecordsByMode(mode)

    suspend fun insert(record: ConversionRecord): Long = dao.insertRecord(record)

    suspend fun delete(record: ConversionRecord) = dao.deleteRecord(record)

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    suspend fun clearAll() = dao.clearAll()
}
