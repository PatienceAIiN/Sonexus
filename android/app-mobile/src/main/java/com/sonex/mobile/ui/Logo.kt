package com.sonex.mobile.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * The SoNex wordmark, identical to the landing page: violet→teal→coral
 * gradient that shimmers forever, with a gentle breathing pulse.
 */
@Composable
fun SonexLogo(fontSize: TextUnit = 56.sp, breathe: Boolean = true) {
    val t = rememberInfiniteTransition(label = "logo")
    val shift by t.animateFloat(
        0f, 600f,
        infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Reverse), label = "shimmer"
    )
    val scale by t.animateFloat(
        1f, if (breathe) 1.05f else 1f,
        infiniteRepeatable(tween(2600), RepeatMode.Reverse), label = "breathe"
    )
    Text(
        "SoNex",
        fontSize = fontSize,
        fontWeight = FontWeight.Black,
        letterSpacing = (-1).sp,
        modifier = Modifier.scale(scale),
        style = TextStyle(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFF7C4DFF), Color(0xFF2DD4BF), Color(0xFFFF5C7A), Color(0xFF7C4DFF)
                ),
                start = Offset(shift, 0f),
                end = Offset(shift + 500f, 160f)
            )
        )
    )
}
