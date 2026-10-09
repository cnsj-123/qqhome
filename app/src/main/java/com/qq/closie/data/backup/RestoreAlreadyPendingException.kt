package com.qq.closie.data.backup

/**
 * Thrown when a **new** restore is refused because one is already in flight or a previous one has not
 * been concluded yet.
 *
 * ### Why this is not [RestoreRecoveryPendingException]
 *
 * The two refusals look alike and mean opposite things:
 *
 * | | [RestoreRecoveryPendingException] | this |
 * |---|---|---|
 * | what is refused | reading the user's data | starting a restore |
 * | who is refused | business code | the Settings screen's restore button |
 * | what the user should do | wait / relaunch | wait for the running restore, or relaunch if it died |
 * | is it an error? | the app is correctly blocking | no — the request arrived at a bad time |
 *
 * Conflating them would make "recovery is broken" and "please try again in a moment" the same message,
 * and the caller cannot act on the difference. They are also raised from different layers: the recovery
 * pending exception comes from the gate's read barrier and is thrown by *every* accessor, whereas this
 * one is produced only by [BackupManager.restore]'s preflight and is returned to the caller as a
 * `Result.failure` rather than thrown out of the suspend function.
 *
 * ### The two conditions it covers, and why both must be refused
 *
 *  - **A leftover marker.** A `COMMITTED` or `ROLLED_BACK` marker left behind by a failed cleanup leaves
 *    the data perfectly consistent — which is why the business gate is legitimately READY and the user
 *    may keep working. But it still points at a parked directory and a snapshot that only the next
 *    cleanup pass removes. A new restore writes the same marker file, so starting one would silently
 *    drop that pointer: the leftovers become permanent litter holding the user's *previous* wardrobe,
 *    with nothing on disk recording that they are there. Note the asymmetry this creates on purpose:
 *    business-READY does not imply restore-allowed.
 *  - **A restore already running.** Two concurrent restores share one marker path and one snapshot path.
 *    Whichever writes last owns the evidence, and the other's compensation would then roll the shared
 *    state back over the winner's data — a race whose result depends on timing rather than on any
 *    decision. [RestoreStartupGate.beginRestore] makes it a decision.
 *
 * ### Why the caller gets a `Result.failure` and not this exception
 *
 * The restore entry point is a `suspend` function called from a button handler that does
 * `result.fold(onSuccess, onFailure)` and then clears its `busy` flag. Anything thrown out of it escapes
 * that block, so the spinner never stops and the screen is stuck — a worse outcome than the refusal
 * itself. "Someone else is already restoring" is a legitimate outcome of pressing the button, so it is
 * reported as one.
 */
class RestoreAlreadyPendingException(
    cause: Throwable?,
    message: String = "已有恢复正在进行或上一次恢复尚未收尾，不能开始新的恢复"
) : IllegalStateException(message, cause)
