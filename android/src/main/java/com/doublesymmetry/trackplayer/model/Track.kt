package com.doublesymmetry.trackplayer.model

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.lovegaoshi.kotlinaudio.models.AudioItemOptions
import com.lovegaoshi.kotlinaudio.models.MediaType
import com.doublesymmetry.trackplayer.utils.BundleUtils

/**
 * @author Milen Pivchev @mpivchev
 */
@OptIn(UnstableApi::class)
class Track
    (context: Context, bundle: Bundle, ratingType: Int) : TrackMetadata() {
    var uri: Uri? = null
    var resourceId: Int?
    var type = MediaType.DEFAULT
    var contentType: String?
    var userAgent: String?
    var originalItem: Bundle?
    var headers: HashMap<String, String>? = null
    val queueId: Long
    var notPlayable: Boolean = false
    // Per-track loudness normalization gain (linear, 1.0 = unity).
    var normalizationGain: Float = 1f

    override fun setMetadata(context: Context, bundle: Bundle?, ratingType: Int) {
        super.setMetadata(context, bundle, ratingType)
        if (originalItem != null && originalItem != bundle) originalItem!!.putAll(bundle)
        // Only when the caller actually said something about it: a metadata-only bundle
        // (title/artwork) must not silently mark an unresolved placeholder as playable.
        if (bundle?.containsKey("notPlayable") == true) notPlayable = bundle.getBoolean("notPlayable", false)
    }

    fun toAudioItem(): TrackAudioItem {
        return TrackAudioItem(this, type, uri.toString(), artist, title, album, artwork.toString(), duration,
                AudioItemOptions(headers, userAgent, resourceId, normalizationGain), mediaId, notPlayable)
    }

    init {
        resourceId = BundleUtils.getRawResourceId(context, bundle, "url")
        uri = if (resourceId == 0) {
            resourceId = null
            BundleUtils.getUri(context, bundle, "url")
        } else {
            // RawResourceDataSource.buildRawResourceUri(resourceId!!)
            Uri.Builder().scheme(ContentResolver.SCHEME_ANDROID_RESOURCE).path(resourceId!!.toString()).build()
        }
        val trackType = bundle.getString("type", "default")
        for (t in MediaType.entries) {
            if (t.name.equals(trackType, ignoreCase = true)) {
                type = t
                break
            }
        }
        contentType = bundle.getString("contentType")
        userAgent = bundle.getString("userAgent")
        val httpHeaders = bundle.getBundle("headers")
        if (httpHeaders != null) {
            headers = HashMap()
            for (header in httpHeaders.keySet()) {
                headers!![header] = httpHeaders.getString(header)!!
            }
        }
        notPlayable = bundle.getBoolean("notPlayable", false)
        normalizationGain = bundle.getDouble("normalizationGain", 1.0).toFloat()
        setMetadata(context, bundle, ratingType)
        queueId = System.currentTimeMillis()
        originalItem = bundle
    }
}