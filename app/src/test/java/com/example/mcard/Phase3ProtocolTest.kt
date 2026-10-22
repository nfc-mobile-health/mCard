package com.example.mcard

import com.example.mcard.crypto.MCardCrypto
import com.example.mcard.crypto.TestPki
import com.example.mcard.protocol.MCardApduProcessor
import com.example.mcard.protocol.hexToBytes
import com.example.mcard.protocol.toHex
import com.example.mcard.storage.AddRecordResult
import com.example.mcard.storage.CardInfo
import com.example.mcard.storage.CardStore
import com.example.mcard.storage.RecordPage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.X509Certificate
import kotlin.math.min

class Phase3ProtocolTest {

    private lateinit var store: InMemoryCardStore
    private lateinit var processor: MCardApduProcessor

    private val cadCertDer = TestPki.CadCertDer
    private val cadPrivateKey: PrivateKey = MCardCrypto.parsePrivateKey(TestPki.CadPrivateKeyPem)
    private val cardPublicKey: PublicKey = MCardCrypto.parseCertificate(TestPki.CardCertPem).publicKey
    private val rootCaCert: X509Certificate = MCardCrypto.parseCertificate(TestPki.RootCaCertPem)

    @Before
    fun setUp() {
        store = InMemoryCardStore()
        processor = MCardApduProcessor(store, requireSecureSession = true)
    }

    @Test
    fun unauthenticatedAccessReturns6982() = runBlocking {
        // SELECT is allowed
        val selResp = processor.process("00A4040006F01234567821".hexToBytes())
        assertEquals("9000", selResp.toHex())

        // GET_INFO without session -> 6982
        val getInfoResp = processor.process("8000000000".hexToBytes())
        assertEquals("6982", getInfoResp.toHex())

        // GET_BASIC_INFO without session -> 6982
        val getBasicResp = processor.process("8002000000".hexToBytes())
        assertEquals("6982", getBasicResp.toHex())

        // ADD_RECORD without session -> 6982
        val addResp = processor.process("8010000004240E3F4900".hexToBytes())
        assertEquals("6982", addResp.toHex())

        // GET_RECORDS without session -> 6982
        val getRecsResp = processor.process("8012000000".hexToBytes())
        assertEquals("6982", getRecsResp.toHex())
    }

    @Test
    fun fullFiveMessageHandshakeEstablishesSession() = runBlocking {
        // SELECT
        processor.process("00A4040006F01234567821".hexToBytes())

        // 1. Send CAD Certificate (chunked)
        val chunkSize = 160
        val totalChunks = (cadCertDer.size + chunkSize - 1) / chunkSize
        for (i in 0 until totalChunks) {
            val start = i * chunkSize
            val end = min(start + chunkSize, cadCertDer.size)
            val more = end < cadCertDer.size
            val chunk = cadCertDer.copyOfRange(start, end)
            val cmd = byteArrayOf(
                0x80.toByte(), 0x80.toByte(), i.toByte(), (if (more) 1 else 0).toByte(), chunk.size.toByte()
            ) + chunk + byteArrayOf(0x00)
            val resp = processor.process(cmd)
            assertEquals("Step 1 chunk $i must return 9000", "9000", resp.toHex())
        }

        // 2. Get Card Certificate
        val cardCertBuffer = ByteArrayOutputStream()
        var chunkIdx = 0
        while (true) {
            val cmd = byteArrayOf(0x80.toByte(), 0x82.toByte(), chunkIdx.toByte(), 0x00, 0x00)
            val resp = processor.process(cmd)
            val sw = resp.takeLast(2).toByteArray().toHex()
            assertEquals("Step 2 cert chunk $chunkIdx must return 9000", "9000", sw)
            val data = resp.dropLast(2).toByteArray()
            val more = data[0].toInt() == 1
            cardCertBuffer.write(data, 1, data.size - 1)
            chunkIdx++
            if (!more) break
        }
        val receivedCardCert = MCardCrypto.parseCertificate(cardCertBuffer.toByteArray())
        assertTrue("Card cert must verify against root CA", MCardCrypto.verify(receivedCardCert, rootCaCert))

        // 2b. Get Card Nonce N1
        val n1EncBuffer = ByteArrayOutputStream()
        val n1Resp0 = processor.process(byteArrayOf(0x80.toByte(), 0x83.toByte(), 0x00, 0x00, 0x00))
        assertEquals("9000", n1Resp0.takeLast(2).toByteArray().toHex())
        val data0 = n1Resp0.dropLast(2).toByteArray()
        assertEquals(1, data0[0].toInt()) // more flag
        n1EncBuffer.write(data0, 1, 128)

        val n1Resp1 = processor.process(byteArrayOf(0x80.toByte(), 0x83.toByte(), 0x01, 0x00, 0x00))
        assertEquals("9000", n1Resp1.takeLast(2).toByteArray().toHex())
        val data1 = n1Resp1.dropLast(2).toByteArray()
        assertEquals(0, data1[0].toInt()) // no more
        n1EncBuffer.write(data1, 1, 128)

        val decryptedN1 = MCardCrypto.decryptRsa(n1EncBuffer.toByteArray(), cadPrivateKey)
        assertEquals(16, decryptedN1.size)

        // 3. Send Mutual Nonce: E_Card(N1+1 || N2)
        val n1Plus1 = MCardCrypto.incrementNonce(decryptedN1)
        val n2 = MCardCrypto.generateNonce(16)
        val mutualEncrypted = MCardCrypto.encryptRsa(n1Plus1 + n2, cardPublicKey)
        assertEquals(256, mutualEncrypted.size)

        val m0 = byteArrayOf(0x80.toByte(), 0x84.toByte(), 0x00, 0x01, 128.toByte()) +
            mutualEncrypted.copyOfRange(0, 128) + byteArrayOf(0x00)
        assertEquals("9000", processor.process(m0).toHex())

        val m1 = byteArrayOf(0x80.toByte(), 0x84.toByte(), 0x01, 0x00, 128.toByte()) +
            mutualEncrypted.copyOfRange(128, 256) + byteArrayOf(0x00)
        assertEquals("9000", processor.process(m1).toHex())

        // 4. Get Nonce ACK: E_CAD(N2+1)
        val ackEncBuffer = ByteArrayOutputStream()
        val ackResp0 = processor.process(byteArrayOf(0x80.toByte(), 0x86.toByte(), 0x00, 0x00, 0x00))
        assertEquals("9000", ackResp0.takeLast(2).toByteArray().toHex())
        ackEncBuffer.write(ackResp0.dropLast(2).toByteArray(), 1, 128)

        val ackResp1 = processor.process(byteArrayOf(0x80.toByte(), 0x86.toByte(), 0x01, 0x00, 0x00))
        assertEquals("9000", ackResp1.takeLast(2).toByteArray().toHex())
        ackEncBuffer.write(ackResp1.dropLast(2).toByteArray(), 1, 128)

        val decryptedAck = MCardCrypto.decryptRsa(ackEncBuffer.toByteArray(), cadPrivateKey)
        val expectedN2Plus1 = MCardCrypto.incrementNonce(n2)
        assertArrayEquals("Card must return N2+1 in ACK", expectedN2Plus1, decryptedAck)

        // 5. Establish HTK
        val htk = MCardCrypto.generateSessionKey(16)
        val htkEncrypted = MCardCrypto.encryptRsa(htk, cardPublicKey)

        val h0 = byteArrayOf(0x80.toByte(), 0x88.toByte(), 0x00, 0x01, 128.toByte()) +
            htkEncrypted.copyOfRange(0, 128) + byteArrayOf(0x00)
        assertEquals("9000", processor.process(h0).toHex())

        val h1 = byteArrayOf(0x80.toByte(), 0x88.toByte(), 0x01, 0x00, 128.toByte()) +
            htkEncrypted.copyOfRange(128, 256) + byteArrayOf(0x00)
        assertEquals("9000", processor.process(h1).toHex())

        // Verify HTK is set in processor
        assertNotNull(processor.sessionHtk)
        assertArrayEquals(htk, processor.sessionHtk)

        // Now test sending GET_INFO wrapped in INS_SECURE_APDU (0x90)
        val plainGetInfo = "8000000000".hexToBytes()
        val envelope = MCardCrypto.encryptAesGcm(plainGetInfo, htk)
        val secureApdu = byteArrayOf(
            0x80.toByte(), 0x90.toByte(), 0x00, 0x00, envelope.size.toByte()
        ) + envelope + byteArrayOf(0x00)

        val secureResp = processor.process(secureApdu)
        assertEquals("9000", secureResp.takeLast(2).toByteArray().toHex())

        val decryptedResp = MCardCrypto.decryptAesGcm(secureResp.dropLast(2).toByteArray(), htk)
        val innerSw = decryptedResp.takeLast(2).toByteArray().toHex()
        assertEquals("9000", innerSw)
        val innerData = decryptedResp.dropLast(2).toByteArray()
        assertEquals(11, innerData.size)
        assertEquals(0x01.toByte(), innerData[0]) // api v1
        assertEquals(0x01.toByte(), innerData[1]) // format v1
    }

    @Test
    fun tamperedCiphertextIsRejected() = runBlocking {
        authenticateTestSession()
        val htk = processor.sessionHtk!!

        val plainGetInfo = "8000000000".hexToBytes()
        val envelope = MCardCrypto.encryptAesGcm(plainGetInfo, htk)
        // Corrupt a byte in the envelope
        envelope[envelope.lastIndex] = (envelope.last().toInt() xor 0xFF).toByte()

        val secureApdu = byteArrayOf(
            0x80.toByte(), 0x90.toByte(), 0x00, 0x00, envelope.size.toByte()
        ) + envelope + byteArrayOf(0x00)

        val resp = processor.process(secureApdu)
        assertEquals("6A80", resp.toHex()) // SW_INCORRECT_DATA
    }

    @Test
    fun reselectingAidClearsSession() = runBlocking {
        authenticateTestSession()
        assertNotNull(processor.sessionHtk)

        // Reselect AID
        val selResp = processor.process("00A4040006F01234567821".hexToBytes())
        assertEquals("9000", selResp.toHex())
        assertNull(processor.sessionHtk)

        // Subsequent secure APDU returns 6982
        val envelope = MCardCrypto.encryptAesGcm("8000000000".hexToBytes(), ByteArray(16))
        val secureApdu = byteArrayOf(
            0x80.toByte(), 0x90.toByte(), 0x00, 0x00, envelope.size.toByte()
        ) + envelope + byteArrayOf(0x00)
        assertEquals("6982", processor.process(secureApdu).toHex())
    }

    private suspend fun authenticateTestSession() {
        processor.process("00A4040006F01234567821".hexToBytes())
        // Step 1
        val chunkSize = 160
        val totalChunks = (cadCertDer.size + chunkSize - 1) / chunkSize
        for (i in 0 until totalChunks) {
            val start = i * chunkSize
            val end = min(start + chunkSize, cadCertDer.size)
            val more = end < cadCertDer.size
            val chunk = cadCertDer.copyOfRange(start, end)
            val cmd = byteArrayOf(
                0x80.toByte(), 0x80.toByte(), i.toByte(), (if (more) 1 else 0).toByte(), chunk.size.toByte()
            ) + chunk + byteArrayOf(0x00)
            processor.process(cmd)
        }
        // Step 2b: N1
        val n1Resp0 = processor.process(byteArrayOf(0x80.toByte(), 0x83.toByte(), 0x00, 0x00, 0x00))
        val n1Resp1 = processor.process(byteArrayOf(0x80.toByte(), 0x83.toByte(), 0x01, 0x00, 0x00))
        val n1Enc = n1Resp0.dropLast(2).toByteArray().drop(1).toByteArray() +
            n1Resp1.dropLast(2).toByteArray().drop(1).toByteArray()
        val n1 = MCardCrypto.decryptRsa(n1Enc, cadPrivateKey)

        // Step 3
        val n1Plus1 = MCardCrypto.incrementNonce(n1)
        val n2 = MCardCrypto.generateNonce(16)
        val mutualEncrypted = MCardCrypto.encryptRsa(n1Plus1 + n2, cardPublicKey)
        processor.process(
            byteArrayOf(0x80.toByte(), 0x84.toByte(), 0x00, 0x01, 128.toByte()) +
                mutualEncrypted.copyOfRange(0, 128) + byteArrayOf(0x00)
        )
        processor.process(
            byteArrayOf(0x80.toByte(), 0x84.toByte(), 0x01, 0x00, 128.toByte()) +
                mutualEncrypted.copyOfRange(128, 256) + byteArrayOf(0x00)
        )

        // Step 4
        processor.process(byteArrayOf(0x80.toByte(), 0x86.toByte(), 0x00, 0x00, 0x00))
        processor.process(byteArrayOf(0x80.toByte(), 0x86.toByte(), 0x01, 0x00, 0x00))

        // Step 5
        val htk = MCardCrypto.generateSessionKey(16)
        val htkEncrypted = MCardCrypto.encryptRsa(htk, cardPublicKey)
        processor.process(
            byteArrayOf(0x80.toByte(), 0x88.toByte(), 0x00, 0x01, 128.toByte()) +
                htkEncrypted.copyOfRange(0, 128) + byteArrayOf(0x00)
        )
        processor.process(
            byteArrayOf(0x80.toByte(), 0x88.toByte(), 0x01, 0x00, 128.toByte()) +
                htkEncrypted.copyOfRange(128, 256) + byteArrayOf(0x00)
        )
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
