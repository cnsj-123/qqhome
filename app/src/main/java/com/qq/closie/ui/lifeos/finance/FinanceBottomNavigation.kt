package com.qq.closie.ui.lifeos.finance

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qq.closie.life.ui.theme.BundledText as Text
import com.qq.closie.ui.lifeos.components.lifeHorizontalSwipe
import com.qq.closie.ui.lifeos.components.reducedLifeMotion
import com.qq.closie.ui.lifeos.theme.LocalLifeOsColors

@Composable
internal fun FinanceBottomNavigation(view: FinanceView, canAdd: Boolean, showAdd: Boolean,
    onSelect: (FinanceView) -> Unit, onAdd: () -> Unit, drawerDrag: ((Float, Boolean?) -> Unit)?) {
    val colors = LocalLifeOsColors.current
    val reduced = reducedLifeMotion()
    Row(Modifier.fillMaxWidth().lifeHorizontalSwipe(onRightDrag = drawerDrag)
        .background(Brush.verticalGradient(listOf(colors.paper.copy(alpha = .85f), colors.paper)))
        .navigationBarsPadding().padding(start = 32.dp, end = 40.dp).height(74.dp).padding(top = 20.dp),
        verticalAlignment = Alignment.CenterVertically) {
        FinanceView.entries.forEach { target ->
            Column(Modifier.weight(1f).height(54.dp).semantics { selected = view == target }
                .clickable { onSelect(target) }, horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
                CompositionLocalProvider(LocalContentColor provides if(view==target) colors.accentDeep else colors.inkSecondary) {
                    Box(Modifier.size(25.dp).background(if(view==target) colors.paperTertiary else Color.Transparent, RoundedCornerShape(7.dp)),
                        contentAlignment = Alignment.Center) { FinanceNavGlyph(target) }
                }
                Text(target.label, style = LedgerText.caption, color = if(view==target) colors.accentDeep else colors.inkSecondary)
            }
        }
        if(showAdd) {
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val scale by animateFloatAsState(if(pressed && !reduced) .92f else 1f, LedgerMotion.SpringBouncy, label="glass-paw-press")
            val shape = RoundedCornerShape(18.dp)
            Box(Modifier.padding(start=16.dp).size(54.dp).graphicsLayer {
                scaleX=scale; scaleY=scale; this.shape=shape; shadowElevation=if(canAdd) 9.dp.toPx() else 0f
                ambientShadowColor=colors.accent.copy(alpha=.35f); spotShadowColor=colors.accent.copy(alpha=.35f)
            }.background(Brush.linearGradient(listOf(colors.soft, colors.secondary)),shape)
                .background(Brush.radialGradient(listOf(Color.White.copy(alpha=.22f),Color.Transparent),center=androidx.compose.ui.geometry.Offset.Zero,radius=110f),shape)
                .testTag("finance-add").clickable(interactionSource=interaction,indication=null,enabled=canAdd,onClick=onAdd),
                contentAlignment=Alignment.Center) {
                Icon(painterResource(com.qq.closie.R.drawable.lifeos_home_paw),"记一笔",Modifier.size(27.dp),tint=if(canAdd) Color.White else colors.inkSecondary)
            }
        }
    }
}
