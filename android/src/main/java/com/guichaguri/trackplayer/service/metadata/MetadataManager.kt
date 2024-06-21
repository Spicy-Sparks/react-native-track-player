package com.guichaguri.trackplayer.service.metadata

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.RatingCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.SimpleTarget
import com.bumptech.glide.request.transition.Transition
import com.facebook.react.views.imagehelper.ResourceDrawableIdHelper
import com.guichaguri.trackplayer.R
import com.guichaguri.trackplayer.service.MusicManager
import com.guichaguri.trackplayer.service.MusicService
import com.guichaguri.trackplayer.service.Utils
import com.guichaguri.trackplayer.model.Track
import java.util.*

/**
 * @author Guichaguri
 */
class MetadataManager(service: MusicService, manager: MusicManager) {
    private val service: MusicService
    private val manager: MusicManager
    val session: MediaSessionCompat
    var ratingType = RatingCompat.RATING_NONE
        private set
    var jumpInterval = 15
        private set
    private var actions: Long = 0
    private var compactActions: Long = 0
    private var artworkTarget: SimpleTarget<Bitmap>? = null
    private val builder: NotificationCompat.Builder
    var optionsBundle: Bundle? = null
        private set
    private var hideArtworkLockScreenBackground = false
    private var previousAction: NotificationCompat.Action? = null
    private var rewindAction: NotificationCompat.Action? = null
    private var playAction: NotificationCompat.Action? = null
    private var pauseAction: NotificationCompat.Action? = null
    private var stopAction: NotificationCompat.Action? = null
    private var forwardAction: NotificationCompat.Action? = null
    private var nextAction: NotificationCompat.Action? = null

    init {
        this.service = service
        this.manager = manager
        val channel: String = Utils.getNotificationChannel(service as Context)
        builder = NotificationCompat.Builder(service, channel)
        session = MediaSessionCompat(service, "TrackPlayer", null, null)
        session.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS or MediaSessionCompat.FLAG_HANDLES_QUEUE_COMMANDS)
        session.setCallback(ButtonEvents(service, manager))
        val context: Context = service.getApplicationContext()
        val packageName = context.packageName
        var openApp = context.packageManager.getLaunchIntentForPackage(packageName)
        if (openApp == null) {
            openApp = Intent()
            openApp.setPackage(packageName)
            openApp.addCategory(Intent.CATEGORY_LAUNCHER)
        }

        // Prevent the app from launching a new instance
        openApp.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP

        // Add the Uri data so apps can identify that it was a notification click
        openApp.action = Intent.ACTION_VIEW
        openApp.data = Uri.parse("trackplayer://notification.click")
        val flags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        builder.setContentIntent(PendingIntent.getActivity(context, 0, openApp, flags))
        builder.setSmallIcon(R.drawable.ic_logo)
        builder.setCategory(NotificationCompat.CATEGORY_TRANSPORT)

        // Stops the playback when the notification is swiped away
        builder.setDeleteIntent(
            MediaButtonReceiver.buildMediaButtonPendingIntent(
                service,
                PlaybackStateCompat.ACTION_STOP
            )
        )

        // Make it visible in the lockscreen
        builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    }

    /**
     * Updates the metadata options
     * @param options The options
     */
    fun updateOptions(options: Bundle) {
        optionsBundle = options
        val capabilities: List<Int>? = options.getIntegerArrayList("capabilities")
        var notification: List<Int>? = options.getIntegerArrayList("notificationCapabilities")
        val compact: List<Int>? = options.getIntegerArrayList("compactCapabilities")
        hideArtworkLockScreenBackground = options.getBoolean("hideArtworkLockScreenBackground")
        actions = 0
        compactActions = 0
        if (capabilities != null) {
            // Create the actions mask
            for (cap in capabilities) actions = actions or cap.toLong()

            // If there is no notification capabilities defined, we'll show all capabilities available
            if (notification == null) notification = capabilities

            // Initialize all actions based on the options
            previousAction = createAction(
                notification, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS, "Previous",
                getIcon(options, "previousIcon", R.drawable.ic_previous)
            )
            rewindAction = createAction(
                notification, PlaybackStateCompat.ACTION_REWIND, "Rewind",
                getIcon(options, "rewindIcon", R.drawable.ic_rewind)
            )
            playAction = createAction(
                notification, PlaybackStateCompat.ACTION_PLAY, "Play",
                getIcon(options, "playIcon", R.drawable.ic_play)
            )
            pauseAction = createAction(
                notification, PlaybackStateCompat.ACTION_PAUSE, "Pause",
                getIcon(options, "pauseIcon", R.drawable.ic_pause)
            )
            stopAction = createAction(
                notification, PlaybackStateCompat.ACTION_STOP, "Stop",
                getIcon(options, "stopIcon", R.drawable.ic_stop)
            )
            forwardAction = createAction(
                notification, PlaybackStateCompat.ACTION_FAST_FORWARD, "Forward",
                getIcon(options, "forwardIcon", R.drawable.ic_forward)
            )
            nextAction = createAction(
                notification, PlaybackStateCompat.ACTION_SKIP_TO_NEXT, "Next",
                getIcon(options, "nextIcon", R.drawable.ic_next)
            )

            // Update the action mask for the compact view
            if (compact != null) {
                for (cap in compact) compactActions = compactActions or cap.toLong()
            }
        }

        // Update the color
        builder.color = Utils.getInt(options, "color", NotificationCompat.COLOR_DEFAULT)

        // Update the icon
        builder.setSmallIcon(getIcon(options, "icon", R.drawable.ic_logo))

        // Update the jump interval
        jumpInterval = Utils.getInt(options, "jumpInterval", 15)

        // Update the rating type
        ratingType = Utils.getInt(options, "ratingType", RatingCompat.RATING_NONE)
        session.setRatingType(ratingType)
        updateNotification()
    }

    fun removeNotifications() {
        val ns = Context.NOTIFICATION_SERVICE
        val context: Context = service.getApplicationContext()
        val manager = context.getSystemService(ns) as NotificationManager
        manager.cancelAll()
    }

    /**
     * Updates the artwork
     * @param bitmap The new artwork
     */
    protected fun updateArtwork(bitmap: Bitmap?) {
        val track: Track =
            manager.currentTrack ?: return
        val metadata = track.toMediaMetadata()
        metadata.putBitmap(
            if (hideArtworkLockScreenBackground) MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON else MediaMetadataCompat.METADATA_KEY_ART,
            bitmap
        )
        builder.setLargeIcon(bitmap)
        session.setMetadata(metadata.build())
        updateNotification()
    }

    private fun getCroppedBitmap(bitmap: Bitmap): Bitmap {
        val output = if (bitmap.width >= bitmap.height) {
            Bitmap.createBitmap(bitmap, (bitmap.width - bitmap.height) / 2, 0, bitmap.height, bitmap.height)
        } else {
            Bitmap.createBitmap(bitmap, 0, (bitmap.height - bitmap.width) / 2, bitmap.width, bitmap.width)
        }

        return output
    }

    /**
     * Updates the current track
     * @param track The new track
     */
    fun updateMetadata(track: Track) {
        val metadata = track.toMediaMetadata()

        val rm = Glide.with(service.applicationContext)
        artworkTarget?.let { rm.clear(it) }

        if (track.artwork != null) {
            artworkTarget = rm.asBitmap()
                .load(track.artwork)
                .into(object : SimpleTarget<Bitmap>() {
                    override fun onResourceReady(
                        resource: Bitmap,
                        transition: Transition<in Bitmap>?
                    ) {
                        try {
                            val bitmap = getCroppedBitmap(resource)
                            metadata.putBitmap(
                                if (hideArtworkLockScreenBackground)
                                    MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON
                                else MediaMetadataCompat.METADATA_KEY_ART, bitmap)
                            builder.setLargeIcon(bitmap)

                            session.setMetadata(metadata.build())
                            updateNotification()
                        } catch (ex: Exception) {
                        }
                        artworkTarget = null
                    }
                })
        }

        builder.setContentTitle(track.title)
        builder.setContentText(track.artist)
        builder.setSubText(track.album)

        session.setMetadata(metadata.build())
        updateNotification()
    }


    /**
     * Updates the playback state
     * @param state The player
     */
    @SuppressLint("RestrictedApi")
    fun updatePlayback(state: Int, position: Long, bufferedPosition: Long, rate: Int) {
        val playing: Boolean = Utils.isPlaying(state)
        val compact: MutableList<Int> = ArrayList()
        builder.mActions.clear()

        // Adds the media buttons to the notification
        addAction(previousAction, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS, compact)
        addAction(rewindAction, PlaybackStateCompat.ACTION_REWIND, compact)
        if (playing) {
            addAction(pauseAction, PlaybackStateCompat.ACTION_PAUSE, compact)
        } else {
            addAction(playAction, PlaybackStateCompat.ACTION_PLAY, compact)
        }
        addAction(stopAction, PlaybackStateCompat.ACTION_STOP, compact)
        addAction(forwardAction, PlaybackStateCompat.ACTION_FAST_FORWARD, compact)
        addAction(nextAction, PlaybackStateCompat.ACTION_SKIP_TO_NEXT, compact)

        // Prevent the media style from being used in older Huawei devices that don't support custom styles
        if (!Build.MANUFACTURER.lowercase(Locale.getDefault())
                .contains("huawei") || Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
        ) {
            val style = androidx.media.app.NotificationCompat.MediaStyle()
            if (playing) {
                style.setShowCancelButton(false)
            } else {
                // Shows the cancel button on pre-lollipop versions due to a bug
                style.setShowCancelButton(true)
                style.setCancelButtonIntent(
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        service,
                        PlaybackStateCompat.ACTION_STOP
                    )
                )
            }

            // Links the media session
            style.setMediaSession(session.sessionToken)

            // Updates the compact media buttons for the notification
            if (!compact.isEmpty()) {
                val compactIndexes = IntArray(compact.size)
                for (i in compact.indices) compactIndexes[i] = compact[i]
                style.setShowActionsInCompactView(*compactIndexes)
            }
            builder.setStyle(style)
        }

        // Updates the media session state
        val pb = PlaybackStateCompat.Builder()
        pb.setActions(actions)
        pb.setState(state, position, rate.toFloat())
        pb.setBufferedPosition(bufferedPosition)
        session.setPlaybackState(pb.build())
        updateNotification()
    }

    fun setActive(active: Boolean) {
        session.isActive = active
        updateNotification()
    }

    fun destroy(intentToStop: Boolean?) {
        service.stopForeground(false)
        if (!intentToStop!!) {
            updateNotification()
        } else {
            session.isActive = false
            session.release()
        }
    }

    private fun updateNotification() {
        try {
            if (session.isActive) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    service.startForeground(
                        1, builder
                            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                            .setPriority(NotificationCompat.PRIORITY_MAX)
                            .build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    service.startForeground(
                        1, builder
                            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                            .setPriority(NotificationCompat.PRIORITY_MAX)
                            .build()
                    )
                }
            }
        } catch (ex: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val context: Context = service.getApplicationContext()
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
    }

    private fun getIcon(options: Bundle, propertyName: String, defaultIcon: Int): Int {
        if (!options.containsKey(propertyName)) return defaultIcon
        val bundle = options.getBundle(propertyName) ?: return defaultIcon
        val helper = ResourceDrawableIdHelper.getInstance()
        val icon = helper.getResourceDrawableId(service, bundle.getString("uri"))
        return if (icon == 0) defaultIcon else icon
    }

    private fun createAction(
        caps: List<Int>,
        action: Long,
        title: String,
        icon: Int
    ): NotificationCompat.Action? {
        return if (!caps.contains(action.toInt())) null else NotificationCompat.Action(
            icon,
            title,
            MediaButtonReceiver.buildMediaButtonPendingIntent(service, action)
        )
    }

    @SuppressLint("RestrictedApi")
    private fun addAction(action: NotificationCompat.Action?, id: Long, compact: MutableList<Int>) {
        if (action == null) return
        if (compactActions and id != 0L) compact.add(builder.mActions.size)
        builder.mActions.add(action)
    }
}