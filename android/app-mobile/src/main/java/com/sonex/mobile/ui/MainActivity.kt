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
import androidx.core.content.ContextCompat
import com.sonex.mobile.audio.ListeningService
import com.sonex.mobile.data.Prefs

/** Single-activity Compose host. Simple enum-based navigation keeps Phase 1 lean. */
class MainActivity : ComponentActivity() {

    enum class Screen { LOGIN, PAIR, HOME, CALIBRATE, SETTINGS }

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
                        onDataDeleted = { screen = Screen.LOGIN }
                    )
                }
            }
        }
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
}
