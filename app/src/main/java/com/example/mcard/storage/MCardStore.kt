package com.example.mcard.storage

import android.content.Context
import android.util.Base64
import com.example.mcard.protocol.BasicInfoCodec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MCardStore(context: Context) : BasicInfoStore {
    private val prefs = context.applicationContext.getSharedPreferences("mcard_store", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    override suspend fun getBasicInfoBlock(): ByteArray? = mutex.withLock {
        prefs.getString(KEY_BASIC_INFO, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
    }

    override suspend fun writeBasicInfoBlock(block: ByteArray): Boolean = mutex.withLock {
        if (!BasicInfoCodec.validate(block)) return@withLock false
        prefs.edit().putString(KEY_BASIC_INFO, Base64.encodeToString(block, Base64.NO_WRAP)).apply()
        true
    }

    override suspend fun isBasicInfoWritten(): Boolean = mutex.withLock {
        prefs.contains(KEY_BASIC_INFO)
    }

    companion object {
        private const val KEY_BASIC_INFO = "basic_info"
    }
}
