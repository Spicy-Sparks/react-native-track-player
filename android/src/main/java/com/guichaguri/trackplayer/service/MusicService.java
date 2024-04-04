package com.guichaguri.trackplayer.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.RatingCompat;
import android.util.Log;
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.media.session.MediaButtonReceiver;

import com.facebook.react.HeadlessJsTaskService;
import com.facebook.react.ReactInstanceManager;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.jstasks.HeadlessJsTaskConfig;
import com.guichaguri.trackplayer.R;
import com.guichaguri.trackplayer.service.metadata.ButtonEvents;
import com.guichaguri.trackplayer.service.models.Track;

import javax.annotation.Nullable;

import static android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
import static android.view.KeyEvent.KEYCODE_MEDIA_NEXT;
import static android.view.KeyEvent.KEYCODE_MEDIA_PLAY;
import static android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS;
import static android.view.KeyEvent.KEYCODE_MEDIA_REWIND;
import static android.view.KeyEvent.KEYCODE_MEDIA_STOP;
import static androidx.core.app.NotificationCompat.PRIORITY_MIN;
import static com.guichaguri.trackplayer.service.Utils.bundleToJson;
import static com.guichaguri.trackplayer.service.Utils.jsonStringToBundle;

import java.util.List;

/**
 * @author Guichaguri
 */
public class MusicService extends HeadlessJsMediaService {

    MusicManager manager;
    Handler handler;
    private Boolean intentToStop = false;

    @Nullable
    @Override
    protected HeadlessJsTaskConfig getTaskConfig(Intent intent) {
        return new HeadlessJsTaskConfig("TrackPlayer", Arguments.createMap(), 0, true);
    }

    @Override
    public void onHeadlessJsTaskFinish(int taskId) {
        // Overridden to prevent the service from being terminated
    }

    public void emit(String event, Bundle data) {
        Intent intent = new Intent(Utils.EVENT_INTENT);

        intent.putExtra("event", event);
        if(data != null) intent.putExtra("data", data);

        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    public void destroy(boolean intentToStop) {
        if(handler != null) {
            handler.removeMessages(0);
            handler = null;
        }

        if(manager != null) {
            manager.destroy(intentToStop);
            manager = null;
        }
    }

    private void onStartForeground() {
        boolean serviceForeground = false;

        if(manager != null) {
            // The session is only active when the service is on foreground
            serviceForeground = manager.getMetadata().getSession().isActive();
        }

        if(!serviceForeground) {
            ReactInstanceManager reactInstanceManager = getReactNativeHost().getReactInstanceManager();
            ReactContext reactContext = reactInstanceManager.getCurrentReactContext();

            // Checks whether there is a React activity
            if(reactContext == null || !reactContext.hasCurrentActivity()) {
                String channel = Utils.getNotificationChannel((Context) this);

                // Sets the service to foreground with an empty notification
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(1, new NotificationCompat.Builder(this, channel).build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                }else{
                    startForeground(1, new NotificationCompat.Builder(this, channel).build());
                }
                // Stops the service right after
                stopSelf();
            }
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        if(Utils.CONNECT_INTENT.equals(intent.getAction())) {
            return new MusicBinder(this, manager);
        }

        return super.onBind(intent);
    }

    @androidx.annotation.Nullable
    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid, @androidx.annotation.Nullable Bundle rootHints) {
        return null;
    }

    @Override
    public void onLoadChildren(@NonNull String parentId, @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {

    }

    @Override
    public void onCreate() {

        super.onCreate();

        //onStartForeground();

        if (manager == null)
            manager = new MusicManager(this);

        if(handler == null)
            handler = new Handler();

        String channel = Utils.getNotificationChannel((Context) this);

        // Sets the service to foreground with an empty notification
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, new NotificationCompat.Builder(this, channel).build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(1, new NotificationCompat.Builder(this, channel).build());
            }
        }catch(Exception ex){}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (manager == null)
            manager = new MusicManager(this);

        if(handler == null)
            handler = new Handler();

        super.onStartCommand(intent, flags, startId);

        startAndStopEmptyNotificationToAvoidANR();
        return START_STICKY;
            /*onStartForeground();
            // Check if the app is on background, then starts a foreground service and then ends it right after


            // Interpret event
            KeyEvent intentExtra = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
            if (intentExtra.getKeyCode() == KEYCODE_MEDIA_STOP) {
                intentToStop = true;
                startServiceOreoAndAbove();
                stopSelf();
            } else {
                intentToStop = false;
            }

            if (manager != null && manager.getMetadata().getSession() != null) {
                MediaButtonReceiver.handleIntent(manager.getMetadata().getSession(), intent);
                return START_NOT_STICKY;
            }
        }

        if (manager == null)
            manager = new MusicManager(this);

        if(handler == null)
            handler = new Handler();

        super.onStartCommand(intent, flags, startId);
        return START_NOT_STICKY;*/
    }

    /**
     * Workaround for the "Context.startForegroundService() did not then call Service.startForeground()"
     * within 5s" ANR and crash by creating an empty notification and stopping it right after. For more
     * information see https://github.com/doublesymmetry/react-native-track-player/issues/1666
     */
    private void startAndStopEmptyNotificationToAvoidANR() {
        NotificationManager notificationManager = (NotificationManager) this.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                    new NotificationChannel("Playback", "Playback", NotificationManager.IMPORTANCE_LOW)
            );
        }

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, "Playback")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setSmallIcon(R.drawable.ic_logo);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            notificationBuilder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
        }
        android.app.Notification notification = notificationBuilder.build();
        startForeground(1, notification);
        stopForeground(true);
    }


    public void startServiceOreoAndAbove(){
        // Needed to prevent crash when dismissing notification
        // https://stackoverflow.com/questions/47609261/bound-service-crash-with-context-startforegroundservice-did-not-then-call-ser?rq=1
        if (Build.VERSION.SDK_INT >= 26) {
            String CHANNEL_ID = Utils.NOTIFICATION_CHANNEL;
            String CHANNEL_NAME = "Playback";

            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(channel);

            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setCategory(Notification.CATEGORY_SERVICE).setSmallIcon(R.drawable.ic_logo).setPriority(PRIORITY_MIN).build();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            }else{
                startForeground(1, notification);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if(manager != null) {
            manager.destroy(true);
            manager = null;
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);

        if (manager == null || manager.shouldStopWithApp()) {
            destroy(true);
            stopSelf();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }
}
