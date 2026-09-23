package com.qq.closie.data.draft

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.qq.closie.data.model.Ootd
import com.qq.closie.data.model.Outfit
import java.io.File

/**
 * Persistent local drafts for OOTD and Outfit editors, stored as JSON so an unfinished edit
 * survives process death. Kept separate from the live wardrobe files. The [File]-based primary
 * constructor keeps the persistence logic unit-testable without an Android Context.
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

    fun saveOotdDraft(ootd: Ootd): Ootd {
        val existing = read<Ootd>("ootd_drafts.json", ootdType)
        val list = if (existing.any { it.id == ootd.id }) existing.map { if (it.id == ootd.id) ootd else it } else existing + ootd
        write("ootd_drafts.json", list)
        return ootd
    }

    fun listOotdDrafts(): List<Ootd> = read("ootd_drafts.json", ootdType)

    /** Deletes a draft and returns the removed record (or null), so callers can clean its images. */
    fun deleteOotdDraft(id: String): Ootd? {
        val all = read<Ootd>("ootd_drafts.json", ootdType)
        val removed = all.firstOrNull { it.id == id }
        write("ootd_drafts.json", all.filterNot { it.id == id })
        return removed
    }

    fun saveOutfitDraft(outfit: Outfit): Outfit {
        val existing = read<Outfit>("outfit_drafts.json", outfitType)
        val list = if (existing.any { it.id == outfit.id }) existing.map { if (it.id == outfit.id) outfit else it } else existing + outfit
        write("outfit_drafts.json", list)
        return outfit
    }

    fun listOutfitDrafts(): List<Outfit> = read("outfit_drafts.json", outfitType)

    /** Deletes a draft and returns the removed record (or null), so callers can clean its images. */
    fun deleteOutfitDraft(id: String): Outfit? {
        val all = read<Outfit>("outfit_drafts.json", outfitType)
        val removed = all.firstOrNull { it.id == id }
        write("outfit_drafts.json", all.filterNot { it.id == id })
        return removed
    }
}

/**
 * Pure helper for draft-image garbage collection. Given the paths a removed/updated draft no longer
 * references, plus the live wardrobe references and the remaining draft references, it returns the
 * subset that is referenced nowhere and is therefore safe to delete from private storage.
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
