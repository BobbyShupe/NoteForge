package com.robert.noteforge.synth

/** Minimal WAV reader: 16/24/32-bit PCM and 32-bit float, any channel count. */
data class WavData(val samples: FloatArray, val sampleRate: Int)

private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
private fun le16s(b: ByteArray, o: Int): Int {
    val v = le16(b, o)
    return if (v >= 32768) v - 65536 else v
}
private fun le24s(b: ByteArray, o: Int): Int {
    val v = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt()) shl 16)
    return v
}
private fun le32(b: ByteArray, o: Int): Int =
    (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
        ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt()) shl 24)

fun decodeWav(bytes: ByteArray): WavData {
    require(bytes.size > 44) { "too small" }
    require(bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
        bytes[8] == 'W'.code.toByte()) { "not RIFF/WAVE" }
    var pos = 12
    var channels = 1
    var rate = 44100
    var bits = 16
    var format = 1
    var dataStart = -1
    var dataLen = 0
    while (pos + 8 <= bytes.size) {
        val id = String(bytes, pos, 4, Charsets.US_ASCII)
        val size = le32(bytes, pos + 4)
        if (size < 0 || pos + 8 + size > bytes.size) break
        when (id) {
            "fmt " -> {
                format = le16(bytes, pos + 8)
                channels = le16(bytes, pos + 10)
                rate = le32(bytes, pos + 12)
                bits = le16(bytes, pos + 22)
            }
            "data" -> {
                dataStart = pos + 8
                dataLen = size
                // keep scanning in case of multiple data chunks; take the first
                if (dataStart > 0) break
            }
        }
        pos += 8 + size + (size and 1)
    }
    require(dataStart > 0 && dataLen > 0) { "no data chunk" }
    val bytesPerSample = bits / 8
    require(bytesPerSample in 1..4 && channels in 1..8)
    val frames = (dataLen / bytesPerSample / channels).coerceAtLeast(1)
    val out = FloatArray(frames)
    for (f in 0 until frames) {
        var sum = 0.0
        for (c in 0 until channels) {
            val o = dataStart + (f * channels + c) * bytesPerSample
            if (o + bytesPerSample > bytes.size) break
            sum += when (format) {
                3 -> if (bits == 32)
                    java.lang.Float.intBitsToFloat(le32(bytes, o)).toDouble() else 0.0
                else -> when (bits) {
                    16 -> le16s(bytes, o) / 32768.0
                    8 -> ((bytes[o].toInt() and 0xFF) - 128) / 128.0
                    24 -> le24s(bytes, o) / 8388608.0
                    32 -> le32(bytes, o) / 2147483648.0
                    else -> 0.0
                }
            }
        }
        out[f] = (sum / channels).toFloat().coerceIn(-1f, 1f)
    }
    return WavData(out, rate)
}
