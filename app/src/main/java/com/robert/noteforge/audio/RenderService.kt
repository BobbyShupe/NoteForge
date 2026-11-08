package com.robert.noteforge.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.robert.noteforge.R
import com.robert.noteforge.data.ScoreNote
import com.robert.noteforge.data.Track
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service for video rendering. Keeps the render running
 * when the screen is off. Shows a progress notification.
 */
@AndroidEntryPoint
class RenderService : Service() {

    @Inject lateinit var videoExporter: VideoExporter

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val binder = RenderBinder()
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotifTime = 0L
    private var renderStartElapsed = 0L

    companion object {
        const val CHANNEL_ID = "render_channel"
        const val NOTIF_ID = 1001
        const val ACTION_START = "com.robert.noteforge.action.RENDER_START"
        const val ACTION_CANCEL = "com.robert.noteforge.action.RENDER_CANCEL"
        const val EXTRA_NOTES = "notes"
        const val EXTRA_TRACKS = "tracks"
        const val EXTRA_TEMPO = "tempo"
        const val EXTRA_SONG_NAME = "song_name"

        // Shared state (service runs in same process)
        private val _progress = MutableStateFlow<VideoExporter.Progress?>(null)
        val progress: StateFlow<VideoExporter.Progress?> = _progress.asStateFlow()
        private val _resultUri = MutableStateFlow<android.net.Uri?>(null)
        val resultUri: StateFlow<android.net.Uri?> = _resultUri.asStateFlow()
        private val _error = MutableStateFlow<String?>(null)
        val error: StateFlow<String?> = _error.asStateFlow()
        @Volatile var isRendering = false
            private set

        /** Clears the result/error state (called when user dismisses the dialog). */
        fun clearResult() {
            _resultUri.value = null
            _error.value = null
            _progress.value = null
        }
    }

    inner class RenderBinder : Binder() {
        fun getService(): RenderService = this@RenderService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createChannel()
        cleanStalePending()
    }

    /** Deletes leftover in-progress entries from renders that died mid-way
     *  (a process kill leaves IS_PENDING=1 rows whose files can never play). */
    private fun cleanStalePending() {
        try {
            val stale = mutableListOf<android.net.Uri>()
            contentResolver.query(
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(android.provider.MediaStore.Video.Media._ID),
                android.provider.MediaStore.Video.Media.RELATIVE_PATH + " LIKE ? AND " +
                    android.provider.MediaStore.Video.Media.IS_PENDING + "=1",
                arrayOf("Movies/NoteForge%"),
                null
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media._ID)
                while (c.moveToNext()) {
                    stale += android.content.ContentUris.withAppendedId(
                        android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        c.getLong(idCol))
                }
            }
            for (u in stale) {
                try { contentResolver.delete(u, null, null) } catch (_: Exception) {}
            }
        } catch (_: Exception) { }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // Notes/tracks are passed via static holders (Parcelable would be heavy)
                val notes = RenderRequest.notes
                val tracks = RenderRequest.tracks
                if (notes != null && tracks != null && !isRendering) {
                    startRender(
                        notes,
                        tracks,
                        intent.getIntExtra(EXTRA_TEMPO, 120),
                        intent.getStringExtra(EXTRA_SONG_NAME) ?: "score"
                    )
                } else {
                    stopSelf()
                }
            }
            ACTION_CANCEL -> {
                isRendering = false
                _progress.value = null
                _resultUri.value = null
                _error.value = "Cancelled"
                serviceScope.coroutineContext.cancel()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startRender(
        notes: List<ScoreNote>,
        tracks: List<Track>,
        tempoBpm: Int,
        songName: String
    ) {
        isRendering = true
        _progress.value = VideoExporter.Progress("Starting", 0f)
        _resultUri.value = null
        _error.value = null

        val settings = RenderPrefs.get(this)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NoteForge:render").apply {
            acquire(30 * 60 * 1000L)
        }

        lastNotifTime = 0L
        renderStartElapsed = android.os.SystemClock.elapsedRealtime()
        startForeground(NOTIF_ID, buildNotification("Starting render", 0))

        serviceScope.launch {
            android.os.Process.setThreadPriority(RenderPrefs.threadPriority(settings.priority))
            try {
                val uri = videoExporter.export(notes, tracks, tempoBpm, songName, settings) { p ->
                    val elapsed = android.os.SystemClock.elapsedRealtime() - renderStartElapsed
                    val eta = if (p.fraction > 0.001f)
                        ((elapsed * (1 - p.fraction) / p.fraction) / 1000).toLong()
                    else null
                    val withEta = p.copy(etaSec = eta)
                    _progress.value = withEta
                    updateProgressNotification(withEta)
                }
                _resultUri.value = uri
                updateNotification("Render complete", 100)
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    _error.value = e.message ?: "Render failed"
                    updateNotification("Render failed", 0)
                }
            } finally {
                _progress.value = null
                isRendering = false
                try { wakeLock?.release() } catch (_: Exception) { }
                wakeLock = null
                try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT) } catch (_: Exception) { }
                // Keep service alive briefly so UI can read the result, then stop
                kotlinx.coroutines.delay(2000)
                stopSelf()
            }
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Video Render",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows video render progress"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, percent: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NoteForge Render")
            .setContentText("$text — $percent%")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setProgress(100, percent, false)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String, percent: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text, percent))
    }

    /** Progress notification with decimal percent + ETA, throttled to 2/sec. */
    private fun updateProgressNotification(p: VideoExporter.Progress) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (p.fraction < 1f && now - lastNotifTime < 500) return
        lastNotifTime = now
        val pct = "%.1f%%".format(java.util.Locale.US, p.fraction * 100)
        val eta = p.etaSec?.let { " \u00b7 ETA ${formatEta(it)}" } ?: ""
        updateNotification("${p.phase} \u2014 $pct$eta", (p.fraction * 100).toInt())
    }

    private fun formatEta(sec: Long): String {
        val m = sec / 60
        val s = sec % 60
        return "%d:%02d".format(java.util.Locale.US, m, s)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        isRendering = false
        try { wakeLock?.release() } catch (_: Exception) { }
        wakeLock = null
        super.onDestroy()
    }
}

/** Holds the render request data (avoids Parcelable overhead for large note lists). */
object RenderRequest {
    var notes: List<ScoreNote>? = null
    var tracks: List<Track>? = null
    var measureBeats: Int = 4
    var timeSigNum: Int = 4
    var timeSigDen: Int = 4
    var instrumentNames: Map<Long, String> = emptyMap()
}
