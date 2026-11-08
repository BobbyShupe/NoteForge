package com.robert.noteforge.sync

import android.content.Context

/** Persists the sync-server address across app restarts. Blank = not set. */
object SyncPrefs {
    private const val FILE = "sync_prefs"
    private const val K_SERVER_URL = "server_url"

    fun loadUrl(ctx: Context): String {
        return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(K_SERVER_URL, "") ?: ""
    }

    fun saveUrl(ctx: Context, url: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(K_SERVER_URL, url.trim())
            .apply()
    }
}
