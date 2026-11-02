package com.lovegaoshi.kotlinaudio.models

/**
 * What JS hears about the audio focus (Event.RemoteDuck).
 *
 * @param playWhenReady whether the player was set to play when this happened, read BEFORE the
 * player paused for it. A track still buffering is not playing yet but is meant to: JS cannot tell
 * the two apart from its own state, and needs the intent to know whether to resume on GAIN.
 * Null when it was not read.
 * @param denied not a focus change but a refused request: the player wanted to start and the
 * system said no (a call is up). It did not start.
 */
data class FocusChangeData(
    val isPaused: Boolean,
    val isFocusLostPermanently: Boolean,
    val playWhenReady: Boolean? = null,
    val denied: Boolean = false,
)
