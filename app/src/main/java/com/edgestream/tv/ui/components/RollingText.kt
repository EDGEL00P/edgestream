package com.edgestream.tv.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/**
 * A number that rolls to its new value: the old one slides up and out, the new one rises in.
 * Scores and percentages change in place instead of blinking.
 */
@Composable
fun RollingText(text: String, style: TextStyle, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            (slideInVertically(tween(320)) { it } + fadeIn(tween(320))) togetherWith
                (slideOutVertically(tween(260)) { -it } + fadeOut(tween(200)))
        },
        modifier = modifier,
        label = "rolling",
    ) { value -> Text(value, style = style, color = color, maxLines = 1) }
}
