package com.robert.noteforge.audio

import android.content.Context

/** Render settings for video export. */
object RenderPrefs {
    private const val FILE = "render_settings"
    private const val K_WIDTH = "width"
    private const val K_HEIGHT = "height"
    private const val K_FPS = "fps"
    private const val K_OUTPUT = "output"
    private const val K_STYLE = "style"
    private const val K_PRIORITY = "priority"
    private const val K_RANGE_FULL = "range_full"
    private const val K_RANGE_START = "range_start"
    private const val K_RANGE_END = "range_end"

    // Output types
    const val OUTPUT_VIDEO_AUDIO = 0
    const val OUTPUT_AUDIO_ONLY = 1
    const val OUTPUT_VIDEO_ONLY = 2

    // Video styles
    const val STYLE_PIANO_ROLL = 0
    const val STYLE_SCORE = 1

    // Priority levels (map to Process thread priorities)
    const val PRIORITY_LOW = 0      // THREAD_PRIORITY_BACKGROUND (10)
    const val PRIORITY_NORMAL = 1   // THREAD_PRIORITY_DEFAULT (0)
    const val PRIORITY_HIGH = 2     // THREAD_PRIORITY_FOREGROUND (-2)

    data class Settings(
        val width: Int = 1920,
        val height: Int = 1080,
        val fps: Int = 30,
        val output: Int = OUTPUT_VIDEO_AUDIO,
        val style: Int = STYLE_PIANO_ROLL,
        val priority: Int = PRIORITY_LOW,
        val rangeFull: Boolean = true,
        val rangeStartSec: Float = 0f,
        val rangeEndSec: Float = 0f // 0 = end of song
    )

    fun get(ctx: Context): Settings {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return Settings(
            width = p.getInt(K_WIDTH, 1920),
            height = p.getInt(K_HEIGHT, 1080),
            fps = p.getInt(K_FPS, 30),
            output = p.getInt(K_OUTPUT, OUTPUT_VIDEO_AUDIO),
            style = p.getInt(K_STYLE, STYLE_PIANO_ROLL),
            priority = p.getInt(K_PRIORITY, PRIORITY_LOW),
            rangeFull = p.getBoolean(K_RANGE_FULL, true),
            rangeStartSec = p.getFloat(K_RANGE_START, 0f),
            rangeEndSec = p.getFloat(K_RANGE_END, 0f)
        )
    }

    fun set(ctx: Context, s: Settings) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(K_WIDTH, s.width)
            .putInt(K_HEIGHT, s.height)
            .putInt(K_FPS, s.fps)
            .putInt(K_OUTPUT, s.output)
            .putInt(K_STYLE, s.style)
            .putInt(K_PRIORITY, s.priority)
            .putBoolean(K_RANGE_FULL, s.rangeFull)
            .putFloat(K_RANGE_START, s.rangeStartSec)
            .putFloat(K_RANGE_END, s.rangeEndSec)
            .apply()
    }

    fun threadPriority(level: Int): Int = when (level) {
        PRIORITY_HIGH -> android.os.Process.THREAD_PRIORITY_FOREGROUND
        // Never BACKGROUND: background-priority threads are starved of CPU
        // when the app loses focus, which froze renders in the background.
        else -> android.os.Process.THREAD_PRIORITY_DEFAULT
    }

    fun formatTime(sec: Float): String {
        val s = sec.toInt().coerceAtLeast(0)
        return "${s / 60}:${String.format("%02d", s % 60)}"
    }

    fun parseTime(text: String): Float? {
        // Accepts "M:SS", "MM:SS", or plain seconds
        val t = text.trim()
        if (t.contains(":")) {
            val parts = t.split(":")
            if (parts.size != 2) return null
            val m = parts[0].toIntOrNull() ?: return null
            val s = parts[1].toIntOrNull() ?: return null
            if (m < 0 || s < 0 || s >= 60) return null
            return (m * 60 + s).toFloat()
        }
        return t.toFloatOrNull()?.coerceAtLeast(0f)
    }

    fun resolutionLabel(w: Int, h: Int): String = when {
        w == 1280 && h == 720 -> "720p"
        w == 1920 && h == 1080 -> "1080p"
        w == 3840 && h == 2160 -> "4K"
        w == 720 && h == 1280 -> "720p portrait"
        w == 1080 && h == 1920 -> "1080p portrait"
        w == 1080 && h == 1080 -> "Square 1080"
        else -> "${w}x$h"
    }

    /** All available resolutions: (width, height, label) */
    val RESOLUTIONS = listOf(
        Triple(1280, 720, "720p"),
        Triple(1920, 1080, "1080p"),
        Triple(3840, 2160, "4K"),
        Triple(720, 1280, "720p portrait"),
        Triple(1080, 1920, "1080p portrait"),
        Triple(1080, 1080, "Square 1080")
    )
}
