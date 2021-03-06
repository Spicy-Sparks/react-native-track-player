package com.guichaguri.trackplayer.service;

import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;

/**
 * @author Guichaguri
 */
public class MusicBinder extends Binder {

    private final MusicService service;
    private final MusicManager manager;

    public MusicBinder(MusicService service, MusicManager manager) {
        this.service = service;
        this.manager = manager;
    }

    public void post(Runnable r) {
        if(service == null)
            return;
        
        if(service.handler == null)
            service.handler = new Handler();

        service.handler.post(r);
    }

    public MusicManager getManager(){ return this.manager; }

    public void updateOptions(Bundle bundle) {
        manager.setStopWithApp(bundle.getBoolean("stopWithApp", false));
        manager.setAlwaysPauseOnInterruption(bundle.getBoolean("alwaysPauseOnInterruption", false));
        manager.getMetadata().updateOptions(bundle);
    }

    public int getRatingType() {
        return manager.getMetadata().getRatingType();
    }

    public void destroy() {
        service.destroy(true);
        service.stopSelf();
    }

}
