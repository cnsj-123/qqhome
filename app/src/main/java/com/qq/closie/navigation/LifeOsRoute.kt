package com.qq.closie.navigation

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** One product graph; Closet's local graph is an embedded professional module. */
object LifeOsRoute {
    const val FINANCE = "life/finance"
    const val GRAPH = "life"
    const val HOME = "life/home"
    const val DEFAULT = HOME
    const val CLOSET = "life/closet"
    const val BACKUP = "life/backup"
    const val CALENDAR = "life/calendar"
    const val MAP = "life/map"
    const val COMPANION = "life/companion"
    const val APPEARANCE = "life/appearance"
    const val SETTINGS = "life/settings"
    const val CAPTURE = "life/capture"
    const val CAPTURE_DETAIL = "life/capture/{id}"
    const val REFERENCE = "life/reference"
    const val REFERENCE_DETAIL = "life/reference/{id}"
    const val REFERENCE_EDIT = "life/reference/{id}/edit"
    const val PLAN = "life/plans"
    const val PLAN_EDIT = "life/plans/{id}/edit"
    const val READING = "life/reading"
    const val MODULE = "life/module/{moduleId}"
    const val NEW_ID = "new"
    fun module(id: String) = "life/module/${encode(id)}"
    fun capture(id: String) = "life/capture/${encode(id)}"
    fun reference(id: String) = "life/reference/${encode(id)}"
    fun referenceEdit(id: String) = "${reference(id)}/edit"
    fun planEdit(id: String) = "life/plans/${encode(id)}/edit"
    private fun encode(id: String) = URLEncoder.encode(id, StandardCharsets.UTF_8.name()).replace("+", "%20")
}
