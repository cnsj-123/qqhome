package com.qq.closie.data.backup

import java.io.File

/**
 * A [RestoreFs] that fails one chosen operation and otherwise behaves exactly like the real one.
 *
 * Used instead of `chmod`-style permission tricks for two reasons: those are unreliable under
 * Robolectric's filesystem, and — more importantly — they produce an *arbitrary* failure, whereas the
 * question these tests ask is about a *specific* operation ("the rename back could not be performed").
 * Targeting the operation keeps the test honest about which recovery step is under examination.
 *
 * Failure is keyed on the *destination* path, because that is what the recovery code names when it
 * decides what it is undoing.
 *
 * ### Why it is a top-level `internal` class rather than a private one
 *
 * It is shared by [RestoreCoordinatorTest], [RestoreRecoveryManagerTest] and
 * [LocalWardrobeRepositoryRecoveryTest] — the last two need it because the previous "make the
 * destination a non-empty directory" trick provably cannot fail: `revertCloset` deletes `live` before
 * renaming `old` into place, so the destination is always empty by the time the rename runs. Naming the
 * operation to fail is the only way to reach that branch. Three private copies would be three chances
 * for them to drift apart, so there is one.
 */
internal class FailingRestoreFs(
    private val failRenameInto: String? = null,
    private val failDelete: String? = null,
    /**
     * Fails deletion of any directory whose **name** contains this substring.
     *
     * Used for the post-commit cleanup tests, where the parked tree's name carries a
     * `System.currentTimeMillis()` suffix that a test cannot predict. Matching on the invariant part of
     * the name (`.closie_restore_old_`, `.life_media_restore_old_`) keeps those tests from depending on
     * the clock while still targeting exactly one class of directory.
     */
    private val failDeleteContaining: String? = null
) : RestoreFs {

    override fun isSlotOccupied(slot: File): Boolean = RealRestoreFs.isSlotOccupied(slot)

    override fun park(live: File, old: File, existedBefore: Boolean) =
        RealRestoreFs.park(live, old, existedBefore)

    override fun rename(from: File, to: File, message: String) {
        if (failRenameInto != null && to.absolutePath == failRenameInto) {
            throw java.io.IOException("注入的失败：重命名到 $failRenameInto 不可用")
        }
        RealRestoreFs.rename(from, to, message)
    }

    override fun deleteTree(dir: File) {
        if (failDelete != null && dir.absolutePath == failDelete) {
            throw java.io.IOException("注入的失败：无法删除 $failDelete")
        }
        if (failDeleteContaining != null && dir.name.contains(failDeleteContaining)) {
            throw java.io.IOException("注入的失败：无法删除 ${dir.name}")
        }
        RealRestoreFs.deleteTree(dir)
    }

    override fun cleanupChecked(intent: RestoreIntent) {
        // Deliberately mirrors [RealRestoreFs.cleanupChecked] field for field, including the stage trees.
        // A test seam that cleans up *less* than production would let a test pass while production leaks
        // the stage directory — which is the exact defect [RealRestoreFs.cleanupChecked] was extended to
        // fix, so the seam must be extended in the same commit rather than left as a stale copy.
        intent.closetOldDir?.let { deleteTree(File(it)) }
        intent.mediaOldDir?.let { deleteTree(File(it)) }
        intent.closetStageDir?.let { deleteTree(File(it)) }
        intent.mediaStageDir?.let { deleteTree(File(it)) }
        intent.dbSnapshot?.let { path ->
            val f = File(path)
            if (f.exists() && !f.delete()) throw java.io.IOException("注入的失败：无法删除快照 $path")
        }
    }
}
