package com.laurencebrwn.ntsradio.data

import android.os.Handler
import android.os.Looper
import android.text.Html
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * Fetches NTS' Infinite Mixtapes (https://www.nts.live/infinite-mixtapes) and
 * keeps them in memory for the mixtapes grid.
 *
 * The list changes very rarely, so it is fetched once per process and only
 * retried when a fetch failed or when a listener asks for it again.
 */
object MixtapeRepository {

    private const val TAG = "MixtapeRepository"
    private const val API_URL = "https://www.nts.live/api/v2/mixtapes"
    private const val USER_AGENT = "NTSRadioTV/1.0 (Android TV)"
    private const val RETRY_GUARD_MS = 10_000L

    private val listeners = CopyOnWriteArraySet<(List<Mixtape>) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nts-mixtapes-fetch").apply { isDaemon = true }
    }

    @Volatile
    private var cache: List<Mixtape> = emptyList()

    @Volatile
    private var loading = false

    @Volatile
    private var lastAttemptMs = 0L

    fun cached(): List<Mixtape> = cache

    fun isLoading(): Boolean = loading

    fun addListener(listener: (List<Mixtape>) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (List<Mixtape>) -> Unit) {
        listeners.remove(listener)
    }

    /** Loads the list if it is not cached yet. Safe to call on every navigation. */
    fun ensureLoaded() {
        if (cache.isNotEmpty() || loading) return
        if (System.currentTimeMillis() - lastAttemptMs < RETRY_GUARD_MS) return
        refresh()
    }

    fun refresh() {
        synchronized(this) {
            if (loading) return
            loading = true
            lastAttemptMs = System.currentTimeMillis()
        }
        executor.execute {
            try {
                val mixtapes = fetchMixtapes()
                if (mixtapes.isNotEmpty()) {
                    cache = mixtapes
                    mainHandler.post {
                        listeners.forEach { it(mixtapes) }
                    }
                } else {
                    Log.w(TAG, "Mixtape list was empty")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not load mixtapes", e)
            } finally {
                loading = false
                mainHandler.post {
                    // Lets the UI swap "loading" for the retry message.
                    listeners.forEach { it(cache) }
                }
            }
        }
    }

    private fun fetchMixtapes(): List<Mixtape> {
        val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("Unexpected HTTP ${connection.responseCode}")
            }
            val body = connection.inputStream.bufferedReader().use(BufferedReader::readText)
            return parseMixtapes(JSONObject(body))
        } finally {
            connection.disconnect()
        }
    }

    private fun parseMixtapes(root: JSONObject): List<Mixtape> {
        val results = root.optJSONArray("results") ?: return emptyList()
        val mixtapes = mutableListOf<Mixtape>()
        for (index in 0 until results.length()) {
            val entry = results.optJSONObject(index) ?: continue
            val alias = optString(entry, "mixtape_alias") ?: continue
            val title = decode(optString(entry, "title")) ?: continue
            val streamUrl = optString(entry, "audio_stream_endpoint") ?: continue
            val media = entry.optJSONObject("media")
            val artwork = optString(media, "picture_medium_large")
                ?: optString(media, "picture_large")
                ?: optString(media, "picture_medium")
                ?: optString(media, "picture_thumb")
            mixtapes += Mixtape(
                alias = alias,
                title = title,
                subtitle = decode(optString(entry, "subtitle")).orEmpty(),
                description = decode(optString(entry, "description")).orEmpty(),
                streamUrl = streamUrl,
                artworkUrl = artwork,
            )
        }
        return mixtapes
    }

    /** [JSONObject.optString] returns the literal "null" for JSON nulls. */
    private fun optString(obj: JSONObject?, key: String): String? {
        if (obj == null || obj.isNull(key)) return null
        val value = obj.optString(key)
        return value.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    }

    /** The API returns some HTML entities in titles / descriptions. */
    private fun decode(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString().trim()
    }
}
