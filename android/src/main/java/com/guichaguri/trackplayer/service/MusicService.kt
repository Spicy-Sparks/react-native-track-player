package com.guichaguri.trackplayer.service

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.RatingCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import android.view.KeyEvent.KEYCODE_MEDIA_STOP
import androidx.annotation.MainThread
import androidx.core.app.NotificationCompat
import androidx.core.content.PackageManagerCompat
import androidx.media.session.MediaButtonReceiver
import androidx.media.utils.MediaConstants
import com.facebook.react.ReactInstanceManager
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.WritableNativeMap
import com.facebook.react.jstasks.HeadlessJsTaskConfig
import com.facebook.react.modules.appregistry.AppRegistry
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.facebook.react.modules.core.TimingModule
import com.facebook.react.views.imagehelper.ResourceDrawableIdHelper
import com.google.android.exoplayer2.source.MediaSource
import com.guichaguri.trackplayer.HeadlessJsMediaService
import com.guichaguri.trackplayer.R
import com.guichaguri.trackplayer.extensions.asLibState
import com.guichaguri.trackplayer.kotlinaudio.models.AAMediaSessionCallBack
import com.guichaguri.trackplayer.kotlinaudio.models.AudioContentType
import com.guichaguri.trackplayer.kotlinaudio.models.AudioItemTransitionReason
import com.guichaguri.trackplayer.kotlinaudio.models.AudioPlayerState
import com.guichaguri.trackplayer.kotlinaudio.models.BufferConfig
import com.guichaguri.trackplayer.kotlinaudio.models.CacheConfig
import com.guichaguri.trackplayer.kotlinaudio.models.Capability
import com.guichaguri.trackplayer.kotlinaudio.models.MediaSessionCallback
import com.guichaguri.trackplayer.kotlinaudio.models.NotificationButton
import com.guichaguri.trackplayer.kotlinaudio.models.NotificationButton.CUSTOM_ACTION
import com.guichaguri.trackplayer.kotlinaudio.models.NotificationButton.NEXT
import com.guichaguri.trackplayer.kotlinaudio.models.NotificationButton.PLAY_PAUSE
import com.guichaguri.trackplayer.kotlinaudio.models.NotificationButton.PREVIOUS
import com.guichaguri.trackplayer.kotlinaudio.models.NotificationButton.SEEK_TO
import com.guichaguri.trackplayer.kotlinaudio.models.NotificationButton.STOP
import com.guichaguri.trackplayer.kotlinaudio.models.NotificationConfig
import com.guichaguri.trackplayer.kotlinaudio.models.NotificationState
import com.guichaguri.trackplayer.kotlinaudio.models.PlayerConfig
import com.guichaguri.trackplayer.kotlinaudio.models.RepeatMode
import com.guichaguri.trackplayer.kotlinaudio.players.QueuedAudioPlayer
import com.guichaguri.trackplayer.model.Track
import com.guichaguri.trackplayer.model.TrackAudioItem
import com.guichaguri.trackplayer.module.AutoConnectionDetector
import com.guichaguri.trackplayer.module.MusicEvents
import com.guichaguri.trackplayer.service.Utils.setRating
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Arrays
import java.util.concurrent.TimeUnit

/**
 * @author Guichaguri
 */

class MusicService : HeadlessJsMediaService() {
    private lateinit var player: QueuedAudioPlayer
    var manager: MusicManager? = null
    var handler: Handler? = null
    private var intentToStop = false
    var mediaTree: MutableMap<String, MutableList<MediaItem>> = HashMap()
    var toUpdateMediaItems: MutableMap<String, MutableList<MediaItem>> = HashMap()
    var loadingChildrenParentMediaId: String? = null
    var searchQuery: String? = null
    var searchResult: Result<MutableList<MediaItem>>? = null

    private val scope = MainScope()

    val tracks: List<Track>
        get() = player.items.map { (it as TrackAudioItem).track }

    val state
        get() = player.playerState

    var ratingType: Int
        get() = player.ratingType
        set(value) {
            player.ratingType = value
        }

    val playbackError
        get() = player.playbackError

    val event
        get() = player.event

    var playWhenReady: Boolean
        get() = player.playWhenReady
        set(value) {
            player.playWhenReady = value
        }

    private var latestOptions: Bundle? = null

    private var mediaSession: MediaSessionCompat? = null
    private var stateBuilder: PlaybackStateCompat.Builder? = null

    override fun getTaskConfig(intent: Intent): HeadlessJsTaskConfig? {
        return HeadlessJsTaskConfig("TrackPlayer", Arguments.createMap(), 0, true)
    }

    override fun onHeadlessJsTaskFinish(taskId: Int) {
        // Overridden to prevent the service from being terminated
    }

    @MainThread
    fun emit(event: String, data: Bundle? = null) {
        reactNativeHost.reactInstanceManager.currentReactContext
            ?.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            ?.emit(event, data?.let { Arguments.fromBundle(it) })
    }

    fun destroy(intentToStop: Boolean) {
        if (handler != null) {
            handler!!.removeMessages(0)
            handler = null
        }
        if (manager != null) {
            manager!!.destroy(intentToStop)
            manager = null
        }
    }

    private fun onStartForeground() {
        var serviceForeground = false
        if (manager != null) {
            // The session is only active when the service is on foreground
            serviceForeground = manager!!.metadata.session.isActive
        }

        if (!serviceForeground) {
            val reactInstanceManager = reactNativeHost.reactInstanceManager
            val reactContext = reactInstanceManager.currentReactContext

            // Checks whether there is a React activity
            if (reactContext == null || !reactContext.hasCurrentActivity()) {
                val channel = Utils.getNotificationChannel(this as Context)

                // Sets the service to foreground with an empty notification
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        1,
                        NotificationCompat.Builder(this, channel).build(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(1, NotificationCompat.Builder(this, channel).build())
                }
                // Stops the service right after
                stopSelf()
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        /* if(Utils.CONNECT_INTENT.equals(intent.getAction())) {
            return new MusicBinder(this, manager);
        }
        return super.onBind(intent); */

        return if (SERVICE_INTERFACE == intent.action) {
            super.onBind(intent)
        } else MusicBinder(this, manager!!)
    }

    fun invokeStartTask(reactContext: ReactContext, restart: Boolean = false) {
        try {
            val catalystInstance = reactContext.catalystInstance
            val jsAppModuleName = "AndroidAuto"
            val appParams = WritableNativeMap()
            appParams.putDouble("rootTag", 1.0)
            appParams.putBoolean("restart", restart)
            val appProperties = Bundle.EMPTY
            if (appProperties != null) {
                appParams.putMap("initialProps", Arguments.fromBundle(appProperties))
            }
            catalystInstance.getJSModule(AppRegistry::class.java)
                .runApplication(jsAppModuleName, appParams)

            val timingModule = reactContext.getNativeModule(
                TimingModule::class.java
            )
            timingModule!!.onHostResume()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onSearch(query: String, extras: Bundle?, result: Result<MutableList<MediaItem>>) {
        searchQuery = query
        searchResult = result
        result.detach()
        emit(MusicEvents.SEARCH, Bundle().apply {
            putString("query", query)
        });
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot? {
        // TODO: verify clientPackageName hee.
        if (Arrays.asList(
                "com.android.systemui",
                "com.example.android.mediacontroller",
                "com.google.android.projection.gearhead"
            ).contains(clientPackageName)
        ) {
            @SuppressLint("VisibleForTests") val reactContext =
                reactNativeHost.reactInstanceManager.currentReactContext

            val params = Arguments.createMap()
            params.putBoolean("connected", true)

            if (reactContext == null) {
                reactNativeHost.reactInstanceManager.addReactInstanceEventListener(object :
                    ReactInstanceManager.ReactInstanceEventListener {
                    override fun onReactContextInitialized(reactContext: ReactContext) {
                        invokeStartTask(reactContext)
                        reactNativeHost.reactInstanceManager.removeReactInstanceEventListener(this)

                        AutoConnectionDetector.isCarConnected = true

                        reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)?.emit(
                            "car-connection-update", params
                        )
                    }
                })
                reactNativeHost.reactInstanceManager.createReactContextInBackground()
            } else {
                AutoConnectionDetector.isCarConnected = true

                reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)?.emit(
                    "car-connection-update", params
                )
            }
        }

        val extras = Bundle()

        extras.putBoolean(
            MediaConstants.BROWSER_SERVICE_EXTRAS_KEY_SEARCH_SUPPORTED, true)

        return BrowserRoot("/", extras)
    }

    @MainThread
    fun add(track: Track) {
        add(listOf(track))
    }

    @MainThread
    fun add(tracks: List<Track>) {
        val items = tracks.map {
            val x = it
            x.toAudioItem() }
        player.add(items)
    }



    @MainThread
    fun add(tracks: List<Track>, atIndex: Int) {
        val items = tracks.map {
            val x = it
            if (x.url.isEmpty()) {
                val rawId = R.raw.silent_5_seconds
                x.url = "android.resource://${applicationContext.packageName}/$rawId"
                x.duration = 10
            }
            x.toAudioItem() }
        player.add(items, atIndex)
    }

    @MainThread
    fun load(track: Track) {
        val audioItem = track.toAudioItem()
        player.load(audioItem)
    }

    @MainThread
    fun move(fromIndex: Int, toIndex: Int) {
        player.move(fromIndex, toIndex);
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
    fun getRepeatMode(): RepeatMode = player.playerOptions.repeatMode

    @MainThread
    fun setRepeatMode(value: RepeatMode) {
        player.playerOptions.repeatMode = value
    }

//    @MainThread
//    fun getVolume(): Float = player.volume
//
//    @MainThread
//    fun setVolume(value: Float) {
//        player.volume = value
//    }

    @MainThread
    fun getDurationInSeconds(): Double = (player.duration / 1000).toDouble() // check seconds conversion

    @MainThread
    fun getPositionInSeconds(): Double = (player.position / 1000).toDouble() // check seconds conversion

    @MainThread
    fun getBufferedPositionInSeconds(): Double = (player.bufferedPosition / 1000).toDouble() // check seconds conversion

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

    @MainThread
    fun getPlayerQueueHead(): MediaSource? {
        return player.getQueueHead()
    }

//    @MainThread
//    fun updateNowPlayingMetadata(track: Track) {
//        val audioItem = track.toAudioItem()
//        player.notificationManager.notificationMetadata = NotificationMetadata(audioItem?.title, audioItem?.artist, audioItem?.artwork, audioItem?.duration)
//    }

    @MainThread
    fun clearNotificationMetadata() {
        player.notificationManager.hideNotification()
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

    override fun onLoadChildren(
        parentMediaId: String,
        result: Result<List<MediaItem>>
    ) {
        val mediaIdParts = parentMediaId.split("-/-")
        val itemType = mediaIdParts?.getOrNull(1)

        if (itemType == "empty") {
            result.sendResult(emptyList())
        }

        if (mediaTree.keys.contains(parentMediaId) || parentMediaId == "/" || parentMediaId.contains("tab")) {
            result.sendResult(mediaTree[parentMediaId])
        } else if (parentMediaId != loadingChildrenParentMediaId) {
            loadingChildrenParentMediaId = parentMediaId
            emit(MusicEvents.BUTTON_BROWSE, Bundle().apply {
                putString("mediaId", parentMediaId)
            })
            result.sendResult(mediaTree["placeholder"])
        }
    }

    override fun onLoadItem(itemId: String, result: Result<MediaItem>) {}

    @SuppressLint("RestrictedApi")
    override fun onCreate() {
        super.onCreate()

        // Create a MediaSessionCompat
        mediaSession = MediaSessionCompat(baseContext, PackageManagerCompat.LOG_TAG)

        // Enable callbacks from MediaButtons and TransportControls
        mediaSession!!.setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        )

        // Set an initial PlaybackState with ACTION_PLAY, so media buttons can start the player
        stateBuilder = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT
            )

        if (stateBuilder != null) {
            mediaSession!!.setPlaybackState(stateBuilder!!.build())
        }

        // MySessionCallback() has methods that handle callbacks from a media controller
        // mediaSession.setCallback(new MySessionCallback());

        // Set the session's token so that client activities can communicate with it.

        // remove comment if not using player
        //sessionToken = mediaSession!!.sessionToken

        //onStartForeground();
        if (manager == null) manager = MusicManager(this)
        if (handler == null) handler = Handler()
        val channel = Utils.getNotificationChannel(this as Context)

        // Sets the service to foreground with an empty notification
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    1,
                    NotificationCompat.Builder(this, channel).build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(1, NotificationCompat.Builder(this, channel).build())
            }
        } catch (_: Exception) { }
    }

    override fun onCustomAction(action: String, extras: Bundle?, result: Result<Bundle>) {}

    /**
     * Workaround for the "Context.startForegroundService() did not then call Service.startForeground()"
     * within 5s" ANR and crash by creating an empty notification and stopping it right after. For more
     * information see https://github.com/doublesymmetry/react-native-track-player/issues/1666
     */
    private fun startAndStopEmptyNotificationToAvoidANR() {
        val notificationManager = this.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel("Playback", "Playback", NotificationManager.IMPORTANCE_LOW)
            )
        }

        val notificationBuilder: NotificationCompat.Builder = NotificationCompat.Builder(
            this, "Playback"
        )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSmallIcon(R.drawable.ic_logo)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            notificationBuilder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }
        val notification = notificationBuilder.build()
        startForeground(1, notification)
        stopForeground(true)
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        if (intent != null && Intent.ACTION_MEDIA_BUTTON == intent.action) {
            onStartForeground()

            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    startAndStopEmptyNotificationToAvoidANR()
                } catch (ex: java.lang.Exception) {
                }
            }

            val intentExtra: KeyEvent? = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
            if (intentExtra!!.keyCode == KEYCODE_MEDIA_STOP) {
                intentToStop = true
                startServiceOreoAndAbove()
                stopSelf()
            } else {
                intentToStop = false
            }

            if (manager != null && manager!!.metadata.session != null) {
                MediaButtonReceiver.handleIntent(manager!!.metadata.session, intent)
                return START_NOT_STICKY
            }
        }

        if (manager == null) manager = MusicManager(this)

        if (handler == null) handler = Handler()

        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    fun startServiceOreoAndAbove() {
        // Needed to prevent crash when dismissing notification
        // https://stackoverflow.com/questions/47609261/bound-service-crash-with-context-startforegroundservice-did-not-then-call-ser?rq=1
        if (Build.VERSION.SDK_INT >= 26) {
            val CHANNEL_ID = Utils.NOTIFICATION_CHANNEL
            val CHANNEL_NAME = "Playback"
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                channel
            )
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setCategory(Notification.CATEGORY_SERVICE).setSmallIcon(R.drawable.ic_logo)
                .setPriority(
                    NotificationCompat.PRIORITY_MIN
                ).build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(1, notification)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (manager != null) {
            manager!!.destroy(true)
            manager = null
        }
    }

    override fun onTaskRemoved(rootIntent: Intent) {
        super.onTaskRemoved(rootIntent)
        if (manager == null || manager!!.shouldStopWithApp()) {
            destroy(true)
            stopSelf()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    @MainThread
    fun setupPlayer(playerOptions: Bundle?) {
        if (this::player.isInitialized) {
            print("Player was initialized. Prevent re-initializing again")
            return
        }

        val bufferConfig = BufferConfig(
            playerOptions?.getDouble(MIN_BUFFER_KEY)?.toInt(),
            playerOptions?.getDouble(MAX_BUFFER_KEY)?.toInt(),
            playerOptions?.getDouble(PLAY_BUFFER_KEY)?.toInt(),
            playerOptions?.getDouble(BACK_BUFFER_KEY)?.toInt(),
        )

        val cacheConfig = CacheConfig(playerOptions?.getDouble(MAX_CACHE_SIZE_KEY)?.toLong())
        val playerConfig = PlayerConfig(
            interceptPlayerActionsTriggeredExternally = true,
            handleAudioBecomingNoisy = true,
            handleAudioFocus = true,
            audioContentType = AudioContentType.MUSIC
        )

        val automaticallyUpdateNotificationMetadata = playerOptions?.getBoolean(AUTO_UPDATE_METADATA, true) ?: true
        val mediaSessionCallback = object: AAMediaSessionCallBack {
            override fun handleCustomActions(action: String?, extras: Bundle?) {}

            override fun handlePlayFromMediaId(mediaId: String?, extras: Bundle?) {
                val emitBundle = extras ?: Bundle()
                emit(MusicEvents.BUTTON_PLAY_FROM_ID, emitBundle.apply {
                    putString("mediaId", mediaId)
                })
            }

            override fun handlePlayFromSearch(query: String?, extras: Bundle?) {
                val emitBundle = extras ?: Bundle()
                emit(MusicEvents.BUTTON_PLAY_FROM_SEARCH, emitBundle.apply {
                    putString("query", query)
                })
            }

            override fun handleSkipToQueueItem(id: Long) {
                val emitBundle = Bundle()
                emit(MusicEvents.BUTTON_PLAY_FROM_QUEUE, emitBundle.apply {
                    putInt("index", id.toInt())
                })
            }
        }
        player = QueuedAudioPlayer(this@MusicService, playerConfig, bufferConfig, cacheConfig, mediaSessionCallback)
        player.volume = 0.0f
        player.automaticallyUpdateNotificationMetadata = automaticallyUpdateNotificationMetadata
        sessionToken = player.getMediaSessionToken()

        observeEvents()
        setupForegrounding()
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

    private fun emitQueueEndedEvent() {
        val bundle = Bundle()
        bundle.putInt(TRACK_KEY, player.currentIndex)
        bundle.putDouble(POSITION_KEY, (player.position / 1000).toDouble())
        emit(MusicEvents.PLAYBACK_QUEUE_ENDED, bundle)
    }

    private var capabilities: List<Capability> = emptyList()
    private var notificationCapabilities: List<Capability> = emptyList()
    private var compactCapabilities: List<Capability> = emptyList()
    private var progressUpdateJob: Job? = null

    private fun isCompact(capability: Capability): Boolean {
        return compactCapabilities.contains(capability)
    }

    private fun getPendingIntentFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
        } else {
            PendingIntent.FLAG_CANCEL_CURRENT
        }
    }

    private fun getIcon(options: Bundle, propertyName: String, defaultIcon: Int): Int {
        if (!options.containsKey(propertyName)) return defaultIcon
        val bundle = options.getBundle(propertyName) ?: return defaultIcon
        val helper = ResourceDrawableIdHelper.getInstance()
        val icon = helper.getResourceDrawableId(this, bundle.getString("uri"))
        return if (icon == 0) defaultIcon else icon
    }

    @MainThread
    fun updateOptions(options: Bundle) {
        latestOptions = options
        val androidOptions = options.getBundle(ANDROID_OPTIONS_KEY)

        ratingType = BundleUtils.getInt(options, "ratingType", RatingCompat.RATING_NONE)

        player.playerOptions.alwaysPauseOnInterruption = androidOptions?.getBoolean(PAUSE_ON_INTERRUPTION_KEY) ?: false

        capabilities = options.getIntegerArrayList("capabilities")?.map {
            Capability.values()[it] } ?: emptyList()
        notificationCapabilities = options.getIntegerArrayList("notificationCapabilities")?.map { Capability.values()[it] } ?: emptyList()
        compactCapabilities = options.getIntegerArrayList("compactCapabilities")?.map { Capability.values()[it] } ?: emptyList()
        val customActions = options.getStringArrayList(CUSTOM_ACTIONS_KEY)
        if (notificationCapabilities.isEmpty()) notificationCapabilities = capabilities

        fun customIcon(customAction: String): Int {
            return when (customAction) {
                "shuffle-on" -> R.mipmap.ic_shuffle_on_foreground
                "shuffle-off" -> R.mipmap.ic_shuffle_off_foreground
                "repeat-on" -> R.mipmap.ic_repeat_on_foreground
                "repeat-off" -> R.mipmap.ic_repeat_off_foreground
                "heart" -> R.mipmap.ic_heart_foreground
                "heart-outlined" -> R.mipmap.ic_heart_outlined_foreground
                "clock" -> R.mipmap.ic_clock_now_foreground
                "arrow-down-circle" -> R.mipmap.ic_arrow_down_circle_foreground
                "md-close" -> R.mipmap.ic_close_foreground
                else -> R.mipmap.ic_heart_outlined_foreground
            }
        }

        val buttonsList = mutableListOf<NotificationButton>()

        notificationCapabilities.forEach { capability ->
            when (capability) {
                Capability.PLAY, Capability.PAUSE -> {
                    val playIcon = getIcon(options, "playIcon", R.drawable.ic_play)
                    val pauseIcon = getIcon(options, "pauseIcon", R.drawable.ic_pause)
                    buttonsList.addAll(listOf(PLAY_PAUSE(playIcon = playIcon, pauseIcon = pauseIcon)))
                }
                Capability.STOP -> {
                    val stopIcon = getIcon(options, "stopIcon", R.drawable.ic_stop)
                    buttonsList.addAll(listOf(STOP(icon = stopIcon)))
                }
                Capability.SKIP_TO_NEXT -> {
                    val nextIcon = getIcon(options, "nextIcon", R.drawable.ic_next)
                    buttonsList.addAll(listOf(NEXT(icon = nextIcon, isCompact = isCompact(capability))))
                }
                Capability.SKIP_TO_PREVIOUS -> {
                    val previousIcon = getIcon(options, "previousIcon", R.drawable.ic_previous)
                    buttonsList.addAll(listOf(PREVIOUS(icon = previousIcon, isCompact = isCompact(capability))))
                }
//                Capability.JUMP_FORWARD -> {
//                    val forwardIcon = BundleUtils.getIconOrNull(this, options, "forwardIcon")
//                    buttonsList.addAll(listOf(FORWARD(icon = forwardIcon, isCompact = isCompact(capability))))
//                }
//                Capability.JUMP_BACKWARD -> {
//                    val backwardIcon = BundleUtils.getIconOrNull(this, options, "rewindIcon")
//                    buttonsList.addAll(listOf(BACKWARD(icon = backwardIcon, isCompact = isCompact(capability))))
//                }
                Capability.SEEK_TO -> {
                    buttonsList.addAll(listOf(SEEK_TO))
                }
                else -> {}
            }
        }

        if (customActions != null) {
            for (customAction in customActions) {
                val customIcon = customIcon(customAction)
                buttonsList.add(CUSTOM_ACTION(icon=customIcon, customAction = customAction, isCompact = false))
            }
        }

        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
//            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            action = Intent.ACTION_VIEW
        }

        val accentColor = BundleUtils.getIntOrNull(options, "color")
        val smallIcon = R.drawable.ic_logo
        val pendingIntent = PendingIntent.getActivity(this, 0, openAppIntent, getPendingIntentFlags())
        val notificationConfig = NotificationConfig(buttonsList, accentColor, smallIcon, pendingIntent)

        // player.notificationManager.destroy()
        player.notificationManager.createNotification(notificationConfig)

        // setup progress update events if configured
        progressUpdateJob?.cancel()
        val updateInterval = BundleUtils.getIntOrNull(options, PROGRESS_UPDATE_EVENT_INTERVAL_KEY)
        if (updateInterval != null && updateInterval > 0) {
            progressUpdateJob = scope.launch {
                progressUpdateEventFlow(updateInterval.toDouble()).collect { emit(MusicEvents.PLAYBACK_PROGRESS_UPDATED, it) }
            }
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
                putDouble(POSITION_KEY, (player.position / 1000).toDouble())
                putDouble(DURATION_KEY, (player.duration / 1000).toDouble())
                putDouble(BUFFERED_POSITION_KEY, (player.bufferedPosition / 1000).toDouble())
                putInt(TRACK_KEY, player.currentIndex)
            }
        }
    }


    @MainThread
    private fun observeEvents() {
        scope.launch {
            event.audioItemTransition.collect {
                if (it !is AudioItemTransitionReason.REPEAT) {
                    emitPlaybackTrackChangedEvents(
                        player.currentIndex,
                        player.previousIndex,
                        (it?.oldPosition ?: 0).toDouble()
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
                            putDouble("position", (it.positionMs / 1000).toDouble())
                            emit(MusicEvents.BUTTON_SEEK_TO, this)
                        }
                    }
                    MediaSessionCallback.PLAY -> emit(MusicEvents.BUTTON_PLAY)
                    MediaSessionCallback.PAUSE -> emit(MusicEvents.BUTTON_PAUSE)
                    MediaSessionCallback.NEXT -> emit(MusicEvents.BUTTON_SKIP_NEXT)
                    MediaSessionCallback.PREVIOUS -> emit(MusicEvents.BUTTON_SKIP_PREVIOUS)
                    MediaSessionCallback.STOP -> emit(MusicEvents.BUTTON_STOP)
                    MediaSessionCallback.FORWARD -> {
//                        Bundle().apply {
//                            val interval = latestOptions?.getDouble(FORWARD_JUMP_INTERVAL_KEY, DEFAULT_JUMP_INTERVAL) ?: DEFAULT_JUMP_INTERVAL
//                            putInt("interval", interval.toInt())
//                            emit(MusicEvents.BUTTON_JUMP_FORWARD, this)
//                        }
                    }
                    MediaSessionCallback.REWIND -> {
//                        Bundle().apply {
//                            val interval = latestOptions?.getDouble(BACKWARD_JUMP_INTERVAL_KEY, DEFAULT_JUMP_INTERVAL) ?: DEFAULT_JUMP_INTERVAL
//                            putInt("interval", interval.toInt())
//                            emit(MusicEvents.BUTTON_JUMP_BACKWARD, this)
//                        }
                    }
                    is MediaSessionCallback.CUSTOMACTION -> {
                        Bundle().apply {
                            if (it.customAction == "shuffle-on" || it.customAction == "shuffle-off") {
                                emit(MusicEvents.BUTTON_SHUFFLE, this)
                            } else if (it.customAction == "repeat-on" || it.customAction == "repeat-off") {
                                emit(MusicEvents.BUTTON_REPEAT, this)
                            } else if (it.customAction == "heart" || it.customAction == "heart-outlined" || it.customAction == "clock" || it.customAction == "arrow-down-circle" || it.customAction == "md-close") {
                                emit(MusicEvents.BUTTON_TRACK_STATUS, this)
                            }
                        }
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    fun isForegroundService(): Boolean {
        val manager = baseContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (MusicService::class.java.name == service.service.className) {
                return service.foreground
            }
        }
        return false
    }

    private var stopForegroundGracePeriod: Int = DEFAULT_STOP_FOREGROUND_GRACE_PERIOD

    @MainThread
    private fun setupForegrounding() {
        // Implementation based on https://github.com/Automattic/pocket-casts-android/blob/ee8da0c095560ef64a82d3a31464491b8d713104/modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/PlaybackService.kt#L218
        var notificationId: Int? = null
        var notification: Notification? = null
        var stopForegroundWhenNotOngoing = false
        var removeNotificationWhenNotOngoing = false

        fun startForegroundIfNecessary() {
            if (isForegroundService()) {
                return
            }
            if (notification == null) {
                return
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        notificationId!!,
                        notification!!,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(notificationId!!, notification)
                }
            } catch (error: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    error is ForegroundServiceStartNotAllowedException
                ) {
                    emit(MusicEvents.PLAYER_ERROR, Bundle().apply {
                        putString("message", error.message)
                        putString("code", "android-foreground-service-start-not-allowed")
                    });
                }
            }
        }

        scope.launch {
            val BACKGROUNDABLE_STATES = listOf(
                AudioPlayerState.IDLE,
                AudioPlayerState.ENDED,
                AudioPlayerState.STOPPED,
                AudioPlayerState.ERROR,
                AudioPlayerState.PAUSED
            )
            val REMOVABLE_STATES = listOf(
                AudioPlayerState.IDLE,
                AudioPlayerState.STOPPED,
                AudioPlayerState.ERROR
            )
            val LOADING_STATES = listOf(
                AudioPlayerState.LOADING,
                AudioPlayerState.READY,
                AudioPlayerState.BUFFERING
            )
            var stateCount = 0
            event.stateChange.collect {
                stateCount++
                if (it in LOADING_STATES) return@collect;
                // Skip initial idle state, since we are only interested when
                // state becomes idle after not being idle
                stopForegroundWhenNotOngoing = stateCount > 1 && it in BACKGROUNDABLE_STATES
                removeNotificationWhenNotOngoing = stopForegroundWhenNotOngoing && it in REMOVABLE_STATES
            }
        }

        fun shouldStopForeground(): Boolean {
            return stopForegroundWhenNotOngoing && (removeNotificationWhenNotOngoing || isForegroundService())
        }

        scope.launch {
            event.notificationStateChange.collect {
                when (it) {
                    is NotificationState.POSTED -> {
                        notificationId = it.notificationId;
                        notification = it.notification;
                        if (it.ongoing) {
                            if (player.playWhenReady) {
                                startForegroundIfNecessary()
                            }
                        } else if (shouldStopForeground()) {
                            // Allow the application a grace period to complete any actions
                            // that may necessitate keeping the service in a foreground state.
                            // For instance, queuing new media (e.g., related music) after the
                            // user's queue is complete. This prevents the service from potentially
                            // being immediately destroyed once the player finishes playing media.
                            scope.launch {
                                delay(stopForegroundGracePeriod.toLong() * 1000)
                                if (shouldStopForeground()) {
                                    // @Suppress("DEPRECATION")
                                    // stopForeground(removeNotificationWhenNotOngoing)
                                }
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    companion object {
        const val EMPTY_NOTIFICATION_ID = 1
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

        const val STOPPING_APP_PAUSES_PLAYBACK_KEY = "stoppingAppPausesPlayback"
        const val APP_KILLED_PLAYBACK_BEHAVIOR_KEY = "appKilledPlaybackBehavior"
        const val STOP_FOREGROUND_GRACE_PERIOD_KEY = "stopForegroundGracePeriod"
        const val PAUSE_ON_INTERRUPTION_KEY = "alwaysPauseOnInterruption"
        const val AUTO_UPDATE_METADATA = "autoUpdateMetadata"
        const val AUTO_HANDLE_INTERRUPTIONS = "autoHandleInterruptions"
        const val ANDROID_AUDIO_CONTENT_TYPE = "androidAudioContentType"
        const val IS_FOCUS_LOSS_PERMANENT_KEY = "permanent"
        const val IS_PAUSED_KEY = "paused"

        const val DEFAULT_JUMP_INTERVAL = 15.0
        const val DEFAULT_STOP_FOREGROUND_GRACE_PERIOD = 5

        const val CUSTOM_ACTIONS_KEY = "customActions"
    }
}