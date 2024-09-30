package com.guichaguri.trackplayer.service

import android.os.Binder
import android.os.Bundle
import android.os.Handler

/**
 * @author Guichaguri
 */
class MusicBinder(val service: MusicService?, val manager: MusicManager) : Binder() {

    fun post(r: Runnable?) {
        if (service == null) return
        if (service.handler == null) service.handler = Handler()
        service.handler!!.post(r!!)
    }

    fun updateOptions(bundle: Bundle) {
        manager.setStopWithApp(bundle.getBoolean("stopWithApp", true))
        manager.setAlwaysPauseOnInterruption(bundle.getBoolean("alwaysPauseOnInterruption", false))
        manager.metadata.updateOptions(bundle)
    }

    val ratingType: Int
        get() = manager.metadata.ratingType

    fun destroy() {
        service!!.destroy(true)
        service.stopSelf()
    }
}