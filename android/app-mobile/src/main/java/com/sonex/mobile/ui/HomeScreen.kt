package com.sonex.mobile.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sonex.core.Action
import com.sonex.core.Command
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
        onEnsureMic()
        ListeningService.stateFlow.collectLatest { (s, db) -> state = s; level = db }
    }

    val target = when (state) {
        RoomState.QUIET -> StateColors.quiet
        RoomState.TALKING -> StateColors.talking
        RoomState.BOOST -> StateColors.boost
    }
    val orbColor by animateColorAsState(target, tween(500), label = "orb")

    val t = rememberInfiniteTransition(label = "orb")
    val ring by t.animateFloat(0.7f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "r")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SoNex") },
                actions = {
                    IconButton(onClick = onCalibrate) { Icon(Icons.Filled.Tune, "Calibrate") }
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
            // Live state orb — a breathing meter, the app's signature.
            Canvas(Modifier.size(240.dp)) {
                val c = Offset(size.width / 2, size.height / 2)
                drawCircle(orbColor.copy(alpha = 0.15f), radius = size.minDimension / 2 * ring, center = c)
                drawCircle(orbColor.copy(alpha = 0.35f), radius = size.minDimension / 3 * ring, center = c)
                drawCircle(orbColor, radius = size.minDimension / 5, center = c)
            }
            Spacer(Modifier.height(24.dp))
            Text(
                when (state) {
                    RoomState.QUIET -> "Listening"
                    RoomState.TALKING -> "Talking — volume lowered"
                    RoomState.BOOST -> "Loud room — volume raised"
                },
                style = MaterialTheme.typography.headlineMedium,
                color = orbColor
            )
            Text(
                "Room level ${level.toInt()} dB",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            FilledTonalButton(onClick = onCalibrate) { Text("Re-run calibration") }

            // ---- Devices: everything SoNex is controlling right now ----
            Spacer(Modifier.height(28.dp))
            val audio = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
            var refresh by remember { mutableStateOf(0) }
            LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(3000); refresh++ } }

            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text("Devices", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))

                key(refresh) {
                    val btOn = audio.isBluetoothA2dpOn
                    DeviceRow(
                        id = "phone", name = "Phone speaker",
                        status = if (btOn) "Idle (audio on Bluetooth)" else "Active", scope = scope
                    )
                    DeviceRow(
                        id = "bt", name = "Bluetooth",
                        status = if (btOn) "Connected · active" else "Not connected", scope = scope
                    )
                    val castOn = remember(refresh) {
                        runCatching {
                            com.google.android.gms.cast.framework.CastContext.getSharedInstance(ctx)
                                .sessionManager.currentCastSession?.isConnected == true
                        }.getOrDefault(false)
                    }
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

/** One controllable output: status + manual mute / volume-restore. */
@Composable
private fun DeviceRow(
    id: String,
    name: String,
    status: String,
    scope: kotlinx.coroutines.CoroutineScope,
    action: Pair<String, () -> Unit>? = null
) {
    fun send(cmd: Command) = scope.launch { ListeningService.manualCommands.emit(id to cmd) }
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
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
            IconButton(onClick = { send(Command(Action.MUTE, reason = "manual")) }) {
                Icon(Icons.AutoMirrored.Filled.VolumeOff, "Mute $name")
            }
            IconButton(onClick = { send(Command(Action.RESTORE, reason = "manual")) }) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, "Restore $name")
            }
        }
    }
}
