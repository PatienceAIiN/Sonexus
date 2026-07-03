package com.sonex.mobile.pairing

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.sonex.core.Command
import com.sonex.core.Net
import com.sonex.core.PairRequest
import com.sonex.core.PairResponse
import com.sonex.core.PairingProtocol
import com.sonex.core.TvState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phone side of pairing. Discovers the SoNex TV on the same Wi-Fi via mDNS,
 * then completes the handshake with the 4-digit code the TV displays.
 * Commands are fired over short-lived sockets; the TV replies with its state.
 */
class PairingClient(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    var host: InetAddress? = null; private set
    var port: Int = Net.DEFAULT_PORT; private set

    /** Latest state the TV reported back, if any. */
    var lastTvState: TvState? = null; private set

    /** Resolve the TV's address on the LAN. False if not found in [timeoutMs]. */
    suspend fun discover(timeoutMs: Long = 10_000): Boolean =
        withTimeoutOrNull(timeoutMs) { discoverOnce() } ?: false

    private suspend fun discoverOnce(): Boolean = suspendCancellableCoroutine { cont ->
        val resumed = AtomicBoolean(false)
        lateinit var listener: NsdManager.DiscoveryListener
        fun finish(found: Boolean) {
            if (resumed.compareAndSet(false, true)) {
                runCatching { nsd.stopServiceDiscovery(listener) }
                cont.resume(found) {}
            }
        }
        listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceType.contains("sonex")) return
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(s: NsdServiceInfo) {
                        host = s.host
                        // Some resolvers report port 0 — fall back to the fixed port.
                        port = if (s.port > 0) s.port else Net.DEFAULT_PORT
                        finish(true)
                    }
                    override fun onResolveFailed(s: NsdServiceInfo, e: Int) {}
                })
            }
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) { finish(false) }
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceLost(s: NsdServiceInfo) {}
        }
        cont.invokeOnCancellation { runCatching { nsd.stopServiceDiscovery(listener) } }
        nsd.discoverServices(Net.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    /** Submit the 4-digit code the TV is showing. */
    suspend fun pair(code: String, phoneName: String): PairResponse = withContext(Dispatchers.IO) {
        val h = host ?: return@withContext PairResponse(false, "", "TV not found on network")
        runCatching {
            connect(h).use { sock ->
                val out = PrintWriter(sock.getOutputStream(), true)
                val inp = BufferedReader(InputStreamReader(sock.getInputStream()))
                out.println(PairingProtocol.encodePair(PairRequest(code, phoneName)))
                val line = inp.readLine() ?: return@use PairResponse(false, "", "No response")
                PairingProtocol.parsePaired(line) ?: PairResponse(false, "", "Bad response")
            }
        }.getOrElse {
            PairResponse(false, "",
                "Couldn't reach the TV — make sure the TV app is open and both devices are on the same Wi-Fi")
        }
    }

    /** Fire a command to the TV; records the state it reports back. */
    suspend fun send(cmd: Command): TvState? = withContext(Dispatchers.IO) {
        val h = host ?: return@withContext null
        runCatching {
            connect(h).use { sock ->
                val out = PrintWriter(sock.getOutputStream(), true)
                val inp = BufferedReader(InputStreamReader(sock.getInputStream()))
                out.println(PairingProtocol.encodeCommand(cmd))
                inp.readLine()?.let(PairingProtocol::parseState)
                    ?.also { lastTvState = it }
            }
        }.getOrNull()
    }

    private fun connect(h: InetAddress): Socket =
        Socket().apply { connect(InetSocketAddress(h, port), 3_000); soTimeout = 3_000 }
}
