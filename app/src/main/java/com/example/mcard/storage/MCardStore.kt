package com.example.mcard.storage

import android.content.Context
import android.util.Base64
import androidx.room.withTransaction
import com.example.mcard.protocol.BasicInfoCodec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MCardStore(context: Context) : CardStore {
    private val database = MCardDatabase.get(context)
    private val dao = database.dao()
    private val legacyPrefs = context.applicationContext.getSharedPreferences(LEGACY_STORE, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    override suspend fun getBasicInfoBlock(): ByteArray? = mutex.withLock {
        database.withTransaction { readOrMigrateBasicInfo()?.copyOf() }
    }

    override suspend fun writeBasicInfoBlock(block: ByteArray): Boolean = mutex.withLock {
        if (!BasicInfoCodec.validate(block)) return@withLock false
        database.withTransaction {
            dao.writeBasicInfo(BasicInfoEntity(block = block.copyOf()))
            legacyPrefs.edit().remove(KEY_BASIC_INFO).apply()
        }
        true
    }

    override suspend fun isBasicInfoWritten(): Boolean = mutex.withLock {
        database.withTransaction { readOrMigrateBasicInfo() != null }
    }

    override suspend fun getCardInfo(): CardInfo = mutex.withLock {
        database.withTransaction {
            val used = dao.usedRecordBytes()
            CardInfo(
                recordCount = dao.recordCount(),
                freeBytes = RING_SIZE_BYTES - used,
                basicInfoWritten = readOrMigrateBasicInfo() != null,
            )
        }
    }

    override suspend fun addRecord(blob: ByteArray): AddRecordResult = mutex.withLock {
        database.withTransaction {
            val newest = dao.newestRecord()
            if (newest != null && newest.blob.contentEquals(blob)) {
                return@withTransaction AddRecordResult(alreadyStored = true, recordCount = dao.recordCount())
            }
            dao.insertRecord(RecordBlobEntity(blob = blob.copyOf(), blobLength = blob.size))
            while (dao.recordCount() > MAX_RECORD_COUNT || dao.usedRecordBytes() > RING_SIZE_BYTES) {
                val oldest = dao.oldestRecord() ?: break
                dao.deleteRecord(oldest.id)
            }
            AddRecordResult(alreadyStored = false, recordCount = dao.recordCount())
        }
    }

    override suspend fun getRecords(startIndex: Int): RecordPage = mutex.withLock {
        database.withTransaction {
            val total = dao.recordCount()
            if (startIndex >= total) return@withTransaction RecordPage(total, 0, emptyList())
            val page = mutableListOf<ByteArray>()
            var dataLength = 5
            for (entity in dao.newestRecords(MAX_RECORD_COUNT, startIndex)) {
                val entryLength = 1 + entity.blobLength
                if (dataLength + entryLength > MAX_RESPONSE_DATA_BYTES) break
                page += entity.blob.copyOf()
                dataLength += entryLength
            }
            RecordPage(total, total - startIndex - page.size, page)
        }
    }

    private suspend fun readOrMigrateBasicInfo(): ByteArray? {
        dao.basicInfoBlock()?.let { return it }
        val legacy = legacyPrefs.getString(KEY_BASIC_INFO, null) ?: return null
        val block = Base64.decode(legacy, Base64.NO_WRAP)
        if (!BasicInfoCodec.validate(block)) return null
        dao.writeBasicInfo(BasicInfoEntity(block = block))
        legacyPrefs.edit().remove(KEY_BASIC_INFO).apply()
        return block
    }

    companion object {
        const val MAX_RECORD_COUNT = 20
        const val RING_SIZE_BYTES = 4096
        const val MAX_RESPONSE_DATA_BYTES = 200
        private const val LEGACY_STORE = "mcard_store"
        private const val KEY_BASIC_INFO = "basic_info"
    }
}
