package com.sonex.mobile.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sonex.mobile.audio.Calibration
import com.sonex.mobile.audio.Calibrator
import com.sonex.mobile.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrateScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val calibrator = remember { Calibrator() }

    var step by remember { mutableStateOf(0) } // 0 intro,1 silence,2 media,3 talk,4 done
    var progress by remember { mutableStateOf(0f) }
    var noise by remember { mutableStateOf(0.0) }
    var media by remember { mutableStateOf(0.0) }
    var talk by remember { mutableStateOf(0.0) }

    fun runStep(seconds: Int, onResult: (Double) -> Unit, next: Int) {
        scope.launch {
            progress = 0f
            val db = withContext(Dispatchers.Default) {
                calibrator.measureDb(seconds) { p -> progress = p }
            }
            onResult(db); step = next
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Calibrate") }) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AnimatedContent(step, label = "step") { s ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    when (s) {
                        0 -> {
                            Text("Let's tune SoNex", style = MaterialTheme.typography.headlineMedium)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Three quick steps teach SoNex your room and where your phone sits — corner or centre, it adapts.",
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(28.dp))
                            Button(onClick = { step = 1 }) { Text("Start") }
                        }
                        1 -> StepPrompt("Step 1 of 3 · Silence", "Stay quiet. TV off.", progress) {
                            runStep(5, { noise = it }, 2)
                        }
                        2 -> StepPrompt("Step 2 of 3 · TV only", "Play the TV normally. Don't talk.", progress) {
                            runStep(6, { media = it }, 3)
                        }
                        3 -> StepPrompt("Step 3 of 3 · TV + talking", "Keep the TV playing and talk normally.", progress) {
                            runStep(7, { talk = it }, 4)
                        }
                        4 -> {
                            val cal = Calibration(
                                name = "Living room",
                                noiseFloorDb = noise, mediaBaselineDb = media, mediaPlusTalkDb = talk
                            )
                            Text("Calibration complete", style = MaterialTheme.typography.headlineMedium)
                            Spacer(Modifier.height(16.dp))
                            Text("Noise floor  ${noise.toInt()} dB")
                            Text("TV baseline  ${media.toInt()} dB")
                            Text("Talk trigger ${cal.trigger.toInt()} dB")
                            Spacer(Modifier.height(24.dp))
                            Button(onClick = { Prefs.saveCalibration(ctx, cal); onDone() }) { Text("Save") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepPrompt(title: String, body: String, progress: Float, onNext: () -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(8.dp))
    Text(body, textAlign = TextAlign.Center)
    Spacer(Modifier.height(20.dp))
    if (progress > 0f && progress < 1f) LinearProgressIndicator(progress = { progress })
    Spacer(Modifier.height(20.dp))
    Button(onClick = onNext) { Text("Measure") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onDataDeleted: () -> Unit) {
    val ctx = LocalContext.current
    var duck by remember { mutableStateOf(Prefs.duckLevel(ctx).toFloat()) }
    var upload by remember { mutableStateOf(Prefs.consentUploadClips(ctx)) }
    var telemetry by remember { mutableStateOf(Prefs.consentTelemetry(ctx)) }
    var training by remember { mutableStateOf(Prefs.consentTraining(ctx)) }
    var wake by remember { mutableStateOf(Prefs.consentWakeWord(ctx)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                }
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(20.dp).verticalScroll(rememberScrollState())) {
            Text("Response", style = MaterialTheme.typography.titleMedium)
            Text("Lower volume to ${duck.toInt()}% when someone talks")
            Slider(duck, { duck = it; Prefs.setDuckLevel(ctx, it.toInt()) }, valueRange = 10f..80f)

            Spacer(Modifier.height(20.dp))
            Text("Privacy & consent", style = MaterialTheme.typography.titleMedium)
            Text(
                "All audio is processed on your device. Nothing leaves your phone unless you turn it on below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            ConsentRow("Upload clips to improve detection", upload) { upload = it; Prefs.setConsent(ctx, "c_upload", it) }
            ConsentRow("Share anonymous usage stats", telemetry) { telemetry = it; Prefs.setConsent(ctx, "c_telemetry", it) }
            ConsentRow("Let SoNex learn my home", training) { training = it; Prefs.setConsent(ctx, "c_training", it) }
            ConsentRow("Wake word \"SoNex\" always listening", wake) { wake = it; Prefs.setConsent(ctx, "c_wakeword", it) }

            Spacer(Modifier.height(20.dp))
            Text("When someone talks, each device should…", style = MaterialTheme.typography.titleMedium)
            listOf("tv" to "SoNex TV", "bt" to "Bluetooth", "cast" to "Cast").forEach { (id, label) ->
                TargetRuleRow(label, Prefs.targetRule(ctx, id)) { Prefs.setTargetRule(ctx, id, it) }
            }

            Spacer(Modifier.height(20.dp))
            Text("SoNex server (optional)", style = MaterialTheme.typography.titleMedium)
            Text(
                "For model updates and, with consent, contributing data. Leave empty to stay fully offline.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            var server by remember { mutableStateOf(Prefs.serverUrl(ctx) ?: "") }
            OutlinedTextField(
                server, { server = it; Prefs.setServerUrl(ctx, it.trim().ifBlank { null }) },
                label = { Text("Server URL") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )

            Spacer(Modifier.height(20.dp))
            var confirmDelete by remember { mutableStateOf(false) }
            // With no server configured, deleting local data IS deleting all data.
            OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete all my data") }
            if (confirmDelete) {
                AlertDialog(
                    onDismissRequest = { confirmDelete = false },
                    title = { Text("Delete all data?") },
                    text = { Text("Removes your account, TV pairing, calibration and consents from this phone.") },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmDelete = false
                            Prefs.clearAll(ctx)
                            onDataDeleted()
                        }) { Text("Delete") }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
                    }
                )
            }
        }
    }
}

@Composable
private fun TargetRuleRow(label: String, initial: com.sonex.core.TargetRule, onChange: (com.sonex.core.TargetRule) -> Unit) {
    var rule by remember { mutableStateOf(initial) }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.sonex.core.TargetRule.entries.forEach { r ->
                FilterChip(
                    selected = rule == r,
                    onClick = { rule = r; onChange(r) },
                    label = { Text(r.name.lowercase().replaceFirstChar(Char::uppercase)) }
                )
            }
        }
    }
}

@Composable
private fun ConsentRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}
