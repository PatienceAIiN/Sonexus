package com.sonex.mobile.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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

                val permissions = androidx.activity.compose.rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { granted ->
                    if (granted[Manifest.permission.RECORD_AUDIO] == true) startListening()
                }

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
                            if (hasPermission(Manifest.permission.RECORD_AUDIO)) startListening()
                            else permissions.launch(requiredPermissions())
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

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        else arrayOf(Manifest.permission.RECORD_AUDIO)

    private fun startListening() {
        ContextCompat.startForegroundService(this, Intent(this, ListeningService::class.java))
    }

    // Fully-automatic start: follow the paired TV onto its Wi-Fi.
    private var netCb: android.net.ConnectivityManager.NetworkCallback? = null

    override fun onResume() {
        super.onResume()
        netCb = com.sonex.mobile.network.AutoStart.register(this)
        com.sonex.mobile.network.AutoStart.maybeStart(this)
        // Pull the latest cross-device settings (rules/duck/room/theme) on focus.
        if (Prefs.isLoggedIn(this)) kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
            .launch { com.sonex.mobile.data.ServerSync.pullSettings(this@MainActivity) }
    }

    override fun onPause() {
        super.onPause()
        netCb?.let { com.sonex.mobile.network.AutoStart.unregister(this, it) }
        netCb = null
    }
}
