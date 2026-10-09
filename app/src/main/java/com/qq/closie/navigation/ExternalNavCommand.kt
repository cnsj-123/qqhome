package com.qq.closie.navigation

import java.io.Serializable

/** Saved UI request, never a canonical fact. The newest incoming request takes precedence. */
sealed interface ExternalNavCommand : Serializable {
    val nonce: Long
    data class ProductImport(val text: String, override val nonce: Long) : ExternalNavCommand
    data class ReferenceLink(val originalText: String, val url: String, override val nonce: Long) : ExternalNavCommand
    data class CaptureText(val text: String, override val nonce: Long) : ExternalNavCommand
    data class Edit(val itemId: String, override val nonce: Long) : ExternalNavCommand
    data class Add(override val nonce: Long) : ExternalNavCommand
}
