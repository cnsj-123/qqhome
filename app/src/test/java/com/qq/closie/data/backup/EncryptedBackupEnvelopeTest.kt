package com.qq.closie.data.backup

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class EncryptedBackupEnvelopeTest {
    private val password get() = "synthetic-only-passphrase".toCharArray()
    private fun encrypt(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also {
        EncryptedBackupEnvelope.encrypt(ByteArrayInputStream(bytes), it, password)
    }.toByteArray()
    private fun decrypt(bytes: ByteArray, key: CharArray = password): ByteArray = ByteArrayOutputStream().also {
        EncryptedBackupEnvelope.decrypt(ByteArrayInputStream(bytes), it, key)
    }.toByteArray()

    @Test fun T11_portableRoundTripUsesFreshSaltAndAuthenticatesAllBytes() {
        val plain = "合成 Gift / Unknown / obligation / -40 / 附件身份".repeat(100).toByteArray()
        val first = encrypt(plain)
        assertArrayEquals(plain, decrypt(first))
        assertFalse(first.contentEquals(encrypt(plain)))
        assertFalse(String(first, Charsets.ISO_8859_1).contains("obligation"))
        for (position in listOf(0, 12, 20, 40, first.lastIndex)) {
            val damaged = first.copyOf(); damaged[position] = (damaged[position].toInt() xor 1).toByte()
            assertTrue(runCatching { decrypt(damaged) }.isFailure)
        }
        assertTrue(runCatching { decrypt(first.copyOf(first.size - 1)) }.isFailure)
        assertTrue(runCatching { decrypt(first, "synthetic-wrong-password".toCharArray()) }.isFailure)
    }

    @Test fun malformedHeaderIsRejectedBeforeAnUnboundedKdfCanRun() {
        val bytes = encrypt(byteArrayOf(1))
        java.nio.ByteBuffer.wrap(bytes).putInt(12, Int.MAX_VALUE)
        assertTrue(runCatching { decrypt(bytes) }.isFailure)
        assertTrue(runCatching { EncryptedBackupEnvelope.encrypt(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), "short".toCharArray()) }.isFailure)
    }

    @Test fun authenticatedSegmentsRejectTruncationDuplicationOrderAndTrailingBytes() {
        val plain = ByteArray(180_000) { (it % 251).toByte() }
        val encrypted = encrypt(plain)
        assertArrayEquals(plain, decrypt(encrypted))
        val firstEnd = 44 + 8 + 65_536 + 16
        val segment = encrypted.copyOfRange(44, firstEnd)
        val duplicated = encrypted.copyOfRange(0, firstEnd) + segment + encrypted.copyOfRange(firstEnd, encrypted.size)
        assertTrue(runCatching { decrypt(duplicated) }.isFailure)
        assertTrue(runCatching { decrypt(encrypted.copyOf(firstEnd)) }.isFailure)
        assertTrue(runCatching { decrypt(encrypted + byteArrayOf(0)) }.isFailure)
        val reordered = encrypted.copyOf()
        java.nio.ByteBuffer.wrap(reordered).putInt(44, 1)
        assertTrue(runCatching { decrypt(reordered) }.isFailure)
    }
}
