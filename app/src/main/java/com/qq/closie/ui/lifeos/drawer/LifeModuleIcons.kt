package com.qq.closie.ui.lifeos.drawer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector

/** Icons are presentation only; stable module identities stay in LifeModules. */
fun LifeModule.icon(): ImageVector = when (id) {
    "capture" -> Icons.Outlined.Add
    "inbox" -> Icons.Outlined.Inbox
    "calendar" -> Icons.Outlined.CalendarMonth
    "search" -> Icons.Outlined.Search
    "closet" -> Icons.Outlined.Checkroom
    "shopping" -> Icons.Outlined.ShoppingBag
    "items" -> Icons.Outlined.Inventory2
    "food" -> Icons.Outlined.Restaurant
    "skincare" -> Icons.Outlined.Spa
    "garden" -> Icons.Outlined.LocalFlorist
    "reading" -> Icons.Outlined.MenuBook
    "hobbies" -> Icons.Outlined.Palette
    "gallery" -> Icons.Outlined.Image
    "travel" -> Icons.Outlined.Luggage
    "place" -> Icons.Outlined.Storefront
    "plans" -> Icons.Outlined.EventNote
    "marks" -> Icons.Outlined.BookmarkBorder
    "map" -> Icons.Outlined.Map
    "plog" -> Icons.Outlined.AutoStories
    "knowledge" -> Icons.Outlined.FolderOpen
    "media" -> Icons.Outlined.PhotoLibrary
    "companion" -> Icons.Outlined.ChatBubbleOutline
    "vault" -> Icons.Outlined.Lock
    "finance" -> Icons.Outlined.ReceiptLong
    "membership" -> Icons.Outlined.CardMembership
    "health" -> Icons.Outlined.FavoriteBorder
    "privacy" -> Icons.Outlined.Shield
    "backup" -> Icons.Outlined.Backup
    "settings" -> Icons.Outlined.Settings
    else -> Icons.Outlined.Circle
}
