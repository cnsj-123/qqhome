package com.qq.closie.life.capture

/**
 * Domain contract for the future unified Capture Pipeline.
 *
 * This is NOT a database entity and NOT a万能 JSON blob. It is a strongly-typed contract that
 * every capture source (Bubble, Share, Clipboard, URL, Camera, Gallery, Notification, Manual)
 * will eventually produce before being persisted as a [CaptureItemEntity].
 *
 * Future pipeline:
 *   Bubble / Share / Clipboard / URL / Camera / Gallery / Notification / Manual
 *       ↓
 *   CapturePayload
 *       ↓
 *   CaptureItem
 *       ↓
 *   Parser Pipeline
 *       ↓
 *   Candidate Fields
 *       ↓
 *   User Confirm
 *       ↓
 *   Typed Domain Entity
 */
data class CapturePayload(
    val source: CaptureSource,
    val rawText: String? = null,
    val sourceUrl: String? = null,
    val mediaReference: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)
