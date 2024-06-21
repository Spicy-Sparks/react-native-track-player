package com.guichaguri.trackplayer.module

import android.annotation.SuppressLint
import android.content.AsyncQueryHandler
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule

class AutoConnectionDetector(val context: ReactContext) {

    companion object {
        const val TAG = "AutoConnectionDetector"

        // columnName for provider to query on connection status
        const val CAR_CONNECTION_STATE = "CarConnectionState"

        // auto app on your phone will send broadcast with this action when connection state changes
        const val ACTION_CAR_CONNECTION_UPDATED = "androidx.car.app.connection.action.CAR_CONNECTION_UPDATED"

        // phone is not connected to car
        const val CONNECTION_TYPE_NOT_CONNECTED = 0

        // phone is connected to Automotive OS
        const val CONNECTION_TYPE_NATIVE = 1

        // phone is connected to Android Auto
        const val CONNECTION_TYPE_PROJECTION = 2

        private const val QUERY_TOKEN = 42

        private const val CAR_CONNECTION_AUTHORITY = "androidx.car.app.connection"

        private val PROJECTION_HOST_URI = Uri.Builder().scheme("content").authority(CAR_CONNECTION_AUTHORITY).build()

        private const val CAR_CONNECTION_UPDATE = "car-connection-update"

        var isCarConnected = false
    }

    private val carConnectionReceiver = CarConnectionBroadcastReceiver()
    private val carConnectionQueryHandler = CarConnectionQueryHandler(context.contentResolver, context)
    private var isReceiverRegistered = false

    fun registerCarConnectionReceiver() {
        if (!isReceiverRegistered) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(carConnectionReceiver, IntentFilter(ACTION_CAR_CONNECTION_UPDATED), Context.RECEIVER_NOT_EXPORTED)
            } else @SuppressLint("UnspecifiedRegisterReceiverFlag") {
                context.registerReceiver(carConnectionReceiver, IntentFilter(ACTION_CAR_CONNECTION_UPDATED))
            }
            isReceiverRegistered = true
        }
        queryForState()
    }

    fun unRegisterCarConnectionReceiver() {
        if (isReceiverRegistered) {
            context.unregisterReceiver(carConnectionReceiver)
            isReceiverRegistered = false
        }
    }

    private fun queryForState() {
        carConnectionQueryHandler.startQuery(
            QUERY_TOKEN,
            null,
            PROJECTION_HOST_URI,
            arrayOf(CAR_CONNECTION_STATE),
            null,
            null,
            null
        )
    }

    inner class CarConnectionBroadcastReceiver : BroadcastReceiver() {
        // query for connection state every time the receiver receives the broadcast
        override fun onReceive(context: Context?, intent: Intent?) {
            queryForState()
        }
    }

    internal class CarConnectionQueryHandler(resolver: ContentResolver?, private val context: ReactContext?) : AsyncQueryHandler(resolver) {

        private fun emitConnectionUpdate(params: WritableMap) {
            context?.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)?.emit(
                CAR_CONNECTION_UPDATE, params
            )
        }

        private fun notifyCarConnected() {
            isCarConnected = true
            val params = Arguments.createMap()
            params.putBoolean("connected", true)
            emitConnectionUpdate(params)
        }

        private fun notifyCarDisconnected() {
            isCarConnected = false
            val params = Arguments.createMap()
            params.putBoolean("connected", false)
            emitConnectionUpdate(params)
        }

        // notify new queryed connection status when query complete
        override fun onQueryComplete(token: Int, cookie: Any?, response: Cursor?) {
            if (response == null) {
                // Null response from content provider when checking connection to the car, treating as disconnected
                notifyCarDisconnected()
                return
            }
            val carConnectionTypeColumn = response.getColumnIndex(CAR_CONNECTION_STATE)
            if (carConnectionTypeColumn < 0) {
                // Connection to car response is missing the connection type, treating as disconnected
                notifyCarDisconnected()
                return
            }
            if (!response.moveToNext()) {
                // Connection to car response is empty, treating as disconnected
                notifyCarDisconnected()
                return
            }
            val connectionState = response.getInt(carConnectionTypeColumn)
            if (connectionState == CONNECTION_TYPE_NOT_CONNECTED) {
                // Android Auto disconnected
                notifyCarDisconnected()
            } else {
                // Android Auto connected
                notifyCarConnected()
            }
        }
    }
}
