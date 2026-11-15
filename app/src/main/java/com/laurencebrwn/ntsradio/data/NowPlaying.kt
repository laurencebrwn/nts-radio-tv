package com.laurencebrwn.ntsradio.data

/** Everything the UI needs to know about what is currently on air. */
data class NowPlaying(
    val channel: NtsChannel,
    val showTitle: String,
    val description: String,
    val location: String?,
    val artworkUrl: String?,
    val startMs: Long,
    val endMs: Long,
    val nextTitle: String?,
    val nextStartMs: Long?,
)
