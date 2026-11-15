package com.laurencebrwn.ntsradio.data

import android.os.Handler
import android.os.Looper
import android.text.Html
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Fetches the NTS live schedule (https://www.nts.live/api/v2/live) and keeps a
 * small in-memory cache for both channels.
 *
 * The same object is used by the playback service (for the media session
 * metadata) and by the activity (for the on-screen display), so we only poll the
 * API once per minute no matter what is open.
 */
object NtsRepository {

    private const val TAG = "NtsRepository"
    private const val API_URL = "https://www.nts.live/api/v2/live"
    private const val USER_AGENT = "NTSRadioTV/1.0 (Android TV)"
    private const val POLL_INTERVAL_SECONDS = 60L
    private const val CACHE_BUSTER_LOOKAHEAD_MS = 3 * 60_000L
    // A CDN edge can keep serving the pre-change copy for the full 15 minute
    // TTL, so keep bypassing it for a while after a show starts too.
    private const val CACHE_BUSTER_AFTER_START_MS = 16 * 60_000L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "nts-live-fetch").apply { isDaemon = true }
    }

    private val listeners = CopyOnWriteArraySet<(Map<NtsChannel, NowPlaying>) -> Unit>()

    @Volatile
    private var cache: Map<NtsChannel, NowPlaying> = emptyMap()

    private var pollFuture: ScheduledFuture<*>? = null
    private var boundaryFuture: ScheduledFuture<*>? = null
    private var catchUpFuture: ScheduledFuture<*>? = null
    private var catchUpAttempt = 0
    private var refreshing = false

    fun cached(channel: NtsChannel): NowPlaying? = cache[channel]

    fun addListener(listener: (Map<NtsChannel, NowPlaying>) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Map<NtsChannel, NowPlaying>) -> Unit) {
        listeners.remove(listener)
    }

    /** Starts a refresh loop. Safe to call more than once. */
    @Synchronized
    fun startPolling() {
        if (pollFuture != null) return
        pollFuture = executor.scheduleWithFixedDelay(
            { refresh() },
            0,
            POLL_INTERVAL_SECONDS,
            TimeUnit.SECONDS,
        )
    }

    @Synchronized
    fun stopPolling() {
        pollFuture?.cancel(false)
        pollFuture = null
        boundaryFuture?.cancel(false)
        boundaryFuture = null
        catchUpFuture?.cancel(false)
        catchUpFuture = null
    }

    /** Fetches immediately on a background thread (no-op if a fetch is in flight). */
    fun refresh() {
        synchronized(this) {
            if (refreshing) return
            refreshing = true
        }
        executor.execute {
            var sawStaleData = false
            try {
                val fresh = fetchLive()
                if (fresh.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    val merged = cache.toMutableMap()
                    var changed = false
                    var accepted = false
                    for ((channel, info) in fresh) {
                        if (!isAcceptable(channel, info, now)) {
                            // The API / CDN can briefly serve the show that just
                            // ended. Never roll the display back to it.
                            sawStaleData = true
                            Log.i(TAG, "Ignoring stale live data for ${channel.apiName}")
                            continue
                        }
                        accepted = true
                        if (merged[channel] != info) {
                            merged[channel] = info
                            changed = true
                        }
                    }
                    if (accepted) catchUpAttempt = 0
                    if (changed) {
                        cache = merged
                        mainHandler.post {
                            listeners.forEach { it(cache) }
                        }
                    }
                    if (accepted) scheduleBoundaryRefresh()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not refresh live data", e)
            } finally {
                synchronized(this) { refreshing = false }
                if (sawStaleData) scheduleCatchUp()
            }
        }
    }

    /**
     * Rejects a response that would move the display backwards. A show whose end
     * time has passed is only used when we have nothing cached at all, so a slow
     * CDN response cannot replace a newer show with the previous one.
     */
    private fun isAcceptable(channel: NtsChannel, incoming: NowPlaying, now: Long): Boolean =
        isLiveEntryAcceptable(incoming, cache[channel], now)

    /** Retries soon after a stale boundary response, with a gentle backoff. */
    @Synchronized
    private fun scheduleCatchUp() {
        if (catchUpFuture?.isDone == false) return
        val delaySeconds = when (catchUpAttempt) {
            0 -> 15L
            1 -> 30L
            2 -> 60L
            3 -> 120L
            else -> 300L
        }
        catchUpAttempt++
        Log.i(TAG, "Live data was stale, retrying in ${delaySeconds}s")
        catchUpFuture = executor.schedule({ refresh() }, delaySeconds, TimeUnit.SECONDS)
    }

    /** Refreshes shortly after the current show ends so the new one appears promptly. */
    @Synchronized
    private fun scheduleBoundaryRefresh() {
        boundaryFuture?.cancel(false)
        boundaryFuture = null
        val now = System.currentTimeMillis()
        val nextEnd = cache.values
            .map { it.endMs }
            .filter { it > now }
            .minOrNull()
            ?: return
        val delayMs = nextEnd - now + 10_000L
        boundaryFuture = executor.schedule({ refresh() }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun fetchLive(): Map<NtsChannel, NowPlaying> {
        // The API sits behind a CDN that caches responses for 15 minutes. Use a
        // cache-busting parameter when we have no data yet, or when a show is
        // about to end, so the schedule changes on time. The rest of the time we
        // happily use the CDN copy.
        val now = System.currentTimeMillis()
        val justChanged = cache.values.any {
            it.startMs > 0L && now - it.startMs < CACHE_BUSTER_AFTER_START_MS
        }
        val nearEnd = cache.values.any {
            it.endMs > 0L && now > it.endMs - CACHE_BUSTER_LOOKAHEAD_MS
        }
        val needsFreshCopy = cache.isEmpty() || justChanged || nearEnd
        // Include seconds so a request can never pick up a stale CDN object that
        // was cached earlier in the same minute during a show changeover.
        val url = if (needsFreshCopy) "$API_URL?t=${now / 1_000}" else API_URL

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("Unexpected HTTP ${connection.responseCode}")
            }
            val body = connection.inputStream.bufferedReader().use(BufferedReader::readText)
            return parseLive(JSONObject(body))
        } finally {
            connection.disconnect()
        }
    }

    private fun parseLive(root: JSONObject): Map<NtsChannel, NowPlaying> {
        val results = mutableMapOf<NtsChannel, NowPlaying>()
        val channels = root.optJSONArray("results") ?: return emptyMap()
        for (index in 0 until channels.length()) {
            val entry = channels.optJSONObject(index) ?: continue
            val channel = NtsChannel.fromApiName(optString(entry, "channel_name")) ?: continue
            val now = entry.optJSONObject("now") ?: continue
            val details = now.optJSONObject("embeds")?.optJSONObject("details")

            val title = decode(optString(details, "name"))
                ?.takeIf { it.isNotBlank() }
                ?: decode(optString(now, "broadcast_title"))
                ?: continue

            val media = details?.optJSONObject("media")
            val artwork = optString(media, "picture_large")
                ?: optString(media, "picture_medium_large")
                ?: optString(media, "picture_medium")

            val next = entry.optJSONObject("next")
            val nextTitle = decode(optString(next, "broadcast_title"))

            results[channel] = NowPlaying(
                channel = channel,
                showTitle = title,
                description = decode(optString(details, "description")).orEmpty(),
                location = optString(details, "location_long")?.takeIf { it.isNotBlank() }
                    ?: optString(details, "location_short")?.takeIf { it.isNotBlank() },
                artworkUrl = artwork,
                startMs = parseIsoTime(optString(now, "start_timestamp")),
                endMs = parseIsoTime(optString(now, "end_timestamp")),
                nextTitle = nextTitle?.takeIf { it.isNotBlank() },
                nextStartMs = parseIsoTime(optString(next, "start_timestamp")),
            )
        }
        return results
    }

    /** [JSONObject.optString] returns the literal "null" for JSON nulls. */
    private fun optString(obj: JSONObject?, key: String): String? {
        if (obj == null || obj.isNull(key)) return null
        val value = obj.optString(key)
        return value.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    }

    private fun parseIsoTime(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC")
            format.parse(value)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /** The API returns some HTML entities in titles / descriptions. */
    private fun decode(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString().trim()
    }
}
