package com.doublesymmetry.trackplayer.service

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.session.DefaultMediaNotificationProvider
import com.doublesymmetry.trackplayer.R
import androidx.media.utils.MediaConstants
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.LibraryResult
import androidx.media3.common.MediaItem
import androidx.media3.common.util.BitmapLoader
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.lovegaoshi.kotlinaudio.models.*
import com.lovegaoshi.kotlinaudio.player.QueuedAudioPlayer
import com.doublesymmetry.trackplayer.HeadlessJsMediaService
import com.doublesymmetry.trackplayer.extensions.NumberExt.Companion.toMilliseconds
import com.doublesymmetry.trackplayer.extensions.NumberExt.Companion.toSeconds
import com.doublesymmetry.trackplayer.extensions.asLibState
import com.doublesymmetry.trackplayer.extensions.find
import com.doublesymmetry.trackplayer.model.MetadataAdapter
import com.doublesymmetry.trackplayer.model.PlaybackMetadata
import com.doublesymmetry.trackplayer.model.Track
import com.doublesymmetry.trackplayer.model.TrackAudioItem
import com.doublesymmetry.trackplayer.module.MusicEvents
import com.doublesymmetry.trackplayer.module.MusicEvents.Companion.METADATA_PAYLOAD_KEY
import com.doublesymmetry.trackplayer.R as TrackPlayerR
import com.doublesymmetry.trackplayer.utils.AppForegroundTracker
import com.doublesymmetry.trackplayer.utils.BundleUtils
import com.doublesymmetry.trackplayer.utils.BundleUtils.setRating
import com.doublesymmetry.trackplayer.utils.CoilBitmapLoader
import com.doublesymmetry.trackplayer.utils.buildMediaItem
import com.facebook.react.bridge.Arguments
import com.facebook.react.jstasks.HeadlessJsTaskConfig
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import java.util.concurrent.TimeUnit
import androidx.core.net.toUri

@OptIn(UnstableApi::class)
@MainThread
class MusicService : HeadlessJsMediaService() {
    companion object {
        @JvmStatic
        var instance: MusicService? = null
            private set
        const val EMPTY_NOTIFICATION_ID = 1

        // ActivityManager kills a service started with startForegroundService() if it has not
        // called startForeground() within 5s (ActiveServices.SERVICE_START_FOREGROUND_TIMEOUT).
        // Kept slightly under that so an expired start is never honoured after the fact.
        private const val FOREGROUND_START_DEADLINE_MS = 4_500L

        // When media3 has not promoted by itself this long after a deferred start, we post the
        // placeholder ourselves. Comfortably inside the 5s deadline, late enough that media3 wins
        // the race whenever it actually has content to show.
        private const val FOREGROUND_PROMOTE_FALLBACK_MS = 3_000L

        const val STATE_KEY = "state"
        const val ERROR_KEY  = "error"
        const val EVENT_KEY = "event"
        const val DATA_KEY = "data"
        const val TRACK_KEY = "track"
        const val NEXT_TRACK_KEY = "nextTrack"
        const val POSITION_KEY = "position"
        const val DURATION_KEY = "duration"
        const val BUFFERED_POSITION_KEY = "buffered"

        const val TASK_KEY = "TrackPlayer"

        const val MIN_BUFFER_KEY = "minBuffer"
        const val MAX_BUFFER_KEY = "maxBuffer"
        const val PLAY_BUFFER_KEY = "playBuffer"
        const val BACK_BUFFER_KEY = "backBuffer"

        const val FORWARD_JUMP_INTERVAL_KEY = "forwardJumpInterval"
        const val BACKWARD_JUMP_INTERVAL_KEY = "backwardJumpInterval"
        const val PROGRESS_UPDATE_EVENT_INTERVAL_KEY = "progressUpdateEventInterval"

        const val MAX_CACHE_SIZE_KEY = "maxCacheSize"

        const val ANDROID_OPTIONS_KEY = "android"

        const val CUSTOM_ACTIONS_KEY = "customActions"
        const val CUSTOM_ACTIONS_LIST_KEY = "customActionsList"

        const val STOPPING_APP_PAUSES_PLAYBACK_KEY = "stoppingAppPausesPlayback"
        const val APP_KILLED_PLAYBACK_BEHAVIOR_KEY = "appKilledPlaybackBehavior"
        const val AUDIO_OFFLOAD_KEY = "audioOffload"
        const val SHUFFLE_KEY = "shuffle"
        const val STOP_FOREGROUND_GRACE_PERIOD_KEY = "stopForegroundGracePeriod"
        const val PAUSE_ON_INTERRUPTION_KEY = "alwaysPauseOnInterruption"
        const val AUTO_UPDATE_METADATA = "autoUpdateMetadata"
        const val AUTO_HANDLE_INTERRUPTIONS = "autoHandleInterruptions"
        const val USE_FFT_PROCESSOR = "useFFTProcessor"
        const val ANDROID_AUDIO_CONTENT_TYPE = "androidAudioContentType"
        const val IS_FOCUS_LOSS_PERMANENT_KEY = "permanent"
        const val IS_PAUSED_KEY = "paused"

        const val PARSE_EMBEDDED_ARTWORK = "androidParseEmbeddedArtwork"
        const val HANDLE_NOISY = "androidHandleAudioBecomingNoisy"
        const val CROSSFADE = "crossfade"
        const val ALWAYS_SHOW_NEXT = "androidAlwaysShowNext"
        const val SKIP_SILENCE = "androidSkipSilence"
        const val WAKE_MODE = "androidWakeMode"

        const val AA_FOR_YOU_KEY = "for-you"
        const val AA_ROOT_KEY = "/"

        const val DEFAULT_JUMP_INTERVAL = 15.0
        const val DEFAULT_STOP_FOREGROUND_GRACE_PERIOD = 5
    }
    private lateinit var player: QueuedAudioPlayer
    // Expose the internal QueuedAudioPlayer instance for other classes (e.g. TrackPlayerVideoView)
    val audioPlayer: QueuedAudioPlayer
        get() = player
    private val binder = MusicBinder()
    private val scope = MainScope()
    private lateinit var fakePlayer: ExoPlayer
    private lateinit var mediaSession: MediaLibrarySession
    private var progressUpdateJob: Job? = null
    var mediaTree: Map<String, List<MediaItem>> = HashMap()
    var mediaTreeStyle: List<Int> = listOf(
        MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
        MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
    private var sessionCommands: SessionCommands? = null
    private var playerCommands: Player.Commands? = null
    // The session's media button preferences: every button the app declares,
    // each carrying the slots it may take. A button whose capability is not in
    // `notificationCapabilities` is marked overflow-only, which keeps it out of
    // the compact notification while Android Auto — which renders every action —
    // still shows it. One list, because the platform session the car reads is
    // built from the session-wide one; a per-controller layout never reaches it.
    private var customLayout: List<CommandButton> = listOf()
    private var customActionButtons: List<CommandButton> = listOf()
    private var sessionCapabilities: List<Capability> = emptyList()
    private var lastWake: Long = 0
    private var shuffleState: Boolean = false
    private var heartState: Boolean = false
    private var notificationCapabilities: List<Capability> = emptyList()
    var searchResults: List<MediaItem> = listOf()
    var searchBrowser: MediaSession.ControllerInfo? = null
    var searchQuery: String = ""
    var lastConnectedPackage: String = ""

    fun setEqualizerPreset(preset: Int) {
        player.setEqualizerPreset(preset)
    }

    fun getCurrentEqualizerPreset(): Int {
        return player.getCurrentEQPreset()
    }

    fun getEqualizerPresets(): List<String> {
        return player.getEqualizerPresets()
    }

    fun setLoudnessEnhance(gain: Int) {
        player.setLoudnessEnhance(gain)
    }

    // Cross-platform Equalizer Band API

    fun setEqualizerEnabled(enabled: Boolean) {
        player.setEqualizerEnabled(enabled)
    }

    fun getEqualizerEnabled(): Boolean {
        return player.getEqualizerEnabled()
    }

    fun setEqualizerBand(band: Int, gain: Float) {
        player.setEqualizerBand(band, gain)
    }

    fun setEqualizerBands(gains: List<Float>) {
        player.setEqualizerBands(gains)
    }

    fun getEqualizerBands(): List<Float> {
        return player.getEqualizerBands()
    }

    fun getEqualizerFrequencies(): List<Int> {
        return player.getEqualizerFrequencies()
    }

    fun getEqualizerBandLevelRange(): List<Float> {
        return player.getEqualizerBandLevelRange()
    }

    fun applyEqualizerPreset(presetIndex: Int) {
        player.applyEqualizerPreset(presetIndex)
    }

    fun getEqualizerPresetNames(): List<String> {
        return player.getEqualizerPresetNames()
    }

    fun resetEqualizer() {
        player.resetEqualizer()
    }

    // Audio Effects (BassBoost, Loudness, Virtualizer)

    fun setBassBoostEnabled(enabled: Boolean) {
        player.setBassBoostEnabled(enabled)
    }

    fun setBassBoostLevel(level: Float) {
        player.setBassBoostLevel(level)
    }

    fun setLoudnessEnabled(enabled: Boolean) {
        player.setLoudnessEnabled(enabled)
    }

    fun setLoudnessLevel(level: Float) {
        player.setLoudnessLevel(level)
    }

    fun setVirtualizerEnabled(enabled: Boolean) {
        player.setVirtualizerEnabled(enabled)
    }

    fun setVirtualizerLevel(level: Float) {
        player.setVirtualizerLevel(level)
    }

    fun setBalance(balance: Float) {
        player.setBalance(balance)
    }

    fun crossFadePrepare(previous: Boolean = false, seekTo: Double = 0.0) {
        player.crossFadePrepare(previous, seekTo)
    }

    fun switchExoPlayer(
        fadeDuration: Long = 2500,
        fadeInterval: Long = 20,
        fadeToVolume: Float = 1f,
        waitUntil: Long = 0
    ) {
        player.switchExoPlayer(
            fadeDuration = fadeDuration,
            fadeInterval = fadeInterval,
            fadeToVolume = fadeToVolume,
            waitUntil = waitUntil,
            playerOperation = {
                player.play()
                emitPlaybackTrackChangedEvents(null, null, 0.0)
            })

    }

    fun acquireWakeLock() { acquireWakeLockNow(this) }

    fun abandonWakeLock() { wakeLock?.release() }

    fun getBitmapLoader(): BitmapLoader {
        return mediaSession.bitmapLoader
    }

    fun getCurrentBitmap(): ListenableFuture<Bitmap>? {
        return player.exoPlayer.currentMediaItem?.mediaMetadata?.let {
            mediaSession.bitmapLoader.loadBitmapFromMetadata(
                it
            )
        }
    }

    @ExperimentalCoroutinesApi
    override fun onCreate() {
        try {
            instance = this
            Timber.plant(Timber.DebugTree())
            Timber.tag("APM").d("RNTP musicservice created.")
            fakePlayer = ExoPlayer.Builder(this).build()
            mediaSession = buildMediaSession(fakePlayer)
            registerAudioDeviceCallback()
            super.onCreate()
        } catch (e: SecurityException) {
            // Rethrow unchanged in kind — this is still fatal — but carry the binder census,
            // which is the whole diagnosis and is otherwise unobtainable. See binderCensus().
            throw SecurityException(binderCensus(), e)
        }
    }

    /**
     * Describes the process' binder object population, for attaching to a [SecurityException]
     * that escapes [onCreate].
     *
     * A SecurityException here is almost never a permission problem of ours. Once the process'
     * binder-proxy map passes its ceiling, AOSP's `BinderProxy.ProxyMap.set()` calls
     * `dumpProxyInterfaceCounts()` -> `getSortedInterfaceCounts()` ->
     * `ActivityManager.getService().enableAppFreezer(false)` — and an ordinary app is not
     * allowed to call enableAppFreezer, so that diagnostic throws. It lands on whichever
     * binder read happened to trip the threshold; creating the MediaSession is a burst of
     * fresh proxies (PendingIntent, MediaSessionManager.createSession, MediaSessionCompat),
     * which is why it keeps landing here. On API <= 35 the same condition surfaced honestly,
     * as BinderProxyMapSizeException("... BinderProxy leak?").
     *
     * So the crash reports name this service while the real fault is a binder-proxy leak
     * somewhere in the process — and AOSP's own histogram is exactly what threw, so nothing
     * about it reaches the report. These counters are cheap in-process reads (no binder call)
     * and Play vitals shows this message verbatim, which is how we find out (a) that the
     * diagnosis is right and (b) what the ceiling actually is on the OEM builds that hit it —
     * AOSP's CRASH_AT_SIZE is 25000, but a vendor build may differ.
     */
    private fun binderCensus(): String =
        "SecurityException creating MusicService — binder-proxy ceiling, not a permission. " +
            "proxies=${Debug.getBinderProxyObjectCount()} " +
            "local=${Debug.getBinderLocalObjectCount()} " +
            "deathRecipients=${Debug.getBinderDeathObjectCount()} " +
            "(AOSP CRASH_AT_SIZE=25000)"

    /**
     * Use [appKilledPlaybackBehavior] instead.
     */
    @Deprecated("This will be removed soon")
    var stoppingAppPausesPlayback = true
        private set

    enum class AppKilledPlaybackBehavior(val string: String) {
        CONTINUE_PLAYBACK("continue-playback"),
        PAUSE_PLAYBACK("pause-playback"),
        STOP_PLAYBACK_AND_REMOVE_NOTIFICATION("stop-playback-and-remove-notification")
    }

    private var appKilledPlaybackBehavior = AppKilledPlaybackBehavior.STOP_PLAYBACK_AND_REMOVE_NOTIFICATION
    private var stopForegroundGracePeriod: Int = DEFAULT_STOP_FOREGROUND_GRACE_PERIOD

    val tracks: List<Track>
        get() = player.items.map { (it as TrackAudioItem).track }

    val currentTrack: Track
        get() {
            return try {
                (player.currentItem as TrackAudioItem).track
            } catch (e: Exception) {
                Track(this, Bundle(), 0)
            }
        }

    val state
        get() = player.playerState


    val playbackError
        get() = player.playbackError

    val event
        get() = player.playerEventHolder

    var playWhenReady: Boolean
        get() = player.playWhenReady
        set(value) {
            player.playWhenReady = value
        }

    private var latestOptions: Bundle? = null
    private var compactCapabilities: List<Capability> = emptyList()
    private var commandStarted = false

    // elapsedRealtime of the last onStartCommand that the system delivered through
    // Context.startForegroundService() — i.e. a start it will kill us for if no matching
    // Service.startForeground() follows within FOREGROUND_START_DEADLINE_MS. Read (and
    // consumed) by onUpdateNotification; 0 means no start is pending.
    private var pendingForegroundStartAt = 0L

    /**
     * Whether the system is currently waiting for the Service.startForeground() that must follow
     * a Context.startForegroundService(). The timestamp self-expires so a start that never
     * reached onUpdateNotification cannot leak into a later, unrelated promotion decision.
     */
    private fun hasPendingForegroundStart(): Boolean =
        pendingForegroundStartAt != 0L &&
            SystemClock.elapsedRealtime() - pendingForegroundStartAt < FOREGROUND_START_DEADLINE_MS

    private val foregroundWatchdogHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Last-resort half of the deferred foreground start. Case 3a in onUpdateNotification only
     * helps when media3 actually asks us to update the notification; on a cold start with nothing
     * loaded yet it never does — it has no session content to render — so nobody calls
     * startForeground() and ActivityManager kills the process with
     * "Context.startForegroundService() did not then call Service.startForeground()".
     * (Reproduced on a Pixel 6a, Android 16: force-stop, then a MEDIA_BUTTON start → am_anr.)
     *
     * So we satisfy the contract ourselves with a placeholder, and let media3 take it from there.
     * It reuses media3's OWN notification id and channel — the constants are public API — so the
     * real notification REPLACES this one instead of appearing next to it. If media3 has already
     * promoted by the time this runs, it does nothing.
     */
    private val promoteForPendingStart = Runnable {
        if (isForegroundService()) return@Runnable
        try {
            val channelId = DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID
            ensureNotificationChannel(channelId)
            val placeholder = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(androidx.media3.session.R.drawable.media3_icon_circular_play)
                .setContentTitle(getString(R.string.playback_channel_name))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setOngoing(true)
                .build()
            ServiceCompat.startForeground(
                this,
                DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID,
                placeholder,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
            Timber.tag("APM").d("promoteForPendingStart: honoured the deferred foreground start")
        } catch (e: Exception) {
            // Same reasoning as the catch in onUpdateNotification: on Android 12+ a promotion the
            // system no longer considers allowed throws, and crashing here would be strictly worse
            // than the ANR we are trying to avoid.
            Timber.tag("APM").e(e, "promoteForPendingStart: could not promote")
        }
    }

    private fun ensureNotificationChannel(channelId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(channelId) != null) return
        // media3 creates this channel itself, but not necessarily before we need it on a cold
        // start. Creating an existing channel is a no-op, so this never fights media3.
        manager.createNotificationChannel(
            NotificationChannel(
                channelId,
                getString(R.string.playback_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    // Marks the brief window after a new audio output device (Bluetooth/wired/USB) becomes
    // available, during which the OS may auto-issue a play command. RemotePlay fired inside
    // this window is tagged with `autoResume: true` so JS can ignore it if the user had paused.
    private var routeChangeWindowEndAtMs: Long = 0L
    private val routeChangeWindowMs: Long = 2_000L
    private var audioDeviceCallback: AudioDeviceCallback? = null

    private val isInRouteChangeWindow: Boolean
        get() = SystemClock.elapsedRealtime() < routeChangeWindowEndAtMs

    private fun buttonPlayBundle(): Bundle = Bundle().apply {
        putBoolean("autoResume", isInRouteChangeWindow)
    }

    private fun registerAudioDeviceCallback() {
        if (audioDeviceCallback != null) return
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                val relevant = addedDevices?.any { isAutoResumeRoute(it.type) } == true
                if (relevant) {
                    routeChangeWindowEndAtMs = SystemClock.elapsedRealtime() + routeChangeWindowMs
                }
            }
        }
        am.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper()))
        audioDeviceCallback = cb
    }

    private fun unregisterAudioDeviceCallback() {
        val cb = audioDeviceCallback ?: return
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        am?.unregisterAudioDeviceCallback(cb)
        audioDeviceCallback = null
    }

    private fun isAutoResumeRoute(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.tag("APM").d("onStartCommand: ${intent?.action}, ${intent?.`package`}")

        // Media notification action buttons (media3 DefaultActionFactory) AND external /
        // Bluetooth media buttons reach the service as ACTION_MEDIA_BUTTON intents carrying
        // a KeyEvent. Handle them on EVERY onStartCommand and on ALL SDK levels. Previously
        // onMediaKeyEvent ran only when SDK < TIRAMISU, and media3's own dispatch
        // (super.onStartCommand) ran only once via the write-once `commandStarted` latch —
        // so on Android 13+ every notification button after the first service start was
        // silently dropped (next/prev/play/pause dead from the notification, while
        // hardware/BT keys still worked through onMediaButtonEvent).
        val isMediaButton = intent?.action == Intent.ACTION_MEDIA_BUTTON

        // A media button (Bluetooth, wired remote, notification button, Android Auto) that
        // arrives while the app is backgrounded reached us through media3's MediaButtonReceiver,
        // which starts this service with ContextCompat.startForegroundService(). That call opens
        // the system's 5s startForeground() deadline, so record it here — onUpdateNotification
        // must honour it (see case 3b there). Recorded before the key is dispatched: whether
        // onMediaKeyEvent consumes the key or not has no bearing on the system's deadline.
        //
        // isForegroundService() excludes the case that does NOT open a deadline: a service that
        // is already foreground satisfies the contract by definition. Without that guard a
        // background pause from a headset would raise the flag too, and case 3a would hold the
        // promotion for one update against media3's wish to demote — harmless, but a behaviour
        // change this fix has no business making.
        if (isMediaButton && !AppForegroundTracker.foregrounded && !isForegroundService()) {
            pendingForegroundStartAt = SystemClock.elapsedRealtime()
            // Arm the fallback. If media3 promotes on its own first (case 3a) the runnable is
            // cancelled there; if it never asks — the cold-start case — this is what keeps the
            // system from killing us.
            foregroundWatchdogHandler.removeCallbacks(promoteForPendingStart)
            foregroundWatchdogHandler.postDelayed(promoteForPendingStart, FOREGROUND_PROMOTE_FALLBACK_MS)
        }

        val mediaKeyConsumed = if (isMediaButton) onMediaKeyEvent(intent) == true else false

        if (!commandStarted) {
            commandStarted = true
            super.onStartCommand(intent, flags, startId)
        } else if (isMediaButton && !mediaKeyConsumed) {
            // Keycodes onMediaKeyEvent intentionally leaves unconsumed (e.g.
            // KEYCODE_MEDIA_PLAY_PAUSE / HEADSETHOOK) must still reach media3 so it can
            // apply its play/pause toggle.
            super.onStartCommand(intent, flags, startId)
        }
        return START_STICKY
    }

    @MainThread
    fun setupPlayer(playerOptions: Bundle?) {
        if (this::player.isInitialized) {
            print("Player was initialized. Prevent re-initializing again")
            return
        }
        Timber.tag("APM").d("RNTP musicservice set up")
        val fftSampleRate = playerOptions?.getDouble(USE_FFT_PROCESSOR)?.toInt() ?: 0
        val mPlayerOptions = PlayerOptions(
            crossfade = playerOptions?.getBoolean(CROSSFADE, false) ?: false,
            cacheSize = playerOptions?.getDouble(MAX_CACHE_SIZE_KEY)?.toLong() ?: 0,
            audioContentType = when(playerOptions?.getString(ANDROID_AUDIO_CONTENT_TYPE)) {
                "music" -> C.AUDIO_CONTENT_TYPE_MUSIC
                "speech" -> C.AUDIO_CONTENT_TYPE_SPEECH
                "sonification" -> C.AUDIO_CONTENT_TYPE_SONIFICATION
                "movie" -> C.AUDIO_CONTENT_TYPE_MOVIE
                "unknown" -> C.AUDIO_CONTENT_TYPE_UNKNOWN
                else -> C.AUDIO_CONTENT_TYPE_MUSIC
            },
            // Default to WAKE_MODE_NETWORK (2) — see PlayerOptions.kt for rationale.
            wakeMode = playerOptions?.getInt(WAKE_MODE, 2) ?: 2,
            handleAudioBecomingNoisy = playerOptions?.getBoolean(HANDLE_NOISY, true) ?: true,
            alwaysShowNext = playerOptions?.getBoolean(ALWAYS_SHOW_NEXT, true) ?: true,
            handleAudioFocus = playerOptions?.getBoolean(AUTO_HANDLE_INTERRUPTIONS) ?: true,
            useFFTProcessor = fftSampleRate,
            bufferOptions = BufferOptions(
                playerOptions?.getDouble(MIN_BUFFER_KEY)?.toMilliseconds()?.toInt(),
                playerOptions?.getDouble(MAX_BUFFER_KEY)?.toMilliseconds()?.toInt(),
                playerOptions?.getDouble(PLAY_BUFFER_KEY)?.toMilliseconds()?.toInt(),
                playerOptions?.getDouble(BACK_BUFFER_KEY)?.toMilliseconds()?.toInt(),
            ),

            skipSilence = playerOptions?.getBoolean(SKIP_SILENCE) ?: false
        )
        player = QueuedAudioPlayer(this@MusicService, mPlayerOptions)
        player.fftEmitter = {v -> emit(MusicEvents.FFT_UPDATED, Bundle().apply {
            // pass the raw data: putDoubleArray("data", v)
            putDoubleArray("data", v)

        })}
        // Set up callback for notPlayable tracks
        player.onNotPlayableTrackActive = { index, item ->
            val bundle = Bundle().apply {
                putInt("index", index)
                if (item is TrackAudioItem) {
                    putBundle("track", item.track.originalItem)
                }
            }
            emit(MusicEvents.PLAYBACK_NOT_PLAYABLE_TRACK_ACTIVE, bundle)
        }
        fakePlayer.release()
        // A cold-start onTaskRemoved can release the (built-once) MediaSession before we
        // reach here, while the process keeps living. Rebuild it if so — otherwise media3
        // is left holding a released session and can never refresh the notification
        // (metadata + play/pause state) even though playback itself works.
        ensureMediaSession()
        mediaSession.player = player.player
        observeEvents()
    }

    @MainThread
    fun updateOptions(options: Bundle) {
        latestOptions = options
        val androidOptions = options.getBundle(ANDROID_OPTIONS_KEY)

        if (androidOptions?.containsKey(PARSE_EMBEDDED_ARTWORK) == true) {
            player.parseEmbeddedArtwork = androidOptions.getBoolean(PARSE_EMBEDDED_ARTWORK)
        }
        if (androidOptions?.containsKey(AUDIO_OFFLOAD_KEY) == true) {
            player.setAudioOffload(androidOptions.getBoolean(AUDIO_OFFLOAD_KEY))
        }
        if (androidOptions?.containsKey(SKIP_SILENCE) == true) {
            player.skipSilence = androidOptions.getBoolean(SKIP_SILENCE)
        }

        appKilledPlaybackBehavior =
            AppKilledPlaybackBehavior::string.find(androidOptions?.getString(APP_KILLED_PLAYBACK_BEHAVIOR_KEY)) ?:
                    AppKilledPlaybackBehavior.CONTINUE_PLAYBACK

        BundleUtils.getIntOrNull(androidOptions, STOP_FOREGROUND_GRACE_PERIOD_KEY)?.let { stopForegroundGracePeriod = it }

        // TODO: This handles a deprecated flag. Should be removed soon.
        options.getBoolean(STOPPING_APP_PAUSES_PLAYBACK_KEY).let {
            stoppingAppPausesPlayback = options.getBoolean(STOPPING_APP_PAUSES_PLAYBACK_KEY)
            if (stoppingAppPausesPlayback) {
                appKilledPlaybackBehavior = AppKilledPlaybackBehavior.PAUSE_PLAYBACK
            }
        }

        player.alwaysPauseOnInterruption = androidOptions?.getBoolean(PAUSE_ON_INTERRUPTION_KEY) ?: false
        val newShuffleState = androidOptions?.getBoolean(SHUFFLE_KEY) ?: false
        // Don't set player.shuffleMode - shuffle is managed by JS layer through queue reordering
        // Only track shuffleState for notification icon display
        shuffleState = newShuffleState

        // Update heart state if provided
        if (androidOptions?.containsKey("heartState") == true) {
            heartState = androidOptions.getBoolean("heartState")
        }

        // setup progress update events if configured
        progressUpdateJob?.cancel()
        val updateInterval = BundleUtils.getDoubleOrNull(options, PROGRESS_UPDATE_EVENT_INTERVAL_KEY)
        if (updateInterval != null && updateInterval > 0) {
            progressUpdateJob = scope.launch {
                progressUpdateEventFlow(updateInterval).collect { emit(MusicEvents.PLAYBACK_PROGRESS_UPDATED, it) }
            }
        }

        val capabilities = options.getIntegerArrayList("capabilities")?.map { Capability.entries[it] } ?: emptyList()
        notificationCapabilities = options.getIntegerArrayList("notificationCapabilities")?.map { Capability.entries[it] } ?: emptyList()
        compactCapabilities = options.getIntegerArrayList("compactCapabilities")?.map { Capability.entries[it] } ?: emptyList()
        val customActions = options.getBundle(CUSTOM_ACTIONS_KEY)
        val customActionsList = customActions?.getStringArrayList(CUSTOM_ACTIONS_LIST_KEY)
        if (notificationCapabilities.isEmpty()) notificationCapabilities = capabilities

        val playerCommandsBuilder = Player.Commands.Builder().addAll(
            // HACK: without COMMAND_GET_CURRENT_MEDIA_ITEM, notification cannot be created
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TRACKS,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_GET_AUDIO_ATTRIBUTES,
            Player.COMMAND_GET_VOLUME,
            Player.COMMAND_GET_DEVICE_VOLUME,
            Player.COMMAND_GET_TEXT,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SET_MEDIA_ITEM,
            Player.COMMAND_PREPARE,
            Player.COMMAND_RELEASE,
            Player.COMMAND_CHANGE_MEDIA_ITEMS,
        )
        notificationCapabilities.forEach {
            when (it) {
                Capability.PLAY, Capability.PAUSE -> {
                    playerCommandsBuilder.add(Player.COMMAND_PLAY_PAUSE)
                }
                Capability.STOP -> {
                    playerCommandsBuilder.add(Player.COMMAND_STOP)
                }
                Capability.SKIP_TO_NEXT -> {
                    playerCommandsBuilder.add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    playerCommandsBuilder.add(Player.COMMAND_SEEK_TO_NEXT)
                }
                Capability.SKIP_TO_PREVIOUS -> {
                    playerCommandsBuilder.add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    playerCommandsBuilder.add(Player.COMMAND_SEEK_TO_PREVIOUS)
                }
                Capability.JUMP_FORWARD -> {
                    playerCommandsBuilder.add(Player.COMMAND_SEEK_FORWARD)
                }
                Capability.JUMP_BACKWARD -> {
                    playerCommandsBuilder.add(Player.COMMAND_SEEK_BACK)
                }
                Capability.SEEK_TO -> {
                    playerCommandsBuilder.add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                }
                else -> { }
            }
        }
        customActionButtons = customActionsList?.map {
                v -> CustomButton(
            displayName = v,
            sessionCommand = v,
            iconRes = BundleUtils.getCustomIcon(
                this,
                customActions,
                v,
                TrackPlayerR.drawable.ifl_24px
            )
        ).commandButton
        } ?: listOf()

        sessionCapabilities = capabilities
        customLayout = buildCustomButtons()

        val sessionCommandsBuilder = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
        customLayout.forEach {
                v ->
            v.sessionCommand?.let { sessionCommandsBuilder.add(it) }
        }

        sessionCommands = sessionCommandsBuilder.build()
        playerCommands = playerCommandsBuilder.build()

        applyMediaButtons()

        // Use safe call to avoid race condition
        mediaSession.mediaNotificationControllerInfo?.let { controllerInfo ->
            // https://github.com/androidx/media/blob/c35a9d62baec57118ea898e271ac66819399649b/demos/session_service/src/main/java/androidx/media3/demo/session/DemoMediaLibrarySessionCallback.kt#L107
            sessionCommands?.let { sc ->
                playerCommands?.let { pc ->
                    mediaSession.setAvailableCommands(controllerInfo, sc, pc)
                }
            }
        }
    }

    // Every declared button, with the slots it may take. `capabilities` decides
    // whether a button exists at all; `notificationCapabilities` only decides
    // whether it may take a notification slot — a button missing from it is
    // overflow-only, so the notification stays plain transport while the car
    // still gets the button.
    @MainThread
    private fun buildCustomButtons(): List<CommandButton> {
        val buttons = customActionButtons.toMutableList()

        // Add heart button if SetRating capability is present
        if (sessionCapabilities.contains(Capability.SET_RATING)) {
            val heartIcon = if (heartState) TrackPlayerR.drawable.heart_24px else TrackPlayerR.drawable.hearte_24px
            buttons.add(0, CustomButton(
                displayName = "Heart",
                sessionCommand = "heart",
                iconRes = heartIcon,
                slots = slotsFor(Capability.SET_RATING)
            ).commandButton)
        }

        // Add shuffle button if capability is present
        if (sessionCapabilities.contains(Capability.SHUFFLE)) {
            val shuffleIcon = if (shuffleState) TrackPlayerR.drawable.shuffle_on_24px else TrackPlayerR.drawable.shuffle_24px
            buttons.add(0, CustomButton(
                displayName = "Shuffle",
                sessionCommand = "shuffle",
                iconRes = shuffleIcon,
                slots = slotsFor(Capability.SHUFFLE)
            ).commandButton)
        }

        return buttons
    }

    private fun slotsFor(capability: Capability): IntArray =
        if (notificationCapabilities.contains(capability)) intArrayOf()
        else intArrayOf(CommandButton.SLOT_OVERFLOW)

    // Android Auto connects as a LEGACY controller (controllerVersion 0), so its
    // buttons come from the platform session's PlaybackState custom actions,
    // which media3 builds from the SESSION-WIDE preferences — a per-controller
    // layout never reaches it.
    @MainThread
    private fun applyMediaButtons() {
        if (!::mediaSession.isInitialized) return
        try {
            mediaSession.setMediaButtonPreferences(customLayout)
        } catch (e: Exception) {
            // Ignore errors in button update - the layout is non-critical
        }
    }

    @MainThread
    private fun progressUpdateEventFlow(interval: Double) = flow {
        while (true) {
            if (player.isPlaying) {
                val bundle = progressUpdateEvent()
                emit(bundle)
            }

            delay((interval * 1000).toLong())
        }
    }

    @MainThread
    private suspend fun progressUpdateEvent(): Bundle {
        return withContext(Dispatchers.Main) {
            Bundle().apply {
                putDouble(POSITION_KEY, player.position.toSeconds())
                putDouble(DURATION_KEY, player.duration.toSeconds())
                putDouble(BUFFERED_POSITION_KEY, player.bufferedPosition.toSeconds())
                putInt(TRACK_KEY, player.currentIndex)
            }
        }
    }

    @MainThread
    fun add(track: Track) {
        add(listOf(track))
    }

    @MainThread
    fun add(tracks: List<Track>) {
        val items = tracks.map { it.toAudioItem() }
        player.add(items)
    }

    @MainThread
    fun add(tracks: List<Track>, atIndex: Int) {
        val items = tracks.map { it.toAudioItem() }
        player.add(items, atIndex)
    }

    @MainThread
    fun load(track: Track) {
        player.load(track.toAudioItem())
    }

    @MainThread
    fun move(fromIndex: Int, toIndex: Int) {
        player.move(fromIndex, toIndex)
    }

    @MainThread
    fun remove(index: Int) {
        remove(listOf(index))
    }

    @MainThread
    fun remove(indexes: List<Int>) {
        player.remove(indexes)
    }

    @MainThread
    fun clear() {
        player.clear()
    }

    @MainThread
    fun play() {
        player.play()
    }

    @MainThread
    fun pause() {
        player.pause()
    }

    @MainThread
    fun stop() {
        player.stop()
    }

    @MainThread
    fun removeUpcomingTracks() {
        player.removeUpcomingItems()
    }

    @MainThread
    fun removePreviousTracks() {
        player.removePreviousItems()
    }

    @MainThread
    fun skip(index: Int) {
        player.jumpToItem(index)
    }

    @MainThread
    fun skipToNext() {
        player.next()
    }

    @MainThread
    fun skipToPrevious() {
        player.previous()
    }

    @MainThread
    fun seekTo(seconds: Float) {
        player.seek((seconds * 1000).toLong(), TimeUnit.MILLISECONDS)
    }

    @MainThread
    fun seekBy(offset: Float) {
        player.seekBy((offset.toLong()), TimeUnit.SECONDS)
    }

    @MainThread
    fun retry() {
        player.prepare()
    }

    @MainThread
    fun getCurrentTrackIndex(): Int = player.currentIndex

    @MainThread
    fun getRate(): Float = player.playbackSpeed

    @MainThread
    fun setRate(value: Float) {
        player.playbackSpeed = value
    }

    @MainThread
    fun getPitch(): Float = player.playbackPitch

    @MainThread
    fun setPitch(value: Float) {
        player.playbackPitch = value
    }

    @MainThread
    fun getRepeatMode(): RepeatMode = player.repeatMode

    @MainThread
    fun setRepeatMode(value: RepeatMode) {
        player.repeatMode = value
    }

    @MainThread
    fun setShuffleState(enabled: Boolean) {
        if (shuffleState != enabled) {
            shuffleState = enabled
            updateCustomLayout()
        }
    }

    @MainThread
    fun setHeartState(saved: Boolean) {
        if (heartState != saved) {
            heartState = saved
            updateCustomLayout()
        }
    }

    @MainThread
    private fun updateCustomLayout() {
        // Check if mediaSession is initialized before accessing it
        if (!::mediaSession.isInitialized) return

        try {
            customLayout = buildCustomButtons()
            applyMediaButtons()
        } catch (e: Exception) {
            // Ignore errors in custom layout update - notification is non-critical
        }
    }

    @MainThread
    fun getVolume(): Float = player.volume

    @MainThread
    fun setVolume(value: Float) {
        player.volume = value
    }

    @MainThread
    fun setAnimatedVolume(value: Float, duration: Long = 500L, interval: Long = 20L, emitEventMsg: String = ""): Deferred<Unit> {
        val eventMsgBundle = Bundle()
        eventMsgBundle.putString(DATA_KEY, emitEventMsg)
        return player.fadeVolume(value, duration, interval) {
            emit(
                MusicEvents.PLAYBACK_ANIMATED_VOLUME_CHANGED,
                eventMsgBundle
            )
        }
    }

    fun fadeOutPause (duration: Long = 500L, interval: Long = 20L) {
        player.fadeVolume(0f, duration, interval) {
            player.pause()
        }
    }

    fun fadeOutNext (duration: Long = 500L, interval: Long = 20L, toVolume: Float = 1f) {
        player.fadeVolume(0f, duration, interval) {
            player.next()
            player.fadeVolume(toVolume, duration, interval)
        }
    }

    fun fadeOutPrevious (duration: Long = 500L, interval: Long = 20L, toVolume: Float = 1f) {
        player.fadeVolume(0f, duration, interval) {
            player.previous()
            player.fadeVolume(toVolume, duration, interval)
        }
    }

    fun fadeOutJump (index: Int, duration: Long = 500L, interval: Long = 20L, toVolume: Float = 1f) {
        player.fadeVolume(0f, duration, interval) {
            player.jumpToItem(index)
            player.fadeVolume(toVolume, duration, interval)
        }
    }
    @MainThread
    fun getDurationInSeconds(): Double = player.duration.toSeconds()

    @MainThread
    fun getPositionInSeconds(): Double = player.position.toSeconds()

    @MainThread
    fun getBufferedPositionInSeconds(): Double = player.bufferedPosition.toSeconds()

    @MainThread
    fun getPlayerStateBundle(state: AudioPlayerState): Bundle {
        val bundle = Bundle()
        bundle.putString(STATE_KEY, state.asLibState.state)
        if (state == AudioPlayerState.ERROR) {
            bundle.putBundle(ERROR_KEY, getPlaybackErrorBundle())
        }
        return bundle
    }

    @MainThread
    fun updateMetadataForTrack(index: Int, track: Track) {
        player.replaceItem(index, track.toAudioItem())
    }

    /**
     * Merge metadata into the CURRENT track instead of replacing it.
     *
     * The old signature took a Track the module had just built out of the metadata bundle
     * (title/artist/artwork) and handed it to replaceItem. That bundle carries no `url`, so
     * the queue item lost its source: the player kept going for a few seconds on what was
     * already buffered, then stopped with nothing to reload — and since artwork/title
     * refreshes happen all the time (async cover load, now-playing refresh), it hit at
     * random and looked like "playback dies in the background". Traced on eSound 5.0.21
     * with a Track logged as `mediaId=null url=null` appearing in the queue milliseconds
     * before a fatal `Source error`.
     *
     * `Track.setMetadata` merges, so the uri, headers and flags of the live item survive.
     */
    @MainThread
    fun updateNowPlayingMetadata(context: Context, metadata: Bundle) {
        val index = player.currentIndex
        val current = tracks.getOrNull(index) ?: return
        current.setMetadata(context, metadata, 0)
        updateMetadataForTrack(index, current)
    }

    @MainThread
    fun setTrackPlayable(index: Int, playable: Boolean) {
        val track = tracks.getOrNull(index) ?: return
        val wasNotPlayable = track.notPlayable
        track.notPlayable = !playable
        player.replaceItem(index, track.toAudioItem())

        // If current track: notPlayable -> playable, load it
        if (wasNotPlayable && playable && player.currentIndex == index) {
            player.load(track.toAudioItem())
        }
        // If current track: playable -> notPlayable, stop and emit event
        else if (!wasNotPlayable && !playable && player.currentIndex == index) {
            player.stop()
            emit(MusicEvents.PLAYBACK_NOT_PLAYABLE_TRACK_ACTIVE, Bundle().apply {
                putInt("index", index)
                putBundle("track", track.originalItem)
            })
        }
    }

    @MainThread
    fun clearNotificationMetadata() {
    }

    private fun emitPlaybackTrackChangedEvents(
        index: Int?,
        previousIndex: Int?,
        oldPosition: Double
    ) {
        val a = Bundle()
        a.putDouble(POSITION_KEY, oldPosition)
        if (index != null) {
            a.putInt(NEXT_TRACK_KEY, index)
        }

        if (previousIndex != null) {
            a.putInt(TRACK_KEY, previousIndex)
        }

        emit(MusicEvents.PLAYBACK_TRACK_CHANGED, a)

        val b = Bundle()
        b.putDouble("lastPosition", oldPosition)
        if (tracks.isNotEmpty()) {
            b.putInt("index", player.currentIndex)
            b.putBundle("track", tracks[player.currentIndex].originalItem)
            if (previousIndex != null) {
                b.putInt("lastIndex", previousIndex)
                b.putBundle("lastTrack", tracks[previousIndex].originalItem)
            }
        }
        emit(MusicEvents.PLAYBACK_ACTIVE_TRACK_CHANGED, b)
    }

    private fun emitQueueEndedEvent() {
        val bundle = Bundle()
        bundle.putInt(TRACK_KEY, player.currentIndex)
        bundle.putDouble(POSITION_KEY, player.position.toSeconds())
        emit(MusicEvents.PLAYBACK_QUEUE_ENDED, bundle)
    }

    /**
     * Whether this service is currently running as a foreground service.
     *
     * Backed by media3's own bookkeeping ([MediaSessionService.isPlaybackOngoing] returns
     * `MediaNotificationManager.isStartedInForeground`), which is authoritative here: media3 is
     * the only code that ever promotes or demotes this service — the flag is set right after its
     * `Service.startForeground()` and cleared in its `stopForeground()`.
     *
     * It deliberately does NOT ask ActivityManager. The previous implementation scanned
     * `ActivityManager.getRunningServices()`, a synchronous binder call into system_server (it
     * takes the AMS lock) issued from the main thread on every notification update — i.e. on
     * every play/pause, track change and metadata change. On slow devices, or whenever
     * system_server is under contention, that call is exactly the kind of main-thread block that
     * produces an ANR. This reads a boolean field in our own process instead.
     */
    fun isForegroundService(): Boolean = isPlaybackOngoing()

    @MainThread
    private fun observeEvents() {
        scope.launch {
            event.stateChange.collect {
                emit(MusicEvents.PLAYBACK_STATE, getPlayerStateBundle(it))

                if (it == AudioPlayerState.ENDED && player.nextItem == null) {
                    emitQueueEndedEvent()
                }
            }
        }

        scope.launch {
            event.audioItemTransition.collect {
                if (it !is AudioItemTransitionReason.REPEAT) {
                    emitPlaybackTrackChangedEvents(
                        player.currentIndex,
                        player.previousIndex,
                        (it?.oldPosition ?: 0).toSeconds()
                    )
                }
            }
        }

        scope.launch {
            event.onAudioFocusChanged.collect {
                Bundle().apply {
                    putBoolean(IS_FOCUS_LOSS_PERMANENT_KEY, it.isFocusLostPermanently)
                    putBoolean(IS_PAUSED_KEY, it.isPaused)
                    emit(MusicEvents.BUTTON_DUCK, this)
                }
            }
        }

        scope.launch {
            event.onPlayerActionTriggeredExternally.collect {
                when (it) {
                    is MediaSessionCallback.RATING -> {
                        Bundle().apply {
                            setRating(this, "rating", it.rating)
                            emit(MusicEvents.BUTTON_SET_RATING, this)
                        }
                    }
                    is MediaSessionCallback.SEEK -> {
                        Bundle().apply {
                            putDouble("position", it.positionMs.toSeconds())
                            emit(MusicEvents.BUTTON_SEEK_TO, this)
                        }
                    }
                    MediaSessionCallback.PLAY -> emit(MusicEvents.BUTTON_PLAY, buttonPlayBundle())
                    MediaSessionCallback.PAUSE -> emit(MusicEvents.BUTTON_PAUSE)
                    MediaSessionCallback.NEXT -> emit(MusicEvents.BUTTON_SKIP_NEXT)
                    MediaSessionCallback.PREVIOUS -> emit(MusicEvents.BUTTON_SKIP_PREVIOUS)
                    MediaSessionCallback.STOP -> emit(MusicEvents.BUTTON_STOP)
                    MediaSessionCallback.FORWARD -> {
                        Bundle().apply {
                            val interval = latestOptions?.getDouble(FORWARD_JUMP_INTERVAL_KEY, DEFAULT_JUMP_INTERVAL) ?:
                            DEFAULT_JUMP_INTERVAL
                            putInt("interval", interval.toInt())
                            emit(MusicEvents.BUTTON_JUMP_FORWARD, this)
                        }
                    }
                    MediaSessionCallback.REWIND -> {
                        Bundle().apply {
                            val interval = latestOptions?.getDouble(BACKWARD_JUMP_INTERVAL_KEY, DEFAULT_JUMP_INTERVAL) ?:
                            DEFAULT_JUMP_INTERVAL
                            putInt("interval", interval.toInt())
                            emit(MusicEvents.BUTTON_JUMP_BACKWARD, this)
                        }
                    }

                    is MediaSessionCallback.PLAY_FROM_ID -> {
                        Bundle().apply {
                            putString("id", it.mediaId)
                            emit(MusicEvents.BUTTON_PLAY_FROM_ID, this)
                        }
                    }
                    is MediaSessionCallback.CUSTOMACTION -> {
                        when (it.customAction) {
                            "shuffle" -> emit(MusicEvents.BUTTON_SHUFFLE)
                            "heart" -> emit(MusicEvents.BUTTON_SET_RATING, Bundle())
                            else -> Bundle().apply {
                                putString("customAction", it.customAction)
                                emit(MusicEvents.BUTTON_CUSTOM_ACTION, this)
                            }
                        }
                    }
                }
            }
        }

        scope.launch {
            event.onTimedMetadata.collect {
                val data = MetadataAdapter.fromMetadata(it)
                val bundle = Bundle().apply {
                    putParcelableArrayList(METADATA_PAYLOAD_KEY, ArrayList(data))
                }
                emit(MusicEvents.METADATA_TIMED_RECEIVED, bundle)

                // TODO: Handle the different types of metadata and publish to new events
                val metadata = PlaybackMetadata.fromId3Metadata(it)
                    ?: PlaybackMetadata.fromIcy(it)
                    ?: PlaybackMetadata.fromVorbisComment(it)
                    ?: PlaybackMetadata.fromQuickTime(it)

                if (metadata != null) {
                    Bundle().apply {
                        putString("source", metadata.source)
                        putString("title", metadata.title)
                        putString("url", metadata.url)
                        putString("artist", metadata.artist)
                        putString("album", metadata.album)
                        putString("date", metadata.date)
                        putString("genre", metadata.genre)
                        emit(MusicEvents.PLAYBACK_METADATA, this)
                    }
                }
            }
        }

        scope.launch {
            event.onCommonMetadata.collect {
                val data = MetadataAdapter.fromMediaMetadata(it)
                val bundle = Bundle().apply {
                    putBundle(METADATA_PAYLOAD_KEY, data)
                }
                emit(MusicEvents.METADATA_COMMON_RECEIVED, bundle)
            }
        }

        scope.launch {
            event.playWhenReadyChange.collect {
                Bundle().apply {
                    putBoolean("playWhenReady", it.playWhenReady)
                    // Why the pause happened, which is the difference between "the item
                    // finished" and "a call took audio focus". media3 tells us
                    // (PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) and AudioPlayer
                    // already computes it, but it stopped here: the JS side only ever saw a
                    // bare `playWhenReady:false` and had to guess the end of a track from how
                    // close the last known position was to the reported duration. That guess
                    // is wrong whenever a stream ends short of its declared length, and the
                    // queue died with the track finished. Pass the reason through.
                    putBoolean("pausedBecauseReachedEnd", it.pausedBecauseReachedEnd)
                    // And why else it paused: the audio route disappearing is the one
                    // reason after which nothing should quietly resume.
                    putBoolean("pausedBecauseBecameNoisy", it.pausedBecauseBecameNoisy)
                    emit(MusicEvents.PLAYBACK_PLAY_WHEN_READY_CHANGED, this)
                }
            }
        }

        scope.launch {
            event.playbackError.collect {
                emit(MusicEvents.PLAYBACK_ERROR, getPlaybackErrorBundle())
            }
        }
    }

    private fun getPlaybackErrorBundle(): Bundle {
        val bundle = Bundle()
        val error = playbackError
        if (error?.message != null) {
            bundle.putString("message", error.message)
        }
        if (error?.code != null) {
            bundle.putString("code", "android-" + error.code)
        }
        return bundle
    }

    @SuppressLint("VisibleForTests")
    @MainThread
    fun emit(event: String, data: Bundle? = null) {
        reactContext?.emitDeviceEvent(event, data?.let { Arguments.fromBundle(it) })
    }

    @SuppressLint("VisibleForTests")
    @MainThread
    private fun emitList(event: String, data: List<Bundle> = emptyList()) {
        val payload = Arguments.createArray()
        data.forEach { payload.pushMap(Arguments.fromBundle(it)) }

        reactContext?.emitDeviceEvent(event, payload)
    }

    override fun getTaskConfig(intent: Intent?): HeadlessJsTaskConfig {
        return HeadlessJsTaskConfig(TASK_KEY, Arguments.createMap(), 0, true)
    }

    @MainThread
    override fun onBind(intent: Intent?): IBinder? {
        val intentAction = intent?.action
        Timber.tag("APM").d("onbind: $intentAction")
        return if (intentAction != null) {
            super.onBind(intent)
        } else {
            binder
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Timber.tag("APM").d("unbind: ${intent?.action}")
        return super.onUnbind(intent)
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        // Foreground-service promotion policy. Three cases, because the illegal
        // operation on Android 12+ is *starting* a foreground service from the
        // background — not keeping (or updating) one that is already running:
        //
        //  1. App foregrounded → force promotion. This is the androidx/media #843
        //     workaround (https://github.com/androidx/media/issues/843#issuecomment-1860555950):
        //     media3 sometimes fails to promote and the service gets killed.
        //  2. App backgrounded and we ALREADY are a foreground service → follow media3's
        //     own signal. While it is playing that signal is `true`, so the service stays
        //     foreground for the whole background session; when playback pauses media3
        //     passes `false` and we demote as usual. Calling startForeground() on a service
        //     that is already foreground is an update, not a background start, so this
        //     cannot throw ForegroundServiceStartNotAllowedException.
        //  3a. App backgrounded, NOT a foreground service, but the system is WAITING for a
        //     startForeground() it already asked for → promote. media3's MediaButtonReceiver
        //     starts this service with ContextCompat.startForegroundService() when a media
        //     button arrives in the background; from that moment the promotion is not a
        //     background start at all, it is the second half of one the system initiated, and
        //     ActivityManager kills us after 5s if it never comes ("Context.startForegroundService()
        //     did not then call Service.startForeground()"). The old policy could not see this
        //     case: `foregrounded` is false and isForegroundService() is still false, because
        //     playback has not begun — the button *was* the request to begin it — so it fell into
        //     case 3 and silently starved the start. That is the residual ANR on every version.
        //  3b. App backgrounded and NOT a foreground service → never promote. This is the
        //     illegal start that crashed (bump 4.1.57): media3 loads the notification's
        //     album art asynchronously (DefaultMediaNotificationProvider's
        //     OnBitmapLoadedFutureCallback) and, once the bitmap arrives, calls
        //     Context.startForegroundService() from the background on a deferred
        //     main-looper callback that ESCAPES the try/catch below — media3 1.8.0 neither
        //     guards that path nor routes it to onForegroundServiceStartNotAllowedException()
        //     (that listener only fires from the MediaButtonReceiver start path).
        //
        // Case 2 is what 4.1.57 got wrong by passing a flat `foregrounded`: it demoted a
        // healthy, *playing* foreground service ~30s into every background session. The
        // process then fell to the cached bucket (oom_score_adj 700) and Android froze it,
        // so JS stopped running — media-button skips queued up and were delivered in a
        // burst minutes later, the queue never auto-advanced, and JS timers never fired.
        // Measured on a Pixel 6a: FGS held at +3s/+10s, gone by +30s while still PLAYING.
        //
        // Note the shape of the expression below: every branch can only turn `required` from
        // false to TRUE. Nothing here can demote a service that media3 wants foreground — that
        // is what 4.1.57 got wrong, and the freeze it caused must not come back.
        val pendingForegroundStart = hasPendingForegroundStart()
        val required = if (AppForegroundTracker.foregrounded) {
            true
        } else if (pendingForegroundStart) {
            true
        } else {
            isForegroundService() && startInForegroundRequired
        }
        // Honoured exactly once, whatever super does with it below: if the promotion succeeds the
        // deadline is satisfied and the flag is stale; if it throws, retrying on a later
        // notification update would be a genuine background start, which is the illegal one.
        if (pendingForegroundStart) pendingForegroundStartAt = 0L
        // media3 is handling the promotion itself, so the placeholder is not needed.
        if (required) foregroundWatchdogHandler.removeCallbacks(promoteForPendingStart)
        try {
            super.onUpdateNotification(session, required)
        } catch (e: IllegalStateException) {
            // On Android 12+ media3 may still try to promote the notification to a foreground
            // service while the app is backgrounded/restricted, throwing
            // ForegroundServiceStartNotAllowedException (a subclass of IllegalStateException).
            // Swallow it instead of crashing; the notification simply isn't promoted to FGS.
            Timber.tag("APM").e(e, "onUpdateNotification: foreground start not allowed")
        }
    }

    // Guards against releasing the MediaSession more than once. androidx.media3 throws
    // IllegalArgumentException("session is already released") on a double release. This
    // happened because onTaskRemoved() releases the session and then calls onDestroy(),
    // which released it again (and the system may also invoke onDestroy() on its own).
    private var isMediaSessionReleased = false

    // Set once the service is being deliberately torn down (STOP_PLAYBACK_AND_REMOVE_NOTIFICATION
    // path or onDestroy). While shutting down we must NOT rebuild the MediaSession: a system
    // MediaController (SystemUI / Assistant / Bluetooth) reconnecting mid-teardown calls
    // onGetSession(), and rebuilding there resurrects a zombie session on the fakePlayer in a
    // dying service — the controllers keep re-poking it, pinning the CPU (Samsung "Excessive CPU").
    @Volatile
    private var isShuttingDown = false

    @MainThread
    private fun releaseMediaSession() {
        if (isMediaSessionReleased || !::mediaSession.isInitialized) return
        isMediaSessionReleased = true
        try {
            mediaSession.release()
        } catch (e: IllegalStateException) {
            // Already released elsewhere — safe to ignore.
        } catch (e: IllegalArgumentException) {
            // "session is already released" — safe to ignore.
        }
    }

    private fun buildMediaSession(forPlayer: Player): MediaLibrarySession {
        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            // addFlags (not `flags =`) so the launcher intent's own
            // FLAG_ACTIVITY_NEW_TASK is preserved — overwriting it made the
            // notification-body tap unreliable on Android 15 / OEM (86cagqyc9).
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            // Add the Uri data so apps can identify that it was a notification click
            data = "trackplayer://notification.click".toUri()
            action = Intent.ACTION_VIEW
        }
        return MediaLibrarySession.Builder(this, forPlayer, APMMediaSessionCallback())
            .setBitmapLoader(CacheBitmapLoader(CoilBitmapLoader(this)))
            // https://github.com/androidx/media/issues/1218
            .setSessionActivity(PendingIntent.getActivity(this, 0, openAppIntent, getPendingIntentFlags()))
            .build()
    }

    // The MediaSession is built once in onCreate(). A spurious onTaskRemoved (e.g. an
    // aggressive OEM relaunching a stale task during a cold start, before setupPlayer())
    // releases it while the process keeps living, permanently orphaning it: media3 then
    // holds a released session and silently drops every notification metadata / playback
    // state update, even though the ExoPlayer set up afterwards still plays audio and
    // handles button presses. Rebuild the session whenever it is needed but has been
    // released so media3 always has a live session to drive the notification.
    @MainThread
    private fun ensureMediaSession() {
        // Never resurrect the session while tearing the service down — that is the zombie-rebuild
        // churn (session released by onTaskRemoved, then rebuilt from a reconnecting controller's
        // onGetSession, spinning the CPU). The cold-start recovery this method exists for only runs
        // while the service is alive (setupPlayer / a live controller), where isShuttingDown is false.
        if (isShuttingDown) return
        if (::mediaSession.isInitialized && !isMediaSessionReleased) return
        val forPlayer: Player = if (::player.isInitialized) player.player else fakePlayer
        mediaSession = buildMediaSession(forPlayer)
        isMediaSessionReleased = false
    }

    @MainThread
    override fun onTaskRemoved(rootIntent: Intent?) {
        onUnbind(rootIntent)
        Timber
            .tag("APM")
            .d("onTaskRemoved: ${::player.isInitialized}, $appKilledPlaybackBehavior")
        if (!::player.isInitialized) {
            releaseMediaSession()
            return
        }

        when (appKilledPlaybackBehavior) {
            AppKilledPlaybackBehavior.PAUSE_PLAYBACK -> player.pause()
            AppKilledPlaybackBehavior.STOP_PLAYBACK_AND_REMOVE_NOTIFICATION -> {
                Timber.tag("APM").d("onTaskRemoved: Killing service")
                // Mark shutdown BEFORE releasing so a controller reconnecting mid-teardown
                // (onGetSession) can't rebuild a zombie session and pin the CPU.
                isShuttingDown = true
                releaseMediaSession()
                player.clear()
                player.stop()
                // HACK: the service first stops, then starts, then call onTaskRemove. Why system
                // registers the service being restarted?
                player.destroy()
                scope.cancel()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                onDestroy()
                // https://github.com/androidx/media/issues/27#issuecomment-1456042326
                stopSelf()
                // Kill the process outright instead of exitProcess(0)/System.exit(0).
                // System.exit() tears the ART runtime down, and that teardown blocks in
                // ThreadList::WaitForOtherNonDaemonThreadsToExit until EVERY non-daemon
                // thread has ended. Any library thread that never ends (image/HTTP pools,
                // billing, an app's own executor) leaves the process alive with no main
                // thread — a zombie that still owns the package's broadcast receivers, so
                // every later broadcast delivered to it ANRs. eSound 5.0.20 Play dumps show
                // exactly that: no main thread, tid=1 parked in
                // WaitForOtherNonDaemonThreadsToExit, and repeated ANRs on the widget's
                // APPWIDGET_UPDATE. killProcess() cannot block: the process is gone at once.
                // Trade-off: shutdown hooks do not run — nothing here relies on them, and
                // the process is being destroyed on purpose anyway.
                android.os.Process.killProcess(android.os.Process.myPid())
            }
            else -> {}
        }
    }

    @SuppressLint("VisibleForTests")
    private fun selfWake(clientPackageName: String): Boolean {
        // FORK PATCH: AVOID STARTING APP IN FOREGROUND, PREFER STARTING HEADLESS
        return false

//        val reactActivity = reactContext?.currentActivity
//        if (
//        // HACK: validate reactActivity is present; if not, send wake intent
//            (reactActivity == null || reactActivity.isDestroyed)
//            && Settings.canDrawOverlays(this)
//        ) {
//            val currentTime = System.currentTimeMillis()
//            if (currentTime - lastWake < 100000) {
//                return false
//            }
//            lastWake = currentTime
//            val activityIntent = packageManager.getLaunchIntentForPackage(packageName)
//            activityIntent!!.data = "trackplayer://service-bound".toUri()
//            activityIntent.action = Intent.ACTION_VIEW
//            activityIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
//            var activityOptions = ActivityOptions.makeBasic()
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
//                activityOptions = activityOptions.setPendingIntentBackgroundActivityStartMode(
//                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
//            }
//            this.startActivity(activityIntent, activityOptions.toBundle())
//            return true
//        }
//        return false
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession {
        Timber.tag("APM").d("onGetSession: ${controllerInfo.packageName}")
        ensureMediaSession()
        return mediaSession
    }

    fun notifyChildrenChanged() {
        mediaSession.connectedControllers.forEach {
                controller ->
            mediaTree.forEach {
                    it -> mediaSession.notifyChildrenChanged(controller, it.key, it.value.size, null)
            }

        }
    }

    @MainThread
    override fun onHeadlessJsTaskFinish(taskId: Int) {
        // This is empty so ReactNative doesn't kill this service
    }

    @MainThread
    override fun onDestroy() {
        Timber.tag("APM").d("RNTP service is destroyed.")
        // Prevent any late onGetSession() from rebuilding a zombie session during/after destroy.
        isShuttingDown = true
        // A pending placeholder promotion must not fire against a dying service.
        foregroundWatchdogHandler.removeCallbacks(promoteForPendingStart)
        pendingForegroundStartAt = 0L
        unregisterAudioDeviceCallback()
        if (::player.isInitialized) {
            // moved down ->
            // mediaSession.release()
            player.destroy()
        }

        // FORK PATCH
        // -> Attempt to fix https://github.com/doublesymmetry/react-native-track-player/issues/2485
        releaseMediaSession()

        instance = null
        progressUpdateJob?.cancel()
        super.onDestroy()
    }

    fun onMediaKeyEvent(intent: Intent?): Boolean? {
        val keyEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            intent?.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
        }

        if (keyEvent?.action == KeyEvent.ACTION_DOWN) {
            return when (keyEvent.keyCode) {
                // Do NOT consume single-button media keys (play/pause + wired headset hook).
                // Returning null delegates to media3's super.onMediaButtonEvent, which applies
                // its built-in single/double/triple-tap -> playPause/seekToNext/seekToPrevious.
                // Those route back to JS via the APMForwardingPlayer overrides. Consuming them
                // here (return true) bypassed media3 and broke double-tap-to-skip on any
                // earbud/AVRCP device that sends PLAY_PAUSE instead of NEXT/PREVIOUS.
                // (Mirrors the old MediaSessionCompat behavior, which let the framework
                // translate raw headset clicks into onSkipToNext/onSkipToPrevious.)
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_HEADSETHOOK -> null
                KeyEvent.KEYCODE_MEDIA_STOP -> {
                    emit(MusicEvents.BUTTON_STOP)
                    true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    emit(MusicEvents.BUTTON_PAUSE)
                    true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    emit(MusicEvents.BUTTON_PLAY, buttonPlayBundle())
                    true
                }
                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    emit(MusicEvents.BUTTON_SKIP_NEXT)
                    true
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    emit(MusicEvents.BUTTON_SKIP_PREVIOUS)
                    true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD, KeyEvent.KEYCODE_MEDIA_STEP_FORWARD -> {
                    emit(MusicEvents.BUTTON_JUMP_FORWARD)
                    true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD, KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD -> {
                    emit(MusicEvents.BUTTON_JUMP_BACKWARD)
                    true
                }
                else -> null
            }
        }
        return null
    }

    public fun setSearchResults (mediaItems: Array<MediaItem>) {
        Timber.tag("APM").d("set search results")
        searchResults = mediaItems.toList()
        scope.launch {
            // Tell the browser that results are ready (or changed)
            val browser = searchBrowser
            if (browser != null) {
                Timber.tag("APM").d("notify search results are ready")
                mediaSession.notifySearchResultChanged(browser, searchQuery, 10, null)
            }
        }
    }

    @MainThread
    inner class MusicBinder : Binder() {
        val service = this@MusicService
    }

    private inner class APMMediaSessionCallback: MediaLibrarySession.Callback {
        // HACK: I'm sure most of the callbacks were not implemented correctly.
        // ATM I only care that andorid auto still functions.

        private val rootItem = buildMediaItem(title = "root", mediaId = AA_ROOT_KEY, isPlayable = false)
        private val forYouItem = buildMediaItem(title = "For You", mediaId = AA_FOR_YOU_KEY, isPlayable = false)

        // FORK PATCH: onDisconnected fires only a long time after Android Auto actually
        // disconnects, so AutoConnectionDetector — which watches CarConnection directly —
        // is what drives the pause. This stays as a BACKSTOP for the case that detector
        // cannot cover: its provider being unavailable, or the React context being torn
        // down and rebuilt around the moment the car goes away. Late is still better than
        // never, which is what the app shipped with while this was commented out — music
        // kept playing on the phone speaker after every drive.
        //
        // Only auto controllers are reported. Every other controller — system UI, the
        // app's own notification, a Wear companion — disconnects constantly in normal
        // use, and forwarding those would have the JS side tear down the Android Auto
        // session while the car is still connected. The JS handler ignores a second
        // disconnect, so whichever of the two paths arrives first wins.
        @OptIn(UnstableApi::class)
        override fun onDisconnected(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ) {
            val isAutomotiveController = session.isAutomotiveController(controller)
            val isAutoCompanionController = session.isAutoCompanionController(controller)
            if (isAutomotiveController || isAutoCompanionController) {
                emit(MusicEvents.CONNECTOR_DISCONNECTED, Bundle().apply {
                    putString("package", controller.packageName)
                    putBoolean("isAutomotiveController", isAutomotiveController)
                    putBoolean("isAutoCompanionController", isAutoCompanionController)
                    putBoolean("isMediaNotificationController", false)
                })
            }
            super.onDisconnected(session, controller)
        }
        // Configure commands available to the controller in onConnect()
        @OptIn(UnstableApi::class)
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            Timber.tag("APM").d("connection via: ${controller.packageName}")
            val isMediaNotificationController = session.isMediaNotificationController(controller)
            val isAutomotiveController = session.isAutomotiveController(controller)
            val isAutoCompanionController = session.isAutoCompanionController(controller)
            emit(MusicEvents.CONNECTOR_CONNECTED, Bundle().apply {
                putString("package", controller.packageName)
                putBoolean("isMediaNotificationController", isMediaNotificationController)
                putBoolean("isAutomotiveController", isAutomotiveController)
                putBoolean("isAutoCompanionController", isAutoCompanionController)
            })
            if (controller.packageName in arrayOf(
                    "com.android.systemui",
                    // https://github.com/googlesamples/android-media-controller
                    "com.example.android.mediacontroller",
                    // Android Auto
                    "com.google.android.projection.gearhead"
                )) {
                lastConnectedPackage = controller.packageName
                // HACK: attempt to wake up activity (for legacy APM). if not, start headless.
                if (!selfWake(controller.packageName)) {
                    onStartCommand(null, 0, 0)
                }
            }
            return if (
                isMediaNotificationController ||
                isAutomotiveController ||
                isAutoCompanionController
            ) {
                MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setMediaButtonPreferences(customLayout)
                    .setAvailableSessionCommands(sessionCommands ?: MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS)
                    .setAvailablePlayerCommands(playerCommands ?: MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                    .build()
            } else {
                super.onConnect(session, controller)
            }
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                "shuffle" -> emit(MusicEvents.BUTTON_SHUFFLE)
                "heart" -> emit(MusicEvents.BUTTON_SET_RATING, Bundle())
                else -> emit(MusicEvents.BUTTON_CUSTOM_ACTION, Bundle().apply { putString("customAction", customCommand.customAction) })
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootExtras = Bundle().apply {
                putBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED", true)
                putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", mediaTreeStyle[0])
                putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT",  mediaTreeStyle[1])
            }
            val libraryParams = LibraryParams.Builder().setExtras(rootExtras).build()
            Timber.tag("APM").d("acquiring root: ${browser.packageName}")
            // https://github.com/androidx/media/issues/1731#issuecomment-2411109462
            val mRootItem = when (browser.packageName) {
                "com.google.android.googlequicksearchbox" -> {
                    if (mediaTree[AA_FOR_YOU_KEY] == null) rootItem else forYouItem
                }
                else -> rootItem
            }
            return Futures.immediateFuture(LibraryResult.ofItem(mRootItem, libraryParams))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            emit(MusicEvents.BUTTON_BROWSE, Bundle().apply { putString("mediaId", parentId) })
            return Futures.immediateFuture(LibraryResult.ofItemList(mediaTree[parentId] ?: listOf(), null))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            Timber.tag("APM").d("acquiring item: ${browser.packageName}, $mediaId")
            // emit(MusicEvents.BUTTON_PLAY_FROM_ID, Bundle().apply { putString("id", mediaId) })
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, null))
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            Timber.tag("APM").d("searching: ${browser.packageName}, $query")
            searchBrowser = browser
            searchQuery = query
            emit(MusicEvents.BUTTON_SEARCH, Bundle().apply {
                putString("query", query)
            })
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            Timber.tag("APM")
                .d("addMediaItem: ${controller.packageName}, ${mediaItems[0].mediaId}, ${mediaItems.size}")
            return super.onAddMediaItems(mediaSession, controller, mediaItems)
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            Timber.tag("APM").d("setMediaItem: ${controller.packageName}, ${mediaItems[0].toBundle()}")
            if (mediaItems[0].requestMetadata.searchQuery == null) {
                emit(MusicEvents.BUTTON_PLAY_FROM_ID, Bundle().apply {
                    putString("id", mediaItems[0].mediaId)
                })
            } else {
                emit(MusicEvents.BUTTON_PLAY_FROM_SEARCH, Bundle().apply {
                    putString("query", mediaItems[0].requestMetadata.searchQuery)
                })
            }
            return super.onSetMediaItems(
                mediaSession,
                controller,
                mediaItems,
                startIndex,
                startPositionMs
            )
        }

        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent
        ): Boolean {
            return onMediaKeyEvent(intent) ?: super.onMediaButtonEvent(session, controllerInfo, intent)
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            Timber.tag("APM").d("searching2: ${browser.packageName}, $query")
            return Futures.immediateFuture(LibraryResult.ofItemList(searchResults, null))
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            Timber.tag("APM").d("triggered onPlaybackResumption")
            try {
                this@MusicService.player
                emit(MusicEvents.PLAYBACK_RESUME, Bundle().apply {
                    putString("package", controller.packageName)
                })
            } catch (e: Exception) {
                // player has not been initialized; forcefully trigger onStartCommand
                // TODO: emit event after the player is initialized?
                this@MusicService.onStartCommand(null, 0, 0)
            }
            return super.onPlaybackResumption(mediaSession, controller)
        }
    }

    private fun getPendingIntentFlags(): Int {
        return PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
    }
}

