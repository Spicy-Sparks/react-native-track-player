package com.guichaguri.trackplayer.module

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.*
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.RatingCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media.utils.MediaConstants
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.facebook.react.bridge.*
import com.google.android.exoplayer2.DefaultLoadControl.*
import com.guichaguri.trackplayer.kotlinaudio.models.AudioPlayerState
import com.guichaguri.trackplayer.service.MusicBinder
import com.guichaguri.trackplayer.service.MusicService
import com.guichaguri.trackplayer.service.Utils
import com.guichaguri.trackplayer.service.Utils.EVENT_INTENT
import com.guichaguri.trackplayer.model.Track
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.annotation.Nonnull
import kotlin.collections.HashMap

/**
 * @author Guichaguri
 */
class MusicModule(reactContext: ReactApplicationContext?) :
    ReactContextBaseJavaModule(reactContext), ServiceConnection, LifecycleEventListener {
    private var playerOptions: Bundle? = null
    private var isServiceBound = false

    // private var binder: MusicBinder? = null
    private var eventHandler: MusicEvents? = null
    private val initCallbacks = ArrayDeque<Runnable>()
    private var connecting = false
    private var options: Bundle? = null
    private var musicService: MusicService? = null
    private val scope = MainScope()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var autoConnectionDetector: AutoConnectionDetector? = null

    companion object {
        var binder: MusicBinder? = null
    }

    @Nonnull
    override fun getName(): String {
        return "TrackPlayerModule"
    }

    override fun initialize() {
        val context: ReactContext = reactApplicationContext
        context.addLifecycleEventListener(this)
        val manager = LocalBroadcastManager.getInstance(context)
        eventHandler = MusicEvents(context)
        manager.registerReceiver(eventHandler!!, IntentFilter(Utils.EVENT_INTENT))
        autoConnectionDetector = AutoConnectionDetector(context)
        autoConnectionDetector?.registerCarConnectionReceiver()
    }

    override fun onCatalystInstanceDestroy() {
        val context: ReactContext = reactApplicationContext
        if (eventHandler != null) {
            val manager = LocalBroadcastManager.getInstance(context)
            manager.unregisterReceiver(eventHandler!!)
            eventHandler = null
        }
        autoConnectionDetector?.unRegisterCarConnectionReceiver()
    }

    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        scope.launch {
            binder = service as MusicBinder
            connecting = false
            if (musicService == null) {
                val binder: MusicBinder = service
                musicService = binder.service
                musicService?.setupPlayer(playerOptions);
            }

            isServiceBound = true

            // Reapply options that user set before with updateOptions
            if (options != null) {
                binder!!.updateOptions(options!!)
            }

            // Triggers all callbacks
            while (!initCallbacks.isEmpty()) {
                binder!!.post(initCallbacks.remove())
            }
        }
    }

    override fun onServiceDisconnected(name: ComponentName) {
        binder = null
        connecting = false
        isServiceBound = false
    }

    private fun verifyServiceBoundOrReject(promise: Promise): Boolean {
        if (!isServiceBound) {
            promise.reject(
                "player_not_initialized",
                "The player is not initialized. Call setupPlayer first."
            )
            return true
        }

        return false
    }

    /**
     * Waits for a connection to the service and/or runs the [Runnable] in the player thread
     */
    private fun waitForConnection(r: Runnable) {
        if (binder != null) {
            binder!!.post(r)
            return
        } else {
            initCallbacks.add(r)
        }
        if (connecting) return
        val context = reactApplicationContext

        // Binds the service to get a MediaWrapper instance
        val intent = Intent(context, MusicService::class.java)
        //context.startService(intent);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (ex: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val mgr = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val i = Intent(context, MusicService::class.java)
                val pi = PendingIntent.getForegroundService(
                    context,
                    0,
                    i,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val calendar = Calendar.getInstance()
                calendar.timeInMillis = System.currentTimeMillis()
                calendar.add(Calendar.MILLISECOND, 200)
                mgr[AlarmManager.RTC_WAKEUP, calendar.timeInMillis] = pi
            }
        }
        intent.action = Utils.CONNECT_INTENT
        context.bindService(intent, this, 0)
        connecting = true
    }

    /* ****************************** API ****************************** */
    override fun getConstants(): Map<String, Any>? {
        val constants: MutableMap<String, Any> = HashMap()

        // Capabilities
        constants["CAPABILITY_PLAY"] = PlaybackStateCompat.ACTION_PLAY
        constants["CAPABILITY_PLAY_FROM_ID"] = PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
        constants["CAPABILITY_PLAY_FROM_SEARCH"] = PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
        constants["CAPABILITY_PAUSE"] = PlaybackStateCompat.ACTION_PAUSE
        constants["CAPABILITY_STOP"] = PlaybackStateCompat.ACTION_STOP
        constants["CAPABILITY_SEEK_TO"] = PlaybackStateCompat.ACTION_SEEK_TO
        constants["CAPABILITY_SKIP"] = PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM
        constants["CAPABILITY_SKIP_TO_NEXT"] = PlaybackStateCompat.ACTION_SKIP_TO_NEXT
        constants["CAPABILITY_SKIP_TO_PREVIOUS"] = PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        constants["CAPABILITY_SET_RATING"] = PlaybackStateCompat.ACTION_SET_RATING
        constants["CAPABILITY_JUMP_FORWARD"] = PlaybackStateCompat.ACTION_FAST_FORWARD
        constants["CAPABILITY_JUMP_BACKWARD"] = PlaybackStateCompat.ACTION_REWIND

        // States
        constants["STATE_NONE"] = PlaybackStateCompat.STATE_NONE
        constants["STATE_READY"] = PlaybackStateCompat.STATE_PAUSED
        constants["STATE_PLAYING"] = PlaybackStateCompat.STATE_PLAYING
        constants["STATE_PAUSED"] = PlaybackStateCompat.STATE_PAUSED
        constants["STATE_STOPPED"] = PlaybackStateCompat.STATE_STOPPED
        constants["STATE_BUFFERING"] = PlaybackStateCompat.STATE_BUFFERING
        constants["STATE_CONNECTING"] = PlaybackStateCompat.STATE_CONNECTING

        // Rating Types
        constants["RATING_HEART"] = RatingCompat.RATING_HEART
        constants["RATING_THUMBS_UP_DOWN"] = RatingCompat.RATING_THUMB_UP_DOWN
        constants["RATING_3_STARS"] = RatingCompat.RATING_3_STARS
        constants["RATING_4_STARS"] = RatingCompat.RATING_4_STARS
        constants["RATING_5_STARS"] = RatingCompat.RATING_5_STARS
        constants["RATING_PERCENTAGE"] = RatingCompat.RATING_PERCENTAGE
        return constants
    }

    private fun convertBlackToWhite(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        val outputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        for (x in 0 until width) {
            for (y in 0 until height) {
                val pixel = bitmap.getPixel(x, y)
                val alpha = Color.alpha(pixel)
                val red = Color.red(pixel)
                val green = Color.green(pixel)
                val blue = Color.blue(pixel)

                outputBitmap.setPixel(
                    x,
                    y,
                    if (red == 0 && green == 0 && blue == 0) Color.argb(
                        alpha,
                        255,
                        255,
                        255
                    ) else pixel
                )
            }
        }

        return outputBitmap
    }

    private fun loadBitmapFromFile(filePath: String): Bitmap? {
        return try {
            val uri = Uri.parse(filePath)
            val path = uri.path ?: ""
            BitmapFactory.decodeFile(path)
        } catch (e: Exception) {
            null
        }
    }

    private fun getCircularBitmap(bitmap: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val paint = Paint()
        paint.isAntiAlias = true
        paint.shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)

        val radius = bitmap.width.coerceAtMost(bitmap.height) / 2f
        canvas.drawCircle(bitmap.width / 2f, bitmap.height / 2f, radius, paint)

        return output
    }

    private fun getCroppedBitmap(bitmap: Bitmap): Bitmap {
        val output = if (bitmap.width >= bitmap.height) {
            Bitmap.createBitmap(
                bitmap,
                (bitmap.width - bitmap.height) / 2,
                0,
                bitmap.height,
                bitmap.height
            )
        } else {
            Bitmap.createBitmap(
                bitmap,
                0,
                (bitmap.height - bitmap.width) / 2,
                bitmap.width,
                bitmap.width
            )
        }

        return output
    }

    var placeholderBitmap: Bitmap? = null

    private fun hashmapToMediaItem(
        parentMediaId: String,
        hashmap: HashMap<String, String>,
        iconBitmap: Bitmap? = null
    ): MediaBrowserCompat.MediaItem {
        val mediaId = hashmap["mediaId"]
        val title = hashmap["title"]
        val subtitle = hashmap["subtitle"]
        val mediaUri = hashmap["mediaUri"]
        val iconUri = hashmap["iconUri"]
        val iconName = hashmap["iconName"]
        val cropThumbnail = hashmap["cropThumbnail"].toBoolean()

        val mediaIdParts = mediaId?.split("-/-")
        val itemType = mediaIdParts?.getOrNull(1)

        val isArtist = itemType == "artist"
        val playableFlag =
            if (itemType == "track" || itemType == "empty" || itemType == "shuffle") MediaItem.FLAG_PLAYABLE else MediaItem.FLAG_BROWSABLE

        val mediaDescriptionBuilder = MediaDescriptionCompat.Builder()
        mediaDescriptionBuilder.setMediaId(mediaId)
        mediaDescriptionBuilder.setTitle(title)
        mediaDescriptionBuilder.setSubtitle(subtitle)
        mediaDescriptionBuilder.setMediaUri(if (mediaUri != null) Uri.parse(mediaUri) else null)

        if (iconBitmap != null) {
            var bitmap = iconBitmap
            if (cropThumbnail) {
                bitmap = if (isArtist) {
                    getCircularBitmap(bitmap)
                } else {
                    getCroppedBitmap(bitmap)
                }
            }

            mediaDescriptionBuilder.setIconBitmap(bitmap)
        } else if (iconName != null) {
            if (iconName.contains("placeholder") && placeholderBitmap != null) {
                mediaDescriptionBuilder.setIconBitmap(placeholderBitmap)
            } else {
                val currentResourceId = reactApplicationContext.resources.getIdentifier(
                    iconName,
                    "mipmap",
                    reactApplicationContext.packageName
                )
                if (currentResourceId != 0) {
                    var bitmap = BitmapFactory.decodeResource(
                        reactApplicationContext.resources,
                        currentResourceId
                    )
                    if (bitmap != null) {
                        if (iconName == "ic_auto_placeholder_foreground") {
                            placeholderBitmap = bitmap
                        } else if (iconName != "ic_auto_play_shuffle_foreground") {
                            bitmap = convertBlackToWhite(bitmap)
                        }
                        mediaDescriptionBuilder.setIconBitmap(bitmap)
                    }
                }
            }
        } else if (iconUri != null) {
            if (iconUri.startsWith("http")) {
                if (cropThumbnail) {
                    if (placeholderBitmap == null) {
                        val currentResourceId = reactApplicationContext.resources.getIdentifier(
                            "placeholder",
                            "mipmap",
                            reactApplicationContext.packageName
                        )
                        if (currentResourceId != 0) {
                            placeholderBitmap = BitmapFactory.decodeResource(
                                reactApplicationContext.resources,
                                currentResourceId
                            )
                        }
                    }
                    if (placeholderBitmap != null) {
                        val bitmap =
                            if (isArtist) getCircularBitmap(placeholderBitmap!!) else placeholderBitmap
                        mediaDescriptionBuilder.setIconBitmap(bitmap)
                    }

                    Glide.with(reactApplicationContext)
                        .asBitmap()
                        .load(iconUri)
                        .into(object : CustomTarget<Bitmap>() {
                            override fun onResourceReady(
                                resource: Bitmap,
                                transition: Transition<in Bitmap>?
                            ) {
                                if (mediaId == null) return

                                executor.submit(Callable {
                                    if (musicService != null) {
                                        val toUpdateMediaItem =
                                            hashmapToMediaItem(parentMediaId, hashmap, resource)
                                        val items =
                                            musicService!!.mediaTree.getOrPut(parentMediaId) { mutableListOf() }
                                        val index =
                                            items.indexOfFirst { it.mediaId == toUpdateMediaItem.mediaId }
                                        if (index != -1) {
                                            items[index] = toUpdateMediaItem
                                        }
                                        musicService!!.mediaTree[parentMediaId] = items
                                        musicService!!.notifyChildrenChanged(parentMediaId)
                                    }
                                })
                            }

                            override fun onLoadCleared(placeholder: Drawable?) {}
                        })
                } else {
                    mediaDescriptionBuilder.setIconUri(Uri.parse(iconUri))
                }
            } else if (iconUri.startsWith("file")) {
                var bitmap = loadBitmapFromFile(iconUri)
                if (bitmap != null) {
                    if (cropThumbnail) {
                        bitmap = if (isArtist) {
                            getCircularBitmap(bitmap)
                        } else {
                            getCroppedBitmap(bitmap)
                        }
                    }

                    mediaDescriptionBuilder.setIconBitmap(bitmap)
                }
            }
        }

        val extras = Bundle()
        if (hashmap["groupTitle"] != null) {
            extras.putString(
                MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE,
                hashmap["groupTitle"]
            )
        }
        if (hashmap["contentStyle"] != null) {
            extras.putInt(
                MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM,
                hashmap["contentStyle"]!!
                    .toInt()
            )
        }
        if (hashmap["childrenPlayableContentStyle"] != null) {
            extras.putInt(
                MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                hashmap["childrenPlayableContentStyle"]!!
                    .toInt()
            )
        }
        if (hashmap["childrenBrowsableContentStyle"] != null) {
            extras.putInt(
                MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                hashmap["childrenBrowsableContentStyle"]!!
                    .toInt()
            )
        }
        if (hashmap["playbackProgress"] != null) {
            val playbackProgress = hashmap["playbackProgress"]!!.toDouble()
            if (playbackProgress > 0.98) {
                extras.putInt(
                    MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS,
                    MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED
                )
            } else if (playbackProgress == 0.0) {
                extras.putInt(
                    MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS,
                    MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED
                )
            } else {
                extras.putInt(
                    MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS,
                    MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED
                )
                extras.putDouble(
                    MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_PERCENTAGE,
                    playbackProgress
                )
            }
        }
        mediaDescriptionBuilder.setExtras(extras)
        return MediaItem(mediaDescriptionBuilder.build(), playableFlag)
    }

    private fun readableArrayToMediaItems(
        parentMediaId: String,
        data: ArrayList<HashMap<String, String>>
    ): MutableList<MediaItem>? {
        val executor: ExecutorService =
            Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
        val mediaItemList: MutableList<MediaItem> = ArrayList()

        val futures: List<Future<MediaItem>> = data.map { hashmap ->
            executor.submit(Callable {
                hashmapToMediaItem(parentMediaId, hashmap)
            })
        }

        for (future in futures) {
            try {
                mediaItemList.add(future.get())
            } catch (_: InterruptedException) {
            }
        }

        executor.shutdown()
        return mediaItemList
    }

    @ReactMethod
    fun setupPlayer(data: ReadableMap?, promise: Promise) {
        if (isServiceBound) {
            promise.reject(
                "player_already_initialized",
                "The player has already been initialized via setupPlayer."
            )
            return
        }

        // prevent crash Fatal Exception: android.app.RemoteServiceException$ForegroundServiceDidNotStartInTimeException
        /*        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && AppForegroundTracker.backgrounded) {
                    promise.reject(
                        "android_cannot_setup_player_in_background",
                        "On Android the app must be in the foreground when setting up the player."
                    )
                    return
                }*/

        // Validate buffer keys.
        val bundledData = Arguments.toBundle(data)
        val minBuffer =
            bundledData?.getDouble(MusicService.MIN_BUFFER_KEY)?.toInt()
                ?: DEFAULT_MIN_BUFFER_MS
        val maxBuffer =
            bundledData?.getDouble(MusicService.MAX_BUFFER_KEY)?.toInt()
                ?: DEFAULT_MAX_BUFFER_MS
        val playBuffer =
            bundledData?.getDouble(MusicService.PLAY_BUFFER_KEY)?.toInt()
                ?: DEFAULT_BUFFER_FOR_PLAYBACK_MS
        val backBuffer =
            bundledData?.getDouble(MusicService.BACK_BUFFER_KEY)?.toInt()
                ?: DEFAULT_BACK_BUFFER_DURATION_MS

        if (playBuffer < 0) {
            promise.reject(
                "play_buffer_error",
                "The value for playBuffer should be greater than or equal to zero."
            )
            return
        }

        if (backBuffer < 0) {
            promise.reject(
                "back_buffer_error",
                "The value for backBuffer should be greater than or equal to zero."
            )
            return
        }

        if (minBuffer < playBuffer) {
            promise.reject(
                "min_buffer_error",
                "The value for minBuffer should be greater than or equal to playBuffer."
            )
            return
        }

        if (maxBuffer < minBuffer) {
            promise.reject(
                "min_buffer_error",
                "The value for maxBuffer should be greater than or equal to minBuffer."
            )
            return
        }

        // playerSetUpPromise = promise
        playerOptions = bundledData


        LocalBroadcastManager.getInstance(reactApplicationContext).registerReceiver(
            MusicEvents(reactApplicationContext),
            IntentFilter(EVENT_INTENT)
        )

        Intent(reactApplicationContext, MusicService::class.java).also { intent ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                reactApplicationContext.startForegroundService(intent)
            } else {
                reactApplicationContext.startService(intent)
            }
            reactApplicationContext.bindService(intent, this, Context.BIND_AUTO_CREATE)
        }
    }

    @ReactMethod
    fun setBrowseTree(mediaItems: ReadableMap?, callback: Promise) {
        waitForConnection {
            if (musicService != null) {
                executor.submit(Callable {
                    val mediaItemsMap = mediaItems?.toHashMap()
                    if (mediaItemsMap != null) {
                        musicService!!.mediaTree =
                            mediaItemsMap.mapValues {
                                readableArrayToMediaItems(
                                    it.key,
                                    it.value as ArrayList<HashMap<String, String>>
                                )
                            } as MutableMap<String, MutableList<MediaItem>>
                    }

                    musicService!!.toUpdateMediaItems.forEach { (parentMediaId, toUpdateItems) ->
                        val items =
                            musicService!!.mediaTree.getOrPut(parentMediaId) { mutableListOf() }
                        toUpdateItems.forEach { toUpdateItem ->
                            val index = items.indexOfFirst { it.mediaId == toUpdateItem.mediaId }
                            if (index != -1) {
                                items[index] = toUpdateItem
                            }
                        }
                        musicService!!.mediaTree[parentMediaId] = items
                    }

                    musicService!!.mediaTree.keys.forEach {
                        musicService!!.notifyChildrenChanged(it)
                    }
                    callback.resolve(null)
                })
            }
        }
    }

    @ReactMethod
    fun updateBrowseTree(mediaItems: ReadableMap, callback: Promise) {
        if (musicService != null) {
            executor.submit(Callable {
                val mediaItemsMap = mediaItems.toHashMap()
                musicService!!.mediaTree += mediaItemsMap.mapValues {
                    readableArrayToMediaItems(
                        it.key,
                        it.value as ArrayList<HashMap<String, String>>
                    )
                } as MutableMap<String, MutableList<MediaItem>>
                mediaItemsMap.keys.forEach {
                    musicService!!.notifyChildrenChanged(it)
                }
                callback.resolve(null)
            })
        }
    }

    @ReactMethod
    fun setSearchResult(mediaItems: ReadableArray, data: ReadableMap, callback: Promise) {
        waitForConnection {
            if (musicService != null) {
                val bundle = Arguments.toBundle(data)
                val query = bundle?.getString("query")
                if (query == musicService!!.searchQuery) {
                    musicService!!.searchResult?.sendResult(
                        readableArrayToMediaItems(
                            "search",
                            mediaItems.toArrayList() as ArrayList<HashMap<String, String>>
                        )
                    )
                }
                callback.resolve(null)
            }
        }
    }

    @ReactMethod
    fun destroy() {
        // Ignore if it was already destroyed
        if (binder == null && !connecting) return
        try {
            if (binder != null) {
                binder!!.destroy()
                binder = null
            }
            val context: ReactContext? = reactApplicationContext
            context?.unbindService(this)
        } catch (ex: Exception) {
            // This method shouldn't be throwing unhandled errors even if something goes wrong.
            Log.e(Utils.LOG, "An error occurred while destroying the service", ex)
        }
    }

    @ReactMethod
    fun updateAndroidAutoPlayerOptions(data: ReadableMap?, callback: Promise) = scope.launch {
        waitForConnection {
            val options = Arguments.toBundle(data)

            options?.let {
                musicService?.updateOptions(it)
            }

            callback.resolve(null)
        }
    }

    @ReactMethod
    fun updateOptions(data: ReadableMap?, callback: Promise) {
        // keep options as we may need them for correct MetadataManager reinitialization later
        options = Arguments.toBundle(data)
        waitForConnection {
            options?.let { binder?.updateOptions(it) }
            callback.resolve(null)
        }
    }

    @ReactMethod
    fun setNowPlaying(trackMap: ReadableMap, callback: Promise) {
        val bundle = Arguments.toBundle(trackMap)

        waitForConnection {
            try {
                val track = bundle?.let {
                    binder?.let { it1 ->
                        Track(
                            reactApplicationContext,
                            it,
                            it1.ratingType
                        )
                    }
                }
                if (track != null) {
                    binder?.manager?.currentTrack = track
                    binder?.manager?.metadata?.updateMetadata(track)
                }
            } catch (ex: Exception) {
                callback.reject("invalid_track_object", ex)
                return@waitForConnection // @waitForConnection
            }

            if (binder != null && binder!!.manager != null && binder!!.manager.currentTrack == null)
                callback.reject("invalid_track_object", "Track is missing a required key")

            callback.resolve(null)
        }
    }

    @ReactMethod
    fun setAndroidAutoPlayerTracks(tracksArray: ReadableArray, options: ReadableMap, callback: Promise) {
        scope.launch {
            waitForConnection {
                val bundle = Arguments.toBundle(options)
                val editQueue = bundle?.getBoolean("editQueue")

                val tracks = mutableListOf<Track>()

                for (i in 0 until tracksArray.size()) {
                    val trackMap = tracksArray.getMap(i)
                    val trackBundle = Arguments.toBundle(trackMap)

                    val track = trackBundle?.let {
                        binder?.let { it1 ->
                            Track(
                                reactApplicationContext,
                                it,
                                it1.ratingType
                            )
                        }
                    }

                    track?.let { tracks.add(it) }
                }

                if (tracks.isNotEmpty()) {
                    if (editQueue == true) {
                        musicService?.removeUpcomingTracks()
                        musicService?.removePreviousTracks()

                        musicService?.add(
                            tracks,
                            1
                        )
                    } else {
                        if (tracks[0].url.isEmpty()) {
                            musicService?.playWhenReady = false
                            if (musicService?.state != AudioPlayerState.PAUSED) musicService?.pause()
                        } else {
                            musicService?.playWhenReady = true
                        }

                        if (musicService?.getPlayerQueueHead() == null) {
                            musicService?.add(
                                tracks,
                                0
                            )
                        } else {
                            musicService?.removeUpcomingTracks()
                            musicService?.removePreviousTracks()

                            musicService?.add(
                                tracks,
                                1
                            )

                            musicService?.skipToNext()

                            musicService?.updateMetadataForTrack(0, tracks[0])

                            musicService?.remove(0)
                        }
                    }
                }
            }
        }

    }

    @ReactMethod
    fun setAndroidAutoPlayerState(trackMap: ReadableMap, callback: Promise) {
        scope.launch {
            waitForConnection {
                val bundle = Arguments.toBundle(trackMap)

                val state = bundle?.getString("state")
                val elapsedTime = bundle?.getDouble("elapsedTime")
                val isLoading = bundle?.getBoolean("isLoading")

                if (state != null) {
                    if (state == "playing") {
                        musicService?.play()
                    } else if (state == "paused") {
                        musicService?.pause()
                    }
                }
                if (elapsedTime != null && isLoading == false) {
                    musicService?.seekTo(elapsedTime.toFloat())
                }
            }
        }

    }


    @ReactMethod
    fun updatePlayback(trackMap: ReadableMap, callback: Promise) {
        val bundle = Arguments.toBundle(trackMap)
        waitForConnection {
            try {
                val state =
                    if (trackMap.hasKey("state")) trackMap.getInt("state") else 0
                var elapsedTime: Long = -1
                if (trackMap.hasKey("elapsedTime")) {
                    elapsedTime = try {
                        Utils.toMillis(trackMap.getInt("elapsedTime").toDouble())
                    } catch (ex: Exception) {
                        Utils.toMillis(trackMap.getDouble("elapsedTime"))
                    }
                }

                var duration: Long = -1
                if (trackMap.hasKey("duration")) {
                    duration = try {
                        Utils.toMillis(trackMap.getInt("duration").toDouble())
                    } catch (ex: Exception) {
                        Utils.toMillis(trackMap.getDouble("duration"))
                    }
                }
                //update current track duration
                val currentTrack: Track? =
                    binder?.manager?.currentTrack
                if (currentTrack != null && duration > -1) {
                    currentTrack.duration = duration
                    binder?.manager?.metadata?.updateMetadata(currentTrack)
                }
                binder?.manager?.setState(state, elapsedTime)

                if (AutoConnectionDetector.isCarConnected) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        val isLoading = bundle?.getBoolean("isLoading")
                        if (state == 3) {
                            musicService?.play()
                        } else if (state == 2) {
                            musicService?.pause()
                        }
                        if (elapsedTime >= 0 && isLoading == false) {
                            musicService?.seekTo((elapsedTime / 1000).toFloat())
                        }
                    }, 100)
                }

            } catch (ex: Exception) {
                callback.reject("invalid_track_object", ex)
                return@waitForConnection
            }
            if (binder != null && binder!!.manager
                    .currentTrack == null
            ) callback.reject("invalid_track_object", "Track is missing a required key")
        }
    }


    private fun bundleToTrack(bundle: Bundle): Track? {
        return musicService?.let {
            Track(
                reactApplicationContext.baseContext,
                bundle,
                it.ratingType
            )
        }
    }

    private fun readableArrayToTrackList(data: ReadableArray?): MutableList<Track> {
        val bundleList = Arguments.toList(data)
        if (bundleList !is ArrayList) {
            // throw RejectionException("invalid_parameter", "Was not given an array of tracks")
        }
        if (bundleList != null) {
            return bundleList.mapNotNull {
                if (it is Bundle) {
                    bundleToTrack(it)
                } else {
                    null
                    // or handle invalid bundle case
                }
            }.toMutableList()
        }
        return mutableListOf()
    }

    @ReactMethod
    fun add(data: ReadableArray?, insertBeforeIndex: Int, callback: Promise) = scope.launch {
        if (verifyServiceBoundOrReject(callback)) return@launch

        try {
            val tracks = readableArrayToTrackList(data);
            if (insertBeforeIndex < -1 || insertBeforeIndex > (musicService?.tracks?.size
                    ?: 0)
            ) {
                callback.reject("index_out_of_bounds", "The track index is out of bounds")
                return@launch
            }
            val index =
                if (insertBeforeIndex == -1) musicService?.tracks?.size else insertBeforeIndex
            if (index != null) {
                musicService?.add(
                    tracks,
                    index
                )
            }
            callback.resolve(index)
        } catch (exception: Exception) {
            // rejectWithException(callback, exception)
        }
    }

    @ReactMethod
    fun load(data: ReadableMap?, callback: Promise) = scope.launch {
        if (verifyServiceBoundOrReject(callback)) return@launch
        if (data == null) {
            callback.resolve(null)
            return@launch
        }
        val bundle = Arguments.toBundle(data);
        if (bundle is Bundle) {
            bundleToTrack(bundle)?.let { musicService?.load(it) }
            callback.resolve(null)
        } else {
            callback.reject("invalid_track_object", "Track was not a dictionary type")
        }
    }

    @ReactMethod
    fun reset(callback: Promise) {
        waitForConnection {
            if (binder != null) {
                binder!!.manager.onReset()
                callback.resolve(null)
            }
        }
    }

    @ReactMethod
    fun remove(callback: Promise) {
        waitForConnection {
            if (binder != null) {
                binder!!.manager.metadata.removeNotifications()
                callback.resolve(null)
            }
        }
    }

    @ReactMethod
    fun resetAndroidAutoService(callback: Promise) = scope.launch {
        if (verifyServiceBoundOrReject(callback)) return@launch

        musicService?.stop()
        delay(300) // Allow playback to stop
        musicService?.clear()

        callback.resolve(null)
    }

    @ReactMethod
    fun clear(callback: Promise) = scope.launch {
        if (verifyServiceBoundOrReject(callback)) return@launch

        musicService?.clear()
        callback.resolve(null)
    }

    @ReactMethod
    fun play(callback: Promise) = scope.launch {
        if (verifyServiceBoundOrReject(callback)) return@launch

        musicService?.play()
        callback.resolve(null)
    }

    @ReactMethod
    fun pause(callback: Promise) = scope.launch {
        if (verifyServiceBoundOrReject(callback)) return@launch

        musicService?.pause()
        callback.resolve(null)
    }

    override fun onHostResume() {}

    override fun onHostPause() {}

    override fun onHostDestroy() {
        if (AutoConnectionDetector.isCarConnected) {
            musicService?.clear()

            Handler(Looper.getMainLooper()).postDelayed({
                if (AutoConnectionDetector.isCarConnected) {
                    musicService?.invokeStartTask(reactApplicationContext, true)
                }
            }, 2000)
        }
    }
}