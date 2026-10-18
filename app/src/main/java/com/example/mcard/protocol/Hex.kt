package com.example.mcard.protocol

fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02X".format(byte) }

fun String.hexToBytes(): ByteArray {
    val clean = filterNot { it.isWhitespace() }
    require(clean.length % 2 == 0) { "Hex string must have an even length." }
    return ByteArray(clean.length / 2) { index ->
        clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

fun Int.u16be(): ByteArray = byteArrayOf(((this ushr 8) and 0xFF).toByte(), (this and 0xFF).toByte())

fun ByteArray.plusStatus(sw: Int): ByteArray = this + sw.u16be()
