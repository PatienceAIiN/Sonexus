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
        val rec = AudioRecord(
            audioSource, // RAW audio (no AGC/NS) so speech modulation survives — like the web app
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, FRAME_SAMPLES * 2 * 4)
        )
        // Only add echo cancellation on processed sources; UNPROCESSED must stay raw.
        if (audioSource != MediaRecorder.AudioSource.UNPROCESSED && AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true }
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
        while (running) {
            val n = record?.read(buf, 0, FRAME_SAMPLES) ?: break
            if (n <= 0) continue
            pcmTap?.invoke(buf, n)
            val db = Dsp.rmsDb(buf, n)
            machine.step(classifier.classify(buf, n, db))?.let { onState(it, db) }
        }
    }
}
