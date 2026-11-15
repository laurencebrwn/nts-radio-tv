package com.laurencebrwn.ntsradio.data

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/** Builds the [MediaItem]s that both the UI and the media session use. */
object MediaItems {

    const val EXTRA_DESCRIPTION = "nts.description"
    const val EXTRA_LOCATION = "nts.location"
    const val EXTRA_START_MS = "nts.start_ms"
    const val EXTRA_END_MS = "nts.end_ms"
    const val EXTRA_NEXT_TITLE = "nts.next_title"
    const val EXTRA_NEXT_START_MS = "nts.next_start_ms"
    const val EXTRA_MIXTAPE_ALIAS = "nts.mixtape_alias"

    private const val MIXTAPE_PREFIX = "mixtape:"
    private const val MIXTAPE_ARTIST = "NTS Mixtapes"
    private const val MIXTAPE_ALBUM = "Infinite Mixtapes"

    fun channelOf(item: MediaItem?): NtsChannel? = NtsChannel.fromId(item?.mediaId)

    fun mixtapeIdOf(item: MediaItem?): String? =
        item?.mediaId
            ?.takeIf { it.startsWith(MIXTAPE_PREFIX) }
            ?.removePrefix(MIXTAPE_PREFIX)

    fun forChannel(channel: NtsChannel, info: NowPlaying?): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(info?.showTitle?.takeIf { it.isNotBlank() } ?: channel.displayName)
            .setArtist(channel.displayName)
            .setAlbumTitle("NTS Radio")
            .setDescription(info?.description)
            .setArtworkUri(info?.artworkUrl?.let(Uri::parse))
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setExtras(
                Bundle().apply {
                    putString(EXTRA_DESCRIPTION, info?.description)
                    putString(EXTRA_LOCATION, info?.location)
                    putLong(EXTRA_START_MS, info?.startMs ?: 0L)
                    putLong(EXTRA_END_MS, info?.endMs ?: 0L)
                    putString(EXTRA_NEXT_TITLE, info?.nextTitle)
                    putLong(EXTRA_NEXT_START_MS, info?.nextStartMs ?: 0L)
                },
            )
            .build()

        return MediaItem.Builder()
            .setMediaId(channel.id)
            .setUri(channel.streamUrl)
            .setMediaMetadata(metadata)
            .build()
    }

    fun forMixtape(mixtape: Mixtape): MediaItem {
        val description = mixtape.description.ifBlank { mixtape.subtitle }
        val metadata = MediaMetadata.Builder()
            .setTitle(mixtape.title)
            .setArtist(MIXTAPE_ARTIST)
            .setAlbumTitle(MIXTAPE_ALBUM)
            .setDescription(description)
            .setArtworkUri(mixtape.artworkUrl?.let(Uri::parse))
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setExtras(
                Bundle().apply {
                    putString(EXTRA_DESCRIPTION, description)
                    putString(EXTRA_MIXTAPE_ALIAS, mixtape.alias)
                },
            )
            .build()

        return MediaItem.Builder()
            .setMediaId(MIXTAPE_PREFIX + mixtape.alias)
            .setUri(mixtape.streamUrl)
            .setMediaMetadata(metadata)
            .build()
    }
}
