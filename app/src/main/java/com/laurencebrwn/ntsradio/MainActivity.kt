package com.laurencebrwn.ntsradio

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.laurencebrwn.ntsradio.data.MediaItems
import com.laurencebrwn.ntsradio.data.Mixtape
import com.laurencebrwn.ntsradio.data.MixtapeRepository
import com.laurencebrwn.ntsradio.data.NtsChannel
import com.laurencebrwn.ntsradio.data.NtsRepository
import com.laurencebrwn.ntsradio.data.NowPlaying
import com.laurencebrwn.ntsradio.playback.PlaybackService
import com.laurencebrwn.ntsradio.ui.ArtworkLoader
import com.laurencebrwn.ntsradio.ui.TimelineView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Single-screen TV UI: live radio and the Infinite Mixtapes, switched from the
 * top bar. All playback state lives in [PlaybackService] so it survives this
 * activity.
 */
class MainActivity : Activity() {

    private lateinit var backdrop: ImageView
    private lateinit var artwork: ImageView
    private lateinit var channelBadge: TextView
    private lateinit var showTitle: TextView
    private lateinit var showMeta: TextView
    private lateinit var description: TextView
    private lateinit var nextShow: TextView
    private lateinit var timeline: TimelineView
    private lateinit var startTime: TextView
    private lateinit var endTime: TextView
    private lateinit var clock: TextView
    private lateinit var dateLabel: TextView
    private lateinit var channelOneTile: View
    private lateinit var channelTwoTile: View
    private lateinit var tabLive: View
    private lateinit var tabMixtapes: View
    private lateinit var tabLiveIndicator: View
    private lateinit var tabMixtapesIndicator: View
    private lateinit var liveSection: View
    private lateinit var mixtapesSection: View
    private lateinit var mixtapeGrid: LinearLayout
    private lateinit var mixtapesStatus: TextView
    private lateinit var mixtapesError: TextView

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var selectedChannel = NtsChannel.ONE
    private var currentInfo: NowPlaying? = null
    private var hasPlaybackError = false
    private var section = Section.LIVE
    private var selectedMixtapeId: String? = null

    private var renderedMixtapes: List<Mixtape> = emptyList()
    private val mixtapeTiles = linkedMapOf<String, MixtapeTile>()
    private var backCallback: OnBackInvokedCallback? = null

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            clock.text = timeFormat.format(Date())
            dateLabel.text = dayFormat.format(Date()).uppercase(Locale.getDefault())
            timeline.invalidate()
            handler.postDelayed(this, 15_000L)
        }
    }

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dayFormat = SimpleDateFormat("EEEE d MMMM", Locale.getDefault())

    private val ntsFont: Typeface? by lazy {
        ResourcesCompat.getFont(this, R.font.univers_light_condensed)
    }

    private val repositoryListener: (Map<NtsChannel, NowPlaying>) -> Unit = { info ->
        runOnUiThread {
            currentInfo = info[selectedChannel]
            render()
        }
    }

    private val mixtapeListener: (List<Mixtape>) -> Unit = { mixtapes ->
        runOnUiThread {
            if (mixtapes != renderedMixtapes) {
                rebuildMixtapeGrid(mixtapes)
            } else {
                render()
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            render()
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            render()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val channel = MediaItems.channelOf(mediaItem)
            val mixtapeId = MediaItems.mixtapeIdOf(mediaItem)
            when {
                channel != null -> {
                    selectedChannel = channel
                    currentInfo = NtsRepository.cached(channel)
                    switchSection(Section.LIVE, moveFocus = false)
                }

                mixtapeId != null -> {
                    selectedMixtapeId = mixtapeId
                    prefs.edit().putString(KEY_LAST_MIXTAPE, mixtapeId).apply()
                    switchSection(Section.MIXTAPES, moveFocus = false)
                }
            }
            render()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_READY) {
                hasPlaybackError = false
            }
            render()
        }

        override fun onPlayerError(error: PlaybackException) {
            hasPlaybackError = true
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedChannel = NtsChannel.fromId(prefs.getString(KEY_LAST_CHANNEL, null)) ?: NtsChannel.ONE
        selectedMixtapeId = prefs.getString(KEY_LAST_MIXTAPE, null)
        section = Section.entries.firstOrNull { it.name == prefs.getString(KEY_SECTION, null) }
            ?: Section.LIVE
        setContentView(R.layout.activity_main)
        applyNtsTypeface(findViewById(android.R.id.content))
        bindViews()
        setupControls()
        setupBackNavigation()
        MixtapeRepository.cached().takeIf { it.isNotEmpty() }?.let(::rebuildMixtapeGrid)
        switchSection(section, moveFocus = true)
        maybeRequestNotificationPermission()

        handler.postDelayed(ticker, 1_000L)
    }

    override fun onStart() {
        super.onStart()
        NtsRepository.addListener(repositoryListener)
        NtsRepository.refresh()
        MixtapeRepository.addListener(mixtapeListener)
        if (section == Section.MIXTAPES) MixtapeRepository.ensureLoaded()
        connectToPlayback()
    }

    override fun onStop() {
        NtsRepository.removeListener(repositoryListener)
        MixtapeRepository.removeListener(mixtapeListener)
        handler.removeCallbacks(ticker)
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        controller = null
        super.onStop()
    }

    private fun bindViews() {
        backdrop = findViewById(R.id.backdrop)
        artwork = findViewById(R.id.artwork)
        channelBadge = findViewById(R.id.channel_badge)
        showTitle = findViewById(R.id.show_title)
        showMeta = findViewById(R.id.show_meta)
        description = findViewById(R.id.description)
        nextShow = findViewById(R.id.next_show)
        timeline = findViewById(R.id.timeline)
        startTime = findViewById(R.id.start_time)
        endTime = findViewById(R.id.end_time)
        clock = findViewById(R.id.clock)
        dateLabel = findViewById(R.id.date_label)
        channelOneTile = findViewById(R.id.channel_one_tile)
        channelTwoTile = findViewById(R.id.channel_two_tile)
        tabLive = findViewById(R.id.tab_live)
        tabMixtapes = findViewById(R.id.tab_mixtapes)
        tabLiveIndicator = findViewById(R.id.tab_live_indicator)
        tabMixtapesIndicator = findViewById(R.id.tab_mixtapes_indicator)
        liveSection = findViewById(R.id.live_section)
        mixtapesSection = findViewById(R.id.mixtapes_section)
        mixtapeGrid = findViewById(R.id.mixtape_grid)
        mixtapesStatus = findViewById(R.id.mixtapes_status)
        mixtapesError = findViewById(R.id.mixtapes_error)
        // Marquee scrolling only runs while the TextView is selected.
        showTitle.isSelected = true
    }

    private fun applyNtsTypeface(view: View) {
        val font = ntsFont ?: return
        applyTypeface(view, font)
    }

    private fun applyTypeface(view: View, font: Typeface) {
        if (view is TextView) {
            // Keep each view's declared weight, e.g. the bold labels.
            view.setTypeface(font, view.typeface?.style ?: Typeface.NORMAL)
        } else if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                applyTypeface(view.getChildAt(index), font)
            }
        }
    }

    private fun setupControls() {
        bindChannelTile(channelOneTile, NtsChannel.ONE)
        bindChannelTile(channelTwoTile, NtsChannel.TWO)
        bindTab(tabLive, Section.LIVE)
        bindTab(tabMixtapes, Section.MIXTAPES)
    }

    private fun bindChannelTile(tile: View, channel: NtsChannel) {
        tile.contentDescription = channel.displayName
        tile.setOnClickListener { selectChannel(channel) }
        tile.onFocusChangeListener = View.OnFocusChangeListener { view, focused ->
            view.animate()
                .scaleX(if (focused) 1.04f else 1f)
                .scaleY(if (focused) 1.04f else 1f)
                .setDuration(150L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun bindTab(tab: View, target: Section) {
        tab.setOnClickListener { switchSection(target, moveFocus = true) }
        tab.onFocusChangeListener = View.OnFocusChangeListener { _, _ -> updateTabs() }
    }

    private fun setupBackNavigation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val callback = OnBackInvokedCallback {
                if (section == Section.MIXTAPES) {
                    switchSection(Section.LIVE, moveFocus = true)
                } else {
                    finish()
                }
            }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback,
            )
            backCallback = callback
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (section == Section.MIXTAPES) {
            switchSection(Section.LIVE, moveFocus = true)
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
            backCallback = null
        }
        super.onDestroy()
    }

    private fun connectToPlayback() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                try {
                    controller = future.get()
                    val item = controller?.currentMediaItem
                    val playbackChannel = MediaItems.channelOf(item)
                    val playbackMixtape = MediaItems.mixtapeIdOf(item)
                    if (playbackChannel != null) {
                        selectedChannel = playbackChannel
                        currentInfo = NtsRepository.cached(selectedChannel)
                    } else if (playbackMixtape != null) {
                        selectedMixtapeId = playbackMixtape
                        switchSection(Section.MIXTAPES, moveFocus = false)
                    }
                    controller?.addListener(playerListener)

                    if (item == null) {
                        // Only auto-start radio when the user is on the live
                        // section; opening the mixtapes section should stay quiet
                        // until a mixtape is chosen.
                        if (section == Section.LIVE) {
                            startPlayback(
                                selectedChannel,
                                autoPlay = prefs.getBoolean(KEY_AUTOPLAY, true),
                            )
                        }
                    } else {
                        currentInfo = NtsRepository.cached(selectedChannel)
                        render()
                    }
                    switchSection(section, moveFocus = true)
                } catch (e: Exception) {
                    render()
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun selectChannel(channel: NtsChannel) {
        val player = controller ?: return
        if (section != Section.LIVE) {
            switchSection(Section.LIVE, moveFocus = true)
        }
        val playingThisChannel = MediaItems.channelOf(player.currentMediaItem) == channel
        if (channel == selectedChannel && playingThisChannel) {
            // Pressing the channel that is already playing acts as play/pause.
            togglePlayPause()
            return
        }
        selectedChannel = channel
        currentInfo = NtsRepository.cached(channel)
        startPlayback(channel, autoPlay = true)
        render()
        focusSelectedChannel()
    }

    private fun startPlayback(channel: NtsChannel, autoPlay: Boolean = true) {
        val player = controller ?: return
        player.setMediaItem(MediaItems.forChannel(channel, NtsRepository.cached(channel)))
        if (autoPlay) {
            player.prepare()
            player.play()
        }
        render()
    }

    private fun playMixtape(mixtape: Mixtape) {
        val player = controller ?: return
        if (MediaItems.mixtapeIdOf(player.currentMediaItem) == mixtape.alias) {
            // The playing mixtape doubles as a play/pause button.
            togglePlayPause()
            return
        }
        selectedMixtapeId = mixtape.alias
        prefs.edit().putString(KEY_LAST_MIXTAPE, mixtape.alias).apply()
        player.setMediaItem(MediaItems.forMixtape(mixtape))
        player.prepare()
        player.play()
        switchSection(Section.MIXTAPES, moveFocus = false)
        render()
    }

    private fun focusSelectedChannel() {
        if (section != Section.LIVE) return
        val tile = if (selectedChannel == NtsChannel.ONE) channelOneTile else channelTwoTile
        if (!tile.isFocused) tile.requestFocus()
    }

    private fun togglePlayPause() {
        val player = controller ?: return
        if (player.isPlaying) {
            player.pause()
        } else if (player.currentMediaItem == null) {
            startPlayback(selectedChannel)
        } else {
            player.play()
        }
        render()
    }

    private fun switchSection(target: Section, moveFocus: Boolean) {
        section = target
        prefs.edit().putString(KEY_SECTION, target.name).apply()
        liveSection.visibility = if (target == Section.LIVE) View.VISIBLE else View.GONE
        mixtapesSection.visibility = if (target == Section.MIXTAPES) View.VISIBLE else View.GONE
        if (target == Section.MIXTAPES) MixtapeRepository.ensureLoaded()
        render()
        if (moveFocus) {
            handler.post {
                if (target == Section.LIVE) {
                    focusSelectedChannel()
                } else {
                    focusMixtape(selectedMixtapeId)
                }
            }
        }
    }

    private fun updateTabs() {
        val liveSelected = section == Section.LIVE
        tabLive.isSelected = liveSelected
        tabMixtapes.isSelected = !liveSelected

        // The focused tab gets an underline: white on the dark background, or
        // black when the tab is also selected so it reads inside its white box.
        tabLiveIndicator.visibility = if (tabLive.hasFocus()) View.VISIBLE else View.INVISIBLE
        tabMixtapesIndicator.visibility =
            if (tabMixtapes.hasFocus()) View.VISIBLE else View.INVISIBLE
        tabLiveIndicator.setBackgroundColor(
            ContextCompat.getColor(this, if (liveSelected) R.color.black else R.color.white))
        tabMixtapesIndicator.setBackgroundColor(
            ContextCompat.getColor(this, if (liveSelected) R.color.white else R.color.black))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                togglePlayPause()
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                controller?.play()
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                controller?.pause()
                return true
            }

            KeyEvent.KEYCODE_CHANNEL_UP -> {
                selectChannel(NtsChannel.TWO)
                return true
            }

            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                selectChannel(NtsChannel.ONE)
                return true
            }

            KeyEvent.KEYCODE_BACK -> {
                if (section == Section.MIXTAPES) {
                    switchSection(Section.LIVE, moveFocus = true)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun render() {
        clock.text = timeFormat.format(Date())
        dateLabel.text = dayFormat.format(Date()).uppercase(Locale.getDefault())
        updateTabs()
        renderBackdrop()
        if (section == Section.LIVE) {
            renderLive()
        } else {
            renderMixtapes()
        }
    }

    private fun renderBackdrop() {
        val currentItem = controller?.currentMediaItem
        val url = if (section == Section.MIXTAPES) {
            mixtapeFor(MediaItems.mixtapeIdOf(currentItem))?.artworkUrl
                ?: currentInfo?.artworkUrl
        } else {
            currentInfo?.artworkUrl
        }
        // Upscaling a tiny bitmap gives the soft backdrop look without the
        // full-screen RenderEffect blur that crashes some TV GPU drivers.
        ArtworkLoader.load(url, backdrop, crossfade = false, maxDimension = 48)
    }

    private fun renderLive() {
        channelBadge.text = selectedChannel.apiName
        channelOneTile.isSelected = selectedChannel == NtsChannel.ONE
        channelTwoTile.isSelected = selectedChannel == NtsChannel.TWO

        val info = currentInfo
        val playing = controller?.isPlaying == true
        val liveChannel = MediaItems.channelOf(controller?.currentMediaItem)
        val error = hasPlaybackError

        val onePlaying = playing && liveChannel == NtsChannel.ONE
        val twoPlaying = playing && liveChannel == NtsChannel.TWO
        channelOneTile.isActivated = onePlaying
        channelTwoTile.isActivated = twoPlaying
        channelOneTile.contentDescription = channelDescription(NtsChannel.ONE, onePlaying)
        channelTwoTile.contentDescription = channelDescription(NtsChannel.TWO, twoPlaying)

        val title = if (error) {
            getString(R.string.offline_title)
        } else {
            info?.showTitle
                ?: controller?.mediaMetadata?.title?.toString()?.takeIf { it.isNotBlank() }
                ?: getString(R.string.live_radio)
        }
        if (showTitle.text?.toString() != title) {
            // Only touch the text when the title actually changes, so playback
            // updates do not restart the marquee. Re-selecting it makes a new
            // title scroll from the start instead of keeping the old offset.
            showTitle.text = title
            showTitle.isSelected = false
            showTitle.isSelected = true
        }

        val artworkUrl = info?.artworkUrl ?: controller?.mediaMetadata?.artworkUri?.toString()
        artwork.contentDescription = title
        ArtworkLoader.load(artworkUrl, artwork)

        showMeta.text = buildMetaLine(info)
        description.text = if (error) {
            getString(R.string.offline_body)
        } else {
            info?.description.orEmpty().ifBlank {
                controller?.mediaMetadata?.description?.toString().orEmpty()
            }
        }

        if (info != null && info.endMs > 0L) {
            timeline.setRange(info.startMs, info.endMs)
            startTime.text = timeFormat.format(Date(info.startMs))
            endTime.text = timeFormat.format(Date(info.endMs))
            startTime.visibility = View.VISIBLE
            endTime.visibility = View.VISIBLE
        } else {
            timeline.setRange(0L, 0L)
            startTime.visibility = View.INVISIBLE
            endTime.visibility = View.INVISIBLE
        }

        val nextTitle = info?.nextTitle
        if (nextTitle != null) {
            val nextTime = info.nextStartMs
                ?.takeIf { it > 0L }
                ?.let { timeFormat.format(Date(it)) + "  " }
                .orEmpty()
            nextShow.text = getString(R.string.next_show, nextTime, nextTitle)
            nextShow.visibility = View.VISIBLE
        } else {
            nextShow.visibility = View.INVISIBLE
        }
    }

    private fun renderMixtapes() {
        val currentId = MediaItems.mixtapeIdOf(controller?.currentMediaItem)
        val playing = controller?.isPlaying == true
        for ((alias, tile) in mixtapeTiles) {
            val isPlaying = playing && alias == currentId
            tile.activeDot.visibility = if (isPlaying) View.VISIBLE else View.GONE
            tile.container.isActivated = isPlaying
            tile.container.contentDescription = getString(
                if (isPlaying) {
                    R.string.mixtape_pause_description
                } else {
                    R.string.mixtape_play_description
                },
                tile.mixtape.title,
            )
        }

        val hasItems = renderedMixtapes.isNotEmpty()
        mixtapesStatus.visibility = if (hasItems) View.GONE else View.VISIBLE
        mixtapesStatus.text = if (MixtapeRepository.isLoading()) {
            getString(R.string.mixtapes_loading)
        } else {
            getString(R.string.mixtapes_unavailable)
        }

        val streamError = hasItems && hasPlaybackError && currentId != null
        mixtapesError.visibility = if (streamError) View.VISIBLE else View.GONE
        mixtapesError.text = getString(R.string.offline_title)
    }

    private fun rebuildMixtapeGrid(mixtapes: List<Mixtape>) {
        renderedMixtapes = mixtapes
        mixtapeGrid.removeAllViews()
        mixtapeTiles.clear()
        if (mixtapes.isNotEmpty()) {
            mixtapes.chunked(MIXTAPE_COLUMNS).forEachIndexed { rowIndex, rowItems ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    clipChildren = false
                    clipToPadding = false
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        if (rowIndex > 0) topMargin = dp(10)
                    }
                }
                rowItems.forEachIndexed { index, mixtape ->
                    val tile = buildMixtapeTile(mixtape)
                    tile.layoutParams = LinearLayout.LayoutParams(
                        dp(MIXTAPE_TILE_DP),
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        if (index < rowItems.lastIndex) marginEnd = dp(MIXTAPE_GAP_DP)
                    }
                    row.addView(tile)
                }
                mixtapeGrid.addView(row)
            }
            ntsFont?.let { applyTypeface(mixtapeGrid, it) }
        }
        render()

        val currentFocus = currentFocus
        val focusIsElsewhere = currentFocus != null &&
            currentFocus != tabLive &&
            currentFocus != tabMixtapes &&
            !gridHasFocus()
        if (section == Section.MIXTAPES && mixtapes.isNotEmpty() && !focusIsElsewhere) {
            focusMixtape(selectedMixtapeId)
        }
    }

    private fun buildMixtapeTile(mixtape: Mixtape): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isClickable = true
            defaultFocusHighlightEnabled = false
            clipChildren = false
            clipToPadding = false
            setOnClickListener { playMixtape(mixtape) }
        }

        val artFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(MIXTAPE_TILE_DP), dp(MIXTAPE_TILE_DP))
            // Keep centerCrop artwork inside the tile so it cannot bleed over
            // neighbouring tiles (and the playing dot).
            clipChildren = true
        }
        val image = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.tile_background))
            contentDescription = null
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val border = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_artwork_border)
        }
        val dotSize = dp(8)
        val dot = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(dotSize, dotSize, Gravity.TOP or Gravity.END)
                .apply {
                    topMargin = dp(8)
                    marginEnd = dp(8)
                }
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.dot_live)
            visibility = View.GONE
        }
        artFrame.addView(image)
        artFrame.addView(border)
        artFrame.addView(dot)

        val label = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
            text = mixtape.title
            textSize = 13f
            setTextColor(ContextCompat.getColorStateList(this@MainActivity, R.color.mixtape_title))
            setTextIsSelectable(false)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            letterSpacing = 0.02f
            setTypeface(typeface, Typeface.BOLD)
            isDuplicateParentStateEnabled = true
        }

        container.addView(artFrame)
        container.addView(label)
        container.onFocusChangeListener = View.OnFocusChangeListener { view, focused ->
            border.background = ContextCompat.getDrawable(
                this,
                if (focused) R.drawable.bg_artwork_focus_border else R.drawable.bg_artwork_border,
            )
            view.animate()
                .scaleX(if (focused) 1.06f else 1f)
                .scaleY(if (focused) 1.06f else 1f)
                .setDuration(150L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        ArtworkLoader.load(mixtape.artworkUrl, image, maxDimension = 256)
        mixtapeTiles[mixtape.alias] = MixtapeTile(mixtape, container, dot)
        return container
    }

    private fun focusMixtape(alias: String?): Boolean {
        val tile = alias?.let(mixtapeTiles::get) ?: mixtapeTiles.values.firstOrNull() ?: return false
        return tile.container.requestFocus()
    }

    private fun gridHasFocus(): Boolean = mixtapeTiles.values.any { it.container.hasFocus() }

    private fun mixtapeFor(alias: String?): Mixtape? =
        alias?.let { id -> renderedMixtapes.firstOrNull { it.alias == id } }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun channelDescription(channel: NtsChannel, playing: Boolean): String = getString(
        when {
            playing -> R.string.channel_pause_description
            selectedChannel == channel -> R.string.channel_play_description
            else -> R.string.channel_switch_description
        },
        channel.displayName,
    )

    private fun buildMetaLine(info: NowPlaying?): String {
        val location = info?.location
        return if (location.isNullOrBlank()) {
            getString(R.string.live_radio)
        } else {
            getString(R.string.live_from, location)
        }.uppercase(Locale.getDefault())
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private enum class Section { LIVE, MIXTAPES }

    private data class MixtapeTile(
        val mixtape: Mixtape,
        val container: View,
        val activeDot: View,
    )

    private companion object {
        const val PREFS = "nts_radio"
        const val KEY_LAST_CHANNEL = "last_channel"
        const val KEY_AUTOPLAY = "autoplay"
        const val KEY_SECTION = "section"
        const val KEY_LAST_MIXTAPE = "last_mixtape"
        const val MIXTAPE_COLUMNS = 6
        const val MIXTAPE_TILE_DP = 112
        const val MIXTAPE_GAP_DP = 16
    }
}
