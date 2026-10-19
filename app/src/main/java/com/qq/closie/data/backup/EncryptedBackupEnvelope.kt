package com.qq.closie.data.backup

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Portable, versioned authenticated framing over JCA AES-256-GCM.
 * Each 64 KiB record authenticates the complete header, sequence and plaintext length. A mandatory
 * authenticated empty final record prevents truncation. No record nonce repeats under a derived key.
 * Decrypt writes private staging only; the caller publishes nothing until the final record verifies.
 */
object EncryptedBackupEnvelope {
    private val magic = "LIFEOS5E".toByteArray(Charsets.US_ASCII)
    private const val VERSION = 2
    private const val ITERATIONS = 600_000
    private const val HEADER_SIZE = 44
    private const val CHUNK = 64 * 1024
    private const val MAX_BYTES = 8L * 1024 * 1024 * 1024

    fun encrypt(input: InputStream, output: OutputStream, password: CharArray) {
        require(password.size >= 12) { "备份口令至少 12 个字符，请单独妥善保管" }
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val noncePrefix = ByteArray(8).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_SIZE).put(magic).putInt(VERSION).putInt(ITERATIONS)
            .put(salt).put(noncePrefix).putInt(CHUNK).array()
        val key = derive(password, salt, ITERATIONS)
        val buffer = ByteArray(CHUNK)
        try {
            val out = DataOutputStream(output)
            out.write(header)
            var sequence = 0
            var total = 0L
            while (true) {
                val size = readChunk(input, buffer)
                total = Math.addExact(total, size.toLong())
                require(total <= MAX_BYTES) { "备份超过当前安全大小限制" }
                val metadata = ByteBuffer.allocate(8).putInt(sequence).putInt(size).array()
                val cipher = cipher(Cipher.ENCRYPT_MODE, key, noncePrefix, sequence, header, metadata)
                out.write(metadata); out.write(cipher.doFinal(buffer, 0, size))
                if (size == 0) break
                sequence = Math.addExact(sequence, 1)
            }
        } finally { key.fill(0); buffer.fill(0) }
    }

    fun decrypt(input: InputStream, output: OutputStream, password: CharArray) {
        val source = DataInputStream(input)
        val header = ByteArray(HEADER_SIZE)
        source.readFully(header)
        val values = ByteBuffer.wrap(header)
        val found = ByteArray(8); values.get(found)
        require(found.contentEquals(magic) && values.int == VERSION) { "不支持的加密备份格式" }
        val rounds = values.int
        require(rounds in ITERATIONS..1_200_000) { "加密备份参数无效" }
        val salt = ByteArray(16); values.get(salt)
        val prefix = ByteArray(8); values.get(prefix)
        require(values.int == CHUNK) { "加密备份分段参数无效" }
        val key = derive(password, salt, rounds)
        try {
            var sequence = 0
            var total = 0L
            while (true) {
                val index = source.readInt()
                val size = source.readInt()
                require(index == sequence && size in 0..CHUNK) { "加密备份记录顺序或大小无效" }
                total = Math.addExact(total, size.toLong())
                require(total <= MAX_BYTES) { "备份超过当前安全大小限制" }
                val encrypted = ByteArray(size + 16)
                source.readFully(encrypted)
                val metadata = ByteBuffer.allocate(8).putInt(index).putInt(size).array()
                val clear = cipher(Cipher.DECRYPT_MODE, key, prefix, index, header, metadata).doFinal(encrypted)
                try {
                    check(clear.size == size)
                    if (size == 0) {
                        require(source.read() == -1) { "加密备份存在未认证尾部" }
                        break
                    }
                    output.write(clear)
                } finally { clear.fill(0) }
                sequence = Math.addExact(sequence, 1)
            }
        } catch (e: javax.crypto.AEADBadTagException) {
            throw IllegalArgumentException("口令错误或备份已损坏，原数据未修改", e)
        } finally { key.fill(0) }
    }

    private fun derive(password: CharArray, salt: ByteArray, rounds: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, rounds, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
    }
    private fun cipher(mode: Int, key: ByteArray, prefix: ByteArray, sequence: Int,
        header: ByteArray, metadata: ByteArray): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        val nonce = ByteBuffer.allocate(12).put(prefix).putInt(sequence).array()
        init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        updateAAD(header); updateAAD(metadata)
    }
    private fun readChunk(input: InputStream, buffer: ByteArray): Int {
        var size = 0
        while (size < buffer.size) {
            val count = input.read(buffer, size, buffer.size - size)
            if (count < 0) break
            if (count == 0) continue
            size += count
        }
        return size
    }
}
