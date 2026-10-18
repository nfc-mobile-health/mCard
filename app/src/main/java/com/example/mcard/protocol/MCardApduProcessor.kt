package com.example.mcard.protocol

import com.example.mcard.storage.BasicInfoStore

class MCardApduProcessor(private val store: BasicInfoStore) {
    suspend fun process(command: ByteArray): ByteArray {
        if (isSelect(command)) return status(SW_SUCCESS)
        if (command.size < 4) return status(SW_WRONG_LENGTH)

        val cla = command[0].toInt() and 0xFF
        if (cla != CLA) return status(SW_CLA_NOT_SUPPORTED)

        return when (command[1].toInt() and 0xFF) {
            INS_GET_INFO -> getInfo(command)
            INS_GET_BASIC_INFO -> getBasicInfo(command)
            else -> status(SW_INS_NOT_SUPPORTED)
        }
    }

    private suspend fun getInfo(command: ByteArray): ByteArray {
        if (!isCase2(command)) return status(SW_WRONG_LENGTH)
        if (!hasZeroP1P2(command)) return status(SW_WRONG_P1P2)

        val basicWritten = if (store.isBasicInfoWritten()) 1 else 0
        val data = byteArrayOf(
            API_VERSION,
            RECORD_FORMAT_VERSION,
        ) +
            0.u16be() +
            MAX_RECORD_COUNT.u16be() +
            RING_SIZE_BYTES.u16be() +
            RING_SIZE_BYTES.u16be() +
            byteArrayOf(basicWritten.toByte())
        return data.plusStatus(SW_SUCCESS)
    }

    private suspend fun getBasicInfo(command: ByteArray): ByteArray {
        if (!isCase2(command)) return status(SW_WRONG_LENGTH)
        if (!hasZeroP1P2(command)) return status(SW_WRONG_P1P2)
        val block = store.getBasicInfoBlock() ?: return status(SW_DATA_NOT_FOUND)
        return block.plusStatus(SW_SUCCESS)
    }

    private fun isCase2(command: ByteArray): Boolean = command.size == 5

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

        private const val SW_SUCCESS = 0x9000
        private const val SW_WRONG_LENGTH = 0x6700
        private const val SW_WRONG_P1P2 = 0x6A86
        private const val SW_DATA_NOT_FOUND = 0x6A88
        private const val SW_INS_NOT_SUPPORTED = 0x6D00
        private const val SW_CLA_NOT_SUPPORTED = 0x6E00

        private const val API_VERSION = 0x01.toByte()
        private const val RECORD_FORMAT_VERSION = 0x01.toByte()
        private const val MAX_RECORD_COUNT = 20
        private const val RING_SIZE_BYTES = 4096
        private val AID = "F01234567821".hexToBytes()
    }
}
