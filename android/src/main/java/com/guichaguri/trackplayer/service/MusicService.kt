package com.guichaguri.trackplayer.service

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.browse.MediaBrowser.MediaItem
import android.os.*
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.RatingCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.MainThread
import androidx.core.app.NotificationCompat
import androidx.core.content.PackageManagerCompat
import androidx.media.session.MediaButtonReceiver
import androidx.media.utils.MediaConstants
// import com.doublesymmetry.kotlinaudio.models.*
// import com.guichaguri.trackplayer.kotlinaudio.players.QueuedAudioPlayer
import com.guichaguri.trackplayer.kotlinaudio.models.NotificationButton.*
import com.facebook.react.ReactInstanceManager
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.WritableNativeMap
import com.facebook.react.jstasks.HeadlessJsTaskConfig
import com.facebook.react.modules.appregistry.AppRegistry
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.guichaguri.trackplayer.HeadlessJsMediaService
import com.guichaguri.trackplayer.R
import com.guichaguri.trackplayer.extensions.asLibState
import com.guichaguri.trackplayer.kotlinaudio.models.*
import com.guichaguri.trackplayer.kotlinaudio.players.QueuedAudioPlayer
import com.guichaguri.trackplayer.model.Track
import com.guichaguri.trackplayer.model.TrackAudioItem
import com.guichaguri.trackplayer.module.MusicEvents
import com.guichaguri.trackplayer.service.Utils.setRating
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * @author Guichaguri
 */

class MusicService : HeadlessJsMediaService() {
    private lateinit var player: QueuedAudioPlayer
    var manager: MusicManager? = null
    var handler: Handler? = null
    private var intentToStop = false
    var mediaTree: Map<String, List<MediaBrowserCompat.MediaItem>> = HashMap()
    var mediaTreeStyle = Arrays.asList(
        MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
        MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
    )

    private val scope = MainScope()

    val tracks: List<Track>
        get() = player.items.map { (it as TrackAudioItem).track }

    val currentTrack
        get() = (player.currentItem as TrackAudioItem).track

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

//    fun emit(event: String?, data: Bundle?) {
//        val intent = Intent(Utils.EVENT_INTENT)
//        intent.putExtra("event", event)
//        if (data != null) intent.putExtra("data", data)
//        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
//    }

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
        Log.d("aaserv", "aaserv onBind, intent: " + intent.action)
        /*        if(Utils.CONNECT_INTENT.equals(intent.getAction())) {
            Log.d("aaserv", "aaserv MusicBinder");
            return new MusicBinder(this, manager);

        }
        return super.onBind(intent);*/

        return if (SERVICE_INTERFACE == intent.action) {
            super.onBind(intent)
        } else MusicBinder(this, manager!!)
    }

    /*    @androidx.annotation.Nullable
    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid, @androidx.annotation.Nullable Bundle rootHints) {
        return null;
    }

    @Override
    public void onLoadChildren(@NonNull String parentId, @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {

    }*/
    private fun invokeStartTask(reactContext: ReactContext) {
        try {
            val catalystInstance = reactContext.catalystInstance
            val jsAppModuleName = "AndroidAuto"
            val appParams = WritableNativeMap()
            appParams.putDouble("rootTag", 1.0)
            val appProperties = Bundle.EMPTY
            if (appProperties != null) {
                appParams.putMap("initialProps", Arguments.fromBundle(appProperties))
            }
            catalystInstance.getJSModule(AppRegistry::class.java)
                .runApplication(jsAppModuleName, appParams)

//            TimingModule timingModule = reactContext.getNativeModule(TimingModule.class);
//            ReactInstanceManager reactInstanceManager = getReactNativeHost().getReactInstanceManager();
//            ReactContext currentReactContext = reactInstanceManager.getCurrentReactContext();
//            CarPlayModule carModule = currentReactContext.getNativeModule(CarPlayModule.class);
//            carModule.setCarContext(carContext, screen);
//            timingModule.onHostResume();
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot? {
        Log.d("aaserv", "aaserv onGetRoot")
        // TODO: verify clientPackageName here.
        // Timber.tag("RNTP-AA").d(clientPackageName + " attempted to get Browsable root.");
        if (Arrays.asList(
                "com.android.systemui",
                "com.example.android.mediacontroller",
                "com.google.android.projection.gearhead"
            ).contains(clientPackageName)
        ) {
            @SuppressLint("VisibleForTests") val reactContext =
                reactNativeHost.reactInstanceManager.currentReactContext
            if (reactContext == null) {
                reactNativeHost.reactInstanceManager.addReactInstanceEventListener(object :
                    ReactInstanceManager.ReactInstanceEventListener {
                    override fun onReactContextInitialized(reactContext: ReactContext) {
                        invokeStartTask(reactContext)
                        reactNativeHost.reactInstanceManager.removeReactInstanceEventListener(this)
                    }
                })
                reactNativeHost.reactInstanceManager.createReactContextInBackground()
            } else {
                invokeStartTask(reactContext)
            }
            val activityIntent = packageManager.getLaunchIntentForPackage(
                packageName
            )
            //            activityIntent.setData(Uri.parse("trackplayer://service-bound"));
//            activityIntent.setAction(Intent.ACTION_VIEW);
//            activityIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            // remove comment to start app in foreground
            // activityIntent!!.action = Utils.CONNECT_INTENT
            // startActivity(activityIntent)


            // ReactActivity reactActivity = reactNativeHost.getReactInstanceManager().getCurrentReactContext().getCurrentActivity();
//            if ((reactActivity == null || reactActivity.isDestroyed()) &&
//                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
//                    Settings.canDrawOverlays(this)) {
//                Log.d("RNTP-AA", clientPackageName + " is in the white list of waking activity.");
//                Intent activityIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
//                if (activityIntent != null) {
//                    activityIntent.setData(Uri.parse("trackplayer://service-bound"));
//                    activityIntent.setAction(Intent.ACTION_VIEW);
//                    activityIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
//                    startActivity(activityIntent);
//                }
//            }

//            player.notificationManager.showNextButton = true
//            player.notificationManager.showPreviousButton = true
//            player.notificationManager.showPlayPauseButton = true
//            player.notificationManager.showForwardButton = true
//            player.notificationManager.showRewindButton = true
//            player.notificationManager.showForwardButtonCompact = true
//            player.notificationManager.showForwardButtonCompact = true
        }
        val extras = Bundle()
        extras.putInt(
            MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            mediaTreeStyle[0]
        )
        extras.putInt(
            MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
            mediaTreeStyle[1]
        )
//        extras.putBoolean(
//            MediaConstants.SESSION_EXTRAS_KEY_SLOT_RESERVATION_SKIP_TO_NEXT,
//            true
//        )
//        extras.putBoolean(
//            MediaConstants.SESSION_EXTRAS_KEY_SLOT_RESERVATION_SKIP_TO_PREV,
//            true
//        )
        return BrowserRoot("/", extras)
    }

/*    @MainThread
    fun add(tracks: List<Track>, atIndex: Int) {
        val items = tracks.map { it.toAudioItem() }
        Log.d("playertest", "playertest add items: " + items + "  index: " + atIndex)
        Log.d("playertest", "playertest player: " + player)
        player.add(items)
        Log.d("playertest", "playertest added")
    }

    @MainThread
    fun load(track: Track) {
        Log.d("playertest", "playertest load")
        Log.d("playertest", "playertest load track: " + track)
        Log.d("playertest", "playertest load track.toAudioItem(): " + track.toAudioItem())
        Log.d("playertest", "playertest player: " + player)
        Log.d("playertest", "playertest isPlaying: " + player.isPlaying)
        player.load(track.toAudioItem())
        Log.d("playertest", "playertest loaded")
    }

    @MainThread
    fun play() {
        Log.d("playertest", "playertest play")
        player.play()
        Log.d("playertest", "playertest play")
    }*/


    @MainThread
    fun add(track: Track) {
        add(listOf(track))
    }

    @MainThread
    fun add(tracks: List<Track>) {
        val items = tracks.map {
            val x = it
            // x.artwork = Uri.parse("https://upload.wikimedia.org/wikipedia/commons/thumb/d/d6/Google_Lens_Icon.svg/1200px-Google_Lens_Icon.svg.png")
            x.toAudioItem() }
        player.add(items)
    }



    @MainThread
    fun add(tracks: List<Track>, atIndex: Int) {
        val items = tracks.map {
            Log.d("testtrack", "testtrack " + it)
            val x = it
            // x.artwork = Uri.parse("https://upload.wikimedia.org/wikipedia/commons/thumb/d/d6/Google_Lens_Icon.svg/1200px-Google_Lens_Icon.svg.png")
            x.toAudioItem() }
        player.add(items, atIndex)
    }

    @MainThread
    fun load(track: Track) {
        // track.artwork =
            // Uri.parse("https://upload.wikimedia.org/wikipedia/commons/thumb/d/d6/Google_Lens_Icon.svg/1200px-Google_Lens_Icon.svg.png")
        player.load(track.toAudioItem())
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

    @MainThread
    fun getVolume(): Float = player.volume

    @MainThread
    fun setVolume(value: Float) {
        player.volume = value
    }

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


    /*    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        // Verifica il clientPackageName
//        if (Arrays.asList(
//                "com.android.systemui",
//                "com.example.android.mediacontroller",
//                "com.google.android.projection.gearhead"
//        ).contains(clientPackageName)) {
        Log.d("aaserv", "aaserv onGetRoot");

            // Inizializza la struttura del "browsetree"
            Map<String, List<MediaBrowserCompat.MediaItem>> browseTree = new HashMap<>();

            // Aggiungi il nodo radice "/"
            List<MediaBrowserCompat.MediaItem> rootNode = new ArrayList<>();
            rootNode.add(createMediaItem("tab1", "tab1", "tab subtitle", true, 2, "", "", "", 1));
            rootNode.add(createMediaItem("tab2", "tab2", "tab subtitle", true, 0, "", "", "", 1));
            rootNode.add(createMediaItem("tab3", "tab3", "tab subtitle", true, 0,  "", "", "", 1));
            browseTree.put("/", rootNode);

            // Aggiungi altri nodi secondo il tuo "browsetree"
            List<MediaBrowserCompat.MediaItem> tab1Node = new ArrayList<>();
            tab1Node.add(createMediaItem("1", "Soul Searching (Demo)", "David Chavez", false, 0, "https://rntp.dev/example/Soul%20Searching.jpeg", "https://rntp.dev/example/Soul%20Searching.mp3", "RNTP Demo Group", 0));
            tab1Node.add(createMediaItem("2", "Lullaby (Demo)", "David Chavez", false, 0, "https://rntp.dev/example/Lullaby%20(Demo).jpeg", "https://rntp.dev/example/Lullaby%20(Demo).mp3", "RNTP Demo Group", 0));
            browseTree.put("tab1", tab1Node);

            // Imposta lo stile del contenuto
            Bundle extras = new Bundle();
            extras.putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, mediaTreeStyle.get(0));
            extras.putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, mediaTreeStyle.get(1));


        Log.d("aaserv", "aaserv onGetRoot end");

        Log.d("aaserv", "aaserv onGetRoot end br " + new BrowserRoot("/", extras));

            return new BrowserRoot("/", extras);
//        }
//
//        return null; // Nel caso il client non sia valido
    }*/
    // Metodo ausiliario per creare un oggetto MediaItem
    private fun createMediaItem(
        mediaId: String,
        title: String,
        subtitle: String,
        playable: Boolean,
        childrenBrowsableContentStyle: Int,
        iconUri: String,
        mediaUri: String,
        groupTitle: String,
        playbackProgress: Long
    ): MediaBrowserCompat.MediaItem {
        val builder = MediaDescriptionCompat.Builder()
        builder.setMediaId(mediaId)
        builder.setTitle(title)
        builder.setSubtitle(subtitle)
        // Aggiungi altre informazioni se necessario (es. iconUri, mediaUri, groupTitle, playbackProgress)

        // Imposta il tipo di elemento (playable o browsable)
        val flags =
            if (playable) MediaBrowserCompat.MediaItem.FLAG_PLAYABLE else MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
        if (playable) {
            builder.setExtras(createPlayableExtras(childrenBrowsableContentStyle))
        }
        // builder.setIconUri(iconUri);
        return MediaBrowserCompat.MediaItem(builder.build(), flags)
    }

    // Metodo ausiliario per creare extras per gli elementi riproducibili
    private fun createPlayableExtras(childrenBrowsableContentStyle: Int): Bundle {
        val extras = Bundle()
        extras.putInt(
            MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            childrenBrowsableContentStyle
        )
        return extras
    }

//    override fun onLoadChildren(
//        parentId: String,
//        result: Result<List<MediaBrowserCompat.MediaItem>>
//    ) {
//        Log.d("aaserv", "aaserv onLoadChildren")
//        result.sendResult(mediaTree[parentId])
//    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<List<MediaBrowserCompat.MediaItem>>
    ) {
        Log.d("aaserv", "aaserv onLoadChildren")
        val mediaItems = mutableListOf<MediaBrowserCompat.MediaItem>()

        mediaTree.forEach { layer ->
                mediaTree[layer.key]?.forEach { mediaItem ->
                    Log.d("bitmapTest", "bitmapTest 1 " + mediaItem.description.title)
                    val modifiedMediaItem =
                        if (mediaItem.description.iconUri != null && mediaItem.description.iconUri.toString()
                                .startsWith("file://")
                        ) {
                            Log.d("bitmapTest", "bitmapTest 2 " + mediaItem.description.iconUri)
                            // Load bitmap from local file
                            val bitmap =
                                mediaItem.description.iconUri?.path?.let { loadBitmapFromFile(it) }
                            if (bitmap != null) {
                                // Replace iconUri with iconBitmap
                                val descriptionBuilder = MediaDescriptionCompat.Builder()
                                    .setMediaId(mediaItem.description.mediaId)
                                    .setTitle(mediaItem.description.title)
                                    .setSubtitle(mediaItem.description.subtitle)
                                    .setDescription(mediaItem.description.description)
                                    .setIconBitmap(bitmap)  // Set the new bitmap
                                // You can set other properties as well if needed
                                MediaBrowserCompat.MediaItem(
                                    descriptionBuilder.build(),
                                    mediaItem.flags
                                )
                            } else {
                                mediaItem
                            }
                        } else {
                            mediaItem
                        }
                    mediaItems.add(modifiedMediaItem)
                }
    }
        result.sendResult(mediaItems)
    }



    private fun loadBitmapFromFile(filePath: String): Bitmap? {
        return try {
            BitmapFactory.decodeFile(filePath)
        } catch (e: Exception) {
            Log.e("Error", "Error loading bitmap from file: $e")
            null
        }
    }


    override fun onLoadItem(itemId: String, result: Result<MediaBrowserCompat.MediaItem>) {
        // Emetti un evento o esegui altre azioni in base all'ID dell'elemento cliccato
        // itemId rappresenta l'ID dell'elemento multimediale cliccato
        Log.d("aaserv", "aaserv onLoadItem")

        // Ad esempio, puoi emettere un evento che notifica l'ID dell'elemento cliccato
//        Bundle data = new Bundle();
//        data.putString("itemId", itemId);
//        emit("mediaItemClicked", data);
//
//        // Successivamente, invia il risultato a chi ha richiesto il caricamento dell'elemento
//        // Puoi creare un oggetto MediaBrowserCompat.MediaItem utilizzando l'ID dell'elemento
//        MediaBrowserCompat.MediaItem mediaItem = createMediaItem(itemId, "Titolo dell'elemento", "Sottotitolo dell'elemento", true, 0, "", "", "", 0);
//        result.sendResult(mediaItem);
    }

    /*    @Override
    public void onLoadChildren(String parentMediaId, Result<List<MediaBrowserCompat.MediaItem>> result) {
        Log.d("aaserv", "aaserv onLoadChildren");
        // Timber.tag("GVA-RNTP").d("RNTP received loadChildren req: %s", parentMediaId);

        // emit(MusicEvents.BUTTON_BROWSE, new Bundle().getString("mediaId", parentMediaId));
        // result.sendResult(mediaTree.get(parentMediaId));
    }*/
    @SuppressLint("RestrictedApi")
    override fun onCreate() {
        Log.d("aaserv", "aaserv onCreate")
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
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE
            )

//        stateBuilder?.addCustomAction(
//            PlaybackStateCompat.CustomAction.Builder(
//                "test_next",
//                "test_name",
//                R.drawable.ic_next
//            ).run {
//                // setExtras(customActionExtras)
//                build()
//            }
//        )

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
        } catch (ex: Exception) {
        }
    }

    override fun onCustomAction(action: String, extras: Bundle?, result: Result<Bundle>) {
        // when(action) {
//            CUSTOM_ACTION_START_RADIO_FROM_MEDIA -> {
//                ...
//            }
            Log.d("test custom action", "test custom action")
        // }
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.d("aaserv", "aaserv onStartCommand")
        if (intent != null && Intent.ACTION_MEDIA_BUTTON == intent.action) {
            onStartForeground()
            // Check if the app is on background, then starts a foreground service and then ends it right after
            /*onStartForeground();

            if(manager != null) {
                MediaButtonReceiver.handleIntent(manager.getMetadata().getSession(), intent);
            }

            return START_NOT_STICKY;*/

            // Interpret event
            val intentExtra = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            if (intentExtra!!.keyCode == KeyEvent.KEYCODE_MEDIA_STOP) {
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
        Log.d("aaserv", "aaserv onDestroy")
        super.onDestroy()
        if (manager != null) {
            manager!!.destroy(true)
            manager = null
        }
    }

    override fun onTaskRemoved(rootIntent: Intent) {
        Log.d("aaserv", "aaserv onTaskRemoved")
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
        Log.d("setupPlayer", "setupPlayer")
        if (this::player.isInitialized) {
            print("Player was initialized. Prevent re-initializing again")
            return
        }
        Log.d("setupPlayer", "setupPlayer init")

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
            override fun handleCustomActions(action: String?, extras: Bundle?) {
                Log.d("testbuttons", "testbuttons handleCustomActions")
            }

            override fun handlePlayFromMediaId(mediaId: String?, extras: Bundle?) {
                Log.d("testbuttons", "testbuttons handlePlayFromMediaId")
                val emitBundle = extras ?: Bundle()
                emit(MusicEvents.BUTTON_PLAY_FROM_ID, emitBundle.apply {
                    putString("id", mediaId)
                })
            }

            override fun handlePlayFromSearch(query: String?, extras: Bundle?) {
                Log.d("testbuttons", "testbuttons handlePlayFromSearch")
                val emitBundle = extras ?: Bundle()
                emit(MusicEvents.BUTTON_PLAY_FROM_SEARCH, emitBundle.apply {
                    putString("query", query)
                })
            }

            override fun handleSkipToQueueItem(id: Long) {
                Log.d("testbuttons", "testbuttons handleSkipToQueueItem")
                val emitBundle = Bundle()
                emit(MusicEvents.BUTTON_SKIP, emitBundle.apply {
                    putInt("index", id.toInt())
                })
            }
        }
        Log.d("setupPlayer", "setupPlayer ok")
        player = QueuedAudioPlayer(this@MusicService, playerConfig, bufferConfig, cacheConfig, mediaSessionCallback)
        Log.d("setupPlayer", "setupPlayer ok player: " + player)
        Log.d("setupPlayer", "setupPlayer ok player.currentItem: " + player.currentItem)
        player.automaticallyUpdateNotificationMetadata = automaticallyUpdateNotificationMetadata

//        player.notificationManager.showNextButton = true
//        player.notificationManager.showPreviousButton = true
//        player.notificationManager.showPlayPauseButton = true
//        player.notificationManager.showForwardButton = true
//        player.notificationManager.showRewindButton = true
//        player.notificationManager.showForwardButtonCompact = true
//        player.notificationManager.showForwardButtonCompact = true

        sessionToken = player.getMediaSessionToken()




        observeEvents()
        setupForegrounding()

        val bundle = Bundle().apply {
            putDouble("duration", 120.0)
            putBoolean("resetControls", true)
            putString("description", "eSound")
            putBoolean("enabled", true)
            putString("artist", "Sfera Ebbasta")
            putBoolean("isLive", false)
            putBoolean("repeat", false)
            putString("artwork", "https://e-cdns-images.dzcdn.net/images/cover/005974b2ee76da784b1ac771b2b26394/500x500-000000-80-0-0.jpg")
            putString("extension", "m4a")
            putDouble("elapsedTime", 0.0)
            putBoolean("updateOnly", false)
            putBoolean("updateSkip", false)
            putBoolean("isLoading", true)
            putLong("id", 2545936202)
            putLong("key", 2545936202)
            putString("url", "https://dl.espressif.com/dl/audio/ff-16b-2c-44100hz.mp3")
            putString("album", "eSound")
            putString("genre", "")
            putDouble("state", 2.0)
            putString("title", "15 Piani 🅴")
            putBoolean("hasSongs", true)
            putString("artworkRemote", "https://e-cdns-images.dzcdn.net/images/cover/005974b2ee76da784b1ac771b2b26394/1000x1000-000000-80-0-0.jpg")
            putString("sharableUrl", "https://esound.app/app/playlist/ZGFpbHltaXgwLDMsMw")
            putString("trackStatus", "downloaded")
            putBoolean("usingLocalFiles", false)
            putString("sharableTrackUrl", "https://esound.app/app/track/NTYwNDQzNTcwLDA")
            putBoolean("isAudio", false)
            putBoolean("shuffle", false)
        }

        val track = Track(baseContext, bundle, 1)

        val audioItem = track.toAudioItem()

        Log.d("playertest", "playertest track url: " + track.url)

        player.load(audioItem)
        Log.d("playertest", "playertest ok load")

        player.add(
            listOf(audioItem),
            0
        )
        Log.d("playertest", "playertest ok add")

        player.play()

        Log.d("playertest", "playertest ok play")

        val handler: Handler = Handler(Looper.getMainLooper())
        handler.postDelayed({ // Qui verrà eseguito il codice dopo 10 secondi
            player.seek(60, TimeUnit.SECONDS)
        }, 10000) // 10000 millisecondi = 10 secondi


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
        bundle.putDouble(POSITION_KEY, (player.position / 1000).toDouble()) // check seconds conversion
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

//    @MainThread
//    private fun progressUpdateEventFlow(interval: Long) = flow {
//        while (true) {
//            if (player.isPlaying) {
//                val bundle = progressUpdateEvent()
//                emit(bundle)
//            }
//
//            delay(interval * 1000)
//        }
//    }

    @MainThread
    fun updateOptions(options: Bundle) {
        latestOptions = options
        val androidOptions = options.getBundle(ANDROID_OPTIONS_KEY)

//        appKilledPlaybackBehavior = AppKilledPlaybackBehavior::string.find(androidOptions?.getString(APP_KILLED_PLAYBACK_BEHAVIOR_KEY)) ?: AppKilledPlaybackBehavior.CONTINUE_PLAYBACK
//
//        //TODO: This handles a deprecated flag. Should be removed soon.
//        options.getBoolean(STOPPING_APP_PAUSES_PLAYBACK_KEY).let {
//            stoppingAppPausesPlayback = options.getBoolean(STOPPING_APP_PAUSES_PLAYBACK_KEY)
//            if (stoppingAppPausesPlayback) {
//                appKilledPlaybackBehavior = AppKilledPlaybackBehavior.PAUSE_PLAYBACK
//            }
//        }

        ratingType = BundleUtils.getInt(options, "ratingType", RatingCompat.RATING_NONE)

        player.playerOptions.alwaysPauseOnInterruption = androidOptions?.getBoolean(PAUSE_ON_INTERRUPTION_KEY) ?: false

        capabilities = options.getIntegerArrayList("capabilities")?.map { Capability.values()[it] } ?: emptyList()
        notificationCapabilities = options.getIntegerArrayList("notificationCapabilities")?.map { Capability.values()[it] } ?: emptyList()
        Log.d("notificationCapabilities", "notificationCapabilities " + notificationCapabilities)
        compactCapabilities = options.getIntegerArrayList("compactCapabilities")?.map { Capability.values()[it] } ?: emptyList()
        val customActions = options.getBundle(CUSTOM_ACTIONS_KEY)
        val customActionsLeftList = customActions?.getStringArrayList(CUSTOM_ACTIONS_LEFT_LIST_KEY)
        val customActionsRightList = customActions?.getStringArrayList(CUSTOM_ACTIONS_RIGHT_LIST_KEY)
        if (notificationCapabilities.isEmpty()) notificationCapabilities = capabilities

        fun customIcon(customAction: String): Int {
            return when (customAction) {
                "customShuffleOn" -> com.google.android.exoplayer2.ui.R.drawable.exo_icon_shuffle_on
                "customShuffleOff" -> com.google.android.exoplayer2.ui.R.drawable.exo_icon_shuffle_off
                "customRepeatOn" -> com.google.android.exoplayer2.ui.R.drawable.exo_icon_repeat_all
                "customRepeatOff" -> com.google.android.exoplayer2.ui.R.drawable.exo_icon_repeat_off
                else -> com.google.android.exoplayer2.ui.R.drawable.exo_ic_play_circle_filled
            }
        }

        val buttonsList = mutableListOf<NotificationButton>()


        if (customActionsLeftList != null) {
            for (customAction in customActionsLeftList ?: emptyList()) {
                Log.d("customAction", "customAction: " + customAction)
                // val customIcon = BundleUtils.getIcon(this, customActions, customAction, customIcon(customAction))
                val customIcon = customIcon(customAction)
                buttonsList.add(CUSTOM_ACTION(icon=customIcon, customAction = customAction, isCompact = false))
            }
        }

        notificationCapabilities.forEach { capability ->
            when (capability) {
                Capability.PLAY, Capability.PAUSE -> {
                    val playIcon = BundleUtils.getIconOrNull(this, options, "playIcon")
                    val pauseIcon = BundleUtils.getIconOrNull(this, options, "pauseIcon")
                    buttonsList.addAll(listOf(PLAY_PAUSE(playIcon = playIcon, pauseIcon = pauseIcon)))
                }
                Capability.STOP -> {
                    val stopIcon = BundleUtils.getIconOrNull(this, options, "stopIcon")
                    buttonsList.addAll(listOf(STOP(icon = stopIcon)))
                }
                Capability.SKIP_TO_NEXT -> {
                    val nextIcon = BundleUtils.getIconOrNull(this, options, "nextIcon")
                    buttonsList.addAll(listOf(NEXT(icon = nextIcon, isCompact = isCompact(capability))))
                }
                Capability.SKIP_TO_PREVIOUS -> {
                    val previousIcon = BundleUtils.getIconOrNull(this, options, "previousIcon")
                    buttonsList.addAll(listOf(PREVIOUS(icon = previousIcon, isCompact = isCompact(capability))))
                }
                Capability.JUMP_FORWARD -> {
                    val forwardIcon = BundleUtils.getIconOrNull(this, options, "forwardIcon")
                    buttonsList.addAll(listOf(FORWARD(icon = forwardIcon, isCompact = isCompact(capability))))
                }
                Capability.JUMP_BACKWARD -> {
                    val backwardIcon = BundleUtils.getIconOrNull(this, options, "rewindIcon")
                    buttonsList.addAll(listOf(BACKWARD(icon = backwardIcon, isCompact = isCompact(capability))))
                }
                Capability.SEEK_TO -> {
                    buttonsList.addAll(listOf(SEEK_TO))
                }
                else -> {}
            }
        }


        if (customActionsRightList != null) {
            for (customAction in customActionsRightList ?: emptyList()) {
                Log.d("customAction", "customAction: " + customAction)
                // val customIcon = BundleUtils.getIcon(this, customActions, customAction, customIcon(customAction))
                val customIcon = customIcon(customAction)
                buttonsList.add(CUSTOM_ACTION(icon=customIcon, customAction = customAction, isCompact = false))
            }
        }

        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
//            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
//            // Add the Uri data so apps can identify that it was a notification click
//            data = Uri.parse("trackplayer://notification.click")
            action = Intent.ACTION_VIEW
        }

        Log.d("notbuttontest", "notbuttontest: " + buttonsList.size + " - " + buttonsList)

        val accentColor = BundleUtils.getIntOrNull(options, "color")
        val smallIcon = R.drawable.ic_logo
        val pendingIntent = PendingIntent.getActivity(this, 0, openAppIntent, getPendingIntentFlags())
        val notificationConfig = NotificationConfig(buttonsList, accentColor, smallIcon, pendingIntent)

        player.notificationManager.createNotification(notificationConfig)

        Log.d("createdNotification", "createdNotification")

        // setup progress update events if configured
//        progressUpdateJob?.cancel()
//        val updateInterval = BundleUtils.getIntOrNull(options, PROGRESS_UPDATE_EVENT_INTERVAL_KEY)
//        if (updateInterval != null && updateInterval > 0) {
//            progressUpdateJob = scope.launch {
//                progressUpdateEventFlow(updateInterval.toLong()).collect { emit(MusicEvents.PLAYBACK_PROGRESS_UPDATED, it) }
//            }
//        }
    }


    @MainThread
    private fun observeEvents() {
//        scope.launch {
//            event.stateChange.collect {
//                Log.d("onPlayerEvent", "onPlayerEvent stateChange state: " + getPlayerStateBundle(it))    // move 1123 circa here
//                emit(MusicEvents.PLAYBACK_STATE, getPlayerStateBundle(it))
//
//                if (it == AudioPlayerState.ENDED && player.nextItem == null) {
//                    emitQueueEndedEvent()
//                }
//
////                player.playerOptions
////
////
////                val currentItem = player.currentItem
////                Log.d("onPlayerEvent", "onPlayerEvent stateChange currentItem: " + currentItem)
////                val bundle = Bundle().apply {
////                    if (currentItem != null) {
////                        putString("sourceData", currentItem.audioUrl)
////                    }
////                    if (currentItem != null) {
////                        putString("artwork", currentItem.artwork)
////                    }
////                    if (currentItem != null) {
////                        putString("title", currentItem.title)
////                    }
////                    putString("artist", "deadmau5")
////                    putString("album", "while(1<2)")
////                    putString("genre", "Progressive House, Electro House")
////                    putString("date", "2014-05-20T07:00:00+00:00")
////                    putDouble("duration", 402.0)
////                }
////                val binder = MusicModule.binder
////                Log.d("onPlayerEvent", "onPlayerEvent stateChange bundle: " + bundle)
////
////                val track = Track(applicationContext, bundle, 0)
////                if (binder != null) {
////                    Log.d("onPlayerEvent", "onPlayerEvent stateChange binder ok")
////                    Log.d("onPlayerEvent", "onPlayerEvent stateChange duration: " + track.duration)
////                    binder.manager.currentTrack = track
////                    binder.manager.metadata.updateMetadata(track)
////
////                    binder.manager.setState(1, 0)
////                }
//            }
//        }

        scope.launch {
            event.audioItemTransition.collect {
                Log.d("onPlayerEvent", "onPlayerEvent audioItemTransition")
                if (it !is AudioItemTransitionReason.REPEAT) {
                    emitPlaybackTrackChangedEvents(
                        player.currentIndex,
                        player.previousIndex,
                        (it?.oldPosition ?: 0).toDouble() // check seconds conversion
                    )
                }
            }
        }

        scope.launch {
            event.onAudioFocusChanged.collect {
                Log.d("onPlayerEvent", "onPlayerEvent onAudioFocusChanged")
                Bundle().apply {
                    putBoolean(IS_FOCUS_LOSS_PERMANENT_KEY, it.isFocusLostPermanently)
                    putBoolean(IS_PAUSED_KEY, it.isPaused)
                    emit(MusicEvents.BUTTON_DUCK, this)
                }
            }
        }


        scope.launch {
            event.onPlayerActionTriggeredExternally.collect {
                Log.d("onPlayerActionTriggeredExternally", "onPlayerActionTriggeredExternally: " + it)
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
                        emit(MusicEvents.BUTTON_SKIP_NEXT)
//                        Bundle().apply {
//                            val interval = latestOptions?.getDouble(FORWARD_JUMP_INTERVAL_KEY, DEFAULT_JUMP_INTERVAL) ?: DEFAULT_JUMP_INTERVAL
//                            putInt("interval", interval.toInt())
//                            emit(MusicEvents.BUTTON_JUMP_FORWARD, this)
//                        }
                    }
                    MediaSessionCallback.REWIND -> {
                        emit(MusicEvents.BUTTON_SKIP_PREVIOUS)
//                        Bundle().apply {
//                            val interval = latestOptions?.getDouble(BACKWARD_JUMP_INTERVAL_KEY, DEFAULT_JUMP_INTERVAL) ?: DEFAULT_JUMP_INTERVAL
//                            putInt("interval", interval.toInt())
//                            emit(MusicEvents.BUTTON_JUMP_BACKWARD, this)
//                        }
                    }
                    is MediaSessionCallback.CUSTOMACTION -> {
                        Bundle().apply {
                            Log.d("customttest", "customttest: " + it.customAction)
                            if (it.customAction == "customSkipPrev") {
                                emit(MusicEvents.BUTTON_SKIP_PREVIOUS, this)
                            } else if (it.customAction == "customSkipNext") {
                                emit(MusicEvents.BUTTON_SKIP_NEXT, this)
                            } else if (it.customAction == "customShuffleOn" || it.customAction == "customShuffleOff") {
                                emit(MusicEvents.BUTTON_SHUFFLE, this)
                            } else if (it.customAction == "customRepeatOn" || it.customAction == "customRepeatOff") {
                                emit(MusicEvents.BUTTON_REPEAT, this)
                            }
                        }
                    }
                    else -> {}
                }
            }
        }

//        scope.launch {
//            event.onPlaybackMetadata.collect {
//                Log.d("onPlayerEvent", "onPlayerEvent onPlaybackMetadata")
//            }
//        }

/*        scope.launch {
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
        }*/

/*        scope.launch {
            event.onCommonMetadata.collect {
                val data = MetadataAdapter.fromMediaMetadata(it)
                val bundle = Bundle().apply {
                    putBundle(METADATA_PAYLOAD_KEY, data)
                }
                emit(MusicEvents.METADATA_COMMON_RECEIVED, bundle)
            }
        }*/

        scope.launch {
            event.playWhenReadyChange.collect {
                Log.d("playWhenReadyChangeEvent", "playWhenReadyChangeEvent")
                Bundle().apply {
                    putBoolean("playWhenReady", it.playWhenReady)
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
                                    @Suppress("DEPRECATION")
                                    stopForeground(removeNotificationWhenNotOngoing)
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
        const val CUSTOM_ACTIONS_RIGHT_LIST_KEY = "customActionsRightList"
        const val CUSTOM_ACTIONS_LEFT_LIST_KEY = "customActionsLeftList"
    }
}