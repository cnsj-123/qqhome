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
import com.qq.closie.life.ui.theme.LifeSpacing
import com.qq.closie.life.ui.theme.LifeTheme

/**
 * 生活 — the module directory.
 *
 * Deliberately quiet: a page title, seven rows separated by hairlines, and a status word. No
 * coloured cards, no icons per module, no grid. Seven coloured cards would read as a dashboard,
 * and a dashboard is the one thing Life OS is not.
 *
 * Only 衣橱 is live; it deep-links into the existing Closie closet. Every other row is disabled
 * (see [LifeModuleRow]) so it cannot be mistaken for a working entry — no ripple, no focus ring,
 * no press animation.
 */
@Composable
fun LifeModulesScreen(
    onOpenCloset: () -> Unit,
    modifier: Modifier = Modifier
) {
    LifePage(modifier = modifier) {
        item {
            LifeTopBar(title = "生活")
        }

        item { LifeGap(LifeSpacing.xxl) }

        LifeModuleCatalog.entries.forEachIndexed { index, module ->
            if (index > 0) {
                item { LifeDivider() }
            }
            item {
                LifeModuleRow(
                    title = module.title,
                    status = LifeModuleCatalog.statusOf(module),
                    enabled = module.open,
                    onClick = if (module.open) onOpenCloset else null,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Preview(showBackground = true, name = "生活 — module directory")
@Composable
private fun LifeModulesScreenPreview() {
    LifeTheme {
        LifeModulesScreen(onOpenCloset = {})
    }
}
