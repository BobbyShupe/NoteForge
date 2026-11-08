package com.robert.noteforge.audio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.toArgb
import com.robert.noteforge.data.Clef
import com.robert.noteforge.data.ScoreNote
import com.robert.noteforge.data.Track
import com.robert.noteforge.ui.score.AndroidScoreCanvas
import com.robert.noteforge.ui.score.ScoreLaneParams
import com.robert.noteforge.ui.score.ScoreTimeStripParams
import com.robert.noteforge.ui.score.StaffGeometry
import com.robert.noteforge.ui.theme.TrackColors
import com.robert.noteforge.ui.score.drawScoreLane
import com.robert.noteforge.ui.score.drawScorePlayhead
import com.robert.noteforge.ui.score.drawScoreTimeStrip
import com.robert.noteforge.ui.theme.Background
import com.robert.noteforge.ui.theme.OnBackground
import com.robert.noteforge.ui.theme.OnSurfaceMuted
import com.robert.noteforge.ui.theme.StaffLine
import com.robert.noteforge.ui.theme.SurfaceVariant
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Renders a high-quality MP4: piano-roll score visualization (no app UI)
 * with 44.1kHz AAC audio mixdown.
 */
@Singleton
class VideoExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mixdownRenderer: MixdownRenderer
) {
    companion object {
        const val WIDTH = 1920
        const val HEIGHT = 1080
        const val FPS = 30
        const val VIDEO_BITRATE = 12_000_000
        const val AUDIO_BITRATE = 192_000
    }

    data class Progress(val phase: String, val fraction: Float, val etaSec: Long? = null)

    suspend fun export(
        notes: List<ScoreNote>,
        tracks: List<Track>,
        tempoBpm: Int,
        songName: String,
        settings: RenderPrefs.Settings,
        onProgress: (Progress) -> Unit
    ): android.net.Uri {
        return when (settings.output) {
            RenderPrefs.OUTPUT_AUDIO_ONLY -> exportAudioOnly(notes, tracks, tempoBpm, songName, settings, onProgress)
            else -> exportVideo(notes, tracks, tempoBpm, songName, settings, onProgress)
        }
    }

    /** Audio-only export: 44.1kHz WAV to Music/NoteForge. */
    private suspend fun exportAudioOnly(
        notes: List<ScoreNote>,
        tracks: List<Track>,
        tempoBpm: Int,
        songName: String,
        settings: RenderPrefs.Settings,
        onProgress: (Progress) -> Unit
    ): android.net.Uri {
        onProgress(Progress("Rendering audio (44.1kHz)", 0f))
        val fullPcm = mixdownRenderer.render(notes, tracks, tempoBpm) { f ->
            onProgress(Progress("Rendering audio (44.1kHz)", f * 0.8f))
        }
        if (fullPcm.isEmpty()) throw IllegalStateException("No audible notes to render")
        // Apply range
        val fullSec = fullPcm.size / 44100.0
        val sSec = if (settings.rangeFull) 0.0 else settings.rangeStartSec.toDouble().coerceIn(0.0, fullSec)
        var eSec = if (settings.rangeFull || settings.rangeEndSec <= 0f) fullSec
            else settings.rangeEndSec.toDouble().coerceIn(sSec, fullSec)
        if (eSec <= sSec) eSec = fullSec
        val sSamp = (sSec * 44100).toInt().coerceIn(0, fullPcm.size)
        val eSamp = (eSec * 44100).toInt().coerceIn(sSamp, fullPcm.size)
        val pcm = fullPcm.sliceArray(sSamp until eSamp)
        if (pcm.isEmpty()) throw IllegalStateException("Render range is empty")

        onProgress(Progress("Writing WAV", 0.85f))
        val safeName = songName.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(50).ifEmpty { "score" }
        val fileName = "${safeName}.wav"
        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(android.provider.MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(android.provider.MediaStore.Audio.Media.RELATIVE_PATH, "Music/NoteForge")
            put(android.provider.MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(
            android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ) ?: throw IllegalStateException("Could not create MediaStore entry")

        try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                // WAV header
                val dataSize = pcm.size * 2
                val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                header.put("RIFF".toByteArray())
                header.putInt(36 + dataSize)
                header.put("WAVE".toByteArray())
                header.put("fmt ".toByteArray())
                header.putInt(16)
                header.putShort(1) // PCM
                header.putShort(1) // mono
                header.putInt(44100)
                header.putInt(44100 * 2)
                header.putShort(2)
                header.putShort(16)
                header.put("data".toByteArray())
                header.putInt(dataSize)
                out.write(header.array())
                // PCM data
                val buf = java.nio.ByteBuffer.allocate(pcm.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                for (s in pcm) buf.putShort(s)
                out.write(buf.array())
            } ?: throw IllegalStateException("Could not write WAV")
        } catch (e: Exception) {
            try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
            throw e
        }
        context.contentResolver.update(uri, android.content.ContentValues().apply {
            put(android.provider.MediaStore.Audio.Media.IS_PENDING, 0)
        }, null, null)

        onProgress(Progress("Done", 1f))
        return uri
    }

    private suspend fun exportVideo(
        notes: List<ScoreNote>,
        tracks: List<Track>,
        tempoBpm: Int,
        songName: String,
        settings: RenderPrefs.Settings,
        onProgress: (Progress) -> Unit
    ): android.net.Uri {
        // 1. Audio mixdown
        onProgress(Progress("Rendering audio (44.1kHz)", 0f))
        val fullPcm = mixdownRenderer.render(notes, tracks, tempoBpm) { f ->
            onProgress(Progress("Rendering audio (44.1kHz)", f * 0.35f))
        }
        if (fullPcm.isEmpty()) throw IllegalStateException("No audible notes to render")

        // Apply render range: trim PCM to [startSec, endSec]
        val fullAudioSec = fullPcm.size / 44100.0
        val rangeStartSec = if (settings.rangeFull) 0.0 else settings.rangeStartSec.toDouble().coerceIn(0.0, fullAudioSec)
        var rangeEndSec = if (settings.rangeFull || settings.rangeEndSec <= 0f) fullAudioSec
            else settings.rangeEndSec.toDouble().coerceIn(rangeStartSec, fullAudioSec)
        if (rangeEndSec <= rangeStartSec) rangeEndSec = fullAudioSec
        val startSample = (rangeStartSec * 44100).toInt().coerceIn(0, fullPcm.size)
        val endSample = (rangeEndSec * 44100).toInt().coerceIn(startSample, fullPcm.size)
        val pcm = fullPcm.sliceArray(startSample until endSample)
        if (pcm.isEmpty()) throw IllegalStateException("Render range is empty")

        val audioSec = pcm.size / 44100.0
        // Video timestamps start at 0; beat is offset by range start
        val startBeat = rangeStartSec * tempoBpm / 60.0
        val fps = settings.fps
        val width = settings.width
        val height = settings.height
        val totalFrames = max(1, (audioSec * fps).toInt())
        val frameDurationUs = 1_000_000L / fps

        // 2. Output via MediaStore (public Movies/NoteForge, visible in Gallery/Files)
        val safeName = songName.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(50).ifEmpty { "score" }
        val fileName = "${safeName}_render.mp4"
        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/NoteForge")
            put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(
            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ) ?: throw IllegalStateException("Could not create MediaStore entry")
        val pfd = context.contentResolver.openFileDescriptor(uri, "w")
            ?: throw IllegalStateException("Could not open output file")

        // 3. Video encoder
        val videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val videoBitrate = (width * height * fps * 0.15).toInt().coerceIn(2_000_000, 40_000_000)
        val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4)
        }
        // Use ByteBuffer input (not Surface) so we can set explicit presentation
        // timestamps. Surface+lockCanvas stamps with wall-clock time, which breaks
        // offline rendering (wrong duration, player crashes).
        videoFormat.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        )
        videoCodec.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        videoCodec.start()

        // 4. Audio encoder
        val audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        audioCodec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        audioCodec.start()

        // 5. Muxer (writes to the MediaStore file descriptor)
        val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoTrack = -1
        var audioTrack = -1
        var muxerStarted = false
        val bufferInfo = MediaCodec.BufferInfo()
        var audioOutputFormat: MediaFormat? = null
        var videoOutputFormat: MediaFormat? = null
        // Buffer audio samples until muxer starts (video format arrives later)
        data class BufferedSample(val data: ByteArray, val info: MediaCodec.BufferInfo)
        val audioBuffer = mutableListOf<BufferedSample>()

        fun ensureMuxerStarted() {
            if (muxerStarted) return
            val needVideo = settings.output != RenderPrefs.OUTPUT_AUDIO_ONLY
            val needAudio = settings.output != RenderPrefs.OUTPUT_VIDEO_ONLY
            val videoReady = !needVideo || videoTrack >= 0
            val audioReady = !needAudio || audioTrack >= 0
            if (videoReady && audioReady) {
                muxer.start()
                muxerStarted = true
                // Flush buffered audio samples (they were encoded before video)
                for (s in audioBuffer) {
                    val buf = java.nio.ByteBuffer.wrap(s.data)
                    muxer.writeSampleData(audioTrack, buf, s.info)
                }
                audioBuffer.clear()
            }
        }

        try {
            try {
                // --- Audio FIRST (buffer samples until muxer starts) ---
                val includeAudio = settings.output != RenderPrefs.OUTPUT_VIDEO_ONLY
                if (includeAudio) {
                    onProgress(Progress("Encoding audio", 0.05f))
                    encodePcmToAac(audioCodec, muxer, bufferInfo, pcm,
                        onFormatChanged = { fmt ->
                            audioOutputFormat = fmt
                            audioTrack = muxer.addTrack(fmt)
                            ensureMuxerStarted()
                        },
                        onSample = { buf, info ->
                            if (info.size > 0 &&
                                info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                // Buffer until muxer starts (video track not ready yet)
                                val data = ByteArray(info.size)
                                buf.get(data)
                                // Copy BufferInfo (it's reused by codec)
                                val infoCopy = MediaCodec.BufferInfo()
                                infoCopy.set(info.offset, info.size, info.presentationTimeUs, info.flags)
                                audioBuffer.add(BufferedSample(data, infoCopy))
                            }
                        },
                        onProgress = { f -> onProgress(Progress("Encoding audio", 0.05f + 0.25f * f)) })
                }

                val layout = ScoreLayout(
                    context, notes, tracks, tempoBpm, audioSec, width, height, settings.style,
                    measureBeats = com.robert.noteforge.audio.RenderRequest.measureBeats,
                    timeSigNum = com.robert.noteforge.audio.RenderRequest.timeSigNum,
                    timeSigDen = com.robert.noteforge.audio.RenderRequest.timeSigDen,
                    instrumentNames = com.robert.noteforge.audio.RenderRequest.instrumentNames
                )
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)

                // --- Video frames (ByteBuffer input with explicit timestamps) ---
                val frameBitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                val frameCanvas = android.graphics.Canvas(frameBitmap)
                val yuvBuffer = java.nio.ByteBuffer.allocateDirect(width * height * 3 / 2)
                val argbPixels = IntArray(width * height)

                // Most Android encoders use NV12 (semi-planar) for
                // COLOR_FormatYUV420Flexible. Default to semi-planar; the
                // outputFormat query below confirms it when available.
                var isSemiPlanar = true
                try {
                    val cf = videoCodec.outputFormat.getInteger(MediaFormat.KEY_COLOR_FORMAT)
                    isSemiPlanar = cf != MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                } catch (_: Exception) {
                    // outputFormat not ready yet; stick with NV12 default
                }

                for (frame in 0 until totalFrames) {
                    val ptsUs = frame * frameDurationUs
                    val timeSec = frame / fps.toDouble()
                    val beat = startBeat + timeSec * tempoBpm / 60.0

                    layout.drawFrame(frameCanvas, paint, beat, timeSec)
                    frameBitmap.getPixels(argbPixels, 0, width, 0, 0, width, height)
                    argbToYuv420(argbPixels, yuvBuffer, width, height, isSemiPlanar)
                    // argbToYuv420 uses absolute puts (position stays 0), so use
                    // rewind() not flip() — flip() would set limit=0 and copy nothing.
                    yuvBuffer.rewind()

                    val inIdx = videoCodec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = videoCodec.getInputBuffer(inIdx)!!
                        inBuf.clear()
                        inBuf.put(yuvBuffer)
                        videoCodec.queueInputBuffer(inIdx, 0, width * height * 3 / 2, ptsUs, 0)
                    }
                    yuvBuffer.clear()
                    // clear() resets position=0, limit=capacity — ready for next frame's absolute puts

                    drainCodec(videoCodec, bufferInfo, ptsUs, endOfStream = false,
                        onFormatChanged = { fmt ->
                            videoOutputFormat = fmt
                            videoTrack = muxer.addTrack(fmt)
                            ensureMuxerStarted()
                        },
                        onSample = { buf, info ->
                            if (muxerStarted && info.size > 0) {
                                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                    muxer.writeSampleData(videoTrack, buf, info)
                                }
                            }
                        })

                    onProgress(Progress("Encoding video", 0.30f + 0.65f * frame / totalFrames))
                    if (frame % 10 == 0) kotlinx.coroutines.yield()
                }
                val eosIdx = videoCodec.dequeueInputBuffer(10_000)
                if (eosIdx >= 0) {
                    videoCodec.queueInputBuffer(eosIdx, 0, 0, totalFrames * frameDurationUs,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
                drainCodec(videoCodec, bufferInfo, totalFrames * frameDurationUs, endOfStream = true,
                    onFormatChanged = { fmt -> if (videoTrack < 0) { videoTrack = muxer.addTrack(fmt); ensureMuxerStarted() } },
                    onSample = { buf, info ->
                        if (muxerStarted && info.size > 0 &&
                            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            muxer.writeSampleData(videoTrack, buf, info)
                        }
                    })
                frameBitmap.recycle()

                onProgress(Progress("Finalizing", 0.98f))
            } finally {
                try { videoCodec.stop() } catch (_: Exception) {}
                try { videoCodec.release() } catch (_: Exception) {}
                try { audioCodec.stop() } catch (_: Exception) {}
                try { audioCodec.release() } catch (_: Exception) {}
                // (inputSurface removed — ByteBuffer input now)
                try { if (muxerStarted) muxer.stop() } catch (_: Exception) {}
                try { muxer.release() } catch (_: Exception) {}
                try { pfd.close() } catch (_: Exception) {}
            }
            // Success: publish the file so Gallery and players can see it
            context.contentResolver.update(uri, android.content.ContentValues().apply {
                put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
            }, null, null)
        } catch (e: Exception) {
            // Cancelled or failed: remove the partial file so a broken
            // entry never lingers in Movies/NoteForge.
            try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
            throw e
        }

        onProgress(Progress("Done", 1f))
        try { pfd.close() } catch (_: Exception) {}
        return uri
    }

    private fun argbToYuv420(
        argb: IntArray,
        out: java.nio.ByteBuffer,
        width: Int,
        height: Int,
        semiPlanar: Boolean
    ) {
        val ySize = width * height
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = argb[y * width + x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val yVal = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                out.put(y * width + x, yVal.coerceIn(0, 255).toByte())
            }
        }
        if (semiPlanar) {
            var uvIdx = ySize
            for (y in 0 until height / 2) {
                for (x in 0 until width / 2) {
                    var rS = 0; var gS = 0; var bS = 0
                    for (dy in 0 until 2) for (dx in 0 until 2) {
                        val px = argb[(y * 2 + dy) * width + (x * 2 + dx)]
                        rS += (px shr 16) and 0xFF; gS += (px shr 8) and 0xFF; bS += px and 0xFF
                    }
                    val r = rS / 4; val g = gS / 4; val b = bS / 4
                    val uV = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val vV = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    out.put(uvIdx++, vV.coerceIn(0, 255).toByte())
                    out.put(uvIdx++, uV.coerceIn(0, 255).toByte())
                }
            }
        } else {
            var uIdx = ySize; var vIdx = ySize + ySize / 4
            for (y in 0 until height / 2) {
                for (x in 0 until width / 2) {
                    var rS = 0; var gS = 0; var bS = 0
                    for (dy in 0 until 2) for (dx in 0 until 2) {
                        val px = argb[(y * 2 + dy) * width + (x * 2 + dx)]
                        rS += (px shr 16) and 0xFF; gS += (px shr 8) and 0xFF; bS += px and 0xFF
                    }
                    val r = rS / 4; val g = gS / 4; val b = bS / 4
                    val uV = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val vV = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    out.put(uIdx++, uV.coerceIn(0, 255).toByte())
                    out.put(vIdx++, vV.coerceIn(0, 255).toByte())
                }
            }
        }
    }

    private fun drainCodec(
        codec: MediaCodec,
        info: MediaCodec.BufferInfo,
        ptsUs: Long,
        endOfStream: Boolean,
        onFormatChanged: (MediaFormat) -> Unit,
        onSample: (ByteBuffer, MediaCodec.BufferInfo) -> Unit
    ) {
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, 10_000)
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    onFormatChanged(codec.outputFormat)
                }
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                }
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)
                    if (buf == null) {
                        codec.releaseOutputBuffer(idx, false)
                    } else {
                        // Adjust PTS for video (audio sets its own)
                        onSample(buf, info)
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
            if (!endOfStream && idx == MediaCodec.INFO_TRY_AGAIN_LATER) return
        }
    }

    private suspend fun encodePcmToAac(
        codec: MediaCodec,
        muxer: MediaMuxer,
        info: MediaCodec.BufferInfo,
        pcm: ShortArray,
        onFormatChanged: (MediaFormat) -> Unit,
        onSample: (ByteBuffer, MediaCodec.BufferInfo) -> Unit,
        onProgress: (Float) -> Unit
    ) {
        val bytesPerInput = 2048 // AAC frame-ish chunk
        var offset = 0
        var ptsUs = 0L
        val bytesPerSec = 44100 * 2 // mono 16-bit

        while (offset < pcm.size) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val buf = codec.getInputBuffer(idx)!!
                buf.clear()
                val chunk = minOf(bytesPerInput / 2, pcm.size - offset)
                for (i in 0 until chunk) {
                    buf.putShort(pcm[offset + i])
                }
                offset += chunk
                val chunkUs = (chunk * 1_000_000L) / 44100
                val flags = if (offset >= pcm.size) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                codec.queueInputBuffer(idx, 0, chunk * 2, ptsUs, flags)
                ptsUs += chunkUs
            }

            // Drain output
            while (true) {
                val outIdx = codec.dequeueOutputBuffer(info, 0)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormatChanged(codec.outputFormat)
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null) onSample(outBuf, info)
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }

            if (offset % 44100 == 0) {
                onProgress(offset / pcm.size.toFloat())
                kotlinx.coroutines.yield()
            }
        }

        // Drain remaining after EOS
        var done = false
        while (!done) {
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormatChanged(codec.outputFormat)
                outIdx >= 0 -> {
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null) onSample(outBuf, info)
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                }
                else -> done = true
            }
        }
    }

    // ---------- Score drawing ----------

    private class ScoreLayout(
        private val appContext: android.content.Context,
        private val notes: List<ScoreNote>,
        private val tracks: List<Track>,
        private val tempoBpm: Int,
        private val durationSec: Double,
        private val width: Int,
        private val height: Int,
        private val style: Int,
        private val measureBeats: Int = 4,
        private val timeSigNum: Int = 4,
        private val timeSigDen: Int = 4,
        private val instrumentNames: Map<Long, String> = emptyMap()
    ) {
        // Bravura music font (cached, loaded from assets)
        private var bravuraTypeface: android.graphics.Typeface? = null
        private val totalBeats = durationSec * tempoBpm / 60.0
        private val notesByTrack = notes.groupBy { it.trackId }
        // Same measure count formula as the UI (ScoreScreen).
        private val measureCount: Int = run {
            val endBeat = notes.maxOfOrNull { it.startBeat + it.durationBeats } ?: 0.0
            maxOf(32, kotlin.math.ceil(endBeat / measureBeats).toInt() + 4)
        }
        // Page-turn scroll offset, persisted across frames like the UI's hScroll.
        private var pageX = 0f

        fun drawFrame(canvas: Canvas, paint: Paint, beat: Double, timeSec: Double) {
            val W = width.toFloat()
            val H = height.toFloat()
            if (style == RenderPrefs.STYLE_SCORE) {
                drawScoreFrame(canvas, paint, beat, timeSec, W, H)
            } else {
                drawPianoRollFrame(canvas, paint, beat, timeSec, W, H)
            }
        }

        private fun drawPianoRollFrame(canvas: Canvas, paint: Paint, beat: Double, timeSec: Double, W: Float, H: Float) {

            // Background
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#0D1117")
            canvas.drawRect(0f, 0f, W, H, paint)

            val leftMargin = 150f
            val rightMargin = 50f
            val topMargin = 90f
            val bottomMargin = 70f
            val scoreW = W - leftMargin - rightMargin
            val scoreH = H - topMargin - bottomMargin
            val pxPerBeat = (scoreW / totalBeats).toFloat()

            // Title
            paint.color = Color.WHITE
            paint.textSize = 36f
            paint.textAlign = Paint.Align.LEFT

            // Beat grid (every 4 beats)
            paint.textSize = 24f
            var b = 0.0
            while (b <= totalBeats) {
                val x = (leftMargin + b * pxPerBeat).toFloat()
                paint.color = Color.parseColor("#21262D")
                paint.strokeWidth = 1f
                canvas.drawLine(x, topMargin, x, topMargin + scoreH, paint)
                paint.color = Color.parseColor("#8B949E")
                canvas.drawText("${b.toInt()}", x - 12f, topMargin - 16f, paint)
                b += 4.0
            }

            // Track lanes
            val laneH = scoreH / max(tracks.size, 1)
            tracks.forEachIndexed { idx, track ->
                val top = topMargin + idx * laneH
                // Lane bg
                paint.color = if (idx % 2 == 0) Color.parseColor("#161B22") else Color.parseColor("#0D1117")
                canvas.drawRect(leftMargin, top, W - rightMargin, top + laneH, paint)
                // Label
                paint.color = Color.parseColor("#8B949E")
                paint.textSize = 26f
                canvas.drawText(track.name.take(14), 12f, top + laneH / 2 + 9f, paint)
            }

            // Notes: vertical position varies by pitch (true piano roll)
            val notesByTrack = notes.groupBy { it.trackId }
            tracks.forEachIndexed { idx, track ->
                val top = topMargin + idx * laneH
                val color = track.noteColorArgb ?: TrackColors[idx % TrackColors.size].toArgb()
                val trackNotes = notesByTrack[track.id] ?: emptyList()
                // Find pitch range for this track (with padding)
                val pitches = trackNotes.map { it.pitchMidi }
                val minPitch = (pitches.minOrNull() ?: 60) - 2
                val maxPitch = (pitches.maxOrNull() ?: 72) + 2
                val pitchRange = max(1, maxPitch - minPitch)
                // Note height: one semitone, with minimum for visibility
                val noteH = (laneH / pitchRange).coerceIn(8f, laneH * 0.9f)
                for (note in trackNotes) {
                    val x = (leftMargin + note.startBeat * pxPerBeat).toFloat()
                    val w = (note.durationBeats * pxPerBeat).toFloat().coerceAtLeast(4f)
                    // Higher pitch = higher on lane (toward top)
                    val pitchOffset = note.pitchMidi - minPitch
                    val yCenter = top + laneH - (pitchOffset.toFloat() / pitchRange) * laneH
                    val noteTop = (yCenter - noteH / 2).coerceIn(top + 2f, top + laneH - noteH - 2f)
                    val noteBottom = noteTop + noteH
                    paint.color = color
                    // Rounded rect positioned by pitch
                    canvas.drawRoundRect(x, noteTop, x + w, noteBottom, 4f, 4f, paint)
                    // Highlight if sounding (playhead within note)
                    if (beat >= note.startBeat && beat < note.startBeat + note.durationBeats) {
                        paint.color = Color.WHITE
                        paint.alpha = 90
                        canvas.drawRoundRect(x, noteTop, x + w, noteBottom, 4f, 4f, paint)
                        paint.alpha = 255
                    }
                }
            }

            // Playhead
            val px = (leftMargin + beat * pxPerBeat).toFloat()
            paint.color = Color.parseColor("#F85149")
            paint.strokeWidth = 5f
            canvas.drawLine(px, topMargin - 10f, px, topMargin + scoreH + 10f, paint)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(px, topMargin - 14f, 9f, paint)

            // Timecode
            paint.color = Color.WHITE
            paint.textSize = 34f
            paint.textAlign = Paint.Align.RIGHT
            val mins = (timeSec / 60).toInt()
            val secs = (timeSec % 60).toInt()
            canvas.drawText(String.format("%d:%02d", mins, secs), W - 30f, 52f, paint)
            paint.textAlign = Paint.Align.LEFT
        }

        /**
         * Score frame drawn with the exact same code as the on-screen UI
         * (shared ScoreDraw module): dark theme, Bravura clefs, time
         * signatures, rests, beamed notes, red playhead. Framing mirrors the
         * UI: beat width at zoom 1, page-turn follow during playback, uniform
         * zoom-out to fit all lanes vertically.
         */
        private fun drawScoreFrame(canvas: Canvas, paint: Paint, beat: Double, timeSec: Double, W: Float, H: Float) {
            if (bravuraTypeface == null) {
                try {
                    bravuraTypeface = android.graphics.Typeface.createFromAsset(
                        appContext.assets, "fonts/Bravura.otf"
                    )
                } catch (_: Exception) {}
            }
            val sc = AndroidScoreCanvas(canvas)
            val density = appContext.resources.displayMetrics.density
            fun dp(v: Float) = v * density

            // The UI zooms only the staff lanes (uniform, aspect preserved);
            // the time strip and track headers stay at fixed dp sizes. Mirror
            // that: fit all lanes vertically, otherwise zoom = 1.
            val stripH = 0f // Time strip removed from video to make room for lanes
            val headerH = dp(32f)
            val laneH1 = dp(200f)
            val zoom = ((H - stripH - tracks.size * headerH) / (tracks.size * laneH1))
                .coerceIn(0.05f, 1f)
            val laneH = laneH1 * zoom

            val geometry = StaffGeometry(
                beatWidthPx = dp(56f) * zoom,
                pxPerStep = dp(12f) / 2f * zoom,
                noteRadiusPx = dp(5f) * zoom,
                noteXOffsetPx = dp(12f + 68f) * zoom,
                hitRadiusPx = 0f,
                clefInsetPx = dp(68f) * zoom
            )
            val contentW = geometry.beatToX((measureBeats * measureCount).toDouble())

            // Page-turn follow, exactly like the UI: when the playhead leaves
            // the visible page, jump a full page ahead (parked near the left).
            val playX = geometry.beatToX(beat)
            if (playX >= pageX + W - 4f || playX < pageX) {
                pageX = maxOf(0f, playX - W * 0.12f)
            }

            // Dark UI background.
            sc.drawRect(Background, 0f, 0f, W, H, fill = true)

            // Time strip removed from video (Bobby 2026-10-14).

            // Sounding notes glow green, like the UI.
            val soundingIds = notes.filter {
                beat >= it.startBeat && beat < it.startBeat + it.durationBeats
            }.map { it.id }.toSet()

            // One lane per track: header + staff.
            var y = stripH
            tracks.forEachIndexed { idx, track ->
                val color = track.noteColorArgb?.let { ComposeColor(it) }
                    ?: TrackColors[idx % TrackColors.size]
                // Header: color dot + "track • instrument", like the UI.
                val dotR = dp(5f)
                val dotCx = dp(12f) + dotR
                val dotCy = y + headerH / 2f
                sc.drawCircle(color, dotCx, dotCy, dotR)
                val label = buildString {
                    append(track.name)
                    instrumentNames[track.instrumentId]?.let { append(" • ").append(it) }
                    when {
                        track.solo -> append(" (solo)")
                        track.muted -> append(" (muted)")
                    }
                }
                val textSize = dp(14f)
                sc.drawText(
                    label, dotCx + dotR + dp(8f), dotCy + textSize * 0.35f,
                    OnBackground, textSize, null, bold = false, center = false
                )
                y += headerH

                canvas.save()
                canvas.translate(-pageX, y)
                val clef = try { Clef.valueOf(track.clef) } catch (_: Exception) { Clef.TREBLE }
                sc.drawScoreLane(
                    ScoreLaneParams(
                        geometry = geometry,
                        laneWidthPx = contentW,
                        laneHeightPx = laneH,
                        notes = notesByTrack[track.id] ?: emptyList(),
                        color = color,
                        clef = clef,
                        timeSigNum = timeSigNum,
                        timeSigDen = timeSigDen,
                        measureBeats = measureBeats,
                        measureCount = measureCount,
                        music = bravuraTypeface,
                        ink = OnSurfaceMuted,
                        soundingNoteIds = soundingIds,
                        selectedNoteIds = emptySet()
                    )
                )
                canvas.restore()

                y += laneH
            }

            // Single playhead line spanning all lanes, like the UI overlay.
            if (beat > 0.0) {
                sc.drawScorePlayhead(playX - pageX, stripH, y, maxOf(1f, dp(1f)))
            }
        }
    }
}
