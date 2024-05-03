package com.guichaguri.trackplayer.service

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.PowerManager
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.annotation.RequiresApi
import com.guichaguri.trackplayer.module.MusicEvents
import com.guichaguri.trackplayer.service.metadata.MetadataManager
import com.guichaguri.trackplayer.model.Track

/**
 * @author Guichaguri
 */
class MusicManager @SuppressLint("InvalidWakeLockTag") constructor(private val service: MusicService) :
    AudioManager.OnAudioFocusChangeListener {
    private val wakeLock: PowerManager.WakeLock
    private val wifiLock: WifiManager.WifiLock
    val metadata: MetadataManager
    var currentTrack: Track? = null
    private var state = 0
    private var previousState = 0
    private var position: Long = 0
    private var bufferedPosition: Long = 0

    @RequiresApi(26)
    private var focus: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var wasDucking = false
    private val noisyReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            service.emit(MusicEvents.BUTTON_PAUSE, null)
        }
    }
    private var receivingNoisyEvents = false
    private var stopWithApp = false
    private var alwaysPauseOnInterruption = false

    init {
        metadata = MetadataManager(service, this)
        val powerManager = service.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock =
            powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "track-player-wake-lock")
        wakeLock.setReferenceCounted(false)

        // Android 7: Use the application context here to prevent any memory leaks
        val wifiManager =
            service.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL, "track-player-wifi-lock")
        wifiLock.setReferenceCounted(false)
    }

    fun shouldStopWithApp(): Boolean {
        return stopWithApp
    }

    fun setStopWithApp(stopWithApp: Boolean) {
        this.stopWithApp = stopWithApp
    }

    fun setAlwaysPauseOnInterruption(alwaysPauseOnInterruption: Boolean) {
        this.alwaysPauseOnInterruption = alwaysPauseOnInterruption
    }

    val handler: Handler?
        get() = service.handler

    fun getState(): Long {
        return state.toLong()
    }

    fun setState(state: Int, position: Long) {
        this.state = state
        if (position != -1L) {
            this.position = position
            bufferedPosition = position
        }
        onPlayerStateChanged()
    }

    @SuppressLint("WakelockTimeout")
    fun onPlay() {
        Log.d(Utils.LOG, "onPlay")
        /*if(playback == null) return;

        Track track = playback.getCurrentTrack();
        if(track == null) return;

        if(!playback.isRemote()) {
            requestFocus();

            if(!receivingNoisyEvents) {
                receivingNoisyEvents = true;
                service.registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
            }

            if(!wakeLock.isHeld()) wakeLock.acquire();

            if(!Utils.isLocal(track.uri)) {
                if(!wifiLock.isHeld()) wifiLock.acquire();
            }
        }*/requestFocus()
        if (!receivingNoisyEvents) {
            receivingNoisyEvents = true
            service.registerReceiver(
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            )
        }
        if (!wakeLock.isHeld) wakeLock.acquire()

        /*if(!Utils.isLocal(currentTrack.uri)) {
            if(!wifiLock.isHeld()) wifiLock.acquire();
        }*/metadata.setActive(true)
    }

    fun onPause() {
        Log.d(Utils.LOG, "onPause")

        // Unregisters the noisy receiver
        if (receivingNoisyEvents) {
            try {
                service.unregisterReceiver(noisyReceiver)
                receivingNoisyEvents = false
            } catch (e: Exception) {
            }
        }

        // Release the wake and the wifi locks
        if (wakeLock.isHeld) wakeLock.release()
        if (wifiLock.isHeld) wifiLock.release()
        metadata.setActive(true)
    }

    fun onStop() {
        Log.d(Utils.LOG, "onStop")

        // Unregisters the noisy receiver
        if (receivingNoisyEvents) {
            try {
                service.unregisterReceiver(noisyReceiver)
                receivingNoisyEvents = false
            } catch (e: Exception) {
            }
        }

        // Release the wake and the wifi locks
        if (wakeLock.isHeld) wakeLock.release()
        if (wifiLock.isHeld) wifiLock.release()
        abandonFocus()
        metadata.setActive(false)
    }

    fun onPlayerStateChanged() {
        if (Utils.isPlaying(state) && !Utils.isPlaying(previousState)) {
            onPlay()
        } else if (Utils.isPaused(state) && !Utils.isPaused(previousState)) {
            onPause()
        } else if (Utils.isStopped(state) && !Utils.isStopped(previousState)) {
            onStop()
        }
        onStateChange(state, position, bufferedPosition)
        previousState = state
        if (state == PlaybackStateCompat.STATE_STOPPED) {
            onEnd(currentTrack, 0)
        }
    }

    fun onStateChange(state: Int, position: Long, bufferedPosition: Long) {
        Log.d(Utils.LOG, "onStateChange")
        val bundle = Bundle()
        bundle.putInt("state", state)
        service.emit(MusicEvents.PLAYBACK_STATE, bundle)
        metadata.updatePlayback(state, position, bufferedPosition, 1)
    }

    fun onTrackUpdate(previous: Track?, prevPos: Long, next: Track?) {
        Log.d(Utils.LOG, "onTrackUpdate")
        if (next != null) metadata.updateMetadata(next)
        val bundle = Bundle()
        bundle.putString("track", previous?.id)
        bundle.putDouble("position", Utils.toSeconds(prevPos))
        bundle.putString("nextTrack", next?.id)
        service.emit(MusicEvents.PLAYBACK_TRACK_CHANGED, bundle)
    }

    fun onReset() {
        metadata.removeNotifications()
    }

    fun onEnd(previous: Track?, prevPos: Long) {
        Log.d(Utils.LOG, "onEnd")
        val bundle = Bundle()
        bundle.putString("track", previous?.id)
        bundle.putDouble("position", Utils.toSeconds(prevPos))
        service.emit(MusicEvents.PLAYBACK_QUEUE_ENDED, bundle)
    }

    fun onMetadataReceived(
        source: String,
        title: String?,
        url: String?,
        artist: String?,
        album: String?,
        date: String?,
        genre: String?
    ) {
        Log.d(
            Utils.LOG,
            "onMetadataReceived: $source"
        )
        val bundle = Bundle()
        bundle.putString("source", source)
        bundle.putString("title", title)
        bundle.putString("url", url)
        bundle.putString("artist", artist)
        bundle.putString("album", album)
        bundle.putString("date", date)
        bundle.putString("genre", genre)
        service.emit(MusicEvents.PLAYBACK_METADATA, bundle)
    }

    fun onError(code: String, error: String) {
        Log.d(Utils.LOG, "onError")
        Log.e(
            Utils.LOG,
            "Playback error: $code - $error"
        )
        val bundle = Bundle()
        bundle.putString("code", code)
        bundle.putString("message", error)
        service.emit(MusicEvents.PLAYBACK_ERROR, bundle)
    }

    override fun onAudioFocusChange(focus: Int) {
        Log.d(Utils.LOG, "onDuck")
        var permanent = false
        var paused = false
        var ducking = false
        when (focus) {
            AudioManager.AUDIOFOCUS_GAIN -> paused = false
            AudioManager.AUDIOFOCUS_LOSS -> {
                permanent = true
                paused = true
                abandonFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> paused = true
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> if (alwaysPauseOnInterruption) paused =
                true else ducking = true
            else -> {}
        }
        if (ducking) {
            //playback.setVolumeMultiplier(0.5F);
            wasDucking = true
        } else if (wasDucking) {
            //playback.setVolumeMultiplier(1.0F);
            wasDucking = false
        }
        val bundle = Bundle()
        bundle.putBoolean("permanent", permanent)
        bundle.putBoolean("paused", paused)
        bundle.putBoolean("ducking", ducking)
        service.emit(MusicEvents.BUTTON_DUCK, bundle)
    }

    private fun requestFocus() {
        if (hasAudioFocus) return
        Log.d(Utils.LOG, "Requesting audio focus...")
        val manager = service.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val r: Int
        if (manager == null) {
            r = AudioManager.AUDIOFOCUS_REQUEST_FAILED
        } else if (Build.VERSION.SDK_INT >= 26) {
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setOnAudioFocusChangeListener(this)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setWillPauseWhenDucked(alwaysPauseOnInterruption)
                .build()
            r = if (focus != null) {
                manager.requestAudioFocus(focus!!)
            } else {
                AudioManager.AUDIOFOCUS_REQUEST_FAILED
            }
        } else {
            r = manager.requestAudioFocus(
                this,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        hasAudioFocus = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        if (!hasAudioFocus) return
        Log.d(Utils.LOG, "Abandoning audio focus...")
        val manager = service.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val r: Int
        r = if (manager == null) {
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        } else if (Build.VERSION.SDK_INT >= 26) {
            manager.abandonAudioFocusRequest(focus!!)
        } else {
            manager.abandonAudioFocus(this)
        }
        hasAudioFocus = r != AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    fun destroy(intentToStop: Boolean) {
        Log.d(Utils.LOG, "Releasing service resources...")

        // Disable audio focus
        abandonFocus()

        // Stop receiving audio becoming noisy events
        if (receivingNoisyEvents) {
            try {
                service.unregisterReceiver(noisyReceiver)
                receivingNoisyEvents = false
            } catch (e: Exception) {
            }
        }

        // Release the playback resources
        //if(playback != null) playback.destroy();

        // Release the metadata resources
        metadata.destroy(intentToStop)

        // Release the locks
        if (wifiLock.isHeld) wifiLock.release()
        if (wakeLock.isHeld) wakeLock.release()
    }
}