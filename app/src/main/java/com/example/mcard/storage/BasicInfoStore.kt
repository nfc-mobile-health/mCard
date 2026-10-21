package com.example.mcard.storage

interface BasicInfoStore {
    suspend fun getBasicInfoBlock(): ByteArray?
    suspend fun writeBasicInfoBlock(block: ByteArray): Boolean
    suspend fun isBasicInfoWritten(): Boolean
}

interface CardStore : BasicInfoStore {
    suspend fun getCardInfo(): CardInfo
    suspend fun addRecord(blob: ByteArray): AddRecordResult
    suspend fun getRecords(startIndex: Int): RecordPage
}

data class CardInfo(
    val recordCount: Int,
    val freeBytes: Int,
    val basicInfoWritten: Boolean,
)

data class AddRecordResult(
    val alreadyStored: Boolean,
    val recordCount: Int,
)

data class RecordPage(
    val totalCount: Int,
    val remainingCount: Int,
    val records: List<ByteArray>,
)
