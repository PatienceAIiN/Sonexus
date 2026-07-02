package com.sonex.mobile.output

import android.content.Context
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import com.sonex.core.Action
import com.sonex.core.Command
import com.sonex.core.VolumePolicy
import com.sonex.mobile.pairing.PairingClient

/**
 * Phone speaker (or wired headphones): direct STREAM_MUSIC control.
 * Inactive while media routes to Bluetooth — BluetoothTarget owns it then.
 */
class PhoneSpeakerTarget(private val audio: AudioManager) : OutputTarget {
    override val id = "phone"
    override val name = "Phone speaker"
    override val isActive: Boolean get() = !audio.isBluetoothA2dpOn
    private val policy = VolumePolicy(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))

    override suspend fun send(cmd: Command) {
        when (cmd.action) {
            Action.PAUSE -> mediaKey(audio, KeyEvent.KEYCODE_MEDIA_PAUSE)
            Action.RESUME -> mediaKey(audio, KeyEvent.KEYCODE_MEDIA_PLAY)
            else -> policy.apply(cmd, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                ?.let { audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) }
        }
    }
}

/**
 * Bluetooth media (speaker/headphones). The phone is the audio source, so
 * echo cancellation is clean and STREAM_MUSIC volume follows the A2DP link
 * (absolute volume). Active only while audio actually routes over BT.
 */
class BluetoothTarget(private val audio: AudioManager) : OutputTarget {
    override val id = "bt"
    override val name = "Bluetooth"
    override val isActive: Boolean get() = audio.isBluetoothA2dpOn
    private val policy = VolumePolicy(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))

    override suspend fun send(cmd: Command) {
        when (cmd.action) {
            Action.PAUSE -> mediaKey(audio, KeyEvent.KEYCODE_MEDIA_PAUSE)
            Action.RESUME -> mediaKey(audio, KeyEvent.KEYCODE_MEDIA_PLAY)
            else -> policy.apply(cmd, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                ?.let { audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) }
        }
    }
}

/** The paired SoNex TV companion app, over the LAN command channel. */
class TvTarget(private val pairing: PairingClient) : OutputTarget {
    override val id = "tv"
    override val name = "SoNex TV"
    override suspend fun send(cmd: Command) { pairing.send(cmd) }
}

/**
 * Google Cast receiver via the Cast SDK. Everything is guarded: on devices
 * without Play Services (or with no session) this target simply stays inactive.
 */
class CastTarget(private val context: Context) : OutputTarget {
    override val id = "cast"
    override val name = "Cast"

    private fun session() = runCatching {
        com.google.android.gms.cast.framework.CastContext.getSharedInstance(context)
            .sessionManager.currentCastSession
    }.getOrNull()

    override val isActive: Boolean get() = session()?.isConnected == true
    private var savedVolume = -1.0

    override suspend fun send(cmd: Command) {
        val s = session() ?: return
        runCatching {
            when (cmd.action) {
                Action.DUCK, Action.BOOST -> {
                    if (savedVolume < 0) savedVolume = s.volume
                    s.volume = cmd.level.coerceIn(0, 100) / 100.0
                }
                Action.MUTE -> { if (savedVolume < 0) savedVolume = s.volume; s.isMute = true }
                Action.RESTORE, Action.RESUME -> {
                    s.isMute = false
                    if (savedVolume >= 0) { s.volume = savedVolume; savedVolume = -1.0 }
                    if (cmd.action == Action.RESUME) s.remoteMediaClient?.play()
                }
                Action.PAUSE -> s.remoteMediaClient?.pause()
            }
        }.onFailure { Log.w("SonexCast", "Cast command failed", it) }
    }
}

private fun mediaKey(audio: AudioManager, code: Int) {
    audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
    audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
}
