package com.guichaguri.trackplayer.module

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.*
import android.net.Uri
import android.os.*
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.RatingCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media.utils.MediaConstants
import com.facebook.react.bridge.*
import com.google.android.exoplayer2.DefaultLoadControl.*
import com.guichaguri.trackplayer.service.MusicBinder
import com.guichaguri.trackplayer.service.MusicService
import com.guichaguri.trackplayer.service.Utils
import com.guichaguri.trackplayer.service.Utils.EVENT_INTENT
import com.guichaguri.trackplayer.model.Track
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*
import javax.annotation.Nonnull

/**
 * @author Guichaguri
 */
class MusicModule(reactContext: ReactApplicationContext?) :
    ReactContextBaseJavaModule(reactContext), ServiceConnection {
    private var playerOptions: Bundle? = null
    private var isServiceBound = false
    // private var binder: MusicBinder? = null
    private var eventHandler: MusicEvents? = null
    private val initCallbacks = ArrayDeque<Runnable>()
    private var connecting = false
    private var options: Bundle? = null
    private var musicService: MusicService? = null
    private val scope = MainScope()

    companion object {
        var binder: MusicBinder? = null
    }

    @Nonnull
    override fun getName(): String {
        return "TrackPlayerModule"
    }

    override fun initialize() {
        Log.d("convertedMediaItems", "convertedMediaItems initialize")
        val context: ReactContext = reactApplicationContext
        val manager = LocalBroadcastManager.getInstance(context)
        eventHandler = MusicEvents(context)
        manager.registerReceiver(eventHandler!!, IntentFilter(Utils.EVENT_INTENT))
    }

    override fun onCatalystInstanceDestroy() {
        val context: ReactContext = reactApplicationContext
        if (eventHandler != null) {
            val manager = LocalBroadcastManager.getInstance(context)
            manager.unregisterReceiver(eventHandler!!)
            eventHandler = null
        }
    }

    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        Log.d("convertedMediaItems", "convertedMediaItems onServiceConnected")
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
        Log.d("convertedMediaItems", "convertedMediaItems waitForConnection")
        Log.d("convertedMediaItems", "convertedMediaItems waitForConnection binder: " + binder)
        Log.d("convertedMediaItems", "convertedMediaItems waitForConnection connecting: " + connecting)
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
            Log.d("convertedMediaItems", "convertedMediaItems waitForConnection startService")
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

    private fun hashmapToMediaItem(hashmap: HashMap<String, String?>): MediaBrowserCompat.MediaItem {
        val mediaId = hashmap["mediaId"]
        val title = hashmap["title"]
        val subtitle = hashmap["subtitle"]
        val mediaUri = hashmap["mediaUri"]
        val iconUri = hashmap["iconUri"]
        val playableFlag = if (hashmap["playable"] != null && hashmap["playable"]!!
                .toInt() == 1
        ) MediaBrowserCompat.MediaItem.FLAG_BROWSABLE else MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
        val mediaDescriptionBuilder = MediaDescriptionCompat.Builder()
        mediaDescriptionBuilder.setMediaId(mediaId)
        mediaDescriptionBuilder.setTitle(title)
        mediaDescriptionBuilder.setSubtitle(subtitle)
        mediaDescriptionBuilder.setMediaUri(if (mediaUri != null) Uri.parse(mediaUri) else null)
        mediaDescriptionBuilder.setIconUri(if (iconUri != null) Uri.parse(iconUri) else null)
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
        return MediaBrowserCompat.MediaItem(mediaDescriptionBuilder.build(), playableFlag)
    }

    private fun readableArrayToMediaItems(data: ArrayList<HashMap<String, String?>>): List<MediaBrowserCompat.MediaItem> {
        val mediaItemList: MutableList<MediaBrowserCompat.MediaItem> = ArrayList()
        for (hashmap in data) {
            mediaItemList.add(hashmapToMediaItem(hashmap))
        }
        return mediaItemList
    }

    @ReactMethod
    fun setupPlayer(data: ReadableMap?, promise: Promise) {
        Log.d("reactMethodTest", "reactMethodTest" + "setupPlayer")
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
    fun setBrowseTree(mediaItems: ReadableMap, callback: Promise) {
        Log.d("reactMethodTest", "reactMethodTest" + "setBrowseTree")
        // scope.launch(() -> {
        // if (verifyServiceBoundOrReject(callback)) return@launch;
//        UiThreadUtil.runOnUiThread(new Runnable() {
        scope.launch {
            val mediaItemsMap = mediaItems.toHashMap()
            val convertedMediaItems: MutableMap<String, List<MediaBrowserCompat.MediaItem>> =
                HashMap()
            for ((key, value) in mediaItemsMap) {
                if (value is ArrayList<*>) {
                    val arrayList =
                        value as ArrayList<HashMap<String, String?>>
                    val mediaItemList = readableArrayToMediaItems(arrayList)
                    convertedMediaItems[key] = mediaItemList
                }
            }
            Log.d(
                "convertedMediaItems",
                "convertedMediaItems: $convertedMediaItems"
            )
            Log.d(
                "convertedMediaItems",
                "convertedMediaItems mediaItemsMap: $mediaItemsMap"
            )
            Log.d(
                "convertedMediaItems",
                "convertedMediaItems mediaItemsMap.keySet(): " + mediaItemsMap.keys
            )
            Log.d(
                "convertedMediaItems",
                "convertedMediaItems musicService: $musicService"
            )
            if (musicService != null) {
                musicService!!.mediaTree = convertedMediaItems
            }
            // Timber.d("refreshing browseTree");
            Log.d(
                "convertedMediaItems",
                "convertedMediaItems: $convertedMediaItems"
            )
            for (key in mediaItemsMap.keys) {
                Log.d(
                    "convertedMediaItems",
                    "pre convertedMediaItems notification$key"
                )
                musicService?.notifyChildrenChanged(key)
                Log.d("aaserv", "aaserv notified")
                Log.d(
                    "convertedMediaItems",
                    "convertedMediaItems notification$key"
                )
            }
            callback.resolve(musicService?.mediaTree.toString())
            // });
        }
    }

    @ReactMethod
    fun setBrowseTreeStyle(browsableStyle: Int, playableStyle: Int, callback: Promise) {
        // scope.launch(() -> {
        // if (verifyServiceBoundOrReject(callback)) return@launch;
        scope.launch {
            val browsable = getStyle(browsableStyle)
            val playable = getStyle(playableStyle)
            if (musicService != null) {
                musicService!!.mediaTreeStyle = Arrays.asList(browsable, playable)
            }
            callback.resolve(null)
            // });
        }
    }

    private fun getStyle(check: Int): Int {
        return when (check) {
            2 -> MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
            3 -> MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_LIST_ITEM
            4 -> MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_GRID_ITEM
            else -> MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
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
        Log.d("reactMethodTest", "reactMethodTest" + "updateAndroidAutoPlayerOptions")
        // if (verifyServiceBoundOrReject(callback)) return@launch

        val options = Arguments.toBundle(data)

        options?.let {
            musicService?.updateOptions(it)
        }

        callback.resolve(null)
    }

    @ReactMethod
    fun updateOptions(data: ReadableMap?, callback: Promise) {
        Log.d("reactMethodTest", "reactMethodTest" + "updateOptions")
        // keep options as we may need them for correct MetadataManager reinitialization later
        options = Arguments.toBundle(data)
        waitForConnection {
            options?.let { binder?.updateOptions(it) }
            callback.resolve(null)
        }
    }

    @ReactMethod
    fun setNowPlaying(trackMap: ReadableMap, callback: Promise) {
        Log.d("reactMethodTest", "reactMethodTest" + "setNowPlaying")
        Log.d("convertedMediaItems", "convertedMediaItems setNowPlaying")
        Log.d("convertedMediaItems", "convertedMediaItems setNowPlaying trackMap: " + trackMap)
        val bundle = Arguments.toBundle(trackMap)

        //waitForConnection {
            try {
                Log.d("convertedMediaItems", "convertedMediaItems setNowPlaying IN")
                val state = if (trackMap.hasKey("state")) trackMap.getInt("state") else 0

                var elapsedTime: Long = -1

                if (trackMap.hasKey("elapsedTime")) {
                    elapsedTime = try {
                        Utils.toMillis(trackMap.getDouble("elapsedTime"))
                    } catch (ex: Exception) {
                        Log.d("convertedMediaItems", "convertedMediaItems setNowPlaying elapsedTime catch")
                        Utils.toMillis(trackMap.getInt("elapsedTime").toDouble())
                    }
                }

                Log.d("convertedMediaItems", "convertedMediaItems setNowPlaying elapsedTime after")
                val track = bundle?.let {
                    binder?.let { it1 ->
                        Log.d("convertedMediaItems", "convertedMediaItems setNowPlaying reactApplicationContext: " + reactApplicationContext)
                        Log.d("convertedMediaItems", "convertedMediaItems setNowPlaying it: " + it)
                        Log.d("convertedMediaItems", "convertedMediaItems setNowPlaying it1: " + it1)
                        Log.d("convertedMediaItems", "convertedMediaItems setNowPlaying  it1.ratingType: " +  it1.ratingType)
                        Track(
                            reactApplicationContext,
                            it,
                            it1.ratingType
                        )
                    }
                }
                Log.d("convertedMediaItems", "convertedMediaItems setNowPlaying track: " + track)
                Log.d("convertedMediaItems", "convertedMediaItems setNowPlaying binder: " + binder)
                Log.d(
                    "convertedMediaItems",
                    "convertedMediaItems setNowPlaying manager: " + binder?.manager
                )


                if (track != null) {
                    binder?.manager?.currentTrack = track
                    binder?.manager?.metadata?.updateMetadata(track)
                }

                Log.d("playertest", "playertest track test" + (track != null))
//                if (track != null) {
//                    Log.d("playertest", "playertest track not null")
//                    binder?.manager?.metadata?.updateMetadata(track)
//                    Log.d("playertest", "playertest updateMetadata")
//
//                    Log.d("isServiceBound", "isServiceBound: " + isServiceBound)
//
//                    // musicService?.setupPlayer(playerOptions)
//                    Log.d("playertest", "playertest add")
//                    musicService?.add(
//                        listOf(track),
//                        0
//                    )
//                    Log.d("playertest", "playertest added")
////                    Log.d("playertest", "playertest setupPlayer")
//                    Log.d("playertest", "playertest load")
//                    musicService?.load(track)
//                    Log.d("playertest", "playertest loaded")
//                    Log.d("playertest", "playertest play")
//                    musicService?.play()
//                    Log.d("playertest", "playertest played")
//
//                }
                binder?.manager?.setState(state, elapsedTime)
            } catch (ex: Exception) {
                callback.reject("invalid_track_object", ex)
                return // @waitForConnection
            }

            if (binder != null && binder!!.manager != null && binder!!.manager.currentTrack == null)
                callback.reject("invalid_track_object", "Track is missing a required key")

            callback.resolve(null)
        //}
    }

    @ReactMethod
    fun setAndroidAutoPlayerData(trackMap: ReadableMap, callback: Promise) {
        Log.d("reactMethodTest", "reactMethodTest" + "setAndroidAutoPlayerData")

        scope.launch {
            Log.d("setPlayer", "setPlayer setAndroidAutoPlayerData")
            val bundle = Arguments.toBundle(trackMap)

            val track = bundle?.let {
                binder?.let { it1 ->
                    Track(
                        reactApplicationContext,
                        it,
                        it1.ratingType
                    )
                }
            }

            Log.d("setAndroidAutoPlayerData", "setAndroidAutoPlayerData track title: " + track?.title)
            Log.d("setAndroidAutoPlayerData", "setAndroidAutoPlayerData track url: " + track?.url)
            Log.d("setAndroidAutoPlayerData", "setAndroidAutoPlayerData track artwork: " + track?.artwork)

            if (track != null) {
                Log.d("setAndroidAutoPlayerData setPlayer", "setPlayer load")
                musicService?.load(track)
                Log.d("setAndroidAutoPlayerData setPlayer", "setPlayer loaded")

                Log.d("setAndroidAutoPlayerData setPlayer", "setPlayer add")
                musicService?.add(
                    listOf(track),
                    0
                )
                Log.d("setAndroidAutoPlayerData setPlayer", "setPlayer added")

                Log.d("setAndroidAutoPlayerData setPlayer", "setPlayer play")
                musicService?.play()
                Log.d("setAndroidAutoPlayerData setPlayer", "setPlayer played")
            }
        }

    }

    @ReactMethod
    fun setAndroidAutoPlayerState(trackMap: ReadableMap, callback: Promise) {
        Log.d("reactMethodTest", "reactMethodTest" + "setAndroidAutoPlayerState")

        scope.launch {
            Log.d("setPlayer", "setPlayer setAndroidAutoPlayerState")
            val bundle = Arguments.toBundle(trackMap)

            Log.d("setAndroidAutoPlayerState", "setAndroidAutoPlayerState bundle: " + bundle)

            val state = bundle?.getString("state")
            val elapsedTime = bundle?.getDouble("elapsedTime")

            if (state != null) {
                Log.d("setAndroidAutoPlayerState", "setAndroidAutoPlayerState: " + state)
                if (state == "playing") {
                    musicService?.play()
                } else if (state == "paused") {
                    musicService?.pause()
                }
            }
            if (elapsedTime != null && elapsedTime > 0) {
                musicService?.seekTo(elapsedTime.toFloat())
            }
        }

    }


    @ReactMethod
    fun updatePlayback(trackMap: ReadableMap, callback: Promise) {
        Log.d("reactMethodTest", "reactMethodTest" + "updatePlayback")
        val bundle = Arguments.toBundle(trackMap)
        waitForConnection {
            try {
                val state =
                    if (trackMap.hasKey("state")) trackMap.getInt("state") else 0
                var elapsedTime: Long = -1
                if (trackMap.hasKey("elapsedTime")) {
                    elapsedTime = try {
                        Log.d("updatePlayback elapsedTime", "updatePlayback elapsedTime: " + trackMap.getInt("elapsedTime"))
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
            } catch (ex: Exception) {
                callback.reject("invalid_track_object", ex)
                return@waitForConnection
            }
            if (binder != null && binder!!.manager != null && binder!!.manager
                    .currentTrack == null
            ) callback.reject("invalid_track_object", "Track is missing a required key")
        }
    }


    private fun bundleToTrack(bundle: Bundle): Track? {
        return musicService?.let { Track(reactApplicationContext.baseContext, bundle, it.ratingType) }
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
        Log.d("reactMethodTest", "reactMethodTest" + "add")
        if (verifyServiceBoundOrReject(callback)) return@launch

        try {
            val tracks = readableArrayToTrackList(data);
            if (insertBeforeIndex < -1 || insertBeforeIndex > (musicService?.tracks?.size ?: 0)) {
                callback.reject("index_out_of_bounds", "The track index is out of bounds")
                return@launch
            }
            val index = if (insertBeforeIndex == -1) musicService?.tracks?.size else insertBeforeIndex
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
//
    @ReactMethod
    fun load(data: ReadableMap?, callback: Promise) = scope.launch {
    Log.d("reactMethodTest", "reactMethodTest" + "load")
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
//
//    @ReactMethod
//    fun move(fromIndex: Int, toIndex: Int, callback: Promise) = scope.launch {
//        if (verifyServiceBoundOrReject(callback)) return@launch
//        musicService.move(fromIndex, toIndex)
//        callback.resolve(null)
//    }
//
//    @ReactMethod
//    fun remove(data: ReadableArray?, callback: Promise) = scope.launch {
//        if (verifyServiceBoundOrReject(callback)) return@launch
//        val inputIndexes = Arguments.toList(data)
//        if (inputIndexes != null) {
//            val size = musicService.tracks.size
//            val indexes: ArrayList<Int> = ArrayList();
//            for (inputIndex in inputIndexes) {
//                val index = if (inputIndex is Int) inputIndex else inputIndex.toString().toInt()
//                if (index < 0 || index >= size) {
//                    callback.reject(
//                        "index_out_of_bounds",
//                        "One or more indexes was out of bounds"
//                    )
//                    return@launch
//                }
//                indexes.add(index)
//            }
//            musicService.remove(indexes)
//        }
//        callback.resolve(null)
//    }
//
//    @ReactMethod
//    fun updateMetadataForTrack(index: Int, map: ReadableMap?, callback: Promise) =
//        scope.launch {
//            if (verifyServiceBoundOrReject(callback)) return@launch
//
//            if (index < 0 || index >= musicService.tracks.size) {
//                callback.reject("index_out_of_bounds", "The index is out of bounds")
//            } else {
//                val context: ReactContext = context
//                val track = musicService.tracks[index]
//                track.setMetadata(context, Arguments.toBundle(map), musicService.ratingType)
//                musicService.updateMetadataForTrack(index, track)
//
//                callback.resolve(null)
//            }
//        }
//
//    @ReactMethod
//    fun updateNowPlayingMetadata(map: ReadableMap?, callback: Promise) = scope.launch {
//        if (verifyServiceBoundOrReject(callback)) return@launch
//
//        if (musicService.tracks.isEmpty())
//            callback.reject("no_current_item", "There is no current item in the player")
//
//        val context: ReactContext = context
//        Arguments.toBundle(map)?.let {
//            val track = bundleToTrack(it)
//            musicService.updateNowPlayingMetadata(track)
//        }
//
//        callback.resolve(null)
//    }
//
//    @ReactMethod
//    fun clearNowPlayingMetadata(callback: Promise) = scope.launch {
//        if (verifyServiceBoundOrReject(callback)) return@launch
//
//        if (musicService.tracks.isEmpty())
//            callback.reject("no_current_item", "There is no current item in the player")
//
//        musicService.clearNotificationMetadata()
//        callback.resolve(null)
//    }
//
//    @ReactMethod
//    fun removeUpcomingTracks(callback: Promise) = scope.launch {
//        if (verifyServiceBoundOrReject(callback)) return@launch
//
//        musicService.removeUpcomingTracks()
//        callback.resolve(null)
//    }
//
//    @ReactMethod
//    fun skip(index: Int, initialTime: Float, callback: Promise) = scope.launch {
//        if (verifyServiceBoundOrReject(callback)) return@launch
//
//        musicService.skip(index)
//
//        if (initialTime >= 0) {
//            musicService.seekTo(initialTime)
//        }
//
//        callback.resolve(null)
//    }
//
//    @ReactMethod
//    fun skipToNext(initialTime: Float, callback: Promise) = scope.launch {
//        if (verifyServiceBoundOrReject(callback)) return@launch
//
//        musicService.skipToNext()
//
//        if (initialTime >= 0) {
//            musicService.seekTo(initialTime)
//        }
//
//        callback.resolve(null)
//    }
//
//    @ReactMethod
//    fun skipToPrevious(initialTime: Float, callback: Promise) = scope.launch {
//        if (verifyServiceBoundOrReject(callback)) return@launch
//
//        musicService.skipToPrevious()
//
//        if (initialTime >= 0) {
//            musicService.seekTo(initialTime)
//        }
//
//        callback.resolve(null)
//    }

//    @ReactMethod
//    fun reset(callback: Promise) {
//        waitForConnection {
//            if (binder != null && binder!!.manager != null) {
//                binder!!.manager.onReset()
//                callback.resolve(null)
//            }
//        }
//    }

    @ReactMethod
    fun reset(callback: Promise) = scope.launch {
        Log.d("reactMethodTest", "reactMethodTest" + "reset")
        if (verifyServiceBoundOrReject(callback)) return@launch

        musicService?.stop()
        delay(300) // Allow playback to stop
        musicService?.clear()

        callback.resolve(null)
    }

    @ReactMethod
    fun play(callback: Promise) = scope.launch {
        Log.d("reactMethodTest", "reactMethodTest" + "play")
        if (verifyServiceBoundOrReject(callback)) return@launch

        musicService?.play()
        callback.resolve(null)
    }

    @ReactMethod
    fun pause(callback: Promise) = scope.launch {
        Log.d("reactMethodTest", "reactMethodTest" + "pause")
        if (verifyServiceBoundOrReject(callback)) return@launch

        musicService?.pause()
        callback.resolve(null)
    }

//    @ReactMethod
//    fun stop(callback: Promise) = scope.launch {
//        if (verifyServiceBoundOrReject(callback)) return@launch
//
//        musicService?.stop()
//        callback.resolve(null)
//    }
//
//    @ReactMethod
//    fun seekTo(seconds: Float, callback: Promise) = scope.launch {
//        if (verifyServiceBoundOrReject(callback)) return@launch
//
//        musicService?.seekTo(seconds)
//        callback.resolve(null)
//    }
//
//    @ReactMethod
//    fun seekBy(offset: Float, callback: Promise) = scope.launch {
//        if (verifyServiceBoundOrReject(callback)) return@launch
//
//        musicService?.seekBy(offset)
//        callback.resolve(null)
//    }
//
//    @ReactMethod
//    fun retry(callback: Promise) = scope.launch {
//        if (verifyServiceBoundOrReject(callback)) return@launch
//
//        musicService?.retry()
//        callback.resolve(null)
//    }
//
//    @ReactMethod
//    fun setVolume(volume: Float, callback: Promise) = scope.launch {
//        if (verifyServiceBoundOrReject(callback)) return@launch
//
//        musicService?.setVolume(volume)
//        callback.resolve(null)
//    }
//
//    @ReactMethod
//    fun getVolume(callback: Promise) = scope.launch {
//        if (verifyServiceBoundOrReject(callback)) return@launch
//
//        callback.resolve(musicService?.getVolume())
//    }
//
//    @ReactMethod
//    fun setRate(rate: Float, callback: Promise) = scope.launch {
//        if (verifyServiceBoundOrReject(callback)) return@launch
//
//        musicService?.setRate(rate)
//        callback.resolve(null)
//    }
//
//    @ReactMethod
//    fun getRate(callback: Promise) = scope.launch {
//        if (verifyServiceBoundOrReject(callback)) return@launch
//
//        callback.resolve(musicService?.getRate())
//    }
//
//    @ReactMethod
//    fun setRepeatMode(mode: Int, callback: Promise) = scope.launch {
//        if (verifyServiceBoundOrReject(callback)) return@launch
//
//        musicService?.setRepeatMode(RepeatMode.fromOrdinal(mode))
//        callback.resolve(null)
//    }
//
//    @ReactMethod
//    fun getRepeatMode(callback: Promise) = scope.launch {
//        if (verifyServiceBoundOrReject(callback)) return@launch
//
//        callback.resolve(musicService.getRepeatMode().ordinal)
//    }
//
//    @ReactMethod
//    fun setPlayWhenReady(playWhenReady: Boolean, callback: Promise) = scope.launch {
//        if (verifyServiceBoundOrReject(callback)) return@launch
//
//        musicService.playWhenReady = playWhenReady
//        callback.resolve(null)
//    }
//
//    @ReactMethod
//    fun getPlayWhenReady(callback: Promise) = scope.launch {
//        if (verifyServiceBoundOrReject(callback)) return@launch
//
//        callback.resolve(musicService.playWhenReady)
//    }
//
//    @ReactMethod
//    fun getTrack(index: Int, callback: Promise) = scope.launch {
//        if (verifyServiceBoundOrReject(callback)) return@launch
//
//        if (index >= 0 && musicService.tracks != null && index < musicService.tracks.size) {
//            callback.resolve(Arguments.fromBundle(musicService?.tracks[index].originalItem))
//        } else {
//            callback.resolve(null)
//        }
//    }
//
//    @ReactMethod
//    fun getQueue(callback: Promise) = scope.launch {
//        if (verifyServiceBoundOrReject(callback)) return@launch
//
//        callback.resolve(Arguments.fromList(musicService?.tracks.map { it.originalItem }))
//    }

//    @ReactMethod
//    fun setQueue(data: ReadableArray?, callback: Promise) = scope.launch {
//        if (verifyServiceBoundOrReject(callback)) return@launch
//
//        try {
//            musicService.clear()
//            musicService.add(readableArrayToTrackList(data))
//            callback.resolve(null)
//        } catch (exception: Exception) {
//            rejectWithException(callback, exception)
//        }
//    }

//    @ReactMethod
//    fun getActiveTrackIndex(callback: Promise) = scope.launch {
//        if (verifyServiceBoundOrReject(callback)) return@launch
//        callback.resolve(
//            if (musicService?.tracks.isEmpty()) null else musicService?.getCurrentTrackIndex()
//        )
//    }
//
//    @ReactMethod
//    fun getActiveTrack(callback: Promise) = scope.launch {
//        if (verifyServiceBoundOrReject(callback)) return@launch
//        callback.resolve(
//            if (musicService.tracks.isEmpty()) null
//            else Arguments.fromBundle(
//                musicService.tracks[musicService.getCurrentTrackIndex()].originalItem
//            )
//        )
//    }
//
//    @ReactMethod
//    fun getDuration(callback: Promise) = scope.launch {
//        if (verifyServiceBoundOrReject(callback)) return@launch
//
//        callback.resolve(musicService.getDurationInSeconds())
//    }
//
//    @ReactMethod
//    fun getBufferedPosition(callback: Promise) = scope.launch {
//        if (verifyServiceBoundOrReject(callback)) return@launch
//
//        callback.resolve(musicService.getBufferedPositionInSeconds())
//    }
//
//    @ReactMethod
//    fun getPosition(callback: Promise) = scope.launch {
//        if (verifyServiceBoundOrReject(callback)) return@launch
//
//        callback.resolve(musicService.getPositionInSeconds())
//    }
//
//    @ReactMethod
//    fun getProgress(callback: Promise) = scope.launch {
//        if (verifyServiceBoundOrReject(callback)) return@launch
//        var bundle = Bundle()
//        bundle.putDouble("duration", musicService.getDurationInSeconds());
//        bundle.putDouble("position", musicService.getPositionInSeconds());
//        bundle.putDouble("buffered", musicService.getBufferedPositionInSeconds());
//        callback.resolve(Arguments.fromBundle(bundle))
//    }
//
//    @ReactMethod
//    fun getPlaybackState(callback: Promise) = scope.launch {
//        if (verifyServiceBoundOrReject(callback)) return@launch
//        callback.resolve(Arguments.fromBundle(musicService.getPlayerStateBundle(musicService.state)))
//    }
}