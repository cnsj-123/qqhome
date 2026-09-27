package com.qq.closie.life.ui.shell

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.qq.closie.life.ui.components.LifeDivider
import com.qq.closie.life.ui.components.LifeGap
import com.qq.closie.life.ui.components.LifeModuleRow
import com.qq.closie.life.ui.components.LifePage
import com.qq.closie.life.ui.components.LifeTopBar
import com.qq.closie.life.ui.theme.LifeTheme
import com.qq.closie.life.ui.theme.rememberLifeDimensions

/**
 * 生活 — the module directory.
 *
 * Deliberately quiet: a page title, eight rows separated by hairlines, and a status word. No
 * coloured cards, no icons per module, no grid. Eight coloured cards would read as a dashboard, and
 * a dashboard is the one thing Life OS is not.
 *
 * **v0.3.0 change — no row is dead any more.** In v0.1 only 衣橱 was live and the other six were
 * disabled: no ripple, no focus ring, nothing. That was honest but useless — a user with a thought
 * about their plants had nowhere to put it, and a row that visibly refuses to respond is worse than
 * no row at all. Now every row opens something: the four built modules open themselves, and the
 * planned ones (财务/物品/旅行/园艺) open [ModuleLandingScreen], which says plainly that the module
 * is 规划中 and offers the one action that is real today — 先记到资料库.
 *
 * The screen itself stays a pure renderer: it maps a [LifeModuleSpec] to a title, a status word and
 * a callback, and knows nothing about routes. The key→destination decision lives in the shell, so
 * this file never needs to import Navigation.
 */
@Composable
fun LifeModulesScreen(
    onOpenModule: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val dims = rememberLifeDimensions()
    LifePage(modifier = modifier) {
        item {
            LifeTopBar(title = "生活")
        }

        // blockGap, not a fixed 32dp: on a 393dp phone 生活 → 衣橱 is the very first thing the eye
        // travels, and a page-title-sized hole above the first row made the directory read as
        // half-loaded. The gap now matches every other block rhythm on the shell.
        item { LifeGap(dims.blockGap) }

        LifeModuleCatalog.entries.forEachIndexed { index, module ->
            if (index > 0) {
                item { LifeDivider() }
            }
            item {
                LifeModuleRow(
                    title = module.title,
                    status = LifeModuleCatalog.statusOf(module),
                    enabled = module.open,
                    onClick = if (module.open) ({ onOpenModule(module.key) }) else null,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800, name = "生活 — 360x800")
@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "生活 — 393x852")
@Preview(showBackground = true, widthDp = 411, heightDp = 891, name = "生活 — 411x891")
@Composable
private fun LifeModulesScreenPreview() {
    LifeTheme {
        LifeModulesScreen(onOpenModule = {})
    }
}
