package com.qq.closie.data.backup

/**
 * Thrown when an interrupted restore has not been repaired yet, so the user's data cannot be read safely.
 *
 * This is deliberately a failure to *open* rather than a degraded read. An interrupted restore can leave
 * a surface in a state that is neither the user's data nor the backup's; serving either one would present
 * a version of the truth that does not exist. The recovery marker, the parked trees and the snapshot are
 * all still on disk, so the next process start completes the repair — the cost of refusing is one failed
 * launch, and the cost of guessing is the user's data.
 *
 * ### Why this lives in `data.backup` and not next to the repository that is refused
 *
 * The exception belongs to the *restore barrier*, not to any one consumer of it. It is thrown by
 * [RestoreStartupGate.requireReady] and by every business accessor that calls that gate — the wardrobe
 * repository today, the Life OS repositories alongside it — so filing it under one particular consumer's
 * package makes the other consumers depend on that package for a reason that has nothing to do with them.
 *
 * Concretely it removes a package cycle: [RestoreStartupGate] (in `data.backup`) needed this type, and
 * `LocalWardrobeRepository` (in `data.repository`) needs that gate — so the type's old home made
 * `data.backup → data.repository → data.backup`. The dependency now points one way: consumers depend on
 * the barrier, the barrier depends on nothing.
 */
class RestoreRecoveryPendingException(cause: Throwable?) : IllegalStateException(
    "恢复尚未完成，不能安全打开数据；已保留恢复标记，将在下次启动重试",
    cause
)
