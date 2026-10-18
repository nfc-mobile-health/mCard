package com.example.mcard.storage

interface BasicInfoStore {
    suspend fun getBasicInfoBlock(): ByteArray?
    suspend fun writeBasicInfoBlock(block: ByteArray): Boolean
    suspend fun isBasicInfoWritten(): Boolean
}
