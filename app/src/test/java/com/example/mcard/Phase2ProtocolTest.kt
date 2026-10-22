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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase2ProtocolTest {

    // Test vectors from API.md §8
    private val v1Hex = "3D0E3F6C044E313031503C46500C466576657220322064617973"
    private val v2Hex = "240E3F49044E31303148"
    private val v3Hex = "3F0E3B25044E3130325840454C1650617261636574616D6F6C20353030206D673B4F525325436F7567682C206164766973656420726573742E2052657669657720696E20332064617973"
    private val v4HeaderHex = "3F080520084E313031303130"
    private val basicInfoHex = "104C67450741562D323034310A41736861205665726D61"

    @Test
    fun basicInfoDecoderValidatesInputRobustly() {
        val valid = basicInfoHex.hexToBytes()
        val decoded = BasicInfoCodec.decode(valid)
        assertEquals("AV-2041", decoded.patientId)
        assertEquals("Asha Verma", decoded.name)
        assertEquals(LocalDate.of(1972, 5, 20), decoded.dateOfBirth)
        assertEquals(BasicInfoCodec.Gender.Female, decoded.gender)
        assertEquals(BasicInfoCodec.BloodGroup.BPositive, decoded.bloodGroup)

        // Truncated input throws
        assertThrows(IllegalArgumentException::class.java) {
            BasicInfoCodec.decode(valid.copyOfRange(0, 10))
        }

        // Invalid format byte throws
        val badFormat = valid.copyOf()
        badFormat[0] = 0x20
        assertThrows(IllegalArgumentException::class.java) {
            BasicInfoCodec.decode(badFormat)
        }

        // Invalid gender code (reserved/out-of-range)
        val badGender = valid.copyOf()
        badGender[1] = 0x03 // bits 1-0 not zero
        assertThrows(IllegalArgumentException::class.java) {
            BasicInfoCodec.decode(badGender)
        }
    }

    @Test
    fun apduProcessorRejectsInvalidClaAndIns() = runTest {
        val processor = MCardApduProcessor(InMemoryCardStore())

        // CLA not 0x80 (e.g. 0x00 for GET_INFO) returns 6E00
        assertEquals("6E00", processor.process("0000000000".hexToBytes()).toHex())

        // Unsupported INS returns 6D00
        assertEquals("6D00", processor.process("8099000000".hexToBytes()).toHex())

        // Truncated APDU (< 4 bytes) returns 6700
        assertEquals("6700", processor.process("800000".hexToBytes()).toHex())
    }

    @Test
    fun apduProcessorEnforcesP1P2AndLeForCase2() = runTest {
        val processor = MCardApduProcessor(InMemoryCardStore())

        // GET_INFO with non-zero P1/P2 returns 6A86
        assertEquals("6A86", processor.process("8000010000".hexToBytes()).toHex())
        assertEquals("6A86", processor.process("8000000100".hexToBytes()).toHex())

        // GET_INFO with non-zero Le returns 6700
        assertEquals("6700", processor.process("8000000005".hexToBytes()).toHex())

        // GET_BASIC_INFO with non-zero P1/P2 returns 6A86
        assertEquals("6A86", processor.process("8002010000".hexToBytes()).toHex())

        // GET_BASIC_INFO with non-zero Le returns 6700
        assertEquals("6700", processor.process("8002000001".hexToBytes()).toHex())

        // GET_RECORDS with wrong size or non-zero Le returns 6700
        assertEquals("6700", processor.process("8012000001".hexToBytes()).toHex())
        assertEquals("6700", processor.process("80120000".hexToBytes()).toHex())
    }

    @Test
    fun addRecordEnforcesLengthAndVersionAndLe() = runTest {
        val processor = MCardApduProcessor(InMemoryCardStore())
        val v1 = v1Hex.hexToBytes()

        // Missing Le byte returns 6700
        assertEquals("6700", processor.process(("801000001A" + v1Hex).hexToBytes()).toHex())

        // Le byte not 0x00 returns 6700
        assertEquals("6700", processor.process(("801000001A" + v1Hex + "05").hexToBytes()).toHex())

        // Non-zero P1/P2 returns 6A86
        assertEquals("6A86", processor.process(("801001001A" + v1Hex + "00").hexToBytes()).toHex())

        // Lc = 0 returns 6700
        assertEquals("6700", processor.process("801000000000".hexToBytes()).toHex())

        // Lc mismatch with payload size returns 6700
        assertEquals("6700", processor.process(("8010000019" + v1Hex + "00").hexToBytes()).toHex())

        // Version bits != 001 returns 6A80
        val badVersion = v1.copyOf()
        badVersion[0] = 0x40.toByte() // version 2
        val badCmd = byteArrayOf(0x80.toByte(), 0x10, 0, 0, badVersion.size.toByte()) + badVersion + byteArrayOf(0)
        assertEquals("6A80", processor.process(badCmd).toHex())
    }

    @Test
    fun addRecordDuplicateDetectionAndSpecOrdering() = runTest {
        val store = InMemoryCardStore(basicInfoHex.hexToBytes())
        val processor = MCardApduProcessor(store)

        // 1. ADD_RECORD V3, V2, V1 in that order
        val v3 = v3Hex.hexToBytes()
        val v2 = v2Hex.hexToBytes()
        val v1 = v1Hex.hexToBytes()

        val addV3 = processor.process(byteArrayOf(0x80.toByte(), 0x10, 0, 0, v3.size.toByte()) + v3 + byteArrayOf(0))
        assertEquals("0000019000", addV3.toHex()) // stored, count 1

        val addV2 = processor.process(byteArrayOf(0x80.toByte(), 0x10, 0, 0, v2.size.toByte()) + v2 + byteArrayOf(0))
        assertEquals("0000029000", addV2.toHex()) // stored, count 2

        val addV1 = processor.process(byteArrayOf(0x80.toByte(), 0x10, 0, 0, v1.size.toByte()) + v1 + byteArrayOf(0))
        assertEquals("0000039000", addV1.toHex()) // stored, count 3

        // Sending V1 again returns already stored (01), count remains 3
        val addV1Dup = processor.process(byteArrayOf(0x80.toByte(), 0x10, 0, 0, v1.size.toByte()) + v1 + byteArrayOf(0))
        assertEquals("0100039000", addV1Dup.toHex()) // already stored, count 3

        // 2. GET_INFO verification
        // Free bytes on Android mCard: 4096 - (75 + 11 + 27) = 4096 - 113 = 3983 (0x0F8F)
        val info = processor.process("8000000000".hexToBytes())
        assertEquals("01010003001410000F8F019000", info.toHex())

        // 3. GET_RECORDS start 0 returns V1, V2, V3 (newest first)
        val page0 = processor.process("8012000000".hexToBytes())
        val expectedPage0 = "0003000003" +
            String.format("%02X", v1.size) + v1Hex +
            String.format("%02X", v2.size) + v2Hex +
            String.format("%02X", v3.size) + v3Hex +
            "9000"
        assertEquals(expectedPage0, page0.toHex())

        // 4. GET_RECORDS start 2 returns V3 only
        val page2 = processor.process("8012000200".hexToBytes())
        val expectedPage2 = "0003000001" +
            String.format("%02X", v3.size) + v3Hex +
            "9000"
        assertEquals(expectedPage2, page2.toHex())

        // 5. GET_RECORDS beyond count (start 3) returns total count, remaining 0, page count 0
        val page3 = processor.process("8012000300".hexToBytes())
        assertEquals("00030000009000", page3.toHex())
    }

    @Test
    fun maximumRecordV4IsAccepted() = runTest {
        val processor = MCardApduProcessor(InMemoryCardStore())

        // Create 179-byte record blob matching V4
        val v4 = byteArrayOf(
            0x3F, 0x08, 0x05, 0x20, // header and timestamp
            0x08, 0x4E, 0x31, 0x30, 0x31, 0x30, 0x31, 0x30, 0x31, // HP ID "N1010101"
            (295 - 40).toByte(), (275 - 20).toByte(), // BP
            (555 - 300).toByte(), // Temp 55.5 C
            0xFF.toByte(), // Heart rate 255
            60.toByte(), // Meds length 60
        ) + "m".repeat(60).toByteArray(Charsets.US_ASCII) +
            byteArrayOf(100.toByte()) + "d".repeat(100).toByteArray(Charsets.US_ASCII)

        assertEquals(179, v4.size)
        assertTrue(v4.toHex().startsWith(v4HeaderHex))

        val addV4 = processor.process(byteArrayOf(0x80.toByte(), 0x10, 0, 0, v4.size.toByte()) + v4 + byteArrayOf(0))
        assertEquals("0000019000", addV4.toHex())
    }

    @Test
    fun evictionMaintainsTwentyRecordLimit() = runTest {
        val store = InMemoryCardStore()
        val processor = MCardApduProcessor(store)

        // Add 25 unique records
        for (i in 1..25) {
            val blob = byteArrayOf(0x20.toByte(), i.toByte())
            val cmd = byteArrayOf(0x80.toByte(), 0x10, 0, 0, blob.size.toByte()) + blob + byteArrayOf(0)
            processor.process(cmd)
        }

        val info = processor.process("8000000000".hexToBytes())
        // Record count must be capped at 20
        assertEquals(20, store.getCardInfo().recordCount)

        // Read all records (page 0)
        val page = processor.process("8012000000".hexToBytes())
        // Total count = 20, remaining = 0, page = 20 entries
        assertEquals("0014000014", page.toHex().substring(0, 10))

        // Newest record should have value 25
        val records = store.getRecords(0).records
        assertEquals(25.toByte(), records[0][1])
        // Oldest retained record should have value 6 (1..5 evicted)
        assertEquals(6.toByte(), records[19][1])
    }

    private class InMemoryCardStore(initial: ByteArray? = null) : CardStore {
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
            if (records.firstOrNull()?.contentEquals(blob) == true) {
                return AddRecordResult(true, records.size)
            }
            records.add(0, blob.copyOf())
            while (records.size > 20 || records.sumOf { it.size + 1 } > 4096) {
                records.removeAt(records.lastIndex)
            }
            return AddRecordResult(false, records.size)
        }

        override suspend fun getRecords(startIndex: Int): RecordPage {
            if (startIndex >= records.size) return RecordPage(records.size, 0, emptyList())
            val page = mutableListOf<ByteArray>()
            var bytes = 5
            for (i in startIndex until records.size) {
                val record = records[i]
                if (bytes + record.size + 1 > 200) break
                page += record
                bytes += record.size + 1
            }
            return RecordPage(records.size, records.size - (startIndex + page.size), page)
        }
    }
}
