package com.laurencebrwn.ntsradio.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSchedulePolicyTest {

    private val now = 1_000_000L
    private val previous = show(title = "Previous", start = 0L, end = now - 60_000L)
    private val current = show(title = "Current", start = now - 60_000L, end = now + 3_600_000L)
    private val later = show(title = "Later", start = now + 3_600_000L, end = now + 7_200_000L)

    @Test
    fun `accepts an ended show when nothing is cached`() {
        assertTrue(isLiveEntryAcceptable(previous, existing = null, now = now))
    }

    @Test
    fun `rejects a stale response after a newer show is cached`() {
        assertFalse(isLiveEntryAcceptable(previous, existing = current, now = now))
    }

    @Test
    fun `accepts the show after the cached one has ended`() {
        assertTrue(isLiveEntryAcceptable(current, existing = previous, now = now))
    }

    @Test
    fun `accepts a later show even before the cached one ends`() {
        assertTrue(isLiveEntryAcceptable(later, existing = current, now = now))
    }

    @Test
    fun `accepts an equal end time for metadata corrections`() {
        val corrected = current.copy(description = "Updated description")
        assertTrue(isLiveEntryAcceptable(corrected, existing = current, now = now))
    }

    @Test
    fun `accepts entries without an end time`() {
        val noEnd = show(title = "Unknown", start = now, end = 0L)
        assertTrue(isLiveEntryAcceptable(noEnd, existing = current, now = now))
        assertTrue(isLiveEntryAcceptable(current, existing = noEnd, now = now))
    }

    private fun show(title: String, start: Long, end: Long) = NowPlaying(
        channel = NtsChannel.ONE,
        showTitle = title,
        description = "",
        location = null,
        artworkUrl = null,
        startMs = start,
        endMs = end,
        nextTitle = null,
        nextStartMs = null,
    )
}
