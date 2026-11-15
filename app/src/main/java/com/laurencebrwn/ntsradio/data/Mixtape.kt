package com.laurencebrwn.ntsradio.data

/**
 * One of NTS' Infinite Mixtapes: a music-only themed stream.
 *
 * The list comes from https://www.nts.live/api/v2/mixtapes. Each mixtape has
 * its own endless relay plus square artwork, so it can be played exactly like a
 * live channel and shown in the system media session.
 */
data class Mixtape(
    val alias: String,
    val title: String,
    val subtitle: String,
    val description: String,
    val streamUrl: String,
    val artworkUrl: String?,
)
