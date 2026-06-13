/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.doublesymmetry.trackplayer

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.PowerManager.WakeLock
import androidx.media3.session.MediaLibraryService
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactInstanceEventListener
import com.facebook.react.ReactNativeHost
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.UiThreadUtil
import com.facebook.react.internal.featureflags.ReactNativeFeatureFlags
import com.facebook.react.jstasks.HeadlessJsTaskConfig
import com.facebook.react.jstasks.HeadlessJsTaskContext.Companion.getInstance
import com.facebook.react.jstasks.HeadlessJsTaskEventListener
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Base class for running JS without a UI. Generally, you only need to override [getTaskConfig],
 * which is called for every [onStartCommand]. The result, if not `null`, is used to run a JS task.
 *
 * If you need more fine-grained control over how tasks are run, you can override [onStartCommand]
 * and call [startTask] depending on your custom logic.
 *
 * If you're starting a `HeadlessJsTaskService` from a `BroadcastReceiver` (e.g. handling push
 * notifications), make sure to call [acquireWakeLockNow] before returning from
 * [BroadcastReceiver.onReceive], to make sure the device doesn't go to sleep before the service is
 * started.
 */
public abstract class HeadlessJsMediaService : MediaLibraryService(), HeadlessJsTaskEventListener {
     val activeTasks: MutableSet<Int> = CopyOnWriteArraySet()
    private var initialized = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            super.onStartCommand(intent, flags, startId)
        } catch (e: IllegalStateException) {
            // media3 MediaLibraryService throws "session is already released" when the
            // service is restarted after its MediaSession was released (post onDestroy).
            // The service is effectively dead — don't start a task on it, just no-op.
            return START_NOT_STICKY
        } catch (e: IllegalArgumentException) {
            return START_NOT_STICKY
        }
        val taskConfig = getTaskConfig(intent)
        return if (!initialized && taskConfig != null) {
            initialized = true
            startTask(taskConfig)
            START_REDELIVER_INTENT
        } else {
            START_NOT_STICKY
        }
    }

    /**
     * Called from [.onStartCommand] to create a [HeadlessJsTaskConfig] for this intent.
     *
     * @return a [HeadlessJsTaskConfig] to be used with [startTask], or `null` to ignore this command.
     */
    protected open fun getTaskConfig(intent: Intent?): HeadlessJsTaskConfig? = null

    override fun onBind(intent: Intent?): IBinder? {
        return try {
            super.onBind(intent)
        } catch (e: IllegalStateException) {
            // See onStartCommand: the MediaSession may already be released on a reused service.
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    override fun onCreate() {
        super.onCreate()
    }

    /**
     * Start a task. This method handles starting a new React instance if required.
     *
     * Has to be called on the UI thread.
     *
     * @param taskConfig describes what task to start and the parameters to pass to it
     */
    protected fun startTask(taskConfig: HeadlessJsTaskConfig) {
        UiThreadUtil.assertOnUiThread()
        // acquireWakeLockNow(this)

        val context = reactContext
        if (context == null) {
            createReactContextAndScheduleTask(taskConfig)
        } else {
            invokeStartTask(context, taskConfig)
        }
    }

    private fun invokeStartTask(reactContext: ReactContext, taskConfig: HeadlessJsTaskConfig) {
        val headlessJsTaskContext = getInstance(reactContext)
        headlessJsTaskContext.addTaskEventListener(this)
        UiThreadUtil.runOnUiThread {
            val taskId = headlessJsTaskContext.startTask(taskConfig)
            activeTasks.add(taskId)
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        reactContext?.let { context ->
            val headlessJsTaskContext = getInstance(context)
            headlessJsTaskContext.removeTaskEventListener(this)
        }
        wakeLock?.release()
    }

    override fun onHeadlessJsTaskStart(taskId: Int): Unit = Unit

    override fun onHeadlessJsTaskFinish(taskId: Int) {
        activeTasks.remove(taskId)
        if (activeTasks.isEmpty()) {
            stopSelf()
        }
    }

    /**
     * Get the [com.facebook.react.ReactNativeHost] used by this app. By default, assumes [getApplication] is an instance
     * of [com.facebook.react.ReactApplication] and calls [com.facebook.react.ReactApplication.reactNativeHost].
     *
     * Override this method if your application class does not implement `ReactApplication` or you
     * simply have a different mechanism for storing a `ReactNativeHost`, e.g. as a static field
     * somewhere.
     */
    protected val reactNativeHost: ReactNativeHost
        get() = (application as ReactApplication).reactNativeHost

    /**
     * Get the [com.facebook.react.ReactHost] used by this app. By default, assumes [.getApplication] is an instance of
     * [ReactApplication] and calls [ReactApplication.getReactHost]. This method assumes it is called
     * in new architecture and returns null if not.
     */
    protected val reactHost: ReactHost?
        get() = (application as ReactApplication).reactHost

    protected val reactContext: ReactContext?
        @SuppressLint("VisibleForTests")
        get() {
            if (ReactNativeFeatureFlags.enableBridgelessArchitecture()) {
                val reactHost =
                    checkNotNull(reactHost) { "ReactHost is not initialized in New Architecture" }
                return reactHost.currentReactContext
            } else {
                val reactInstanceManager = reactNativeHost.reactInstanceManager
                return reactInstanceManager.currentReactContext
            }
        }

    private fun createReactContextAndScheduleTask(taskConfig: HeadlessJsTaskConfig) {
        if (ReactNativeFeatureFlags.enableBridgelessArchitecture()) {
            val reactHost = checkNotNull(reactHost)
            reactHost.addReactInstanceEventListener(
                object : ReactInstanceEventListener {
                    override fun onReactContextInitialized(context: ReactContext) {
                        invokeStartTask(context, taskConfig)
                        reactHost.removeReactInstanceEventListener(this)
                    }
                })
            reactHost.start()
        } else {
            val reactInstanceManager = reactNativeHost.reactInstanceManager
            reactInstanceManager.addReactInstanceEventListener(
                object : ReactInstanceEventListener {
                    override fun onReactContextInitialized(context: ReactContext) {
                        invokeStartTask(context, taskConfig)
                        reactInstanceManager.removeReactInstanceEventListener(this)
                    }
                })
            reactInstanceManager.createReactContextInBackground()
        }
    }

    public companion object {
        var wakeLock: WakeLock? = null

        /**
         * Acquire a wake lock to ensure the device doesn't go to sleep while processing background
         * tasks.
         */
        @JvmStatic
        @SuppressLint("WakelockTimeout")
        public fun acquireWakeLockNow(context: Context) {
            if (wakeLock == null || wakeLock?.isHeld == false) {
                val powerManager = checkNotNull(context.getSystemService(POWER_SERVICE) as PowerManager)
                wakeLock =
                    powerManager
                        .newWakeLock(
                            PowerManager.PARTIAL_WAKE_LOCK, HeadlessJsMediaService::class.java.canonicalName)
                        .also { lock ->
                            lock.setReferenceCounted(false)
                            lock.acquire()
                        }
            }
        }
    }
}