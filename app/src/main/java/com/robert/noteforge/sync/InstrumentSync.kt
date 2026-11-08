package com.robert.noteforge.sync

import com.robert.noteforge.data.Instrument
import com.robert.noteforge.data.InstrumentRepository
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Syncs synth instruments with the user's sync server (address set under Instruments; no auth). */
object InstrumentSync {
    /** Throws a clear error when no server address has been entered yet. */
    private fun requireBaseUrl(baseUrl: String): String {
        val url = baseUrl.trim().trimEnd('/')
        if (url.isBlank()) throw Exception("Sync server address not set — enter it under Instruments")
        return url
    }

    /** Uploads all instruments with a syncUid. Returns count stored by server. */
    fun upload(instruments: List<Instrument>, baseUrl: String): Int {
        val arr = JSONArray()
        for (inst in instruments) {
            if (inst.syncUid.isBlank()) continue
            arr.put(JSONObject().apply {
                put("uid", inst.syncUid)
                put("name", inst.name)
                put("updatedAt", inst.updatedAt)
                put("rootNoteMidi", inst.rootNoteMidi)
                put("gain", inst.gain.toDouble())
                put("colorHex", inst.colorHex)
                if (inst.synthPatchJson != null) {
                    put("patch", JSONObject(inst.synthPatchJson))
                } else {
                    put("patch", JSONObject.NULL)
                }
            })
        }
        val base = requireBaseUrl(baseUrl)
        val (code, body) = post("$base/api/instruments", arr.toString())
        if (code != 200) throw Exception("upload failed: HTTP $code")
        return JSONObject(body).optInt("stored", 0)
    }

    /** Downloads all instruments from server, merging by syncUid (newer wins). Returns count applied. */
    suspend fun download(
        repo: InstrumentRepository,
        local: List<Instrument>,
        baseUrl: String
    ): Int {
        val base = requireBaseUrl(baseUrl)
        val (code, body) = get("$base/api/instruments")
        if (code != 200) throw Exception("download failed: HTTP $code")
        val arr = JSONArray(body)
        val localByUid = local.associateBy { it.syncUid }
        var n = 0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val uid = o.optString("uid", "")
            if (uid.isBlank()) continue
            val updatedAt = o.optLong("updatedAt", 0)
            val existing = localByUid[uid]
            if (existing != null && existing.updatedAt >= updatedAt) continue
            val patchJson = if (o.isNull("patch")) null else o.getJSONObject("patch").toString()
            val inst = Instrument(
                id = existing?.id ?: 0,
                name = o.optString("name", "Synth"),
                audioFileId = 0,
                rootNoteMidi = o.optInt("rootNoteMidi", 60),
                gain = o.optDouble("gain", 1.0).toFloat(),
                colorHex = o.optString("colorHex", "#66FCF1"),
                synthPatchJson = patchJson,
                syncUid = uid,
                updatedAt = updatedAt
            )
            if (existing != null) repo.update(inst) else repo.insert(inst)
            n++
        }
        return n
    }

    fun status(baseUrl: String): Int {
        val base = requireBaseUrl(baseUrl)
        val (code, body) = get("$base/api/status")
        if (code != 200) throw Exception("status failed: HTTP $code")
        return JSONObject(body).optInt("count", 0)
    }

    private fun post(urlStr: String, body: String): Pair<Int, String> {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 15000
            readTimeout = 30000
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val resp = try {
            conn.inputStream.bufferedReader().readText()
        } catch (_: Exception) {
            conn.errorStream?.bufferedReader()?.readText() ?: ""
        }
        conn.disconnect()
        return code to resp
    }

    private fun get(urlStr: String): Pair<Int, String> {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 30000
        }
        val code = conn.responseCode
        val resp = try {
            conn.inputStream.bufferedReader().readText()
        } catch (_: Exception) {
            conn.errorStream?.bufferedReader()?.readText() ?: ""
        }
        conn.disconnect()
        return code to resp
    }
}
