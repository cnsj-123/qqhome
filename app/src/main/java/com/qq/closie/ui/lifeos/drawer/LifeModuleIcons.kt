package com.qq.closie.ui.lifeos.drawer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Original thin-line drawings. Icon presentation never owns module identity. */
private val moduleIcons = mapOf(
    "capture" to "M12 4V20M4 12H20",
    "inbox" to "M4 5H20V19H4ZM4 13H8L10 16H14L16 13H20",
    "calendar" to "M4 6H20V21H4ZM4 10H20M8 3V8M16 3V8M8 14H9M15 14H16M8 17H9M15 17H16",
    "search" to "M10 3A7 7 0 1 0 10 17A7 7 0 1 0 10 3M15 15L21 21",
    "closet" to "M9 6C9 2 15 2 15 6C15 8 12 8 12 10L22 18H2L12 10",
    "shopping" to "M5 8H19L20 21H4ZM8 8V6A4 4 0 0 1 16 6V8",
    "items" to "M3 4H21V8H3ZM5 8V21H19V8M9 12H15",
    "food" to "M4 3V10C4 13 10 13 10 10V3M7 3V21M18 3V21M18 3C13 8 13 12 18 12",
    "skincare" to "M12 21C2 20 2 12 3 9C10 9 12 15 12 21C22 20 22 12 21 9C14 9 12 15 12 21M12 3C7 8 12 12 12 12C12 12 17 8 12 3",
    "garden" to "M12 14V21M12 19C5 19 4 16 4 14C8 14 12 16 12 19M12 18C19 18 20 15 20 13C16 13 12 15 12 18M12 4C8 0 5 5 8 8C3 12 8 15 12 11C16 15 21 12 16 8C19 5 16 0 12 4Z",
    "reading" to "M12 6C8 3 4 4 2 5V20C6 18 9 19 12 21C15 19 18 18 22 20V5C18 4 15 3 12 6V21M5 8L9 9M15 9L19 8",
    "hobbies" to "M12 3C1 3 0 19 10 21C16 23 10 16 16 16H18C25 16 21 3 12 3ZM7 8H8M13 6H14M18 9H19M5 13H6",
    "gallery" to "M3 3H21V21H3ZM3 17L9 11L14 16L17 13L21 17M16 6A1 1 0 1 0 16 8A1 1 0 1 0 16 6",
    "travel" to "M6 6H18V20H6ZM9 6V3H15V6M9 9V17M15 9V17M8 20V22M16 20V22",
    "place" to "M4 9V21H20V9M3 9L5 3H19L21 9C21 12 17 12 17 9C17 12 13 12 13 9C13 12 9 12 9 9C9 12 3 12 3 9M8 21V15H16V21",
    "plans" to "M5 5H19V21H5ZM9 5V3H15V5M8 10H16M8 14H16M8 18H13",
    "marks" to "M6 3H18V22L12 17L6 22Z",
    "map" to "M3 5L9 2L15 5L21 2V19L15 22L9 19L3 22ZM9 2V19M15 5V22",
    "plog" to "M3 4H21V21H3ZM7 8H17M7 12H17M7 16H12",
    "knowledge" to "M3 7V4H10L12 7H21V20H3ZM3 11H21",
    "media" to "M6 3H22V18H6ZM2 7V22H18M6 14L11 9L16 14L19 11L22 14",
    "companion" to "M3 4H21V17H9L3 22ZM7 9H17M7 13H14",
    "vault" to "M5 10H19V21H5ZM8 10V6A4 4 0 0 1 16 6V10M12 14V17",
    "finance" to "M5 2L8 4L12 2L16 4L19 2V22L16 20L12 22L8 20L5 22ZM8 8H16M8 12H16M8 16H13",
    "membership" to "M3 5H21V19H3ZM3 10H21M6 15H10",
    "health" to "M12 21L3 11C-1 4 7 0 12 7C17 0 25 4 21 11Z",
    "privacy" to "M12 2L21 6V12C21 18 12 22 12 22C12 22 3 18 3 12V6ZM8 12L11 15L16 9",
    "backup" to "M6 18C0 18 0 10 6 10C5 1 18 1 18 9C24 9 25 18 19 18M12 22V10M8 14L12 10L16 14",
    "settings" to "M4 6H20M4 12H20M4 18H20M8 3V9M16 9V15M10 15V21"
).mapValues { (name, data) ->
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = PathParser().parsePathString(data).toNodes(),
        fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.35f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
    ).build()
}
fun LifeModule.icon(): ImageVector = moduleIcons.getValue(id)
