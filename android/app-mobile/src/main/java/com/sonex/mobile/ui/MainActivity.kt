package com.sonex.mobile.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.sonex.mobile.data.UpdateChecker
import com.sonex.mobile.audio.ListeningService
import com.sonex.mobile.data.Prefs
import kotlinx.coroutines.launch

/** Single-activity Compose host. Simple enum-based navigation keeps Phase 1 lean. */
class MainActivity : ComponentActivity() {

    enum class Screen { LOGIN, PAIR, HOME, CALIBRATE, SETTINGS, PRIVACY }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SonexTheme {
                var screen by remember {
                    mutableStateOf(if (Prefs.isLoggedIn(this)) Screen.HOME else Screen.LOGIN)
                }
                // Shown when the mic permission is permanently denied — the system
                // dialog no longer appears, so we must send the user to Settings.
                var needMicSettings by remember { mutableStateOf(false) }

                val permissions = androidx.activity.compose.rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { granted ->
                    if (granted[Manifest.permission.RECORD_AUDIO] == true) startListening()
                    else if (!androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                            this, Manifest.permission.RECORD_AUDIO)) {
                        // Denied with "don't ask again" -> only Settings can fix it.
                        needMicSettings = true
                    } else toast("SoNex needs the microphone to hear the room and adjust volume.")
                }

                if (needMicSettings) MicPermissionDialog(onDismiss = { needMicSettings = false })

                // System back: navigate inside the app, never silently exit.
                var lastBackPress by remember { mutableStateOf(0L) }
                androidx.activity.compose.BackHandler {
                    when (screen) {
                        Screen.PAIR, Screen.CALIBRATE, Screen.SETTINGS -> screen = Screen.HOME
                        Screen.PRIVACY -> screen = Screen.SETTINGS
                        Screen.HOME, Screen.LOGIN -> {
                            val now = System.currentTimeMillis()
                            if (now - lastBackPress < 2000) finish()
                            else {
                                lastBackPress = now
                                android.widget.Toast.makeText(
                                    this, "Press back again to exit", android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }

                // Mirror the OTA model download/configure to a notification, so the
                // user sees progress even outside the app; cleared when it's done.
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.flow.combine(
                        com.sonex.mobile.data.ModelStore.syncStatus,
                        com.sonex.mobile.data.ModelStore.syncProgress
                    ) { s, p -> s to p }.collect { (s, p) -> updateSetupNotification(s, p) }
                }

                AutoUpdatePrompt()
                when (screen) {
                    Screen.LOGIN -> LoginScreen(onLoggedIn = { screen = Screen.HOME })
                    Screen.PAIR -> PairScreen(
                        onPaired = { tv ->
                            Prefs.setPairedTv(this, tv)
                            screen = Screen.HOME
                        },
                        onBack = { screen = Screen.HOME }
                    )
                    Screen.HOME -> HomeScreen(
                        onCalibrate = { screen = Screen.CALIBRATE },
                        onSettings = { screen = Screen.SETTINGS },
                        onPairTv = { screen = Screen.PAIR },
                        onEnsureMic = {
                            when {
                                hasPermission(Manifest.permission.RECORD_AUDIO) -> startListening()
                                // Never asked, or a normal denial -> the system dialog can still show.
                                !Prefs.micPermissionAsked(this) ||
                                    androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                                        this, Manifest.permission.RECORD_AUDIO) -> {
                                    Prefs.setMicPermissionAsked(this, true)
                                    permissions.launch(requiredPermissions())
                                }
                                // Asked before and the dialog won't reappear -> permanently denied.
                                else -> needMicSettings = true
                            }
                        }
                    )
                    Screen.CALIBRATE -> CalibrateScreen(onDone = { screen = Screen.HOME })
                    Screen.SETTINGS -> SettingsScreen(
                        onBack = { screen = Screen.HOME },
                        onDataDeleted = { screen = Screen.LOGIN },
                        onLoggedOut = { screen = Screen.LOGIN },
                        onOpenPrivacy = { screen = Screen.PRIVACY }
                    )
                    Screen.PRIVACY -> PrivacyScreen(onBack = { screen = Screen.SETTINGS })
                }
            }
        }
    }

    /**
     * On every launch: if a newer build is published, show an interactive
     * Update/Not-now dialog. "Not now" hides it for this session only — it
     * returns on next restart until the app is actually updated, after which
     * the version check makes it disappear on its own.
     */
    @Composable
    private fun AutoUpdatePrompt() {
        val scope = rememberCoroutineScope()
        var update by remember { mutableStateOf<UpdateChecker.Release?>(null) }
        var dismissed by remember { mutableStateOf(false) }
        var progress by remember { mutableStateOf<Float?>(null) }

        LaunchedEffect(Unit) {
            val m = UpdateChecker.fetch(this@MainActivity)?.get("mobile") ?: return@LaunchedEffect
            if (m.version_code > UpdateChecker.installedVersionCode(this@MainActivity)) update = m
        }

        val u = update ?: return
        if (dismissed) return
        AlertDialog(
            onDismissRequest = { if (progress == null) dismissed = true },
            icon = { Icon(Icons.Filled.SystemUpdate, null) },
            title = { Text("Update available") },
            text = {
                Column {
                    Text("SoNex v${u.version_name} is ready — new improvements and fixes.")
                    progress?.let {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
                        Text("Downloading… ${(it * 100).toInt()}%")
                    }
                }
            },
            confirmButton = {
                if (progress == null) TextButton(onClick = {
                    progress = 0f
                    scope.launch {
                        UpdateChecker.downloadAndInstall(this@MainActivity, u) { p -> progress = p }
                        progress = null
                    }
                }) { Text("Update") }
            },
            dismissButton = {
                if (progress == null) TextButton(onClick = { dismissed = true }) { Text("Not now") }
            }
        )
    }

    /** Mic permanently denied: the system dialog no longer appears, so guide the
     *  user to app Settings where they can turn Microphone back on. */
    @Composable
    private fun MicPermissionDialog(onDismiss: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Turn on the microphone") },
            text = { Text("SoNex needs microphone access to hear the room and adjust your volume. " +
                    "It's currently off — tap Open Settings, then allow Microphone for SoNex.") },
            confirmButton = { TextButton(onClick = { openAppSettings(); onDismiss() }) { Text("Open Settings") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
        )
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", packageName, null)))
        }
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        else arrayOf(Manifest.permission.RECORD_AUDIO)

    /** OTA download/configure progress mirrored to the notification shade. */
    private fun updateSetupNotification(status: String?, progress: Float?) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (status == null) { nm.cancel(42); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm.getNotificationChannel("sonex_setup") == null) {
            nm.createNotificationChannel(
                NotificationChannel("sonex_setup", "SoNex setup", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val done = status.contains("ready", ignoreCase = true)
        val b = NotificationCompat.Builder(this, "sonex_setup")
            .setSmallIcon(if (done) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_sys_download)
            .setContentTitle("SoNex")
            .setContentText(status)
            .setOngoing(!done)
            .setSilent(true)
        when {
            done -> b.setProgress(0, 0, false)
            progress != null -> b.setProgress(100, (progress * 100).toInt(), false)
            else -> b.setProgress(0, 0, true) // indeterminate (configuring)
        }
        runCatching { nm.notify(42, b.build()) }
    }

    private fun startListening() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, ListeningService::class.java))
        } catch (t: Throwable) {
            android.util.Log.e("SonexMain", "startForegroundService failed", t)
            toast("Couldn't start listening: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun toast(m: String) = runOnUiThread {
        android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show()
    }

    // Fully-automatic start: follow the paired TV onto its Wi-Fi.
    private var netCb: android.net.ConnectivityManager.NetworkCallback? = null

    override fun onResume() {
        super.onResume()
        netCb = com.sonex.mobile.network.AutoStart.register(this)
        com.sonex.mobile.network.AutoStart.maybeStart(this)
        // Pull the latest cross-device settings + newest trained model on focus,
        // so model improvements arrive automatically without an app update.
        if (Prefs.isLoggedIn(this)) kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            com.sonex.mobile.data.ServerSync.pullSettings(this@MainActivity)
            val url = Prefs.serverUrl(this@MainActivity)
            val key = Prefs.deviceKey(this@MainActivity)
            val id = Prefs.deviceId(this@MainActivity)
            if (url != null && key != null && id != null) {
                val store = com.sonex.mobile.data.ModelStore(this@MainActivity)
                // One-time: tell the user the smart-detection model is downloading.
                val hadVad = store.verifiedFile("vad") != null
                if (!hadVad) toast("Downloading smart detection… (one-time)")
                val updated = store.sync(url, key, id)
                if (!hadVad && "vad" in updated && store.verifiedFile("vad") != null)
                    toast("Smart detection ready — restart listening to use it")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        netCb?.let { com.sonex.mobile.network.AutoStart.unregister(this, it) }
        netCb = null
    }
}
