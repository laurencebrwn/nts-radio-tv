package com.laurencebrwn.ntsradio.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.DefaultMediaNotificationProvider
import com.laurencebrwn.ntsradio.MainActivity
import com.laurencebrwn.ntsradio.R
import com.laurencebrwn.ntsradio.data.MediaItems
import com.laurencebrwn.ntsradio.data.NtsChannel
import com.laurencebrwn.ntsradio.data.NtsRepository
import com.laurencebrwn.ntsradio.data.NowPlaying

/**
 * Holds the ExoPlayer instance for the whole process.
 *
 * Running playback in a service (rather than in the activity) is what makes the
 * audio keep going when the TV app is closed, and gives other apps / the system
 * a media session to show in "now playing".
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private var mediaSession: MediaSession? = null
    private var currentChannel: NtsChannel? = null

    private val handler = Handler(Looper.getMainLooper())
    private var retryAttempt = 0
    private var retryRunnable: Runnable? = null

    private val liveInfoListener: (Map<NtsChannel, NowPlaying>) -> Unit = { info ->
        onLiveInfo(info)
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val channel = MediaItems.channelOf(mediaItem)
            // A mixtape is not a live channel: clear the tracked channel so the
            // live schedule poller cannot overwrite the mixtape metadata.
            currentChannel = channel
            if (channel != null) {
                prefs.edit().putString(KEY_LAST_CHANNEL, channel.id).apply()
                // If an external controller started playback, fill in the metadata
                // as soon as we have it.
                NtsRepository.cached(channel)?.let { applyLiveInfo(channel, it) }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                retryAttempt = 0
                handler.removeCallbacksAndMessages(null)
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            prefs.edit().putBoolean(KEY_AUTOPLAY, playWhenReady).apply()
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "Playback error: ${error.errorCodeName}", error)
            scheduleRetry()
        }
    }

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        currentChannel = NtsChannel.fromId(prefs.getString(KEY_LAST_CHANNEL, null))

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpDataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .apply { addListener(playerListener) }

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .setBitmapLoader(DataSourceBitmapLoader.Builder(this).build())
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelName(R.string.notification_channel_name)
                .build()
                .apply { setSmallIcon(R.drawable.ic_stat_nts) },
        )

        NtsRepository.addListener(liveInfoListener)
        NtsRepository.startPolling()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    /**
     * Keep playing after the user swipes the app away from the recents screen.
     * The default implementation already does this, but being explicit makes the
     * "radio keeps playing when the app is quit" behaviour obvious.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.isPlaying) {
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        NtsRepository.removeListener(liveInfoListener)
        NtsRepository.stopPolling()
        handler.removeCallbacksAndMessages(null)
        mediaSession?.release()
        mediaSession = null
        player.release()
        super.onDestroy()
    }

    private fun onLiveInfo(info: Map<NtsChannel, NowPlaying>) {
        val channel = currentChannel ?: return
        val now = info[channel] ?: return
        applyLiveInfo(channel, now)
    }

    /**
     * Updates the media session / notification metadata. ExoPlayer can apply a
     * metadata-only [MediaItem] change in place, so this does not interrupt the
     * audio stream.
     */
    private fun applyLiveInfo(channel: NtsChannel, info: NowPlaying) {
        if (currentChannel != channel) return
        val item = player.currentMediaItem ?: return
        if (MediaItems.channelOf(item) != channel) return

        val metadata = item.mediaMetadata
        val extras = metadata.extras
        val sameShow = metadata.title?.toString() == info.showTitle
        val sameArtwork = metadata.artworkUri?.toString() == info.artworkUrl
        val sameDescription = metadata.description?.toString() == info.description
        val sameTimes = extras?.getLong(MediaItems.EXTRA_START_MS) == info.startMs &&
            extras.getLong(MediaItems.EXTRA_END_MS) == info.endMs &&
            extras.getString(MediaItems.EXTRA_NEXT_TITLE) == info.nextTitle
        if (sameShow && sameArtwork && sameDescription && sameTimes) return

        val index = player.currentMediaItemIndex
        if (index == C.INDEX_UNSET) return
        player.replaceMediaItem(index, MediaItems.forChannel(channel, info))
    }

    private fun scheduleRetry() {
        if (retryRunnable != null) return
        val delayMs = when (retryAttempt) {
            0 -> 2_000L
            1 -> 5_000L
            2 -> 10_000L
            else -> 30_000L
        }
        retryAttempt++
        val runnable = Runnable {
            retryRunnable = null
            if (player.playWhenReady && player.currentMediaItem != null) {
                Log.i(TAG, "Retrying live stream after error")
                player.prepare()
                player.play()
            }
        }
        retryRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private companion object {
        const val TAG = "PlaybackService"
        const val PREFS = "nts_radio"
        const val KEY_LAST_CHANNEL = "last_channel"
        const val KEY_AUTOPLAY = "autoplay"
        const val USER_AGENT = "NTSRadioTV/1.0 (Android TV)"
    }
}
