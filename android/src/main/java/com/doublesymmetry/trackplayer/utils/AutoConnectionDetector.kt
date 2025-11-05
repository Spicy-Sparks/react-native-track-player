package com.doublesymmetry.trackplayer.utils

import android.os.Handler
import android.os.Looper
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.LifecycleOwner
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.doublesymmetry.trackplayer.module.MusicEvents

class AutoConnectionDetector(
    val context: ReactContext,
    onConnectionChange: ((Boolean) -> Unit)? = null
) {

    companion object {
        const val TAG = "AutoConnectionDetector"
    }

    var isCarConnected = false
    private var carConnection: CarConnection? = null
    private val onConnectionChangeCallback = onConnectionChange
    private var isObserving = false

    fun registerCarConnectionReceiver() {
        if (carConnection == null) {
            carConnection = CarConnection(context)
        }

        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post {
            val lifecycleOwner = context.currentActivity as? LifecycleOwner
            if (lifecycleOwner != null && !isObserving) {
                try {
                    carConnection?.type?.observe(lifecycleOwner, ::onConnectionStateUpdated)
                    isObserving = true
                } catch (_: Throwable) {}
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

        context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            ?.emit(eventName, params)
    }

    private fun notifyCarConnected(connectionState: Int) {
        if (isCarConnected) return
        isCarConnected = true

        // emitConnectionUpdate(params) // old
        // emitConnectorEvent(MusicEvents.CONNECTOR_CONNECTED, connectionState) // new but not necessary (MusicService event works)

        onConnectionChangeCallback?.invoke(true)
    }

    private fun notifyCarDisconnected() {
        if (!isCarConnected) return
        isCarConnected = false

        // emitConnectionUpdate(params) // old

        // MusicService event doesn't work -> emit manually
        emitConnectorEvent(MusicEvents.CONNECTOR_DISCONNECTED, CarConnection.CONNECTION_TYPE_NOT_CONNECTED)

        onConnectionChangeCallback?.invoke(false)
    }
}