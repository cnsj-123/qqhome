package com.qq.closie.life.media

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.qq.closie.data.backup.BackupValidator
import com.qq.closie.life.data.database.LifeDatabase
import com.qq.closie.life.repository.MediaRepository
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The durability contract of a single media import.
 *
 * A managed media file is content-addressed: its name *is* the SHA-256 of its bytes, and every
 * business row that references it does so by that name. That makes the file itself an identity, and an
 * identity that can be wrong is worse than one that is missing — a missing file shows a broken
 * thumbnail, while a file whose contents do not match its name is silently trusted forever.
 *
 * Two shapes of "wrong" are covered here, and they are different bugs:
 *
 *  1. **A partial file under the final name.** Caused by a write that was interrupted (process kill,
 *     full disk). The old code wrote directly to `<sha256>.<ext>`, so `exists()` returned true while
 *     the file held a prefix of the bytes. The next import of the same image would compute the same
 *     sha256, see the file "already there", and dedup onto the truncated copy — permanently.
 *  2. **A pre-existing file with the right name and the wrong bytes.** Caused by a previous
 *     corruption or a half-replaced write. `exists()` is equally useless here; only re-hashing the
 *     content decides.
 *
 * In both cases the required behaviour is the same: never trust the name, verify the bytes, and
 * publish through a temporary file so the final name is only ever observable in a complete state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaStoreImporterTest {

    private lateinit var context: Context
    private lateinit var db: LifeDatabase
    private lateinit var media: MediaRepository
    private lateinit var importer: MediaStoreImporter

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LifeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        media = MediaRepository(db)
        importer = MediaStoreImporter(db, media)
        // A clean media directory per test: these tests are about file state, so inheriting files from
        // a previous test would make "the file was not created" assertions meaningless.
        File(context.filesDir, MediaStoreImporter.MEDIA_DIR).deleteRecursively()
    }

    @After
    fun tearDown() {
        runCatching { db.close() }
        File(context.filesDir, MediaStoreImporter.MEDIA_DIR).deleteRecursively()
    }

    private fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun mediaDir(): File = File(context.filesDir, MediaStoreImporter.MEDIA_DIR)

    /**
     * The extension the importer derives for a `file://` source.
     *
     * `ContentResolver.getType` returns null for a `file://` URI, so the importer falls back to
     * `image/<wildcard>` and then to its `else -> "img"` branch. Hard-coding `.png` here would make the tests
     * assert against a name production never creates — the assertions would fail for the right reason
     * but point at the wrong cause, and a future change to the fallback would silently decouple these
     * tests from reality. Naming the constant makes that coupling explicit.
     */
    private val EXT = "img"

    /** A URI the importer can read, backed by a real file on disk. */
    private fun readableUri(bytes: ByteArray, name: String): Uri {
        val f = File(context.cacheDir, name)
        f.writeBytes(bytes)
        return Uri.fromFile(f)
    }

    /**
     * Runs [MediaStoreImporter.import] and returns its value.
     *
     * `runTest` itself returns `TestResult` (i.e. `Unit`), so it cannot be used as an expression here —
     * the value under test has to be threaded back out of the coroutine explicitly.
     */
    private fun importResult(uri: Uri): MediaImportResult {
        var captured: MediaImportResult? = null
        runTest { captured = importer.import(context, uri) }
        return captured ?: error("import() did not return")
    }

    /**
     * A pre-existing final file with the *wrong* bytes must not be reused.
     *
     * This is the "interrupted write" shape reproduced deterministically: the file sits at exactly the
     * name the importer is about to publish to, but its contents are a different (shorter) byte string.
     * An implementation that trusted `exists()` would report success and record a row pointing at
     * corrupt bytes — and because `managedPathFor` only checks `isFile`, the corruption would never be
     * noticed afterwards either.
     */
    @Test
    fun corruptedExistingFinal_isReplacedNotTrusted() {
        val bytes = byteArrayOf(10, 20, 30, 40, 50)
        val sha = sha256Of(bytes)
        val dir = mediaDir().apply { mkdirs() }
        val target = File(dir, "$sha.${EXT}")
        // The exact name, the wrong content — and shorter, as a torn write would leave it.
        target.writeBytes(byteArrayOf(10, 20))

        val result = importResult(readableUri(bytes, "corrupt-source.png"))

        assertThat(result).isInstanceOf(MediaImportResult.Success::class.java)
        // The file is now the real content, not the leftover.
        assertThat(target.readBytes()).isEqualTo(bytes)
        assertThat(BackupValidator.sha256Hex(target)).isEqualTo(sha)
        // …and the recorded row points at a file whose bytes actually match its recorded hash.
        val success = result as MediaImportResult.Success
        assertThat(File(success.resource.managedPath!!).canonicalPath).isEqualTo(target.canonicalPath)
        assertThat(success.resource.sizeBytes).isEqualTo(bytes.size.toLong())
    }

    /**
     * A *legitimate* existing final file is reused, and no duplicate is created.
     *
     * The positive control for the rule above. Without it, "always rewrite" would pass the corrupted
     * case while quietly throwing away the content-addressing the whole design rests on.
     */
    @Test
    fun correctExistingFinal_isReused() {
        val bytes = byteArrayOf(60, 70, 80)
        val sha = sha256Of(bytes)
        val dir = mediaDir().apply { mkdirs() }
        val target = File(dir, "$sha.${EXT}")
        target.writeBytes(bytes)
        val modifiedAt = target.lastModified()

        val result = importResult(readableUri(bytes, "reuse-source.png"))

        assertThat(result).isInstanceOf(MediaImportResult.Success::class.java)
        assertThat(target.readBytes()).isEqualTo(bytes)
        // Reused rather than rewritten: the same inode/file was kept, which is what "reuse" means.
        assertThat(target.lastModified()).isEqualTo(modifiedAt)
        // Exactly one file in the directory — no stray temp or duplicate was left behind.
        assertThat(dir.listFiles().orEmpty().map { it.name }).containsExactly("$sha.${EXT}")
    }

    /**
     * A successful import must never leave a temporary file behind, and must never publish a partial
     * one under the final name.
     *
     * `tmp → verify → publish` is only correct if the temporary file is *invisible* in the steady state
     * and the final name is only created by an atomic rename. So after a successful import the
     * directory must contain the final file and nothing whose name marks it as scratch.
     */
    @Test
    fun successfulImport_leavesNoTemporaryFile() {
        val bytes = byteArrayOf(1, 3, 5, 7, 9, 11)
        val sha = sha256Of(bytes)

        val result = importResult(readableUri(bytes, "clean-source.png"))
        assertThat(result).isInstanceOf(MediaImportResult.Success::class.java)

        val names = mediaDir().listFiles().orEmpty().map { it.name }
        assertThat(names).containsExactly("$sha.${EXT}")
        // Belt and braces: nothing that looks like the temp-file convention survives.
        assertThat(names.none { it.startsWith(".") || it.endsWith(".tmp") }).isTrue()
    }

    /**
     * An unreadable source URI must fail without creating anything at all.
     *
     * The "read the bytes first" ordering exists precisely so that a failed import cannot leave an
     * ownerless file behind — no asset row, no resource row, no file. A failure that leaked a file
     * would be storage that nothing references and nothing will ever collect.
     */
    @Test
    fun unreadableSource_recordsNothingAndWritesNothing() {
        val missing = Uri.fromFile(File(context.cacheDir, "does-not-exist-${System.nanoTime()}.png"))

        val result = importResult(missing)

        assertThat(result).isInstanceOf(MediaImportResult.Failure::class.java)
        assertThat(mediaDir().listFiles().orEmpty()).isEmpty()
        assertThat(runTestBlocking { db.mediaDao().getAllAssets() }).isEmpty()
        assertThat(runTestBlocking { db.mediaDao().getAllResources() }).isEmpty()
    }

    /**
     * The recorded row must be consistent with the file it points at — the same hash, the same size,
     * and a path that resolves to real bytes.
     *
     * This is the invariant the whole class exists to maintain, so it is asserted end to end rather
     * than left implied by the two tests above: a row whose `sha256` disagrees with its file is a
     * backup that will either drop the file or fail, depending on which side is checked first.
     */
    @Test
    fun recordedRowIsConsistentWithThePublishedFile() {
        val bytes = byteArrayOf(2, 4, 6, 8, 10, 12, 14)
        val sha = sha256Of(bytes)

        val result = importResult(readableUri(bytes, "consistent-source.png"))
        assertThat(result).isInstanceOf(MediaImportResult.Success::class.java)
        val success = result as MediaImportResult.Success

        val file = File(success.resource.managedPath!!)
        assertThat(file.isFile).isTrue()
        assertThat(file.name).isEqualTo("$sha.${EXT}")
        assertThat(BackupValidator.sha256Hex(file)).isEqualTo(success.resource.sha256)
        assertThat(file.length()).isEqualTo(success.resource.sizeBytes)
    }

    /**
     * A second import of the *same* image must never disturb the file the first one recorded.
     *
     * The cleanup rule is ownership-based: on failure, remove the file **only if this call created
     * it**. The dangerous version of the rule is the obvious one — "delete the target on failure" —
     * and it is dangerous in exactly the case that matters: several business records can reference the
     * same image, so they share one content-addressed file. A later import that failed after touching
     * that file would break the earlier record's still-committed row, turning a working thumbnail into
     * a missing one with no error ever reported.
     *
     * This exercises the rule from the reachable side: the second call resolves through dedup, so the
     * importer must treat the file as *not its own* — reuse it, leave it byte-identical, and add no
     * second row or second file. If a future change made the importer re-publish on every call, the
     * timestamp and the row count here would both move.
     */
    @Test
    fun secondImportOfTheSameImage_leavesTheOriginalFileAndRowUntouched() {
        val bytes = byteArrayOf(31, 41, 59, 26)
        val sha = sha256Of(bytes)

        // First import publishes the file and commits its row.
        val first = importResult(readableUri(bytes, "shared-source.png"))
        assertThat(first).isInstanceOf(MediaImportResult.Success::class.java)
        val target = File(mediaDir(), "$sha.${EXT}")
        assertThat(target.isFile).isTrue()
        val modifiedAt = target.lastModified()
        val rowCountAfterFirst = runTestBlocking { db.mediaDao().getAllResources().size }

        // A second import of the same bytes resolves through dedup rather than rebuilding the file.
        val second = importResult(readableUri(bytes, "shared-source-2.png"))
        assertThat(second).isInstanceOf(MediaImportResult.Success::class.java)

        assertThat(target.isFile).isTrue()
        assertThat(target.readBytes()).isEqualTo(bytes)
        assertThat(BackupValidator.sha256Hex(target)).isEqualTo(sha)
        // The file was reused, not rewritten.
        assertThat(target.lastModified()).isEqualTo(modifiedAt)
        // …and no duplicate row or duplicate file was created.
        assertThat(runTestBlocking { db.mediaDao().getAllResources().size }).isEqualTo(rowCountAfterFirst)
        assertThat(mediaDir().listFiles().orEmpty().map { it.name }).containsExactly("$sha.${EXT}")
    }

    /** Runs a suspend block and returns its value, for DAO reads inside non-suspend test bodies. */
    private fun <T> runTestBlocking(block: suspend () -> T): T {
        var captured: T? = null
        runTest { captured = block() }
        @Suppress("UNCHECKED_CAST")
        return captured as T
    }
}
