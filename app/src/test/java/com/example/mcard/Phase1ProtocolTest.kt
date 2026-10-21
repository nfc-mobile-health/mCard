package com.example.mcard

import com.example.mcard.protocol.BasicInfoCodec
import com.example.mcard.protocol.MCardApduProcessor
import com.example.mcard.protocol.hexToBytes
import com.example.mcard.protocol.toHex
import com.example.mcard.storage.AddRecordResult
import com.example.mcard.storage.CardInfo
import com.example.mcard.storage.CardStore
import com.example.mcard.storage.RecordPage
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase1ProtocolTest {
    @Test
    fun basicInfoVectorEncodesExactly() {
        val encoded = BasicInfoCodec.encode(
            BasicInfoCodec.BasicInfo(
                patientId = "AV-2041",
                name = "Asha Verma",
                dateOfBirth = LocalDate.of(1972, 5, 20),
                gender = BasicInfoCodec.Gender.Female,
                bloodGroup = BasicInfoCodec.BloodGroup.BPositive,
            ),
        )

        assertEquals("104C67450741562D323034310A41736861205665726D61", encoded.toHex())
        assertTrue(BasicInfoCodec.validate(encoded))
    }

    @Test
    fun getInfoFreshUsesAndroidLimits() = runTest {
        val response = MCardApduProcessor(FakeCardStore()).process("8000000000".hexToBytes())

        assertEquals("01010000001410001000009000", response.toHex())
    }

    @Test
    fun getBasicInfoBeforeWriteReturnsDataNotFound() = runTest {
        val response = MCardApduProcessor(FakeCardStore()).process("8002000000".hexToBytes())

        assertEquals("6A88", response.toHex())
    }

    @Test
    fun getBasicInfoReturnsStoredBlockByteExact() = runTest {
        val block = "104C67450741562D323034310A41736861205665726D61".hexToBytes()
        val response = MCardApduProcessor(FakeCardStore(block)).process("8002000000".hexToBytes())

        assertEquals("${block.toHex()}9000", response.toHex())
    }

    @Test
    fun addRecordAndReadPageMatchTheSpecification() = runTest {
        val store = FakeCardStore()
        val processor = MCardApduProcessor(store)
        val v1 = "3D0E3F6C044E313031503C46500C466576657220322064617973".hexToBytes()

        assertEquals("0000019000", processor.process("801000001A${v1.toHex()}00".hexToBytes()).toHex())
        assertEquals("0100019000", processor.process("801000001A${v1.toHex()}00".hexToBytes()).toHex())
        assertEquals("00010000011A${v1.toHex()}9000", processor.process("8012000000".hexToBytes()).toHex())
        assertEquals("01010001001410000FE5009000", processor.process("8000000000".hexToBytes()).toHex())
    }

    @Test
    fun recordsEvictTheOldestAfterTwentyEntries() = runTest {
        val processor = MCardApduProcessor(FakeCardStore())
        repeat(22) { index ->
            val blob = byteArrayOf(0x20, index.toByte())
            val command = byteArrayOf(0x80.toByte(), 0x10, 0, 0, blob.size.toByte()) + blob + byteArrayOf(0)
            processor.process(command)
        }
        val info = processor.process("8000000000".hexToBytes())
        assertEquals("01010014001410000FC4009000", info.toHex())
        val page = processor.process("8012000000".hexToBytes())
        assertEquals(20, ((page[4].toInt() and 0xFF)))
        assertEquals(0x20, page[6].toInt() and 0xFF)
        assertEquals(21, page[7].toInt() and 0xFF)
        assertEquals(2, page[64].toInt() and 0xFF)
    }

    private class FakeCardStore(initial: ByteArray? = null) : CardStore {
        private var block: ByteArray? = initial
        private val records = mutableListOf<ByteArray>()

        override suspend fun getBasicInfoBlock(): ByteArray? = block

        override suspend fun writeBasicInfoBlock(block: ByteArray): Boolean {
            this.block = block
            return true
        }

        override suspend fun isBasicInfoWritten(): Boolean = block != null

        override suspend fun getCardInfo(): CardInfo = CardInfo(
            recordCount = records.size,
            freeBytes = 4096 - records.sumOf { it.size + 1 },
            basicInfoWritten = block != null,
        )

        override suspend fun addRecord(blob: ByteArray): AddRecordResult {
            if (records.lastOrNull()?.contentEquals(blob) == true) {
                return AddRecordResult(true, records.size)
            }
            records += blob.copyOf()
            while (records.size > 20 || records.sumOf { it.size + 1 } > 4096) records.removeAt(0)
            return AddRecordResult(false, records.size)
        }

        override suspend fun getRecords(startIndex: Int): RecordPage {
            val newest = records.asReversed().drop(startIndex)
            val page = mutableListOf<ByteArray>()
            var bytes = 5
            for (record in newest) {
                if (bytes + record.size + 1 > 200) break
                page += record
                bytes += record.size + 1
            }
            return RecordPage(records.size, newest.size - page.size, page)
        }
    }
}
