package com.qq.closie.life.ui.modules

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.qq.closie.life.ui.components.LifeEmptyState
import com.qq.closie.life.ui.components.LifeGap
import com.qq.closie.life.ui.components.LifePage
import com.qq.closie.life.ui.components.LifePrimaryAction
import com.qq.closie.life.ui.components.LifeSecondaryAction
import com.qq.closie.life.ui.components.LifeTopAppBar
import com.qq.closie.life.ui.theme.LifeColors
import com.qq.closie.life.ui.theme.LifeSpacing
import com.qq.closie.life.ui.theme.LifeTheme
import com.qq.closie.life.ui.theme.LifeType
import com.qq.closie.life.ui.theme.rememberLifeDimensions

/**
 * The shared landing page for modules that are announced but not built — 财务 / 物品 / 旅行 / 园艺.
 *
 * Why this exists at all: in v0.2 those four rows were `enabled = false`, so tapping them did
 * nothing. From the user's side a dead row and a missing row are the same thing, and a directory
 * where half the entries do not respond reads as broken rather than as unfinished. Giving each
 * module an honest page with its own name, one line about what it will be, and a real way back fixes
 * that without inventing a single feature.
 *
 * The rule this page must never break: **it does not pretend the feature exists.** There is no fake
 * list, no disabled form, no "coming soon" button that does nothing. It says what is true and then
 * offers the one thing that genuinely works today — saving something into 资料库 so it is not lost
 * in the meantime.
 */
@Composable
fun ModuleLandingScreen(
    spec: ModuleLandingSpec,
    onBack: () -> Unit,
    onSaveToLibrary: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dims = rememberLifeDimensions()

    LifePage(modifier = modifier) {
        item {
            LifeTopAppBar(
                title = spec.title,
                subtitle = spec.tagline,
                onBack = onBack
            )
        }

        item { LifeGap(dims.sectionGap) }

        item {
            LifeEmptyState(
                title = "当前功能尚在建设",
                body = spec.body
            )
        }

        item { LifeGap(dims.sectionGap) }

        item {
            // The honest, working option: the content the user was about to add still has somewhere
            // to go, so tapping a half-built module costs them nothing.
            LifePrimaryAction(
                label = "先记到资料库",
                onClick = onSaveToLibrary
            )
        }

        item { Spacer(Modifier.height(LifeSpacing.sm)) }

        item {
            LifeSecondaryAction(
                label = "返回生活",
                onClick = onBack
            )
        }

        item { Spacer(Modifier.height(LifeSpacing.lg)) }
    }
}

/**
 * What a landing page needs to say about its module.
 *
 * Kept as plain data (no Compose types) so the copy for all four modules lives in one table that a
 * JVM test can assert on — the same approach [com.qq.closie.life.ui.shell.LifeModuleCatalog] takes,
 * and for the same reason: this is product structure, not drawing code.
 */
data class ModuleLandingSpec(
    val key: String,
    val title: String,
    val tagline: String,
    val body: String
)

/**
 * Copy for every not-yet-built module.
 *
 * Each tagline is a sentence about what the module is *for*, not an apology — the difference between
 * "每一段路，都值得被记住。" and "尚未开放" is the difference between a product and a placeholder.
 */
object ModuleLandingCatalog {

    private val specs: Map<String, ModuleLandingSpec> = listOf(
        ModuleLandingSpec(
            key = "money",
            title = "财务",
            tagline = "把钱花在哪里，就是生活本来的样子。",
            body = "记录开销、看清单笔金额与分类，会在之后的版本里出现。"
        ),
        ModuleLandingSpec(
            key = "items",
            title = "物品",
            tagline = "你拥有的东西，也值得被记住。",
            body = "物品清单、购买时间与保养记录，会在之后的版本里出现。"
        ),
        ModuleLandingSpec(
            key = "travel",
            title = "旅行",
            tagline = "每一段路，都值得被记住。",
            body = "行程、地点与照片归档，会在之后的版本里出现。"
        ),
        ModuleLandingSpec(
            key = "garden",
            title = "园艺",
            tagline = "植物有自己的时间。",
            body = "种植记录、浇水与生长观察，会在之后的版本里出现。"
        )
    ).associateBy { it.key }

    /** Null for modules that have a real screen — callers must not land in a placeholder. */
    fun specFor(key: String): ModuleLandingSpec? = specs[key]

    val keys: Set<String> get() = specs.keys
}

// ------------------------------------------------------------------
//  Preview
// ------------------------------------------------------------------

@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "旅行 Landing — 393x852")
@Preview(showBackground = true, widthDp = 360, heightDp = 800, name = "旅行 Landing — 360x800")
@Preview(showBackground = true, widthDp = 411, heightDp = 891, name = "旅行 Landing — 411x891")
@Composable
private fun ModuleLandingPreview() {
    LifeTheme {
        ModuleLandingScreen(
            spec = ModuleLandingCatalog.specFor("travel")!!,
            onBack = {},
            onSaveToLibrary = {}
        )
    }
}
