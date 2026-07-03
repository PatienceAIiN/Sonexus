package com.sonex.mobile.audio

import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder

/**
 * Picks the RAW mic source so the Android app hears the room the same way SoNex
 * Web does. VOICE_RECOGNITION/VOICE_COMMUNICATION apply automatic gain control
 * and noise suppression that FLATTEN level dynamics — which destroys the
 * modulation cue we use to tell a talking person from a steady machine. The web
 * app opens the mic with autoGainControl/noiseSuppression OFF and works great;
 * UNPROCESSED (or plain MIC as a fallback) gives us the same untouched signal.
 */
object MicSource {
    fun best(context: Context): Int {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val unprocessed = runCatching {
            am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        }.getOrDefault(false)
        return if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED
        else MediaRecorder.AudioSource.MIC
    }
}
