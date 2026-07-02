package com.sonex.tv

import android.content.Context
import android.media.AudioManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.sonex.core.Net
import com.sonex.core.PairingProtocol
import com.sonex.core.TvState
import com.sonex.core.VolumePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket

/**
 * TV side. Generates a 4-digit pairing code, advertises "_sonex._tcp" on the
 * LAN, accepts the phone's PAIR handshake, then applies incoming volume/playback
 * commands natively via AudioManager. Reliable because it runs ON the TV — no
 * per-brand remote-control hacks needed. Framing and volume math live in :core
 * (PairingProtocol / VolumePolicy) so they are unit-tested on the JVM.
 */
class TvServer(
    private val context: Context,
    private val onCode: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val code = PairingProtocol.newCode()
    private val policy = VolumePolicy(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
    private var server: ServerSocket? = null
    private var pairedPhone: String? = null
    private var registration: NsdManager.RegistrationListener? = null

    fun start() {
        onCode(code)
        scope.launch {
            runCatching {
                server = ServerSocket(Net.DEFAULT_PORT)
                advertise()
                acceptLoop()
            }.onFailure { onStatus("Server error: ${it.message}") }
        }
    }

    private fun advertise() {
        val info = NsdServiceInfo().apply {
            serviceName = Net.SERVICE_NAME
            serviceType = Net.SERVICE_TYPE
            port = Net.DEFAULT_PORT
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) {}
            override fun onRegistrationFailed(s: NsdServiceInfo, e: Int) {}
            override fun onServiceUnregistered(s: NsdServiceInfo) {}
            override fun onUnregistrationFailed(s: NsdServiceInfo, e: Int) {}
        }
        registration = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun acceptLoop() {
        val srv = server ?: return
        while (!srv.isClosed) {
            runCatching {
                srv.accept().use { sock ->
                    val inp = BufferedReader(InputStreamReader(sock.getInputStream()))
                    val out = PrintWriter(sock.getOutputStream(), true)
                    val line = inp.readLine() ?: return@use
                    when (val msg = PairingProtocol.parseLine(line)) {
                        is PairingProtocol.Message.Pair -> {
                            val res = PairingProtocol.pairResponseFor(msg.request, code, tvName())
                            if (res.ok) {
                                pairedPhone = msg.request.phoneName
                                onStatus("Paired with ${msg.request.phoneName}")
                            }
                            out.println(PairingProtocol.encodePaired(res))
                        }
                        is PairingProtocol.Message.Cmd -> {
                            val cmd = msg.command
                            policy.apply(cmd, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                                ?.let { audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) }
                            onStatus("Applied ${cmd.action} (${cmd.reason})")
                            out.println(PairingProtocol.encodeState(currentState()))
                        }
                        null -> {}
                    }
                }
            }
        }
    }

    private fun currentState() = TvState(
        deviceName = tvName(),
        currentVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC),
        isPlaying = audio.isMusicActive
    )

    private fun tvName(): String = android.os.Build.MODEL ?: "Android TV"

    fun stop() {
        runCatching { registration?.let(nsd::unregisterService) }
        runCatching { server?.close() }
        scope.cancel()
    }
}
