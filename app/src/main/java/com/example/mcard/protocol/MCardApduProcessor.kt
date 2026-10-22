package com.example.mcard.protocol

import com.example.mcard.crypto.MCardCrypto
import com.example.mcard.crypto.TestPki
import com.example.mcard.storage.CardStore
import com.example.mcard.storage.MCardStore
import java.io.ByteArrayOutputStream
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.X509Certificate
import kotlin.math.min

class MCardApduProcessor(
    private val store: CardStore,
    private val cardCertDer: ByteArray = TestPki.CardCertDer,
    private val cardPrivateKey: PrivateKey = MCardCrypto.parsePrivateKey(TestPki.CardPrivateKeyPem),
    private val rootCaCert: X509Certificate = MCardCrypto.parseCertificate(TestPki.RootCaCertPem),
    var requireSecureSession: Boolean = false
) {
    // Session state
    var sessionHtk: ByteArray? = null
        private set

    private val cadCertBuffer = ByteArrayOutputStream()
    private var cadCertChunksExpected = 0
    private var peerCadPublicKey: PublicKey? = null
    private var cardNonceN1: ByteArray? = null
    private var cadNonceN2: ByteArray? = null
    private var cachedEncryptedN1: ByteArray? = null
    private var cachedEncryptedAck: ByteArray? = null
    private val mutualNonceBuffer = ByteArrayOutputStream()
    private val htkBuffer = ByteArrayOutputStream()

    fun resetSession() {
        sessionHtk = null
        cadCertBuffer.reset()
        cadCertChunksExpected = 0
        peerCadPublicKey = null
        cardNonceN1 = null
        cadNonceN2 = null
        cachedEncryptedN1 = null
        cachedEncryptedAck = null
        mutualNonceBuffer.reset()
        htkBuffer.reset()
    }

    suspend fun process(command: ByteArray): ByteArray {
        if (isSelect(command)) {
            resetSession()
            return status(SW_SUCCESS)
        }
        if (command.size < 4) return status(SW_WRONG_LENGTH)

        val cla = command[0].toInt() and 0xFF
        if (cla != CLA) return status(SW_CLA_NOT_SUPPORTED)

        return when (val ins = command[1].toInt() and 0xFF) {
            // Phase 3 Handshake APDUs
            INS_AUTH_SEND_CAD_CERT -> handleSendCadCert(command)
            INS_AUTH_GET_CARD_CERT -> handleGetCardCert(command)
            INS_AUTH_GET_N1 -> handleGetN1(command)
            INS_AUTH_SEND_MUTUAL_NONCE -> handleSendMutualNonce(command)
            INS_AUTH_GET_NONCE_ACK -> handleGetNonceAck(command)
            INS_AUTH_ESTABLISH_HTK -> handleEstablishHtk(command)

            // Phase 3 Secure APDU
            INS_SECURE_APDU -> handleSecureApdu(command)

            // Data commands
            INS_GET_INFO, INS_GET_BASIC_INFO, INS_ADD_RECORD, INS_GET_RECORDS -> {
                if (requireSecureSession) {
                    status(SW_NO_SECURE_SESSION)
                } else {
                    processDataCommand(ins, command)
                }
            }
            else -> status(SW_INS_NOT_SUPPORTED)
        }
    }

    private suspend fun handleSecureApdu(command: ByteArray): ByteArray {
        val htk = sessionHtk ?: return status(SW_NO_SECURE_SESSION)
        if (command.size < 5) return status(SW_WRONG_LENGTH)
        val lc = command[4].toInt() and 0xFF
        if (command.size < 5 + lc) return status(SW_WRONG_LENGTH)
        val envelope = command.copyOfRange(5, 5 + lc)

        val decrypted: ByteArray = try {
            MCardCrypto.decryptAesGcm(envelope, htk)
        } catch (_: Exception) {
            return status(SW_INCORRECT_DATA)
        }

        if (decrypted.size < 4) return status(SW_WRONG_LENGTH)
        val cla = decrypted[0].toInt() and 0xFF
        if (cla != CLA) return status(SW_CLA_NOT_SUPPORTED)
        val innerIns = decrypted[1].toInt() and 0xFF
        val innerResponse = processDataCommand(innerIns, decrypted)

        val encryptedResponse = MCardCrypto.encryptAesGcm(innerResponse, htk)
        return encryptedResponse.plusStatus(SW_SUCCESS)
    }

    private fun handleSendCadCert(command: ByteArray): ByteArray {
        if (command.size < 5) return status(SW_WRONG_LENGTH)
        val chunkIdx = command[2].toInt() and 0xFF
        val moreFlag = command[3].toInt() and 0xFF
        val lc = command[4].toInt() and 0xFF
        if (command.size < 5 + lc) return status(SW_WRONG_LENGTH)
        if (chunkIdx != cadCertChunksExpected) {
            resetSession()
            return status(SW_WRONG_P1P2)
        }

        val chunk = command.copyOfRange(5, 5 + lc)
        cadCertBuffer.write(chunk)
        cadCertChunksExpected++

        if (moreFlag == 0) {
            val fullCert = cadCertBuffer.toByteArray()
            try {
                val cert = MCardCrypto.parseCertificate(fullCert)
                if (!MCardCrypto.verify(cert, rootCaCert)) {
                    resetSession()
                    return status(SW_INCORRECT_DATA)
                }
                peerCadPublicKey = cert.publicKey
            } catch (_: Exception) {
                resetSession()
                return status(SW_INCORRECT_DATA)
            }
        }
        return status(SW_SUCCESS)
    }

    private fun handleGetCardCert(command: ByteArray): ByteArray {
        if (!isCase2(command)) return status(SW_WRONG_LENGTH)
        val chunkIdx = command[2].toInt() and 0xFF
        val chunkSize = 160
        val offset = chunkIdx * chunkSize
        if (offset >= cardCertDer.size) return status(SW_WRONG_P1P2)

        val end = min(offset + chunkSize, cardCertDer.size)
        val hasMore = end < cardCertDer.size
        val chunk = cardCertDer.copyOfRange(offset, end)
        return byteArrayOf(if (hasMore) 1 else 0) + chunk + SW_SUCCESS.u16be()
    }

    private fun handleGetN1(command: ByteArray): ByteArray {
        if (!isCase2(command)) return status(SW_WRONG_LENGTH)
        val peerKey = peerCadPublicKey ?: return status(SW_CONDITIONS_NOT_SATISFIED)
        val chunkIdx = command[2].toInt() and 0xFF

        return when (chunkIdx) {
            0 -> {
                val n1 = MCardCrypto.generateNonce(16)
                cardNonceN1 = n1
                val encrypted = MCardCrypto.encryptRsa(n1, peerKey)
                cachedEncryptedN1 = encrypted
                byteArrayOf(0x01) + encrypted.copyOfRange(0, 128) + SW_SUCCESS.u16be()
            }
            1 -> {
                val encrypted = cachedEncryptedN1 ?: return status(SW_CONDITIONS_NOT_SATISFIED)
                byteArrayOf(0x00) + encrypted.copyOfRange(128, 256) + SW_SUCCESS.u16be()
            }
            else -> status(SW_WRONG_P1P2)
        }
    }

    private fun handleSendMutualNonce(command: ByteArray): ByteArray {
        val n1 = cardNonceN1 ?: return status(SW_CONDITIONS_NOT_SATISFIED)
        if (command.size < 5) return status(SW_WRONG_LENGTH)
        val moreFlag = command[3].toInt() and 0xFF
        val lc = command[4].toInt() and 0xFF
        if (command.size < 5 + lc) return status(SW_WRONG_LENGTH)

        val chunk = command.copyOfRange(5, 5 + lc)
        mutualNonceBuffer.write(chunk)

        if (moreFlag == 0) {
            val ciphertext = mutualNonceBuffer.toByteArray()
            if (ciphertext.size != 256) {
                resetSession()
                return status(SW_WRONG_LENGTH)
            }
            val decrypted = try {
                MCardCrypto.decryptRsa(ciphertext, cardPrivateKey)
            } catch (_: Exception) {
                resetSession()
                return status(SW_INCORRECT_DATA)
            }
            if (decrypted.size != 32) {
                resetSession()
                return status(SW_INCORRECT_DATA)
            }
            val receivedN1Plus1 = decrypted.copyOfRange(0, 16)
            val expectedN1Plus1 = MCardCrypto.incrementNonce(n1)
            if (!receivedN1Plus1.contentEquals(expectedN1Plus1)) {
                resetSession()
                return status(SW_INCORRECT_DATA)
            }
            cadNonceN2 = decrypted.copyOfRange(16, 32)
        }
        return status(SW_SUCCESS)
    }

    private fun handleGetNonceAck(command: ByteArray): ByteArray {
        if (!isCase2(command)) return status(SW_WRONG_LENGTH)
        val n2 = cadNonceN2 ?: return status(SW_CONDITIONS_NOT_SATISFIED)
        val peerKey = peerCadPublicKey ?: return status(SW_CONDITIONS_NOT_SATISFIED)
        val chunkIdx = command[2].toInt() and 0xFF

        return when (chunkIdx) {
            0 -> {
                val n2Plus1 = MCardCrypto.incrementNonce(n2)
                val encrypted = MCardCrypto.encryptRsa(n2Plus1, peerKey)
                cachedEncryptedAck = encrypted
                byteArrayOf(0x01) + encrypted.copyOfRange(0, 128) + SW_SUCCESS.u16be()
            }
            1 -> {
                val encrypted = cachedEncryptedAck ?: return status(SW_CONDITIONS_NOT_SATISFIED)
                byteArrayOf(0x00) + encrypted.copyOfRange(128, 256) + SW_SUCCESS.u16be()
            }
            else -> status(SW_WRONG_P1P2)
        }
    }

    private fun handleEstablishHtk(command: ByteArray): ByteArray {
        if (cadNonceN2 == null) return status(SW_CONDITIONS_NOT_SATISFIED)
        if (command.size < 5) return status(SW_WRONG_LENGTH)
        val moreFlag = command[3].toInt() and 0xFF
        val lc = command[4].toInt() and 0xFF
        if (command.size < 5 + lc) return status(SW_WRONG_LENGTH)

        val chunk = command.copyOfRange(5, 5 + lc)
        htkBuffer.write(chunk)

        if (moreFlag == 0) {
            val ciphertext = htkBuffer.toByteArray()
            if (ciphertext.size != 256) {
                resetSession()
                return status(SW_WRONG_LENGTH)
            }
            val decrypted = try {
                MCardCrypto.decryptRsa(ciphertext, cardPrivateKey)
            } catch (_: Exception) {
                resetSession()
                return status(SW_INCORRECT_DATA)
            }
            if (decrypted.size != 16) {
                resetSession()
                return status(SW_INCORRECT_DATA)
            }
            sessionHtk = decrypted
        }
        return status(SW_SUCCESS)
    }

    private suspend fun processDataCommand(ins: Int, command: ByteArray): ByteArray = when (ins) {
        INS_GET_INFO -> getInfo(command)
        INS_GET_BASIC_INFO -> getBasicInfo(command)
        INS_ADD_RECORD -> addRecord(command)
        INS_GET_RECORDS -> getRecords(command)
        else -> status(SW_INS_NOT_SUPPORTED)
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

        private const val INS_AUTH_SEND_CAD_CERT = 0x80
        private const val INS_AUTH_GET_CARD_CERT = 0x82
        private const val INS_AUTH_GET_N1 = 0x83
        private const val INS_AUTH_SEND_MUTUAL_NONCE = 0x84
        private const val INS_AUTH_GET_NONCE_ACK = 0x86
        private const val INS_AUTH_ESTABLISH_HTK = 0x88
        private const val INS_SECURE_APDU = 0x90

        private const val SW_SUCCESS = 0x9000
        private const val SW_WRONG_LENGTH = 0x6700
        private const val SW_NO_SECURE_SESSION = 0x6982
        private const val SW_CONDITIONS_NOT_SATISFIED = 0x6985
        private const val SW_INCORRECT_DATA = 0x6A80
        private const val SW_WRONG_P1P2 = 0x6A86
        private const val SW_DATA_NOT_FOUND = 0x6A88
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
