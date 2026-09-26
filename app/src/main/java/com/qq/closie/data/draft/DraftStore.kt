package com.qq.closie.data.draft

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.data.model.Ootd
import com.qq.closie.data.model.Outfit
import java.io.File

/**
 * Persistent local drafts for OOTD and Outfit editors, stored as JSON so an unfinished edit
 * survives process death. Kept separate from the live wardrobe files. The [File]-based primary
 * constructor keeps the persistence logic unit-testable without an Android Context.
 *
 * ### Why every mutation here holds one business lease, and why it is one lease per mutation
 *
 * Drafts live in `filesDir/closie/drafts/`, inside the `closie/` directory a restore replaces
 * **wholesale** — the live tree is renamed aside and a staged generation is moved into its place. Two
 * distinct failures follow, and they need different halves of the same fix.
 *
 * **The window between the read and the write.** Every mutation here is a read-modify-write:
 * `saveOotdDraft` reads the list, replaces or appends one record, and writes the whole file back;
 * `deleteOotdDraft` reads the list, removes one, writes the rest. If a restore swaps `closie/` between
 * the read and the write, the write lands in the *new* generation — so the draft list written back is
 * derived from the *old* one, and it silently discards every draft the restore just brought in. Taking a
 * lease for the read and a second for the write would not help: the swap fits precisely between them,
 * which is the interleaving to prevent, so the **whole** read-modify-write runs under **one** lease.
 *
 * **The write itself.** Even a mutation that skipped the read would still be writing into a directory
 * that can be moved out from under it mid-write, landing the file in the generation that is about to be
 * deleted.
 *
 * So the guarantee is: while any draft mutation is in flight, `beginRestore()` refuses; while a restore
 * owns the gate, no draft mutation can start. Drafts are small and their writes are short, so the refusal
 * window is negligible — and it is the correct direction to fail in, because the alternative silently
 * loses user work.
 *
 * ### Reads are leased too, and the earlier reasoning for not leasing them was wrong
 *
 * A previous revision left `listOotdDrafts` / `listOutfitDrafts` unleased, on the argument that a read
 * landing inside the swap returns a "stale but consistent" previous generation. That argument does not
 * survive contact with what the swap actually does. The restore replaces `closie/` with **two** renames:
 * the live directory is renamed aside, and only then is the staged generation renamed into its place.
 * Between the two there is no `closie/` directory at all, so a read in that window finds no file — and
 * `read` reports a missing file as `emptyList()`.
 *
 * The result is not a stale list. It is a **false empty**: the drafts screen shows "you have no drafts"
 * while the user's drafts are sitting in `.closie_restore_old_<id>/`, and any UI that acts on that answer
 * acts on a lie. That is a different failure class from staleness, and it is why the reads take a lease.
 *
 * A lease rather than a bare `requireReady()` check, because the check would still leave the read itself
 * able to straddle the swap: `readText` on a file whose directory is being renamed out from under it is
 * exactly the race. The lease is what makes the check and the read one step.
 *
 * The cost is that listing drafts briefly refuses a `beginRestore()`, which a previous revision weighed
 * against staleness and chose the other way. Given the false-empty above, the trade is not close: these
 * are local file reads measured in microseconds, and the alternative is a screen telling the user their
 * work is gone.
 *
 * ### Where the boundary is drawn now
 *
 * Every method that touches `closie/drafts/` — the four mutations and the two reads — holds one lease per
 * call. Nothing here is ungated.
 *
 * [DraftImageCleanup] is pure and touches no storage, so it is not leased. Its *caller* deletes the files
 * it returns through [com.qq.closie.data.ImageStore.deletePrivatePath], which takes its own lease — and
 * that is the right owner for it, because it is the object that knows the path layout it is containing
 * the delete against.
 */
class DraftStore(private val folder: File) {
    constructor(context: Context) : this(File(context.filesDir, "closie/drafts"))

    private val gson = Gson()
    private val ootdType = object : TypeToken<List<Ootd>>() {}.type
    private val outfitType = object : TypeToken<List<Outfit>>() {}.type

    init {
        folder.mkdirs()
    }

    private fun <T> read(name: String, type: java.lang.reflect.Type): List<T> =
        runCatching { File(folder, name).takeIf { it.exists() }?.let { gson.fromJson<List<T>>(it.readText(), type) } ?: emptyList() }
            .getOrDefault(emptyList())

    private fun write(name: String, value: Any) {
        val f = File(folder, name)
        val t = File(folder, ".$name.tmp")
        t.writeText(gson.toJson(value))
        if (!t.renameTo(f)) { f.delete(); check(t.renameTo(f)) }
    }

    fun saveOotdDraft(ootd: Ootd): Ootd = RestoreStartupGate.withBusinessAccess {
        // Read and write inside the same lease — see the class doc. A lease around only the write would
        // leave the swap free to land between them and make this write discard the restored drafts.
        val existing = read<Ootd>("ootd_drafts.json", ootdType)
        val list = if (existing.any { it.id == ootd.id }) existing.map { if (it.id == ootd.id) ootd else it } else existing + ootd
        write("ootd_drafts.json", list)
        ootd
    }

    /**
     * Reads the OOTD drafts. **Leased**, like the mutations — see the class doc.
     *
     * A read does not need a lease to be *correct about the data it returns*, but it does need one not to
     * return a **false empty**. `read` reports an unreadable or missing file as `emptyList()`, and the
     * restore's swap makes `closie/` genuinely absent for the length of two renames (live → old, stage →
     * live). A read landing in that window does not see "the old generation" — it sees *no file*, and
     * reports "you have no drafts" to a screen that is about to show an empty list to the user. The lease
     * removes the window entirely rather than trying to distinguish the two cases afterwards, which no
     * amount of care at the read could do: by then the information is gone.
     */
    fun listOotdDrafts(): List<Ootd> = RestoreStartupGate.withBusinessAccess {
        read("ootd_drafts.json", ootdType)
    }

    /** Deletes a draft and returns the removed record (or null), so callers can clean its images. */
    fun deleteOotdDraft(id: String): Ootd? = RestoreStartupGate.withBusinessAccess {
        val all = read<Ootd>("ootd_drafts.json", ootdType)
        val removed = all.firstOrNull { it.id == id }
        write("ootd_drafts.json", all.filterNot { it.id == id })
        removed
    }

    fun saveOutfitDraft(outfit: Outfit): Outfit = RestoreStartupGate.withBusinessAccess {
        val existing = read<Outfit>("outfit_drafts.json", outfitType)
        val list = if (existing.any { it.id == outfit.id }) existing.map { if (it.id == outfit.id) outfit else it } else existing + outfit
        write("outfit_drafts.json", list)
        outfit
    }

    /** Reads the Outfit drafts. **Leased**, for the same reason as [listOotdDrafts]. */
    fun listOutfitDrafts(): List<Outfit> = RestoreStartupGate.withBusinessAccess {
        read("outfit_drafts.json", outfitType)
    }

    /** Deletes a draft and returns the removed record (or null), so callers can clean its images. */
    fun deleteOutfitDraft(id: String): Outfit? = RestoreStartupGate.withBusinessAccess {
        val all = read<Outfit>("outfit_drafts.json", outfitType)
        val removed = all.firstOrNull { it.id == id }
        write("outfit_drafts.json", all.filterNot { it.id == id })
        removed
    }
}

/**
 * Pure helper for draft-image garbage collection. Given the paths a removed/updated draft no longer
 * references, plus the live wardrobe references and the remaining draft references, it returns the
 * subset that is referenced nowhere and is therefore safe to delete from private storage.
 *
 * Pure and storage-free by design: the deletion it *describes* is performed by the caller through
 * [com.qq.closie.data.ImageStore.deletePrivatePath], which owns the lease for the unlink. Keeping the
 * decision here free of I/O is what lets the same logic be used from a screen, a repository and a test
 * without each of them acquiring the barrier.
 */
object DraftImageCleanup {
    fun orphans(
        removed: Collection<String>,
        live: Collection<String>,
        remainingDrafts: Collection<String>
    ): Set<String> {
        val liveSet = live.filter { it.isNotBlank() }.toSet()
        val draftSet = remainingDrafts.filter { it.isNotBlank() }.toSet()
        return removed.filter { it.isNotBlank() && it !in liveSet && it !in draftSet }.toSet()
    }

    /** Single entry point used by every screen: computes orphans and deletes them via [deleter]. */
    fun cleanupOrphans(
        removed: Collection<String>,
        live: Collection<String>,
        remainingDrafts: Collection<String>,
        deleter: (String) -> Unit
    ) {
        orphans(removed, live, remainingDrafts).forEach(deleter)
    }
}
