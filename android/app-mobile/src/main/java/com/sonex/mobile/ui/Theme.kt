package com.sonex.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * SoNex "studio" theme — a dark, cinematic recording-app aesthetic. A near-black
 * ink background with a subtle violet aurora, glassy raised surfaces, and three
 * live state accents (teal quiet, coral talk, amber boost) that drive the home
 * waveform. Bespoke (not Material-You dynamic) so the look is consistent on every
 * device. A clean light variant is kept for users who pick Light in Settings.
 */
// ---- Signature palette ----
val SignalViolet = Color(0xFF8B6DFF)   // primary / brand
val VioletDeep = Color(0xFF5B34E8)
val QuietTeal = Color(0xFF2DD4BF)      // room is calm
val TalkCoral = Color(0xFFFF5C7A)      // someone talking -> duck
val BoostAmber = Color(0xFFFFB020)     // loud room -> boost

// ---- Dark (primary) surfaces ----
val InkBlack = Color(0xFF07060F)       // page background base
val InkPanel = Color(0xFF141026)       // raised card
val InkPanelHi = Color(0xFF1E1836)     // hovered / elevated card
val InkOutline = Color(0xFF2A2444)
val TextHi = Color(0xFFF3F0FF)
val TextLo = Color(0xFF9D96C4)

private val DarkScheme = darkColorScheme(
    primary = SignalViolet,
    onPrimary = Color.White,
    primaryContainer = VioletDeep,
    onPrimaryContainer = Color.White,
    secondary = QuietTeal,
    onSecondary = Color(0xFF042722),
    tertiary = BoostAmber,
    onTertiary = Color(0xFF2A1A00),
    background = InkBlack,
    onBackground = TextHi,
    surface = InkPanel,
    onSurface = TextHi,
    surfaceVariant = InkPanelHi,
    onSurfaceVariant = TextLo,
    outline = InkOutline,
    outlineVariant = InkOutline,
    error = TalkCoral,
    onError = Color.White,
    errorContainer = Color(0xFF3A0E1A),
    onErrorContainer = TalkCoral,
)

private val LightScheme = lightColorScheme(
    primary = VioletDeep,
    secondary = Color(0xFF12A594),
    tertiary = Color(0xFFB8790C),
    background = Color(0xFFF7F5FF),
    surface = Color.White,
    surfaceVariant = Color(0xFFF0ECFB),
    onSurfaceVariant = Color(0xFF5B5570),
    outline = Color(0xFFDED7F2),
    error = Color(0xFFE0345A),
)

// Generously rounded corners everywhere — text fields, cards, sheets, dialogs
// all inherit these, giving the whole app that soft, iOS-smooth feel.
val SonexShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

// Expressive type scale — heavy display, calm body.
val SonexType = Typography(
    displayLarge = Typography().displayLarge.copy(fontWeight = FontWeight.Black, letterSpacing = (-1.5).sp),
    displayMedium = Typography().displayMedium.copy(fontWeight = FontWeight.Black, letterSpacing = (-1).sp),
    headlineMedium = Typography().headlineMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    titleLarge = Typography().titleLarge.copy(fontWeight = FontWeight.Bold),
    labelLarge = Typography().labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

/** True when the current theme is the dark studio look — screens use it to pick
 *  the aurora background vs. the clean light background. */
val LocalIsDark = androidx.compose.runtime.compositionLocalOf { true }

@Composable
fun SonexTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    // Live theme switching: Settings writes Prefs.themeState, we recompose.
    val override by com.sonex.mobile.data.Prefs.themeState.collectAsState()
    val mode = override ?: com.sonex.mobile.data.Prefs.themeMode(ctx)
    val dark = when (mode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalIsDark provides dark) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = SonexType,
            shapes = SonexShapes,
        ) {
            // Give EVERY screen a sensible default text/icon colour, even ones not
            // wrapped in a Surface (e.g. Login). Without this, LocalContentColor
            // defaults to black and text vanishes on the dark background.
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onBackground,
                content = content
            )
        }
    }
}

/** Full-screen background: a dark violet aurora for the studio look, or a soft
 *  light wash. Used by every screen so the app feels like one continuous surface. */
@Composable
fun sonexBackground(): Brush {
    val dark = LocalIsDark.current
    return if (dark) Brush.radialGradient(
        colors = listOf(Color(0xFF1A1130), InkBlack, Color(0xFF05040A)),
        center = Offset(360f, 240f), radius = 1400f
    ) else Brush.verticalGradient(
        listOf(Color(0xFFFBFAFF), Color(0xFFF2EEFF))
    )
}

object StateColors {
    val quiet = QuietTeal
    val talking = TalkCoral
    val boost = BoostAmber
    val whisper = SignalViolet
    val signal = SignalViolet
}
