package com.example.mcard.protocol

import java.time.LocalDate
import java.time.temporal.ChronoUnit

object BasicInfoCodec {
    const val MAX_LENGTH = 58
    private const val FORMAT = 0x10
    private val epoch = LocalDate.of(1900, 1, 1)
    private val maxDob = LocalDate.of(2079, 6, 5)

    enum class Gender(val code: Int, val label: String) {
        Male(0, "Male"),
        Female(1, "Female"),
        NonBinary(2, "Non-binary"),
        NotStated(3, "Not stated");

        companion object {
            fun fromCode(code: Int): Gender? = entries.firstOrNull { it.code == code }
        }
    }

    enum class BloodGroup(val code: Int, val label: String) {
        Unknown(0, "Unknown"),
        APositive(1, "A+"),
        ANegative(2, "A-"),
        BPositive(3, "B+"),
        BNegative(4, "B-"),
        AbPositive(5, "AB+"),
        AbNegative(6, "AB-"),
        OPositive(7, "O+"),
        ONegative(8, "O-");

        companion object {
            fun fromCode(code: Int): BloodGroup? = entries.firstOrNull { it.code == code }
        }
    }

    data class BasicInfo(
        val patientId: String,
        val name: String,
        val dateOfBirth: LocalDate,
        val gender: Gender,
        val bloodGroup: BloodGroup,
    )

    fun encode(info: BasicInfo): ByteArray {
        requirePrintableAscii(info.patientId, 1, 12, "Patient ID")
        requirePrintableAscii(info.name, 1, 40, "Name")
        require(!info.dateOfBirth.isBefore(epoch) && !info.dateOfBirth.isAfter(maxDob)) {
            "Date of birth is outside the encodable range."
        }

        val days = ChronoUnit.DAYS.between(epoch, info.dateOfBirth).toInt()
        val flags = (info.gender.code shl 6) or (info.bloodGroup.code shl 2)
        val patientBytes = info.patientId.encodeToByteArray()
        val nameBytes = info.name.encodeToByteArray()
        val out = byteArrayOf(FORMAT.toByte(), flags.toByte()) +
            days.u16be() +
            byteArrayOf(patientBytes.size.toByte()) +
            patientBytes +
            byteArrayOf(nameBytes.size.toByte()) +
            nameBytes
        require(out.size <= MAX_LENGTH) { "Basic info block is too long." }
        return out
    }

    fun decode(block: ByteArray): BasicInfo {
        validate(block)
        val flags = block[1].toInt() and 0xFF
        val days = ((block[2].toInt() and 0xFF) shl 8) or (block[3].toInt() and 0xFF)
        var offset = 4
        val patientLength = block[offset++].toInt() and 0xFF
        val patientId = block.copyOfRange(offset, offset + patientLength).decodeToString()
        offset += patientLength
        val nameLength = block[offset++].toInt() and 0xFF
        val name = block.copyOfRange(offset, offset + nameLength).decodeToString()

        return BasicInfo(
            patientId = patientId,
            name = name,
            dateOfBirth = epoch.plusDays(days.toLong()),
            gender = requireNotNull(Gender.fromCode((flags ushr 6) and 0x03)),
            bloodGroup = requireNotNull(BloodGroup.fromCode((flags ushr 2) and 0x0F)),
        )
    }

    fun validate(block: ByteArray): Boolean {
        if (block.size < 7 || block.size > MAX_LENGTH) return false
        if ((block[0].toInt() and 0xFF) != FORMAT) return false

        val flags = block[1].toInt() and 0xFF
        if ((flags and 0x03) != 0) return false
        if (Gender.fromCode((flags ushr 6) and 0x03) == null) return false
        if (BloodGroup.fromCode((flags ushr 2) and 0x0F) == null) return false

        val days = ((block[2].toInt() and 0xFF) shl 8) or (block[3].toInt() and 0xFF)
        val dob = epoch.plusDays(days.toLong())
        if (dob.isBefore(epoch) || dob.isAfter(maxDob)) return false

        var offset = 4
        if (offset >= block.size) return false
        val patientLength = block[offset++].toInt() and 0xFF
        if (patientLength !in 1..12 || offset + patientLength >= block.size) return false
        if (!block.isPrintableAscii(offset, patientLength)) return false
        offset += patientLength

        val nameLength = block[offset++].toInt() and 0xFF
        if (nameLength !in 1..40 || offset + nameLength != block.size) return false
        if (!block.isPrintableAscii(offset, nameLength)) return false

        return true
    }

    private fun requirePrintableAscii(value: String, min: Int, max: Int, field: String) {
        require(value.length in min..max) { "$field must be $min to $max characters." }
        require(value.all { it.code in 0x20..0x7E }) { "$field must use printable ASCII." }
    }

    private fun ByteArray.isPrintableAscii(offset: Int, length: Int): Boolean {
        for (index in offset until offset + length) {
            if ((this[index].toInt() and 0xFF) !in 0x20..0x7E) return false
        }
        return true
    }
}
