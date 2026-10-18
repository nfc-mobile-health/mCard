package com.example.mcard

import com.example.mcard.protocol.BasicInfoCodec
import com.example.mcard.protocol.MCardApduProcessor
import com.example.mcard.protocol.hexToBytes
import com.example.mcard.protocol.toHex
import com.example.mcard.storage.BasicInfoStore
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
        val response = MCardApduProcessor(FakeBasicInfoStore()).process("8000000000".hexToBytes())

        assertEquals("01010000001410001000009000", response.toHex())
    }

    @Test
    fun getBasicInfoBeforeWriteReturnsDataNotFound() = runTest {
        val response = MCardApduProcessor(FakeBasicInfoStore()).process("8002000000".hexToBytes())

        assertEquals("6A88", response.toHex())
    }

    @Test
    fun getBasicInfoReturnsStoredBlockByteExact() = runTest {
        val block = "104C67450741562D323034310A41736861205665726D61".hexToBytes()
        val response = MCardApduProcessor(FakeBasicInfoStore(block)).process("8002000000".hexToBytes())

        assertEquals("${block.toHex()}9000", response.toHex())
    }

    private class FakeBasicInfoStore(initial: ByteArray? = null) : BasicInfoStore {
        private var block: ByteArray? = initial

        override suspend fun getBasicInfoBlock(): ByteArray? = block

        override suspend fun writeBasicInfoBlock(block: ByteArray): Boolean {
            this.block = block
            return true
        }

        override suspend fun isBasicInfoWritten(): Boolean = block != null
    }
}
