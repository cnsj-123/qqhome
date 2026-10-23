package com.qq.closie.data.backup

import android.content.Context
import android.net.Uri
import com.qq.closie.data.repository.WardrobeRepository
import com.qq.closie.life.data.database.LifeDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

/** Encrypts the existing complete typed backup, and verifies stored bytes before reporting success. */
object EncryptedBackupManager {
    /** Crash leftovers are private cache, never a recovery credential or authoritative data. */
    fun cleanInterruptedScratch(context: Context) {
        val root = context.cacheDir.canonicalFile
        root.listFiles()?.filter { it.name.startsWith("secure_backup_") || it.name.startsWith("secure_restore_") }?.forEach {
            if (it.canonicalFile.parentFile == root) it.deleteRecursively()
        }
    }
    suspend fun export(context: Context, repo: WardrobeRepository, uri: Uri, db: LifeDatabase,
        password: CharArray): Result<Unit> = withContext(Dispatchers.IO) {
        val scratch = File(context.cacheDir, "secure_backup_" + UUID.randomUUID())
        try {
            runCatching {
                check(scratch.mkdirs()) { "无法创建备份暂存目录" }
                val plain = File(scratch, "payload.zip")
                BackupManager.export(context, repo, Uri.fromFile(plain), db).getOrThrow()
                val encrypted = File(scratch, "payload.lifeos")
                plain.inputStream().use { input -> encrypted.outputStream().use { output ->
                    EncryptedBackupEnvelope.encrypt(input, output, password)
                } }
                val expected = digest(plain)
                val verified = MessageDigest.getInstance("SHA-256")
                encrypted.inputStream().use { input ->
                    EncryptedBackupEnvelope.decrypt(input, object : OutputStream() {
                        override fun write(b: Int) { verified.update(b.toByte()) }
                        override fun write(b: ByteArray, off: Int, len: Int) { verified.update(b, off, len) }
                    }, password)
                }
                check(expected.contentEquals(verified.digest())) { "加密备份恢复校验未通过" }
                val resolver = context.contentResolver
                requireNotNull(resolver.openOutputStream(uri, "wt")) { "无法写入备份文件" }.use { out ->
                    encrypted.inputStream().use { it.copyTo(out) }
                }
                val stored:ByteArray = requireNotNull(resolver.openInputStream(uri)) { "无法重新读取并验证已写入的备份" }.use { input -> digest(input) }
                check(stored.contentEquals(digest(encrypted))) { "备份文件写入不完整" }
            }
        } finally { password.fill('\u0000'); scratch.deleteRecursively() }
    }

    suspend fun restore(context: Context, repo: WardrobeRepository, uri: Uri, db: LifeDatabase,
        password: CharArray, hooks: RestoreHooks = NoOpRestoreHooks): Result<Unit> = withContext(Dispatchers.IO) {
        val scratch = File(context.cacheDir, "secure_restore_" + UUID.randomUUID())
        try { runCatching {
            check(scratch.mkdirs()) { "无法创建恢复暂存目录" }
            val plain = File(scratch, "verified.zip")
            requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                plain.outputStream().use { output -> EncryptedBackupEnvelope.decrypt(input, output, password) }
            }
            // Authentication completed before the existing staging/coordinator sees any bytes.
            BackupManager.restore(context, repo, Uri.fromFile(plain), db, hooks).getOrThrow()
        } } finally { password.fill('\u0000'); scratch.deleteRecursively() }
    }

    private fun digest(file: File): ByteArray = file.inputStream().use { input -> digest(input) }
    private fun digest(input: java.io.InputStream): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) { val count = input.read(buffer); if (count < 0) break; if (count > 0) hash.update(buffer, 0, count) }
        return hash.digest()
    }
}
