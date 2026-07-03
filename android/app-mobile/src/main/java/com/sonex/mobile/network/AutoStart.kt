package com.sonex.mobile.network

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.content.ContextCompat
import com.sonex.mobile.audio.ListeningService
import com.sonex.mobile.data.Prefs
import com.sonex.mobile.pairing.PairingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Fully-automatic start: the product is meant to "just work". When the phone
 * is on Wi-Fi and a paired TV is actually reachable on that LAN, SoNex starts
 * listening on its own — no tapping Start. A manual Stop suppresses this until
 * the phone leaves and rejoins Wi-Fi, so the user always has the final say.
 *
 * Foreground mic services can only be launched while the app is in the
 * foreground, so this is driven from MainActivity (onResume + a live network
 * callback), never from the background.
 */
object AutoStart {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Check right now: if eligible and a paired TV is on this Wi-Fi, start. */
    fun maybeStart(context: Context) {
        val app = context.applicationContext
        if (!Prefs.autoStartOnWifi(app)) return
        if (Prefs.autoStartSuppressed(app)) return
        if (ListeningService.running.value) return
        if (Prefs.pairedTv(app) == null) return   // nothing paired => nothing to follow
        if (!onWifi(app)) return

        scope.launch {
            val found = runCatching { PairingClient(app).discover(timeoutMs = 6_000) }.getOrDefault(false)
            if (!found) return@launch
            if (ListeningService.running.value || Prefs.autoStartSuppressed(app)) return@launch
            Prefs.setListeningEnabled(app, true)
            ContextCompat.startForegroundService(app, Intent(app, ListeningService::class.java))
        }
    }

    /** Register a network callback so joining Wi-Fi triggers a start attempt,
     *  and leaving it clears the manual-Stop suppression. Call from onResume. */
    fun register(context: Context): ConnectivityManager.NetworkCallback {
        val app = context.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { maybeStart(app) }
            override fun onLost(network: Network) {
                // Fresh Wi-Fi session => honour auto-start again next time.
                Prefs.setAutoStartSuppressed(app, false)
            }
        }
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        runCatching { cm.registerNetworkCallback(req, cb) }
        return cb
    }

    fun unregister(context: Context, cb: ConnectivityManager.NetworkCallback) {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        runCatching { cm.unregisterNetworkCallback(cb) }
    }

    private fun onWifi(c: Context): Boolean {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
