package com.sonex.mobile.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * SoNex expressive palette. Signature colour is an electric "signal violet"
 * that pulses when the room goes quiet->talking, evoking a live audio meter.
 * Falls back to Material You dynamic colour on Android 12+.
 */
private val SignalViolet = Color(0xFF7C4DFF)
private val DeepInk = Color(0xFF0E0B1A)
private val QuietTeal = Color(0xFF2DD4BF)
private val BoostAmber = Color(0xFFFFB020)
private val TalkCoral = Color(0xFFFF5C7A)

private val DarkScheme = darkColorScheme(
    primary = SignalViolet,
    secondary = QuietTeal,
    tertiary = BoostAmber,
    background = DeepInk,
    surface = Color(0xFF161226),
    error = TalkCoral
)

private val LightScheme = lightColorScheme(
    primary = SignalViolet,
    secondary = QuietTeal,
    tertiary = BoostAmber,
    error = TalkCoral
)

// Expressive type scale — heavy display, calm body.
val SonexType = Typography(
    displayLarge = Typography().displayLarge.copy(fontWeight = FontWeight.Black, letterSpacing = (-1).sp),
    headlineMedium = Typography().headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
    labelLarge = Typography().labelLarge.copy(fontWeight = FontWeight.SemiBold)
)

@Composable
fun SonexTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    // Live theme switching: Settings writes Prefs.themeState, we recompose.
    val override by com.sonex.mobile.data.Prefs.themeState.collectAsState()
    val mode = override ?: com.sonex.mobile.data.Prefs.themeMode(ctx)
    val dark = when (mode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme() // auto-adjust to the system
    }
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, typography = SonexType, content = content)
}

object StateColors {
    val quiet = QuietTeal
    val talking = TalkCoral
    val boost = BoostAmber
    val signal = SignalViolet
}
