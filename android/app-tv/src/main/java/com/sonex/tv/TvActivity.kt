package com.sonex.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * TV UI: a dark, cinematic pairing screen — big glowing code tiles, a slow
 * breathing aura, and a live connection status. Runs the TvServer that the phone
 * discovers and pairs with over the same Wi-Fi.
 */
private val Violet = Color(0xFF8B6DFF)
private val Teal = Color(0xFF2DD4BF)
private val Ink = Color(0xFF07060F)

class TvActivity : ComponentActivity() {
    private lateinit var server: TvServer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var code by remember { mutableStateOf("----") }
            var status by remember { mutableStateOf("Waiting for your phone…") }
            val paired = status.contains("paired", true) || status.contains("connected", true)
            var update by remember { mutableStateOf<TvUpdater.Release?>(null) }
            var updateProgress by remember { mutableStateOf<Float?>(null) }
            var updateError by remember { mutableStateOf<String?>(null) }

            LaunchedEffect(Unit) {
                server = TvServer(this@TvActivity, onCode = { code = it }, onStatus = { status = it })
                server.start()
                update = TvUpdater.check(this@TvActivity)
            }

            MaterialTheme(colorScheme = darkColorScheme(primary = Violet)) {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.radialGradient(
                            listOf(Color(0xFF1A1130), Ink, Color(0xFF05040A)),
                            center = Offset(700f, 340f), radius = 1600f
                        )
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    BreathingAura()
                    Column(
                        Modifier.fillMaxSize().padding(64.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Row {
                            Text("So", fontSize = 46.sp, fontWeight = FontWeight.Black, color = Color(0xFFF3F0FF))
                            Text("Nex", fontSize = 46.sp, fontWeight = FontWeight.Black, color = Violet)
                            Text(" TV", fontSize = 46.sp, fontWeight = FontWeight.Black, color = Color(0xFF9D96C4))
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "Open SoNex on your phone and enter this code",
                            fontSize = 20.sp, color = Color(0xFF9D96C4)
                        )
                        Spacer(Modifier.height(48.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                            code.forEachIndexed { i, c -> CodeTile(c, i) }
                        }
                        Spacer(Modifier.height(56.dp))
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatusDot(on = paired)
                            AnimatedContent(
                                targetState = status,
                                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
                                label = "status"
                            ) { s ->
                                Text(s, fontSize = 20.sp, color = if (paired) Teal else Color(0xFF9D96C4))
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        // If the phone can't auto-find the TV, type this IP in the app.
                        Text("Same Wi-Fi · TV IP  ${remember { localIp() }}",
                            fontSize = 16.sp, color = Color(0xFF6B647F))

                        // ---- In-app update (remote-clickable) ----
                        update?.let { u ->
                            val scope = rememberCoroutineScope()
                            Spacer(Modifier.height(28.dp))
                            androidx.compose.material3.Button(
                                onClick = {
                                    if (updateProgress == null) scope.launch {
                                        updateProgress = 0f
                                        updateError = TvUpdater.downloadAndInstall(
                                            this@TvActivity, u) { p -> updateProgress = p }
                                        updateProgress = null
                                    }
                                },
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Violet)
                            ) {
                                Text(
                                    when {
                                        updateProgress != null ->
                                            "Downloading… ${(updateProgress!! * 100).toInt()}%"
                                        else -> "Update to v${u.version_name} — press OK"
                                    },
                                    fontSize = 18.sp, color = Color.White
                                )
                            }
                            updateError?.let {
                                Spacer(Modifier.height(8.dp))
                                Text(it, fontSize = 14.sp, color = Color(0xFFFF5C7A))
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() { if (::server.isInitialized) server.stop(); super.onDestroy() }
}

private fun localIp(): String = runCatching {
    java.net.NetworkInterface.getNetworkInterfaces().toList()
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
        ?.hostAddress ?: "—"
}.getOrDefault("—")

@Composable
private fun BreathingAura() {
    val t = rememberInfiniteTransition(label = "aura")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3400), RepeatMode.Restart), label = "p")
    Canvas(Modifier.fillMaxSize()) {
        val c = Offset(size.width / 2, size.height / 2)
        val base = size.minDimension / 2
        for (i in 0 until 3) {
            val p = (phase + i / 3f) % 1f
            drawCircle(Violet.copy(alpha = (1f - p) * 0.10f),
                radius = base * (0.5f + p * 0.7f), center = c)
        }
    }
}

@Composable
private fun CodeTile(c: Char, index: Int) {
    // Each tile pops in with a staggered scale/fade as the code arrives.
    AnimatedContent(
        targetState = c,
        transitionSpec = {
            (scaleIn(tween(400)) + fadeIn(tween(400))) togetherWith fadeOut(tween(150))
        },
        label = "tile$index"
    ) { ch ->
        Box(
            Modifier.size(128.dp, 158.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF1E1836), Color(0xFF141026))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                ch.toString(), fontSize = 78.sp, fontWeight = FontWeight.Black,
                color = if (ch == '-') Color(0xFF3A3358) else Color(0xFFF3F0FF)
            )
        }
    }
}

@Composable
private fun StatusDot(on: Boolean) {
    val t = rememberInfiniteTransition(label = "dot")
    val a by t.animateFloat(0.4f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Box(
        Modifier.size(12.dp).clip(CircleShape)
            .background((if (on) Teal else Violet).copy(alpha = a))
    )
}
