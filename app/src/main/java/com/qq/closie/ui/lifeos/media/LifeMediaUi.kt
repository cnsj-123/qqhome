package com.qq.closie.ui.lifeos.media

enum class LifeMediaKind { PHOTO, MOTION_PHOTO, VIDEO }
enum class MediaSourceState { AVAILABLE, SOURCE_MISSING, PENDING_RESTORE }

/** Shared presentation DTO. id is supplied by a projection; uri is only a source locator. */
data class LifeMediaUi(
    val id: String, val uri: String?, val title: String? = null, val caption: String? = null,
    val mediaKind: LifeMediaKind = LifeMediaKind.PHOTO,
    val motionAvailable: Boolean = false, val pairedVideoUri: String? = null,
    val sourceState: MediaSourceState = MediaSourceState.AVAILABLE
)
