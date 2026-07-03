package com.sonex.mobile.audio

import java.io.ByteArrayOutputStream

/**
 * Consented, privacy-respecting training-clip collector. Keeps only the last
 * ~1.5s of mic audio in a rolling in-memory buffer (nothing written to disk).
 * When a clear detection happens AND the user has opted into "Let SoNex learn my
 * home", a short labelled clip is snapshotted for upload — heavily rate-limited.
 * On the server it is used to train and then deleted. If consent is off, the
 * collector is never fed, so no audio is retained at all.
 */
class ClipCollector(sampleRate: Int) {
    private val capacity = (sampleRate * 1.5).toInt()
    private val ring = ShortArray(capacity)
    private var head = 0
    private var filled = 0

    @Synchronized
    fun append(buf: ShortArray, n: Int) {
        for (i in 0 until n) {
            ring[head] = buf[i]
            head = (head + 1) % capacity
            if (filled < capacity) filled++
        }
    }

    /** Copy the buffered audio in chronological order, or null if too little. */
    @Synchronized
    fun snapshot(minSamples: Int): ShortArray? {
        if (filled < minSamples) return null
        val out = ShortArray(filled)
        val start = if (filled < capacity) 0 else head
        for (i in 0 until filled) out[i] = ring[(start + i) % capacity]
        return out
    }

    @Synchronized fun clear() { head = 0; filled = 0 }
}

/** Minimal 16-bit PCM mono WAV encoder (RIFF) — matches the server's decoder. */
object Wav {
    fun encode(pcm: ShortArray, sampleRate: Int): ByteArray {
        val dataLen = pcm.size * 2
        val out = ByteArrayOutputStream(44 + dataLen)
        fun i32(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff); out.write((v shr 16) and 0xff); out.write((v shr 24) and 0xff) }
        fun i16(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff) }
        out.write("RIFF".toByteArray()); i32(36 + dataLen); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); i32(16); i16(1); i16(1)      // PCM, mono
        i32(sampleRate); i32(sampleRate * 2); i16(2); i16(16)         // byte rate, block align, bits
        out.write("data".toByteArray()); i32(dataLen)
        for (s in pcm) i16(s.toInt())
        return out.toByteArray()
    }
}
