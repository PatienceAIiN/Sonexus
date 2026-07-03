package com.sonex.mobile.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import android.content.Context
import android.media.AudioManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonex.core.RoomState
import com.sonex.mobile.audio.ListeningService
import com.sonex.mobile.data.Prefs
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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
        // Resume only if the user had listening ON — never start by ourselves.
        if (Prefs.listeningEnabled(ctx)) onEnsureMic()
        ListeningService.stateFlow.collectLatest { (s, db) -> state = s; level = db }
    }
    val listening by ListeningService.running.collectAsState()

    val target = when (state) {
        RoomState.QUIET -> StateColors.quiet
        RoomState.TALKING -> StateColors.talking
        RoomState.BOOST -> StateColors.boost
        RoomState.WHISPER -> StateColors.whisper
    }
    val orbColor by animateColorAsState(target, tween(500), label = "orb")

    val t = rememberInfiniteTransition(label = "orb")
    val breathe by t.animateFloat(0.92f, 1f, infiniteRepeatable(
        tween(2400, easing = androidx.compose.animation.core.FastOutSlowInEasing), RepeatMode.Reverse), label = "b")
    val sweep by t.animateFloat(0f, 360f, infiniteRepeatable(
        tween(3600, easing = androidx.compose.animation.core.LinearEasing)), label = "s")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(16.dp))
            // Live animation: plays while listening, freezes on Stop, tinted
            // with the current activity colour (quiet/talk/boost/whisper).
            val composition by com.airbnb.lottie.compose.rememberLottieComposition(
                com.airbnb.lottie.compose.LottieCompositionSpec.Url(
                    "https://lottie.host/3bb755f7-57d6-4860-97ac-15150ea0021c/CdE2dpfrUc.lottie"
                )
            )
            // Always bouncing — grey while stopped, activity-coloured while
            // listening (matches SoNex Web).
            val lottieProgress by com.airbnb.lottie.compose.animateLottieCompositionAsState(
                composition,
                isPlaying = true,
                iterations = com.airbnb.lottie.compose.LottieConstants.IterateForever,
                speed = 3f
            )
            val tint = com.airbnb.lottie.compose.rememberLottieDynamicProperties(
                com.airbnb.lottie.compose.rememberLottieDynamicProperty(
                    com.airbnb.lottie.LottieProperty.COLOR_FILTER,
                    android.graphics.PorterDuffColorFilter(
                        (if (listening) orbColor else Color(0xFF9AA0A6)).toArgb(),
                        android.graphics.PorterDuff.Mode.SRC_ATOP
                    ),
                    "**"
                )
            )
            if (composition != null) {
                com.airbnb.lottie.compose.LottieAnimation(
                    composition, { lottieProgress },
                    dynamicProperties = tint,
                    modifier = Modifier.size(220.dp)
                )
            } else {
                // Offline fallback: the minimal orb.
                Canvas(Modifier.size(220.dp)) {
                    val c = Offset(size.width / 2, size.height / 2)
                    val r = size.minDimension / 2
                    drawCircle(
                        androidx.compose.ui.graphics.Brush.radialGradient(
                            listOf(orbColor.copy(alpha = 0.22f), orbColor.copy(alpha = 0f)),
                            center = c, radius = r * breathe
                        ), radius = r * breathe, center = c
                    )
                    drawCircle(orbColor, radius = r * 0.24f * breathe, center = c)
                    drawArc(
                        color = orbColor.copy(alpha = 0.8f),
                        startAngle = sweep, sweepAngle = 72f, useCenter = false,
                        topLeft = Offset(c.x - r * 0.62f, c.y - r * 0.62f),
                        size = androidx.compose.ui.geometry.Size(r * 1.24f, r * 1.24f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = 5f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                when {
                    !listening -> "Ready"
                    state == RoomState.TALKING -> "Talking — volume lowered"
                    state == RoomState.BOOST -> "Loud room — volume raised"
                    state == RoomState.WHISPER -> "Whispering 🤫 — volume untouched"
                    else -> "Listening"
                },
                style = MaterialTheme.typography.headlineMedium,
                color = if (listening) orbColor else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (listening) Text(
                "Room level: ${(level + 100).toInt().coerceIn(0, 100)}/100",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))

            // Master switch (user-only) + calibration, one tidy row.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        if (listening) {
                            Prefs.setListeningEnabled(ctx, false)
                            ctx.stopService(android.content.Intent(ctx, ListeningService::class.java))
                        } else {
                            Prefs.setListeningEnabled(ctx, true)
                            onEnsureMic()
                        }
                    },
                    modifier = Modifier.height(48.dp),
                    colors = if (listening) ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ) else ButtonDefaults.buttonColors()
                ) {
                    Icon(
                        if (listening) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        null, Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (listening) "Stop" else "Start")
                }
                FilledTonalButton(onClick = onCalibrate, modifier = Modifier.height(48.dp)) {
                    Icon(Icons.Filled.Tune, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Calibrate")
                }
            }

            // ---- Devices: everything SoNex is controlling right now ----
            Spacer(Modifier.height(28.dp))
            val audio = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
            // Probe BT/Cast on a background thread (Cast is binder IPC) and only
            // touch compose state when something actually changed — zero churn.
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
                Text("Devices", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))

                run {
                    val (btOn, castOn) = routes
                    DeviceRow(
                        id = "phone", name = "Phone speaker",
                        status = if (btOn) "Idle (audio on Bluetooth)" else "Active", scope = scope
                    )
                    DeviceRow(
                        id = "bt", name = "Bluetooth",
                        status = if (btOn) "Connected · active" else "Not connected", scope = scope
                    )
                    DeviceRow(
                        id = "cast", name = "Cast",
                        status = if (castOn) "Session active" else "No session", scope = scope
                    )
                    val tvName = Prefs.pairedTv(ctx)
                    DeviceRow(
                        id = "tv", name = tvName?.let { "TV · $it" } ?: "SoNex TV",
                        status = if (tvName != null) "Paired" else "Not paired",
                        scope = scope,
                        action = if (tvName == null) "Pair" to onPairTv else "Re-pair" to onPairTv
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** One output SoNex manages automatically — status only, no manual knobs. */
@Composable
private fun DeviceRow(
    id: String,
    name: String,
    status: String,
    scope: kotlinx.coroutines.CoroutineScope,
    action: Pair<String, () -> Unit>? = null
) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                Text(status, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (action != null) {
                TextButton(onClick = action.second) { Text(action.first) }
            }
        }
    }
}
