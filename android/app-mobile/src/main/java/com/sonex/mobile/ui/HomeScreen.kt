package com.sonex.mobile.ui

import android.content.Context
import android.media.AudioManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonex.core.RoomState
import com.sonex.mobile.audio.ListeningService
import com.sonex.mobile.data.Prefs
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlin.math.sin

private const val BARS = 56

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onCalibrate: () -> Unit,
    onSettings: () -> Unit,
    onPairTv: () -> Unit,
    onEnsureMic: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(RoomState.QUIET) }
    var level by remember { mutableStateOf(-60.0) }

    LaunchedEffect(Unit) {
        if (Prefs.listeningEnabled(ctx)) onEnsureMic()
        ListeningService.stateFlow.collectLatest { (s, db) -> state = s; level = db }
    }
    val listening by ListeningService.running.collectAsState()
    val actionLabel by ListeningService.actionLabel.collectAsState()

    // Live rolling waveform history, fed by the real mic level (~8x/sec).
    val amps = remember { mutableStateListOf<Float>().apply { repeat(BARS) { add(0.04f) } } }
    LaunchedEffect(level, listening) {
        // Map dBFS -> 0..1 with headroom for this app's raw-mic range.
        val norm = if (!listening) 0.04f
            else (((level + 52.0) / 46.0).coerceIn(0.04, 1.0)).toFloat()
        amps.removeAt(0); amps.add(norm)
    }

    val stateColor = when (state) {
        RoomState.TALKING -> StateColors.talking
        RoomState.BOOST -> StateColors.boost
        RoomState.WHISPER, RoomState.WHISPER_GROUP -> StateColors.whisper
        RoomState.QUIET -> StateColors.quiet
    }
    val accent by animateColorAsState(if (listening) stateColor else MaterialTheme.colorScheme.onSurfaceVariant, tween(600), label = "accent")

    Box(Modifier.fillMaxSize().background(sonexBackground())) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ---- Top bar ----
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(start = 22.dp, end = 10.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("So", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
                Text("Nex", style = MaterialTheme.typography.titleLarge, color = SignalViolet)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onSettings) {
                    Icon(Icons.Filled.Settings, "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // ---- Live OTA model download / configure status (iOS-style pill) ----
            val otaStatus by com.sonex.mobile.data.ModelStore.syncStatus.collectAsState()
            val otaProgress by com.sonex.mobile.data.ModelStore.syncProgress.collectAsState()
            androidx.compose.animation.AnimatedVisibility(
                visible = otaStatus != null,
                enter = fadeIn() + androidx.compose.animation.expandVertically(),
                exit = fadeOut() + androidx.compose.animation.shrinkVertically()
            ) {
                OtaBanner(otaStatus ?: "", otaProgress)
            }

            Spacer(Modifier.height(52.dp))

            // ---- Status headline (animated swap) ----
            AnimatedContent(
                targetState = if (!listening) "Ready" else actionLabel,
                transitionSpec = { (fadeIn(tween(300)) togetherWith fadeOut(tween(200))) },
                label = "status"
            ) { label ->
                Text(
                    label,
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (listening) accent else MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
            if (listening) {
                Spacer(Modifier.height(6.dp))
                Text("Room level", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Big live meter readout, like a recorder's dB display.
                Text(
                    "${(level + 100).toInt().coerceIn(0, 100)}",
                    style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Black),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            Spacer(Modifier.height(34.dp))

            // ---- Live scrolling waveform (the single hero animation) ----
            LiveWaveform(amps = amps, accent = accent, active = listening,
                modifier = Modifier.fillMaxWidth().height(120.dp).padding(horizontal = 20.dp))

            Spacer(Modifier.height(40.dp))

            // ---- Record control + calibrate ----
            Row(
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RecordButton(listening = listening, accent = accent) {
                    if (listening) {
                        Prefs.setListeningEnabled(ctx, false)
                        Prefs.setAutoStartSuppressed(ctx, true)
                        ctx.stopService(android.content.Intent(ctx, ListeningService::class.java))
                    } else {
                        Prefs.setListeningEnabled(ctx, true)
                        Prefs.setAutoStartSuppressed(ctx, false)
                        onEnsureMic()
                        android.widget.Toast.makeText(ctx,
                            "Getting used to your room — detection sharpens in a few seconds.",
                            android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onCalibrate) {
                Icon(Icons.Filled.Tune, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text("Calibrate room", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(24.dp))

            // ---- Devices ----
            val audio = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
            val routes by produceState(initialValue = false to false) {
                while (true) {
                    val bt = audio.isBluetoothA2dpOn
                    val cast = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            com.google.android.gms.cast.framework.CastContext.getSharedInstance(ctx)
                                .sessionManager.currentCastSession?.isConnected == true
                        }.getOrDefault(false)
                    }
                    if (value != bt to cast) value = bt to cast
                    kotlinx.coroutines.delay(3000)
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text("OUTPUTS", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 2.sp)
                Spacer(Modifier.height(10.dp))
                val (btOn, castOn) = routes
                DeviceRow("🔊", "Phone speaker", if (btOn) "Idle · audio on Bluetooth" else "Active", accent, !btOn)
                DeviceRow("🎧", "Bluetooth", if (btOn) "Connected · active" else "Not connected", accent, btOn)
                DeviceRow("📺", "Cast", if (castOn) "Session active" else "No session", accent, castOn)
                val tvName = Prefs.pairedTv(ctx)
                DeviceRow("🖥️", tvName?.let { "TV · $it" } ?: "SoNex TV",
                    if (tvName != null) "Paired" else "Not paired", accent, tvName != null,
                    action = if (tvName == null) "Pair" to onPairTv else "Re-pair" to onPairTv)
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

/** Live scrolling waveform, mirrored around the centre line, fed by [amps]. */
@Composable
private fun LiveWaveform(amps: List<Float>, accent: Color, active: Boolean, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "wave")
    val shimmer by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "sh")
    Canvas(modifier) {
        val n = amps.size
        val gap = 4.dp.toPx()
        val barW = (size.width - gap * (n - 1)) / n
        val midY = size.height / 2
        for (i in 0 until n) {
            val a = amps[i].coerceIn(0.03f, 1f)
            // Idle: a gentle sine ripple so it never looks frozen.
            val idle = 0.06f + 0.04f * (0.5f + 0.5f * sin((i * 0.5f) + shimmer * 6.28f))
            val amp = if (active) a else idle
            val h = amp * size.height * 0.92f
            val x = i * (barW + gap)
            val alpha = 0.35f + 0.65f * amp
            drawRoundRect(
                color = accent.copy(alpha = alpha),
                topLeft = Offset(x, midY - h / 2),
                size = androidx.compose.ui.geometry.Size(barW, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2, barW / 2)
            )
        }
    }
}

/** Big circular record/stop control that pulses while listening. */
@Composable
private fun RecordButton(listening: Boolean, accent: Color, onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "rec")
    val ring by t.animateFloat(1f, 1.14f,
        infiniteRepeatable(tween(1100, easing = androidx.compose.animation.core.FastOutSlowInEasing), RepeatMode.Reverse),
        label = "ring")
    val glow = if (listening) accent else SignalViolet
    Box(contentAlignment = Alignment.Center) {
        // Pulsing glow ring behind the button.
        Box(
            Modifier.size(96.dp).scale(if (listening) ring else 1f)
                .background(glow.copy(alpha = 0.18f), CircleShape)
        )
        Box(
            Modifier.size(78.dp).clip(CircleShape)
                .background(Brush.verticalGradient(listOf(glow, glow.copy(alpha = 0.7f))))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (listening)
                Box(Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)).background(Color.White))
            else
                Icon(Icons.Filled.PlayArrow, "Start", tint = Color.White, modifier = Modifier.size(40.dp))
        }
    }
}

/** One managed output, as a glassy card with a live status dot. */
@Composable
private fun DeviceRow(
    emoji: String,
    name: String,
    status: String,
    accent: Color,
    on: Boolean,
    action: Pair<String, () -> Unit>? = null
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(emoji, fontSize = 20.sp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (action != null) {
            FilledTonalButton(onClick = action.second, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                Text(action.first)
            }
        } else {
            // Live status dot: lit accent when active, dim otherwise.
            Box(Modifier.size(10.dp).clip(CircleShape)
                .background(if (on) accent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)))
        }
    }
}

/** iOS-style status pill shown while the OTA detection model downloads/configures. */
@Composable
private fun OtaBanner(status: String, progress: Float?) {
    val done = status.contains("ready", ignoreCase = true)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (done) Box(Modifier.size(10.dp).clip(CircleShape).background(StateColors.quiet))
        else CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = SignalViolet)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(status, style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface)
            if (progress != null && !done) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                    color = SignalViolet,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
        if (progress != null && !done) {
            Spacer(Modifier.width(12.dp))
            Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
