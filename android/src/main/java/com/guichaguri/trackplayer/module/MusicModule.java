package com.guichaguri.trackplayer.module;

import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v4.media.RatingCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.facebook.react.bridge.*;
import com.guichaguri.trackplayer.service.MusicBinder;
import com.guichaguri.trackplayer.service.MusicService;
import com.guichaguri.trackplayer.service.Utils;
import com.guichaguri.trackplayer.service.models.Track;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

/**
 * @author Guichaguri
 */
public class MusicModule extends ReactContextBaseJavaModule implements ServiceConnection {

    private MusicBinder binder;
    private MusicEvents eventHandler;
    private ArrayDeque<Runnable> initCallbacks = new ArrayDeque<>();
    private boolean connecting = false;
    private Bundle options;

    public MusicModule(ReactApplicationContext reactContext) {
        super(reactContext);
    }

    @Override
    @Nonnull
    public String getName() {
        return "TrackPlayerModule";
    }

    @Override
    public void initialize() {
        ReactContext context = getReactApplicationContext();
        LocalBroadcastManager manager = LocalBroadcastManager.getInstance(context);

        eventHandler = new MusicEvents(context);
        manager.registerReceiver(eventHandler, new IntentFilter(Utils.EVENT_INTENT));
    }

    @Override
    public void onCatalystInstanceDestroy() {
        ReactContext context = getReactApplicationContext();

        if(eventHandler != null) {
            LocalBroadcastManager manager = LocalBroadcastManager.getInstance(context);

            manager.unregisterReceiver(eventHandler);
            eventHandler = null;
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        binder = (MusicBinder)service;
        connecting = false;

        // Reapply options that user set before with updateOptions
        if (options != null) {
            binder.updateOptions(options);
        }

        // Triggers all callbacks
        while(!initCallbacks.isEmpty()) {
            binder.post(initCallbacks.remove());
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        binder = null;
        connecting = false;
    }

    /**
     * Waits for a connection to the service and/or runs the {@link Runnable} in the player thread
     */
    private void waitForConnection(Runnable r) {
        if(binder != null) {
            binder.post(r);
            return;
        } else {
            initCallbacks.add(r);
        }

        if(connecting) return;

        ReactApplicationContext context = getReactApplicationContext();

        // Binds the service to get a MediaWrapper instance
        Intent intent = new Intent(context, MusicService.class);
        //context.startService(intent);
        try {
            context.startService(intent);
        } catch (Exception e) {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        }
        intent.setAction(Utils.CONNECT_INTENT);
        context.bindService(intent, this, 0);



        connecting = true;
    }

    /* ****************************** API ****************************** */

    @Nullable
    @Override
    public Map<String, Object> getConstants() {
        Map<String, Object> constants = new HashMap<>();

        // Capabilities
        constants.put("CAPABILITY_PLAY", PlaybackStateCompat.ACTION_PLAY);
        constants.put("CAPABILITY_PLAY_FROM_ID", PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID);
        constants.put("CAPABILITY_PLAY_FROM_SEARCH", PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH);
        constants.put("CAPABILITY_PAUSE", PlaybackStateCompat.ACTION_PAUSE);
        constants.put("CAPABILITY_STOP", PlaybackStateCompat.ACTION_STOP);
        constants.put("CAPABILITY_SEEK_TO", PlaybackStateCompat.ACTION_SEEK_TO);
        constants.put("CAPABILITY_SKIP", PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM);
        constants.put("CAPABILITY_SKIP_TO_NEXT", PlaybackStateCompat.ACTION_SKIP_TO_NEXT);
        constants.put("CAPABILITY_SKIP_TO_PREVIOUS", PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS);
        constants.put("CAPABILITY_SET_RATING", PlaybackStateCompat.ACTION_SET_RATING);
        constants.put("CAPABILITY_JUMP_FORWARD", PlaybackStateCompat.ACTION_FAST_FORWARD);
        constants.put("CAPABILITY_JUMP_BACKWARD", PlaybackStateCompat.ACTION_REWIND);

        // States
        constants.put("STATE_NONE", PlaybackStateCompat.STATE_NONE);
        constants.put("STATE_READY", PlaybackStateCompat.STATE_PAUSED);
        constants.put("STATE_PLAYING", PlaybackStateCompat.STATE_PLAYING);
        constants.put("STATE_PAUSED", PlaybackStateCompat.STATE_PAUSED);
        constants.put("STATE_STOPPED", PlaybackStateCompat.STATE_STOPPED);
        constants.put("STATE_BUFFERING", PlaybackStateCompat.STATE_BUFFERING);
        constants.put("STATE_CONNECTING", PlaybackStateCompat.STATE_CONNECTING);

        // Rating Types
        constants.put("RATING_HEART", RatingCompat.RATING_HEART);
        constants.put("RATING_THUMBS_UP_DOWN", RatingCompat.RATING_THUMB_UP_DOWN);
        constants.put("RATING_3_STARS", RatingCompat.RATING_3_STARS);
        constants.put("RATING_4_STARS", RatingCompat.RATING_4_STARS);
        constants.put("RATING_5_STARS", RatingCompat.RATING_5_STARS);
        constants.put("RATING_PERCENTAGE", RatingCompat.RATING_PERCENTAGE);

        return constants;
    }

    @ReactMethod
    public void destroy() {
        // Ignore if it was already destroyed
        if (binder == null && !connecting) return;

        try {
            if(binder != null) {
                binder.destroy();
                binder = null;
            }

            ReactContext context = getReactApplicationContext();
            if(context != null) context.unbindService(this);
        } catch(Exception ex) {
            // This method shouldn't be throwing unhandled errors even if something goes wrong.
            Log.e(Utils.LOG, "An error occurred while destroying the service", ex);
        }
    }

    @ReactMethod
    public void updateOptions(ReadableMap data, final Promise callback) {
        // keep options as we may need them for correct MetadataManager reinitialization later
        options = Arguments.toBundle(data);

        waitForConnection(() -> {
            if(binder != null)
                binder.updateOptions(options);
            callback.resolve(null);
        });
    }

    @ReactMethod
    public void setNowPlaying(ReadableMap trackMap, final Promise callback) {
        final Bundle bundle = Arguments.toBundle(trackMap);

        waitForConnection(() -> {
            try {
                int state = trackMap.hasKey("state") ? trackMap.getInt("state") : 0;

                long elapsedTime = -1;

                if(trackMap.hasKey("elapsedTime")) {
                    try {
                        elapsedTime = Utils.toMillis(trackMap.getDouble("elapsedTime"));

                    }
                    catch(Exception ex){
                        elapsedTime = Utils.toMillis((long)trackMap.getInt("elapsedTime"));
                    }
                }

                Track track = new Track(getReactApplicationContext(), bundle, binder.getRatingType());
                binder.getManager().setCurrentTrack(track);
                binder.getManager().getMetadata().updateMetadata(track);
                binder.getManager().setState(state, elapsedTime);
            } catch(Exception ex) {
                callback.reject("invalid_track_object", ex);
                return;
            }

            if(binder != null && binder.getManager() != null && binder.getManager().getCurrentTrack() == null)
                callback.reject("invalid_track_object", "Track is missing a required key");

            callback.resolve(null);
        });
    }

    @ReactMethod
    public void updatePlayback(ReadableMap trackMap, final Promise callback) {
        final Bundle bundle = Arguments.toBundle(trackMap);

        waitForConnection(() -> {
            try {
                int state = trackMap.hasKey("state") ? trackMap.getInt("state") : 0;
                long elapsedTime = -1;

                if(trackMap.hasKey("elapsedTime")) {
                    try {
                        elapsedTime = Utils.toMillis((long)trackMap.getInt("elapsedTime"));
                    }
                    catch(Exception ex){
                        elapsedTime = Utils.toMillis(trackMap.getDouble("elapsedTime"));
                    }
                }

                long duration = -1;

                if(trackMap.hasKey("duration")) {
                    try {
                        duration = Utils.toMillis((long)trackMap.getInt("duration"));
                    }
                    catch(Exception ex){
                        duration = Utils.toMillis(trackMap.getDouble("duration"));
                    }
                }
                //update current track duration
                Track currentTrack = binder.getManager().getCurrentTrack();
                if(currentTrack != null && duration > -1)
                {
                    currentTrack.duration = duration;
                    binder.getManager().getMetadata().updateMetadata(currentTrack);
                }

                binder.getManager().setState(state, elapsedTime);
            } catch (Exception ex) {
                callback.reject("invalid_track_object", ex);
                return;
            }

            if (binder.getManager().getCurrentTrack() == null)
                callback.reject("invalid_track_object", "Track is missing a required key");
        });
    }
    @ReactMethod
    public void reset(final Promise callback) {
        waitForConnection(() -> {
            binder.getManager().onReset();
            callback.resolve(null);
        });
    }
}
