package com.doublesymmetry.trackplayer.service

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import timber.log.Timber

/**
 * Delegating [MediaNotification.Provider] whose only job is to make the notification-changed
 * callback survive a foreground-service start that Android refuses.
 *
 * This closes the hole documented as case 3b in [MusicService.onUpdateNotification]. media3 loads
 * the notification's album art asynchronously; when the bitmap arrives,
 * `DefaultMediaNotificationProvider.OnBitmapLoadedFutureCallback` invokes the provider callback,
 * which runs straight into `MediaNotificationManager.updateNotificationInternal()` ->
 * `startForeground()` -> `Context.startForegroundService()`. That chain never passes through
 * [MusicService.onUpdateNotification], so the try/catch there cannot see it, and media3 1.8.0
 * guards none of it itself. If the app went to the background between requesting the artwork and
 * receiving it, the start is illegal and the process dies — for a *notification artwork update*,
 * which is worth nothing at all.
 *
 * The callback we hand to the delegate is therefore wrapped: a refused start is logged and
 * dropped, exactly as [MusicService.onUpdateNotification] already does for the synchronous path.
 * The consequence of dropping it is that the notification is not promoted to a foreground service
 * on that update — which is the state the system just insisted on anyway.
 *
 * Only the callback is wrapped. Everything about how the notification looks and behaves stays with
 * the delegate, so this cannot drift from media3's own defaults.
 */
@OptIn(UnstableApi::class)
class ForegroundSafeNotificationProvider(
    private val delegate: MediaNotification.Provider
) : MediaNotification.Provider {

    override fun createNotification(
        mediaSession: MediaSession,
        customLayout: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback
    ): MediaNotification =
        delegate.createNotification(
            mediaSession,
            customLayout,
            actionFactory
        ) { notification ->
            try {
                onNotificationChangedCallback.onNotificationChanged(notification)
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (API 31+) and the plain
                // IllegalStateException older releases throw for the same refusal.
                Timber.tag("APM")
                    .e(e, "notification update: foreground start not allowed, dropping promotion")
            }
        }

    override fun handleCustomCommand(
        session: MediaSession,
        action: String,
        extras: Bundle
    ): Boolean = delegate.handleCustomCommand(session, action, extras)
}
