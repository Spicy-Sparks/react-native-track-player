package com.doublesymmetry.kotlinaudio.players

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.AudioManager.AUDIOFOCUS_LOSS
import android.net.Uri
import android.os.Bundle
import android.os.ResultReceiver
import android.support.v4.media.RatingCompat
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.CallSuper
import androidx.core.content.ContextCompat
import androidx.media.AudioAttributesCompat
import androidx.media.AudioAttributesCompat.CONTENT_TYPE_MUSIC
import androidx.media.AudioAttributesCompat.USAGE_MEDIA
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import androidx.media.AudioManagerCompat.AUDIOFOCUS_GAIN
import com.guichaguri.trackplayer.kotlinaudio.event.EventHolder
import com.guichaguri.trackplayer.kotlinaudio.event.NotificationEventHolder
import com.guichaguri.trackplayer.kotlinaudio.event.PlayerEventHolder
// import com.doublesymmetry.kotlinaudio.models.*
import com.guichaguri.trackplayer.kotlinaudio.notification.NotificationManager
import com.guichaguri.trackplayer.kotlinaudio.players.components.PlayerCache
import com.guichaguri.trackplayer.kotlinaudio.utils.isUriLocal
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.DefaultLoadControl.*
import com.google.android.exoplayer2.Player.Listener
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory
import com.google.android.exoplayer2.metadata.Metadata
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.dash.DashMediaSource
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource
import com.google.android.exoplayer2.upstream.*
import com.google.android.exoplayer2.upstream.cache.CacheDataSource
import com.google.android.exoplayer2.upstream.cache.SimpleCache
import com.google.android.exoplayer2.util.Util
import com.guichaguri.trackplayer.kotlinaudio.models.*
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.*
import java.util.concurrent.TimeUnit

abstract class BaseAudioPlayer internal constructor(
    internal val context: Context,
    playerConfig: PlayerConfig,
    private val bufferConfig: BufferConfig?,
    private val cacheConfig: CacheConfig?,
    mediaSessionCallback: AAMediaSessionCallBack
) : AudioManager.OnAudioFocusChangeListener {
    protected val exoPlayer: ExoPlayer

    private var cache: SimpleCache? = null
    private val scope = MainScope()
    private var playerConfig: PlayerConfig = playerConfig
    var mediaSessionCallBack: AAMediaSessionCallBack = mediaSessionCallback

    val notificationManager: NotificationManager

    open val playerOptions: PlayerOptions = DefaultPlayerOptions()

    open val currentItem: AudioItem?
        get() = exoPlayer.currentMediaItem?.localConfiguration?.tag as AudioItem?

    var playbackError: PlaybackError? = null
    var playerState: AudioPlayerState = AudioPlayerState.IDLE
        private set(value) {
            if (value != field) {
                field = value
                playerEventHolder.updateAudioPlayerState(value)
                if (!playerConfig.handleAudioFocus) {
                    when (value) {
                        AudioPlayerState.IDLE,
                        AudioPlayerState.ERROR -> abandonAudioFocusIfHeld()
                        AudioPlayerState.READY -> requestAudioFocus()
                        else -> {}
                    }
                }
            }
        }

    var playWhenReady: Boolean
        get() = exoPlayer.playWhenReady
        set(value) {
            exoPlayer.playWhenReady = value
        }

    val duration: Long
        get() {
            return if (exoPlayer.duration == C.TIME_UNSET) 0
            else exoPlayer.duration
        }

    private var oldPosition = 0L

    val position: Long
        get() {
            return if (exoPlayer.currentPosition == C.POSITION_UNSET.toLong()) 0
            else exoPlayer.currentPosition
        }

    val bufferedPosition: Long
        get() {
            return if (exoPlayer.bufferedPosition == C.POSITION_UNSET.toLong()) 0
            else exoPlayer.bufferedPosition
        }

    var volume: Float
        get() = exoPlayer.volume
        set(value) {
            exoPlayer.volume = value * volumeMultiplier
        }

    var playbackSpeed: Float
        get() = exoPlayer.playbackParameters.speed
        set(value) {
            exoPlayer.setPlaybackSpeed(value)
        }

    var automaticallyUpdateNotificationMetadata: Boolean = true

    private var volumeMultiplier = 1f
        private set(value) {
            field = value
            volume = volume
        }

    val isPlaying
        get() = exoPlayer.isPlaying

    private val notificationEventHolder = NotificationEventHolder()
    private val playerEventHolder = PlayerEventHolder()

    var ratingType: Int = RatingCompat.RATING_NONE
        set(value) {
            field = value

            mediaSession.setRatingType(ratingType)
            mediaSessionConnector.setRatingCallback(object : MediaSessionConnector.RatingCallback {
                override fun onCommand(
                    player: Player,
                    command: String,
                    extras: Bundle?,
                    cb: ResultReceiver?
                ): Boolean {
                    return true
                }

                override fun onSetRating(player: Player, rating: RatingCompat) {
                    playerEventHolder.updateOnPlayerActionTriggeredExternally(
                        MediaSessionCallback.RATING(
                            rating,
                            null
                        )
                    )
                }

                override fun onSetRating(player: Player, rating: RatingCompat, extras: Bundle?) {
                    playerEventHolder.updateOnPlayerActionTriggeredExternally(
                        MediaSessionCallback.RATING(
                            rating,
                            extras
                        )
                    )
                }
            })
        }

    val event = EventHolder(notificationEventHolder, playerEventHolder)

    private var focus: AudioFocusRequestCompat? = null
    private var hasAudioFocus = false
    private var wasDucking = false

    protected val mediaSession = MediaSessionCompat(context, "KotlinAudioPlayer")
    protected val mediaSessionConnector = MediaSessionConnector(mediaSession)

    init {
        if (cacheConfig != null) {
            cache = PlayerCache.getInstance(context, cacheConfig)
        }

        exoPlayer = ExoPlayer.Builder(context)
            .setHandleAudioBecomingNoisy(playerConfig.handleAudioBecomingNoisy)
            .apply {
                if (bufferConfig != null) setLoadControl(setupBuffer(bufferConfig))
            }
            .build()

        exoPlayer.pauseAtEndOfMediaItems = true

        mediaSession.isActive = true

        val playerToUse =
            if (playerConfig.interceptPlayerActionsTriggeredExternally) createForwardingPlayer() else exoPlayer

        mediaSession.setCallback(object: MediaSessionCompat.Callback() {
            // HACK: special for podverse that intercepts SkipNext and SkipPrevious and handles accordingly
            // as podverse does not enable these playback capabilities
            override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                if (mediaButtonEvent?.action == Intent.ACTION_MEDIA_BUTTON) {
                    val event = mediaButtonEvent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    if (event != null && event.action == KeyEvent.ACTION_DOWN) {
                        when (event.keyCode) {
                            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                                this.onSkipToNext()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                                this.onSkipToPrevious()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD, KeyEvent.KEYCODE_MEDIA_STEP_FORWARD -> {
                                this.onFastForward()
                            }
                            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD, KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD -> {
                                this.onRewind()
                            }
                            else -> {
                            }
                        }
                    }
                }
                return super.onMediaButtonEvent(mediaButtonEvent)
            }
            override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                Timber.tag("GVATest").d("playing from mediaID: %s", mediaId)
                mediaSessionCallback.handlePlayFromMediaId(mediaId, extras)
            }

            override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                super.onPlayFromSearch(query, extras)
                Timber.tag("GVATest").d("playing from query: %s", query)
                mediaSessionCallback.handlePlayFromSearch(query, extras)
            }
            // https://stackoverflow.com/questions/53837783/selecting-media-item-in-android-auto-queue-does-nothing
            override fun onSkipToQueueItem(id: Long) {
                mediaSessionCallback.handleSkipToQueueItem(id)
            }
            // TODO: what's missing?
            override fun onPlay() {
                playerToUse.play()
            }

            override fun onPause() {
                playerToUse.pause()
            }

            override fun onSkipToNext() {
                playerToUse.seekToNext()
            }

            override fun onSkipToPrevious() {
                playerToUse.seekToPrevious()
            }

            override fun onFastForward() {
                playerToUse.seekForward()
            }

            override fun onRewind() {
                playerToUse.seekBack()
            }

            override fun onStop() {
                playerToUse.stop()
            }

            override fun onSeekTo(pos: Long) {
                playerToUse.seekTo(pos)
            }

            override fun onSetRating(rating: RatingCompat?) {
                if (rating == null) return
                playerEventHolder.updateOnPlayerActionTriggeredExternally(
                    MediaSessionCallback.RATING(
                        rating, null
                    )
                )
            }

            override fun onSetRating(rating: RatingCompat?, extras: Bundle?) {
                if (rating == null) return
                playerEventHolder.updateOnPlayerActionTriggeredExternally(
                    MediaSessionCallback.RATING(
                        rating,
                        extras
                    )
                )
            }
            // see NotificationManager.kt. onRewind, onFastForward and onStop do not trigger.
            override fun onCustomAction(action: String?, extras: Bundle?) {
                when (action) {
                    NotificationManager.REWIND -> playerToUse.seekBack()
                    NotificationManager.FORWARD -> playerToUse.seekForward()
                    NotificationManager.STOP-> playerToUse.stop()
                    else -> playerEventHolder.updateOnPlayerActionTriggeredExternally(
                            MediaSessionCallback.CUSTOMACTION(
                                action ?: "NO_ACTION_CODE_PROVIDED"
                            )
                        )
                }
            }
        })

        notificationManager = NotificationManager(
            context,
            playerToUse,
            mediaSession,
            mediaSessionConnector,
            notificationEventHolder,
            playerEventHolder
        )

        exoPlayer.addListener(PlayerListener())

        scope.launch {
            // Whether ExoPlayer should manage audio focus for us automatically
            // see https://medium.com/google-exoplayer/easy-audio-focus-with-exoplayer-a2dcbbe4640e
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(
                    when (playerConfig.audioContentType) {
                        AudioContentType.MUSIC -> C.AUDIO_CONTENT_TYPE_MUSIC
                        AudioContentType.SPEECH -> C.AUDIO_CONTENT_TYPE_SPEECH
                        AudioContentType.SONIFICATION -> C.AUDIO_CONTENT_TYPE_SONIFICATION
                        AudioContentType.MOVIE -> C.AUDIO_CONTENT_TYPE_MOVIE
                        AudioContentType.UNKNOWN -> C.AUDIO_CONTENT_TYPE_UNKNOWN
                    }
                )
                .build();
            exoPlayer.setAudioAttributes(audioAttributes, playerConfig.handleAudioFocus);
            mediaSessionConnector.setPlayer(playerToUse)
            mediaSessionConnector.setMediaMetadataProvider {
                notificationManager.getMediaMetadataCompat()
            }
        }

        playerEventHolder.updateAudioPlayerState(AudioPlayerState.IDLE)
    }

    public fun getMediaSessionToken(): MediaSessionCompat.Token {
        return mediaSession.sessionToken
    }

    private fun createForwardingPlayer(): ForwardingPlayer {
        return object : ForwardingPlayer(exoPlayer) {
            override fun play() {
                playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.PLAY)
            }

            override fun pause() {
                playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.PAUSE)
            }

            override fun seekToNext() {
                playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.NEXT)
            }

            override fun seekToPrevious() {
                playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.PREVIOUS)
            }

            override fun seekForward() {
                playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.FORWARD)
            }

            override fun seekBack() {
                playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.REWIND)
            }

            override fun stop() {
                playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.STOP)
            }

            override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
                playerEventHolder.updateOnPlayerActionTriggeredExternally(
                    MediaSessionCallback.SEEK(
                        positionMs
                    )
                )
            }

            override fun seekTo(positionMs: Long) {
                playerEventHolder.updateOnPlayerActionTriggeredExternally(
                    MediaSessionCallback.SEEK(
                        positionMs
                    )
                )
            }
        }
    }

    internal fun updateNotificationMetadataIfAutomatic(overrideAudioItem: AudioItem? = null) {
        if (automaticallyUpdateNotificationMetadata) {
            notificationManager.notificationMetadata = NotificationMetadata(
                overrideAudioItem?.title ?: currentItem?.title,
                overrideAudioItem?.artist ?: currentItem?.artist,
                overrideAudioItem?.artwork ?: currentItem?.artwork,
                overrideAudioItem?.duration ?: currentItem?.duration

            )
        }
    }

    private fun setupBuffer(bufferConfig: BufferConfig): DefaultLoadControl {
        bufferConfig.apply {
            val multiplier =
                DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS / DEFAULT_BUFFER_FOR_PLAYBACK_MS
            val minBuffer =
                if (minBuffer != null && minBuffer != 0) minBuffer else DEFAULT_MIN_BUFFER_MS
            val maxBuffer =
                if (maxBuffer != null && maxBuffer != 0) maxBuffer else DEFAULT_MAX_BUFFER_MS
            val playBuffer =
                if (playBuffer != null && playBuffer != 0) playBuffer else DEFAULT_BUFFER_FOR_PLAYBACK_MS
            val backBuffer =
                if (backBuffer != null && backBuffer != 0) backBuffer else DEFAULT_BACK_BUFFER_DURATION_MS

            return Builder()
                .setBufferDurationsMs(minBuffer, maxBuffer, playBuffer, playBuffer * multiplier)
                .setBackBuffer(backBuffer, false)
                .build()
        }
    }

    /**
     * Will replace the current item with a new one and load it into the player.
     * @param item The [AudioItem] to replace the current one.
     * @param playWhenReady Whether playback starts automatically.
     */
    open fun load(item: AudioItem, playWhenReady: Boolean = true) {
        exoPlayer.playWhenReady = playWhenReady
        load(item)
    }

    /**
     * Will replace the current item with a new one and load it into the player.
     * @param item The [AudioItem] to replace the current one.
     */
    open fun load(item: AudioItem) {
        val mediaSource = getMediaSourceFromAudioItem(item)
        exoPlayer.addMediaSource(mediaSource)
        exoPlayer.prepare()
    }

    fun togglePlaying() {
        if (exoPlayer.isPlaying) {
            pause()
        } else {
            play()
        }
    }

    var skipSilence: Boolean
        get() = exoPlayer.skipSilenceEnabled
        set(value) {
            exoPlayer.skipSilenceEnabled = value;
        }

    fun play() {
        exoPlayer.play()
        Log.d("playTest", "playTest play")
        if (currentItem != null) {
            exoPlayer.prepare()
        }
    }

    fun prepare() {
        Log.d("playTest", "playTest prepare")
        if (currentItem != null) {
            exoPlayer.prepare()
        }
    }

    fun pause() {
        Log.d("playTest", "playTest pause")
        exoPlayer.pause()
    }

    /**
     * Stops playback, without clearing the active item. Calling this method will cause the playback
     * state to transition to AudioPlayerState.IDLE and the player will release the loaded media and
     * resources required for playback.
     */
    open fun stop() {
        playerState = AudioPlayerState.STOPPED
        exoPlayer.playWhenReady = false
        exoPlayer.stop()
    }

    open fun clear() {
        exoPlayer.clearMediaItems()
    }

    /**
     * Pause playback whenever an item plays to its end.
     */
    fun setPauseAtEndOfItem(pause: Boolean) {
        exoPlayer.setPauseAtEndOfMediaItems(pause)
    }

    /**
     * Stops and destroys the player. Only call this when you are finished using the player, otherwise use [pause].
     */
    @CallSuper
    open fun destroy() {
        abandonAudioFocusIfHeld()
        stop()
        notificationManager.destroy()
        exoPlayer.release()
        cache?.release()
        cache = null
        mediaSession.isActive = false
    }

    open fun seek(duration: Long, unit: TimeUnit) {
        Log.d("playTest", "playTest seek")
        val positionMs = TimeUnit.MILLISECONDS.convert(duration, unit)
        exoPlayer.seekTo(positionMs)
    }

    open fun seekBy(offset: Long, unit: TimeUnit) {
        val positionMs = exoPlayer.currentPosition + TimeUnit.MILLISECONDS.convert(offset, unit)
        exoPlayer.seekTo(positionMs)
    }

    private fun getMediaItemFromAudioItem(audioItem: AudioItem): MediaItem {
        return MediaItem.Builder().setUri(audioItem.audioUrl).setTag(AudioItemHolder(audioItem)).build()
    }

    protected fun getMediaSourceFromAudioItem(audioItem: AudioItem): MediaSource {
        val factory: DataSource.Factory
        val uri = Uri.parse(audioItem.audioUrl)
        val mediaItem = getMediaItemFromAudioItem(audioItem)

        Log.d("pcTest", "pcTest getMediaSource 1")

        val userAgent =
            if (audioItem.options == null || audioItem.options!!.userAgent.isNullOrBlank()) {
                Util.getUserAgent(context, APPLICATION_NAME)
            } else {
                audioItem.options!!.userAgent
            }

        Log.d("pcTest", "pcTest getMediaSource 2")

        factory = when {
            audioItem.options?.resourceId != null -> {
                Log.d("pcTest", "pcTest getMediaSource 2 1")
                val raw = RawResourceDataSource(context)
                Log.d("pcTest", "pcTest getMediaSource 2 1 uri: " + uri)
                raw.open(DataSpec(uri))
                Log.d("pcTest", "pcTest getMediaSource 2 2")
                DataSource.Factory { raw }
            }
            isUriLocal(uri) -> {
                Log.d("pcTest", "pcTest getMediaSource 2 3")
                DefaultDataSourceFactory(context, userAgent)
            }
            else -> {
                val tempFactory = DefaultHttpDataSource.Factory().apply {
                    Log.d("pcTest", "pcTest getMediaSource 3")
                    setUserAgent(userAgent)
                    setAllowCrossProtocolRedirects(true)
                    Log.d("pcTest", "pcTest getMediaSource 4")

                    audioItem.options?.headers?.let {
                        setDefaultRequestProperties(it.toMap())
                    }
                    Log.d("pcTest", "pcTest getMediaSource 5")
                }

                enableCaching(tempFactory)
            }
        }

        Log.d("pcTest", "pcTest getMediaSource 6")

        return when (audioItem.type) {
            MediaType.DASH -> createDashSource(mediaItem, factory)
            MediaType.HLS -> createHlsSource(mediaItem, factory)
            MediaType.SMOOTH_STREAMING -> createSsSource(mediaItem, factory)
            else -> createProgressiveSource(mediaItem, factory)
        }
    }

    private fun createDashSource(mediaItem: MediaItem, factory: DataSource.Factory?): MediaSource {
        return DashMediaSource.Factory(DefaultDashChunkSource.Factory(factory!!), factory)
            .createMediaSource(mediaItem)
    }

    private fun createHlsSource(mediaItem: MediaItem, factory: DataSource.Factory?): MediaSource {
        return HlsMediaSource.Factory(factory!!)
            .createMediaSource(mediaItem)
    }

    private fun createSsSource(mediaItem: MediaItem, factory: DataSource.Factory?): MediaSource {
        return SsMediaSource.Factory(DefaultSsChunkSource.Factory(factory!!), factory)
            .createMediaSource(mediaItem)
    }

    private fun createProgressiveSource(
        mediaItem: MediaItem,
        factory: DataSource.Factory
    ): ProgressiveMediaSource {
        return ProgressiveMediaSource.Factory(
            factory, DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
        )
            .createMediaSource(mediaItem)
    }

    private fun enableCaching(factory: DataSource.Factory): DataSource.Factory {
        return if (cache == null || cacheConfig == null || (cacheConfig.maxCacheSize ?: 0) <= 0) {
            factory
        } else {
            CacheDataSource.Factory().apply {
                setCache(this@BaseAudioPlayer.cache!!)
                setUpstreamDataSourceFactory(factory)
                setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            }
        }
    }

    private fun requestAudioFocus() {
        if (hasAudioFocus) return
        Timber.d("Requesting audio focus...")

        val manager = ContextCompat.getSystemService(context, AudioManager::class.java)

        focus = AudioFocusRequestCompat.Builder(AUDIOFOCUS_GAIN)
            .setOnAudioFocusChangeListener(this)
            .setAudioAttributes(
                AudioAttributesCompat.Builder()
                    .setUsage(USAGE_MEDIA)
                    .setContentType(CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setWillPauseWhenDucked(playerOptions.alwaysPauseOnInterruption)
            .build()

        val result: Int = if (manager != null && focus != null) {
            AudioManagerCompat.requestAudioFocus(manager, focus!!)
        } else {
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }

        hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    }

    private fun abandonAudioFocusIfHeld() {
        if (!hasAudioFocus) return
        Timber.d("Abandoning audio focus...")

        val manager = ContextCompat.getSystemService(context, AudioManager::class.java)

        val result: Int = if (manager != null && focus != null) {
            AudioManagerCompat.abandonAudioFocusRequest(manager, focus!!)
        } else {
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }

        hasAudioFocus = (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    }

    override fun onAudioFocusChange(focusChange: Int) {
        Timber.d("Audio focus changed")
        var isPermanent = focusChange == AUDIOFOCUS_LOSS
        var isPaused = when (focusChange) {
            AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> true
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> playerOptions.alwaysPauseOnInterruption
            else -> false
        }
        if (!playerConfig.handleAudioFocus) {
            if (isPermanent) abandonAudioFocusIfHeld()

            var isDucking = focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
                    && !playerOptions.alwaysPauseOnInterruption
            if (isDucking) {
                volumeMultiplier = 0.5f
                wasDucking = true
            } else if (wasDucking) {
                volumeMultiplier = 1f
                wasDucking = false
            }
        }

        playerEventHolder.updateOnAudioFocusChanged(isPaused, isPermanent)
    }

    companion object {
        const val APPLICATION_NAME = "react-native-track-player"
    }

    inner class PlayerListener : Listener {
        /**
         * Called when there is metadata associated with the current playback time.
         */
        override fun onMetadata(metadata: Metadata) {
            PlaybackMetadata.fromId3Metadata(metadata)
                ?.let { playerEventHolder.updateOnPlaybackMetadata(it) }
            PlaybackMetadata.fromIcy(metadata)
                ?.let { playerEventHolder.updateOnPlaybackMetadata(it) }
            PlaybackMetadata.fromVorbisComment(metadata)
                ?.let { playerEventHolder.updateOnPlaybackMetadata(it) }
            PlaybackMetadata.fromQuickTime(metadata)
                ?.let { playerEventHolder.updateOnPlaybackMetadata(it) }
        }


        /**
         * A position discontinuity occurs when the playing period changes, the playback position
         * jumps within the period currently being played, or when the playing period has been
         * skipped or removed.
         */
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            this@BaseAudioPlayer.oldPosition = oldPosition.positionMs

            when (reason) {
                Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> playerEventHolder.updatePositionChangedReason(
                    PositionChangedReason.AUTO(oldPosition.positionMs, newPosition.positionMs)
                )
                Player.DISCONTINUITY_REASON_SEEK -> playerEventHolder.updatePositionChangedReason(
                    PositionChangedReason.SEEK(oldPosition.positionMs, newPosition.positionMs)
                )
                Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> playerEventHolder.updatePositionChangedReason(
                    PositionChangedReason.SEEK_FAILED(
                        oldPosition.positionMs,
                        newPosition.positionMs
                    )
                )
                Player.DISCONTINUITY_REASON_REMOVE -> playerEventHolder.updatePositionChangedReason(
                    PositionChangedReason.QUEUE_CHANGED(
                        oldPosition.positionMs,
                        newPosition.positionMs
                    )
                )
                Player.DISCONTINUITY_REASON_SKIP -> playerEventHolder.updatePositionChangedReason(
                    PositionChangedReason.SKIPPED_PERIOD(
                        oldPosition.positionMs,
                        newPosition.positionMs
                    )
                )
                Player.DISCONTINUITY_REASON_INTERNAL -> playerEventHolder.updatePositionChangedReason(
                    PositionChangedReason.UNKNOWN(oldPosition.positionMs, newPosition.positionMs)
                )
            }
        }

        /**
         * Called when playback transitions to a media item or starts repeating a media item
         * according to the current repeat mode. Note that this callback is also called when the
         * playlist becomes non-empty or empty as a consequence of a playlist change.
         */
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            when (reason) {
                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> playerEventHolder.updateAudioItemTransition(
                    AudioItemTransitionReason.AUTO(oldPosition)
                )
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> playerEventHolder.updateAudioItemTransition(
                    AudioItemTransitionReason.QUEUE_CHANGED(oldPosition)
                )
                Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> playerEventHolder.updateAudioItemTransition(
                    AudioItemTransitionReason.REPEAT(oldPosition)
                )
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> playerEventHolder.updateAudioItemTransition(
                    AudioItemTransitionReason.SEEK_TO_ANOTHER_AUDIO_ITEM(oldPosition)
                )
            }

            updateNotificationMetadataIfAutomatic()
        }

        /**
         * Called when the value returned from Player.getPlayWhenReady() changes.
         */
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val pausedBecauseReachedEnd = reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
            playerEventHolder.updatePlayWhenReadyChange(PlayWhenReadyChangeData(playWhenReady, pausedBecauseReachedEnd))
        }

        /**
         * The generic onEvents callback provides access to the Player object and specifies the set
         * of events that occurred together. It’s always called after the callbacks that correspond
         * to the individual events.
         */
        override fun onEvents(player: Player, events: Player.Events) {
            // Note that it is necessary to set `playerState` in order, since each mutation fires an
            // event.
            for (i in 0 until events.size()) {
                when (events[i]) {
                    Player.EVENT_PLAYBACK_STATE_CHANGED -> {
                        var state = when (player.playbackState) {
                            Player.STATE_BUFFERING -> AudioPlayerState.BUFFERING
                            Player.STATE_READY -> AudioPlayerState.READY
                            Player.STATE_IDLE ->
                                // Avoid transitioning to idle from error or stopped
                                if (
                                    playerState == AudioPlayerState.ERROR ||
                                    playerState == AudioPlayerState.STOPPED
                                )
                                    null
                                else
                                    AudioPlayerState.IDLE
                            Player.STATE_ENDED -> AudioPlayerState.ENDED
                            else -> null // noop
                        }
                        if (state != null && state != playerState) {
                            playerState = state
                        }
                    }
                    Player.EVENT_MEDIA_ITEM_TRANSITION -> {
                        playbackError = null
                        if (currentItem != null) {
                            playerState = AudioPlayerState.LOADING
                            if (isPlaying) {
                                playerState = AudioPlayerState.READY
                                playerState = AudioPlayerState.PLAYING
                            }
                        }
                    }
                    Player.EVENT_PLAY_WHEN_READY_CHANGED -> {
                        if (!player.playWhenReady && playerState != AudioPlayerState.STOPPED) {
                            playerState = AudioPlayerState.PAUSED
                        }
                    }
                    Player.EVENT_IS_PLAYING_CHANGED -> {
                        if (player.isPlaying) {
                            playerState = AudioPlayerState.PLAYING
                        }
                    }
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            var _playbackError = PlaybackError(
                error.errorCodeName
                    .replace("ERROR_CODE_", "")
                    .lowercase(Locale.getDefault())
                    .replace("_", "-"),
                error.message
            )
            playerEventHolder.updatePlaybackError(_playbackError)
            playbackError = _playbackError
            playerState = AudioPlayerState.ERROR
        }
    }
}
