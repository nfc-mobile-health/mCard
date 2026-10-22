package com.example.mcard.protocol

import com.example.mcard.storage.CardStore
import com.example.mcard.storage.MCardStore

class MCardApduProcessor(private val store: CardStore) {
    suspend fun process(command: ByteArray): ByteArray {
        if (isSelect(command)) return status(SW_SUCCESS)
        if (command.size < 4) return status(SW_WRONG_LENGTH)

        val cla = command[0].toInt() and 0xFF
        if (cla != CLA) return status(SW_CLA_NOT_SUPPORTED)

        return when (command[1].toInt() and 0xFF) {
            INS_GET_INFO -> getInfo(command)
            INS_GET_BASIC_INFO -> getBasicInfo(command)
            INS_ADD_RECORD -> addRecord(command)
            INS_GET_RECORDS -> getRecords(command)
            else -> status(SW_INS_NOT_SUPPORTED)
        }
    }

    private suspend fun getInfo(command: ByteArray): ByteArray {
        if (!isCase2(command)) return status(SW_WRONG_LENGTH)
        if (!hasZeroP1P2(command)) return status(SW_WRONG_P1P2)

        val info = store.getCardInfo()
        val data = byteArrayOf(
            API_VERSION,
            RECORD_FORMAT_VERSION,
        ) +
            info.recordCount.u16be() +
            MAX_RECORD_COUNT.u16be() +
            RING_SIZE_BYTES.u16be() +
            info.freeBytes.u16be() +
            byteArrayOf(if (info.basicInfoWritten) 1 else 0)
        return data.plusStatus(SW_SUCCESS)
    }

    private suspend fun getBasicInfo(command: ByteArray): ByteArray {
        if (!isCase2(command)) return status(SW_WRONG_LENGTH)
        if (!hasZeroP1P2(command)) return status(SW_WRONG_P1P2)
        val block = store.getBasicInfoBlock() ?: return status(SW_DATA_NOT_FOUND)
        return block.plusStatus(SW_SUCCESS)
    }

    private suspend fun addRecord(command: ByteArray): ByteArray {
        if (command.size < 6) return status(SW_WRONG_LENGTH)
        val length = command[4].toInt() and 0xFF
        if (length !in 1..MAX_RECORD_LENGTH || command.size != length + 6 || command.last() != 0x00.toByte()) {
            return status(SW_WRONG_LENGTH)
        }
        if (!hasZeroP1P2(command)) return status(SW_WRONG_P1P2)
        val blob = command.copyOfRange(5, 5 + length)
        if ((blob[0].toInt() ushr 5) != RECORD_FORMAT_VERSION.toInt()) return status(SW_INCORRECT_DATA)

        val result = store.addRecord(blob)
        return byteArrayOf(if (result.alreadyStored) 1 else 0) +
            result.recordCount.u16be() +
            SW_SUCCESS.u16be()
    }

    private suspend fun getRecords(command: ByteArray): ByteArray {
        if (!isCase2(command)) return status(SW_WRONG_LENGTH)
        val startIndex = ((command[2].toInt() and 0xFF) shl 8) or (command[3].toInt() and 0xFF)
        val page = store.getRecords(startIndex)
        var data = page.totalCount.u16be() + page.remainingCount.u16be() + byteArrayOf(page.records.size.toByte())
        page.records.forEach { blob ->
            data += byteArrayOf(blob.size.toByte()) + blob
        }
        return data.plusStatus(SW_SUCCESS)
    }

    private fun isCase2(command: ByteArray): Boolean =
        command.size == 5 && command[4] == 0x00.toByte()

    private fun hasZeroP1P2(command: ByteArray): Boolean =
        (command[2].toInt() and 0xFF) == 0 && (command[3].toInt() and 0xFF) == 0

    private fun isSelect(command: ByteArray): Boolean {
        if (command.size != 11) return false
        if ((command[0].toInt() and 0xFF) != 0x00) return false
        if ((command[1].toInt() and 0xFF) != 0xA4) return false
        if ((command[2].toInt() and 0xFF) != 0x04) return false
        if ((command[3].toInt() and 0xFF) != 0x00) return false
        if ((command[4].toInt() and 0xFF) != AID.size) return false
        return command.copyOfRange(5, 5 + AID.size).contentEquals(AID)
    }

    private fun status(sw: Int): ByteArray = byteArrayOf().plusStatus(sw)

    companion object {
        private const val CLA = 0x80
        private const val INS_GET_INFO = 0x00
        private const val INS_GET_BASIC_INFO = 0x02
        private const val INS_ADD_RECORD = 0x10
        private const val INS_GET_RECORDS = 0x12

        private const val SW_SUCCESS = 0x9000
        private const val SW_WRONG_LENGTH = 0x6700
        private const val SW_WRONG_P1P2 = 0x6A86
        private const val SW_DATA_NOT_FOUND = 0x6A88
        private const val SW_INCORRECT_DATA = 0x6A80
        private const val SW_INS_NOT_SUPPORTED = 0x6D00
        private const val SW_CLA_NOT_SUPPORTED = 0x6E00

        private const val API_VERSION = 0x01.toByte()
        private const val RECORD_FORMAT_VERSION = 0x01.toByte()
        private const val MAX_RECORD_COUNT = MCardStore.MAX_RECORD_COUNT
        private const val RING_SIZE_BYTES = MCardStore.RING_SIZE_BYTES
        private const val MAX_RECORD_LENGTH = 180
        private val AID = "F01234567821".hexToBytes()
    }
}
