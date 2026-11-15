package com.laurencebrwn.ntsradio.data

/**
 * Decides whether a freshly fetched show may replace what is currently cached.
 *
 * The NTS API sits behind a CDN with a 15 minute TTL, so around a show change it
 * can briefly return the show that has just ended. Accepting that response would
 * make the display jump back to the previous show, so ended entries are ignored
 * unless there is no cached data at all.
 */
internal fun isLiveEntryAcceptable(
    incoming: NowPlaying,
    existing: NowPlaying?,
    now: Long,
): Boolean {
    // Nothing to compare against: showing the last known show beats an empty UI.
    if (existing == null) return true
    if (incoming.endMs <= 0L || existing.endMs <= 0L) return true
    if (incoming.endMs <= now) return false
    return incoming.endMs >= existing.endMs
}
