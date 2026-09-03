package com.doublesymmetry.trackplayer.utils

import android.os.Handler
import android.os.Looper
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.Observer
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.doublesymmetry.trackplayer.module.MusicEvents
import timber.log.Timber

/**
 * Watches the car connection and tells JS when Android Auto goes away.
 *
 * This is the ONLY source of [MusicEvents.CONNECTOR_DISCONNECTED]: media3's
 * `MediaLibrarySession.Callback.onDisconnected` fires so long after the car
 * actually disconnects that it cannot drive the pause (see the FORK PATCH note
 * in MusicService), so it is only a late backstop.
 *
 * Which makes the registration below load-bearing, and it used to be tied to an
 * Activity:
 *
 *     val lifecycleOwner = context.currentActivity as? LifecycleOwner
 *     if (lifecycleOwner != null && !isObserving) carConnection?.type?.observe(lifecycleOwner, ..)
 *
 * built once from [com.doublesymmetry.trackplayer.module.MusicModule.initialize]
 * on a throwaway instance. Two ways that silently stopped watching, both of them
 * the normal state of a media app:
 *
 *   - no Activity at that moment — the service starts headless whenever a
 *     controller (system UI, Android Auto) connects to the session, and then
 *     `currentActivity` is null, nothing registers and nothing ever retries;
 *   - the Activity is destroyed later — LiveData removes the observer with it.
 *
 * Either way the disconnect never reached JS, so nothing paused when the car
 * went away: playback simply carried on, rerouted to the phone speaker, with the
 * app closed and the screen locked. It also left the JS side believing Android
 * Auto was still connected, so the next connect was a no-op too.
 *
 * So the detector is process-scoped instead: one instance for the life of the
 * process, [observeForever] rather than an Activity lifecycle, and the React
 * context refreshed on each install so events keep reaching whichever JS context
 * is current.
 */
class AutoConnectionDetector private constructor(
    private var reactContext: ReactContext
) {

    companion object {
        const val TAG = "AutoConnectionDetector"

        @Volatile
        private var instance: AutoConnectionDetector? = null

        /**
         * Start watching, or re-point an already-watching detector at the current
         * React context. Safe to call on every module initialize: the observer is
         * registered once and survives Activity and React-context churn.
         */
        @JvmStatic
        @JvmOverloads
        fun install(
            context: ReactContext,
            onConnectionChange: ((Boolean) -> Unit)? = null
        ): AutoConnectionDetector {
            val detector = instance ?: synchronized(this) {
                instance ?: AutoConnectionDetector(context).also { instance = it }
            }
            detector.reactContext = context
            if (onConnectionChange != null) detector.onConnectionChangeCallback = onConnectionChange
            detector.registerCarConnectionReceiver()
            return detector
        }
    }

    var isCarConnected = false
        private set

    private var carConnection: CarConnection? = null
    private var onConnectionChangeCallback: ((Boolean) -> Unit)? = null
    private var isObserving = false

    private val observer = Observer<Int> { connectionState -> onConnectionStateUpdated(connectionState) }

    fun registerCarConnectionReceiver() {
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post {
            if (isObserving) return@post
            try {
                // CarConnection registers a ContentObserver on Android Auto's provider
                // when its LiveData goes active, so observeForever is what keeps the
                // watch alive with no Activity in the process.
                val connection = carConnection ?: CarConnection(reactContext.applicationContext)
                    .also { carConnection = it }
                connection.type.observeForever(observer)
                isObserving = true
            } catch (e: Throwable) {
                // Nothing to fall back to — log rather than swallow, so a device where
                // the car-connection provider is unavailable is visible in a bug report
                // instead of looking like "the pause on disconnect just doesn't work".
                Timber.tag(TAG).w(e, "could not observe car connection")
            }
        }
    }

    private fun onConnectionStateUpdated(connectionState: Int) {
        when (connectionState) {
            CarConnection.CONNECTION_TYPE_NOT_CONNECTED -> {
                if (isCarConnected) {
                    notifyCarDisconnected()
                }
            }
            CarConnection.CONNECTION_TYPE_NATIVE -> {
                notifyCarConnected(connectionState)
            }
            CarConnection.CONNECTION_TYPE_PROJECTION -> {
                notifyCarConnected(connectionState)
            }
        }
    }

    private fun emitConnectorEvent(eventName: String, connectionState: Int?) {
        val params = Arguments.createMap()
        params.putString("package", "com.google.android.projection.gearhead") // Android Auto package name
        params.putBoolean(
            "isAutomotiveController",
            connectionState == CarConnection.CONNECTION_TYPE_NATIVE // Android Automotive
        )
        params.putBoolean(
            "isAutoCompanionController",
            connectionState == CarConnection.CONNECTION_TYPE_PROJECTION // Android Auto
        )
        params.putBoolean("isMediaNotificationController", false)

        try {
            reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                ?.emit(eventName, params)
        } catch (e: Throwable) {
            // A dead React context (reload, teardown) is the only expected cause. The
            // disconnect is lost either way; say so rather than failing silently.
            Timber.tag(TAG).w(e, "could not emit $eventName")
        }
    }

    private fun notifyCarConnected(connectionState: Int) {
        if (isCarConnected) return
        isCarConnected = true

        // The connect event comes from MusicService.onConnect, which carries the real
        // controller package; this side only reports the disconnect.

        onConnectionChangeCallback?.invoke(true)
    }

    private fun notifyCarDisconnected() {
        if (!isCarConnected) return
        isCarConnected = false

        // MusicService event doesn't work -> emit manually
        emitConnectorEvent(MusicEvents.CONNECTOR_DISCONNECTED, CarConnection.CONNECTION_TYPE_NOT_CONNECTED)

        onConnectionChangeCallback?.invoke(false)
    }
}
