package com.sonex.mobile.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.SquareFoot
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.LockReset
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SystemUpdate
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
import com.sonex.mobile.data.ServerSync
import com.sonex.mobile.data.UpdateChecker
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

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Calibrate") },
            navigationIcon = {
                IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            }
        )
    }) { pad ->
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
                            val problems = com.sonex.core.CalibrationQuality.issues(noise, media, talk)
                            Text(
                                if (problems.isEmpty()) "Calibration complete" else "Hmm, let's check that",
                                style = MaterialTheme.typography.headlineMedium
                            )
                            Spacer(Modifier.height(16.dp))
                            Text("Noise floor  ${noise.toInt()} dB")
                            Text("TV baseline  ${media.toInt()} dB")
                            Text("Talk trigger ${cal.trigger.toInt()} dB")
                            problems.forEach {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    "⚠ $it", textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            Spacer(Modifier.height(24.dp))
                            if (problems.isNotEmpty()) {
                                Button(onClick = { step = 1 }) { Text("Redo calibration") }
                                Spacer(Modifier.height(8.dp))
                                TextButton(onClick = { Prefs.saveCalibration(ctx, cal); onDone() }) {
                                    Text("Save anyway")
                                }
                            } else {
                                Button(onClick = { Prefs.saveCalibration(ctx, cal); onDone() }) { Text("Save") }
                            }
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
fun SettingsScreen(onBack: () -> Unit, onDataDeleted: () -> Unit, onLoggedOut: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    fun buzz() {
        if (Prefs.hapticsEnabled(ctx))
            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
    }
    fun toast(msg: String) =
        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()

    /** Flip a consent locally, then confirm the server sync in a toast. */
    fun syncedConsent(prefKey: String, value: Boolean) {
        buzz()
        Prefs.setConsent(ctx, prefKey, value)
        scope.launch { toast(ServerSync.syncConsent(ctx, prefKey, value).message) }
    }

    var duck by remember { mutableStateOf(Prefs.duckLevel(ctx).toFloat()) }
    var upload by remember { mutableStateOf(Prefs.consentUploadClips(ctx)) }
    var telemetry by remember { mutableStateOf(Prefs.consentTelemetry(ctx)) }
    var training by remember { mutableStateOf(Prefs.consentTraining(ctx)) }
    var wake by remember { mutableStateOf(Prefs.consentWakeWord(ctx)) }
    var onDevice by remember { mutableStateOf(Prefs.storeOnDeviceOnly(ctx)) }
    var hapticsOn by remember { mutableStateOf(Prefs.hapticsEnabled(ctx)) }
    var theme by remember { mutableStateOf(Prefs.themeMode(ctx)) }

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

            SectionHeader(Icons.Filled.GraphicEq, "Response")
            Text("Lower volume to ${duck.toInt()}% when someone talks")
            Slider(duck, { duck = it; Prefs.setDuckLevel(ctx, it.toInt()) }, valueRange = 10f..80f)

            SectionHeader(Icons.Filled.SquareFoot, "Room size")
            Text(
                "Tell SoNex how big the room is — bigger rooms get higher sensitivity so talk from across the hall still registers.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            var roomW by remember { mutableStateOf(Prefs.roomWidth(ctx).takeIf { it > 0 }?.toString() ?: "") }
            var roomL by remember { mutableStateOf(Prefs.roomLength(ctx).takeIf { it > 0 }?.toString() ?: "") }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    roomW, { roomW = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Width (m)") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal)
                )
                OutlinedTextField(
                    roomL, { roomL = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Length (m)") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal)
                )
            }
            val w = roomW.toFloatOrNull(); val l = roomL.toFloatOrNull()
            if (w != null && l != null && w > 0 && l > 0) {
                val sens = com.sonex.core.RoomProfile.sensitivityFor(w.toDouble(), l.toDouble())
                Text(
                    "Coverage sensitivity: ${(sens * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                TextButton(onClick = {
                    buzz()
                    Prefs.setRoomSize(ctx, w, l)
                    val cal = Prefs.currentCalibration(ctx).copy(
                        sensitivity = sens,
                        restoreDelaySec = com.sonex.core.RoomProfile.restoreDelaySecFor(w.toDouble(), l.toDouble())
                    )
                    Prefs.saveCalibration(ctx, cal)
                    toast("Room saved — sensitivity adapted ✓")
                }) { Text("Apply room size") }
            }

            SectionHeader(Icons.Filled.Palette, "Appearance")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "Auto", "light" to "Light", "dark" to "Dark").forEach { (mode, label) ->
                    FilterChip(
                        selected = theme == mode,
                        onClick = { buzz(); theme = mode; Prefs.setThemeMode(ctx, mode); toast("Theme: $label ✓") },
                        label = { Text(label) },
                        leadingIcon = {
                            Icon(
                                when (mode) {
                                    "light" -> Icons.Filled.LightMode
                                    "dark" -> Icons.Filled.DarkMode
                                    else -> Icons.Filled.BrightnessAuto
                                }, null, Modifier.size(18.dp)
                            )
                        }
                    )
                }
            }
            ConsentRow("Haptic feedback", hapticsOn) {
                hapticsOn = it; Prefs.setHapticsEnabled(ctx, it); buzz(); toast(if (it) "Haptics on ✓" else "Haptics off ✓")
            }

            SectionHeader(Icons.Filled.Shield, "Privacy & consent")
            Text(
                "All audio is processed on your device. Nothing leaves your phone unless you turn it on below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            ConsentRow("Keep all my data on this device only", onDevice) {
                onDevice = it
                syncedConsent("c_store_server", !it)
            }
            ConsentRow("Upload clips to improve detection", upload) { upload = it; syncedConsent("c_upload", it) }
            ConsentRow("Share anonymous usage stats", telemetry) { telemetry = it; syncedConsent("c_telemetry", it) }
            ConsentRow("Let SoNex learn my home", training) { training = it; syncedConsent("c_training", it) }
            ConsentRow("Wake word \"SoNex\" always listening", wake) { wake = it; syncedConsent("c_wakeword", it) }

            SectionHeader(Icons.Filled.Speaker, "When someone talks, each device should…")
            listOf(
                "tv" to "SoNex TV", "bt" to "Bluetooth",
                "wired" to "Earphones", "cast" to "Cast"
            ).forEach { (id, label) ->
                TargetRuleRow(label, Prefs.targetRule(ctx, id)) { buzz(); Prefs.setTargetRule(ctx, id, it) }
            }

            SectionHeader(Icons.Filled.SystemUpdate, "App")
            UpdateCheckRow(onToast = ::toast)
            Spacer(Modifier.height(12.dp))
            var showFeedback by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { buzz(); showFeedback = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Feedback, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Send feedback")
            }
            if (showFeedback) {
                FeedbackDialog(onDismiss = { showFeedback = false }, onToast = ::toast)
            }

            SectionHeader(Icons.Filled.AccountCircle, "Account")
            var showChangePw by remember { mutableStateOf(false) }
            OutlinedButton(
                onClick = { buzz(); showChangePw = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.LockReset, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Change password")
            }
            if (showChangePw) {
                PasswordResetDialog(
                    initialEmail = Prefs.accountEmail(ctx) ?: "",
                    onDismiss = { showChangePw = false },
                    onDone = { msg ->
                        showChangePw = false
                        toast("$msg — signed out everywhere")
                        Prefs.logout(ctx)   // server already tore down all sessions
                        onLoggedOut()
                    }
                )
            }
            Spacer(Modifier.height(12.dp))
            var confirmLogout by remember { mutableStateOf(false) }
            OutlinedButton(
                onClick = { buzz(); confirmLogout = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Log out")
            }
            if (confirmLogout) {
                AlertDialog(
                    onDismissRequest = { confirmLogout = false },
                    title = { Text("Log out?") },
                    text = { Text("You'll need to sign in again. Your calibration, pairing and settings stay on this phone.") },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmLogout = false
                            Prefs.logout(ctx)
                            toast("Logged out ✓")
                            onLoggedOut()
                        }) { Text("Log out") }
                    },
                    dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } }
                )
            }

            Spacer(Modifier.height(12.dp))
            var confirmDelete by remember { mutableStateOf(false) }
            OutlinedButton(
                onClick = { buzz(); confirmDelete = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Filled.DeleteForever, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Delete all my data")
            }
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
                    dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
                )
            }

            Spacer(Modifier.height(28.dp))
            Text(
                "A product of Patience AI",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Feedback modal: message + opt-in diagnostics, mailed to the growth team. */
@Composable
private fun FeedbackDialog(onDismiss: () -> Unit, onToast: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    var includeDiag by remember { mutableStateOf(true) }
    var sending by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text("Send feedback") },
        text = {
            Column {
                OutlinedTextField(
                    message, { message = it },
                    label = { Text("What's working? What isn't?") },
                    minLines = 4, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(includeDiag, { includeDiag = it })
                    Text(
                        "Attach diagnostics (app version, calibration, settings — never audio)",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = message.isNotBlank() && !sending,
                onClick = {
                    sending = true
                    scope.launch {
                        val status = ServerSync.sendFeedback(ctx, message.trim(), includeDiag)
                        sending = false
                        onToast(status.message)
                        if (status is ServerSync.Status.Ok) onDismiss()
                    }
                }
            ) {
                if (sending) CircularProgressIndicator(Modifier.size(18.dp)) else Text("Send")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !sending) { Text("Cancel") } }
    )
}

@Composable
private fun SectionHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Spacer(Modifier.height(22.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
    Spacer(Modifier.height(8.dp))
}

/** "Check for update" -> popup: fetching -> versions for phone & TV -> install. */
@Composable
private fun UpdateCheckRow(onToast: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var releases by remember { mutableStateOf<Map<String, UpdateChecker.Release>?>(null) }
    var fetching by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }

    OutlinedButton(onClick = {
        open = true; fetching = true; releases = null
        scope.launch { releases = UpdateChecker.fetch(ctx); fetching = false }
    }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.SystemUpdate, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Check for updates")
    }

    if (open) {
        val installed = UpdateChecker.installedVersionCode(ctx)
        val mobile = releases?.get("mobile")
        val newer = mobile != null && mobile.version_code > installed
        AlertDialog(
            onDismissRequest = { if (progress == null) open = false },
            title = { Text("App updates") },
            text = {
                Column {
                    when {
                        fetching -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp)); Spacer(Modifier.width(12.dp))
                            Text("Fetching latest versions…")
                        }
                        releases == null -> Text("Couldn't reach the update server. Check your connection and try again.")
                        else -> {
                            Text(
                                if (newer) "📱 Phone: v${mobile!!.version_name} available (you have v$installed)"
                                else "📱 Phone: you're up to date (v$installed)"
                            )
                            Spacer(Modifier.height(6.dp))
                            releases?.get("tv")?.let {
                                Text("📺 TV: latest is v${it.version_name} — update from the SoNex TV app or sonexus.onrender.com")
                            }
                            progress?.let {
                                Spacer(Modifier.height(12.dp))
                                LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
                                Text("Downloading… ${(it * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (newer && progress == null) {
                    TextButton(onClick = {
                        progress = 0f
                        scope.launch {
                            val err = UpdateChecker.downloadAndInstall(ctx, mobile!!) { p -> progress = p }
                            progress = null
                            if (err != null) onToast(err) else open = false
                        }
                    }) { Text("Update now") }
                }
            },
            dismissButton = {
                if (progress == null) TextButton(onClick = { open = false }) { Text("Close") }
            }
        )
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
