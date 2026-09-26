package com.qq.closie.life.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.qq.closie.life.ui.theme.LifeColors
import com.qq.closie.life.ui.theme.LifeShape
import com.qq.closie.life.ui.theme.LifeSpacing
import com.qq.closie.life.ui.theme.LifeType

/**
 * The one search field in Life OS.
 *
 * Shared between 资料库 and 记录 because they must not drift: two hand-rolled search fields would
 * inevitably end up with different heights, different clear-button behaviour and different keyboard
 * actions, and the user would read that inconsistency as two different features.
 *
 * Deliberately **not** Material's `SearchBar`: that brings an elevated container, a drop shadow, a
 * rounded pill and an expanding "search view" mode, all of which turn a quiet archive into a
 * dashboard. Here the field is `SurfaceInset` with transparent indicator lines, so it reads as a
 * line of text with a magnifier — closer to a card in a paper index than to a search box.
 *
 * `clickable` is applied to the full-height clear button *before* its padding, so clearing is
 * comfortable to hit; the field itself is bordered by the inset background rather than by an
 * indicator line, which is why all three indicator colours are transparent.
 *
 * @param contentDescription accessibility label; "搜索资料" and "搜索记录" are genuinely different
 *   screens, so callers pass their own rather than sharing a generic "搜索".
 */
@Composable
fun LifeSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = LifeSpacing.minTouchTarget)
            .background(
                color = LifeColors.SurfaceInset,
                shape = RoundedCornerShape(LifeShape.small)
            ),
        contentAlignment = Alignment.Center
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { this.contentDescription = contentDescription },
            placeholder = {
                Text(
                    text = placeholder,
                    style = LifeType.BodySecondary,
                    color = LifeColors.TextTertiary
                )
            },
            textStyle = LifeType.Body,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    tint = LifeColors.TextTertiary,
                    modifier = Modifier.height(LifeSpacing.iconSize)
                )
            },
            // Only present when there is something to clear. A permanently visible close icon on an
            // empty field is a control that does nothing, which reads as broken.
            trailingIcon = if (value.isNotEmpty()) {
                {
                    Box(
                        modifier = Modifier
                            .heightIn(min = LifeSpacing.minTouchTarget)
                            .clickable {
                                onValueChange("")
                                keyboard?.hide()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "清空搜索",
                            tint = LifeColors.TextTertiary,
                            modifier = Modifier.height(LifeSpacing.iconSize)
                        )
                    }
                }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                cursorColor = LifeColors.Accent,
                focusedTextColor = LifeColors.TextPrimary,
                unfocusedTextColor = LifeColors.TextPrimary
            )
        )
    }
}
