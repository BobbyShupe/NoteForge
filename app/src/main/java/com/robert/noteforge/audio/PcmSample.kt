package com.robert.noteforge.audio

/** Decoded, mono, 16-bit PCM sample data held in memory for fast playback + resampling. */
data class PcmSample(
    val samples: ShortArray,   // interleaved if channelCount > 1
    val sampleRate: Int,
    val channelCount: Int
) {
    val durationMs: Long
        get() = if (sampleRate == 0) 0 else (samples.size.toLong() * 1000L) / (sampleRate.toLong() * channelCount)
}
