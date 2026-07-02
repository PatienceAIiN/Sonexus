package com.sonex.mobile.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/**
 * Phone-call layer: an active call forces TALKING (duck everything) and
 * restore waits until the call has ended AND the room is quiet.
 * Auto-decline is intentionally not implemented — permission-sensitive and
 * default OFF per the product spec.
 */
class CallMonitor(private val context: Context, private val onCallActive: (Boolean) -> Unit) {

    private val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private var callback: TelephonyCallback? = null
    @Suppress("DEPRECATION")
    private var listener: PhoneStateListener? = null

    private fun granted() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_PHONE_STATE
    ) == PackageManager.PERMISSION_GRANTED

    fun start() {
        if (!granted()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = report(state)
            }
            callback = cb
            telephony.registerTelephonyCallback(context.mainExecutor, cb)
        } else {
            @Suppress("DEPRECATION")
            val l = object : PhoneStateListener() {
                @Deprecated("pre-S path")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) = report(state)
            }
            listener = l
            @Suppress("DEPRECATION")
            telephony.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    private fun report(state: Int) =
        onCallActive(state != TelephonyManager.CALL_STATE_IDLE)

    fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            callback?.let { telephony.unregisterTelephonyCallback(it) }; callback = null
        } else {
            @Suppress("DEPRECATION")
            listener?.let { telephony.listen(it, PhoneStateListener.LISTEN_NONE) }; listener = null
        }
    }
}
