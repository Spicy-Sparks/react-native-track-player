package com.doublesymmetry.trackplayer.video

import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.annotations.ReactProp

class TrackPlayerVideoViewManager : SimpleViewManager<TrackPlayerVideoView>() {
    override fun getName() = "RNTrackPlayerVideoView"

    override fun createViewInstance(reactContext: ThemedReactContext): TrackPlayerVideoView {
        return TrackPlayerVideoView(reactContext)
    }

    @ReactProp(name = "resizeMode")
    fun setResizeMode(view: TrackPlayerVideoView, mode: String?) {
        view.setResizeMode(mode)
    }
}
