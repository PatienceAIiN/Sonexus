package com.sonex.mobile.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import com.sonex.core.Dsp
import com.sonex.core.RoomState
import com.sonex.core.RoomStateMachine

/**
 * The "ear + brain". Captures mic frames, computes loudness, runs a simple
 * energy-based Voice Activity Detection, and drives a smoothed state machine.
 *
 * PHASE 1 uses an energy + zero-crossing heuristic so the loop works with zero
 * model files. The classification seam lives in [RoomStateMachine.classify],
 * where Silero VAD (speech?) and YAMNet (speech vs. non-speech noise?) drop in
 * later. Those ship as OTA model files, so swapping them never needs an app
 * update. All decision math lives in :core so it is unit-tested on the JVM.
 */
class DetectionEngine(
    private val calibration: Calibration,
    private val classifier: FrameClassifier,
    /** RAW mic source (matches the web app) — see [MicSource]. */
    private val audioSource: Int = MediaRecorder.AudioSource.UNPROCESSED,
    /** AudioDeviceInfo.id of the mic the user picked in Settings; -1 = auto. */
    private val preferredMicId: Int = -1,
    /** Used only to resolve [preferredMicId] to a device; null = auto mic. */
    private val audioManager: android.media.AudioManager? = null,
    /** Live loudness ~8×/sec, EVERY frame regardless of state — so the UI shows a
     *  moving room level and proves the mic works even in a quiet room (otherwise
     *  the screen looks frozen on "Ready" and the app seems dead). */
    private val onLevel: (Double) -> Unit = {},
    private val onState: (RoomState, Double) -> Unit
) {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 30
        val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000 // 480 samples
    }

    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null

    /** Optional PCM tap (voice control shares this mic — never open a second AudioRecord). */
    @Volatile var pcmTap: ((ShortArray, Int) -> Unit)? = null

    private val machine = RoomStateMachine(
        quietOffFrames = calibration.restoreDelaySec * 1000 / FRAME_MS
    )

    fun start() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufBytes = maxOf(minBuf, FRAME_SAMPLES * 2 * 4)
        // Some phones don't support UNPROCESSED and return an uninitialised
        // recorder (then startRecording throws -> "Ready/not working"). Try the
        // chosen source first, then fall back until one actually initialises.
        val sources = listOf(audioSource,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION).distinct()
        var rec: AudioRecord? = null
        var usedSource = audioSource
        for (src in sources) {
            val r = runCatching {
                AudioRecord(src, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufBytes)
            }.getOrNull()
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED) { rec = r; usedSource = src; break }
            runCatching { r?.release() }
        }
        if (rec == null) { running = false; return } // mic unavailable — bail safely
        // Only add echo cancellation on processed sources; UNPROCESSED must stay raw.
        if (usedSource != MediaRecorder.AudioSource.UNPROCESSED && AcousticEchoCanceler.isAvailable()) {
            aec = runCatching { AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true } }.getOrNull()
        }
        // Honour the user's chosen microphone from Settings, if it's connected.
        val am = audioManager
        if (preferredMicId >= 0 && am != null) runCatching {
            am.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.id == preferredMicId }
                ?.let { rec.setPreferredDevice(it) }
        }
        record = rec
        rec.startRecording()
        running = true
        Thread(::loop, "sonex-detect").start()
    }

    fun stop() {
        running = false
        aec?.release(); aec = null
        record?.apply { stop(); release() }
        record = null
        classifier.close()
    }

    private fun loop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ShortArray(FRAME_SAMPLES)
        var lvlTick = 0
        while (running) {
            val n = record?.read(buf, 0, FRAME_SAMPLES) ?: break
            if (n <= 0) continue
            pcmTap?.invoke(buf, n)
            val db = Dsp.rmsDb(buf, n)
            // Push the live level ~every 4th frame (~120ms) so the UI is always
            // visibly alive; routing/notifications still fire only on real changes.
            if (++lvlTick >= 4) { lvlTick = 0; onLevel(db) }
            machine.step(classifier.classify(buf, n, db))?.let { onState(it, db) }
        }
    }
}
