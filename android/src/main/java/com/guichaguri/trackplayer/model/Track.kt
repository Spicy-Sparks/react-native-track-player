package com.guichaguri.trackplayer.model

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.RatingCompat
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import com.guichaguri.trackplayer.kotlinaudio.models.AudioItemOptions
import com.guichaguri.trackplayer.kotlinaudio.models.MediaType
import com.guichaguri.trackplayer.service.Utils
import com.guichaguri.trackplayer.service.Utils.bundleToJson
import org.json.JSONObject

/**
 * @author Guichaguri
 */
class Track(context: Context?, bundle: Bundle, ratingType: Int) {
    var url: String = ""
    var id: String?
    var resourceId: Int? = null // leave it null, not 0
    var type = MediaType.DEFAULT
    var contentType: String?
    var userAgent: String?
    var artwork: Uri? = null
    var title: String? = null
    var artist: String? = null
    var album: String? = null
    var date: String? = null
    var genre: String? = null
    var duration: Long = 0
    var originalItem: Bundle?
    var json: JSONObject
    var rating: RatingCompat? = null
    var headers: MutableMap<String, String>? = null
    val queueId: Long

    init {
        id = bundle.getString("mediaId")
        val trackType = bundle.getString("type", "default")
        for (t in MediaType.values()) {
            if (t.name.equals(trackType, ignoreCase = true)) {
                type = t
                break
            }
        }
        if (bundle.getString("sourceData") != null) {
            url = bundle.getString("sourceData").toString()
        }
        contentType = bundle.getString("contentType")
        userAgent = bundle.getString("userAgent")
        val httpHeaders = bundle.getBundle("headers")
        if (httpHeaders != null) {
            headers = HashMap()
            for (header in httpHeaders.keySet()) {
                (headers as HashMap<String, String?>)[header] = httpHeaders.getString(header)
            }
        }
        setMetadata(context, bundle, ratingType)
        queueId = System.currentTimeMillis()
        json = bundleToJson(bundle)
        originalItem = bundle
    }

    fun setMetadata(context: Context?, bundle: Bundle, ratingType: Int) {
        artwork = context?.let { Utils.getUri(it, bundle, "artwork") }
        title = bundle.getString("title")
        artist = bundle.getString("artist")
        album = bundle.getString("album")
        date = bundle.getString("date")
        genre = bundle.getString("genre")
        duration = try {
            Utils.toMillis(bundle.getDouble("duration", 0.0))
        } catch (ex: Exception) {
            val durationInt = bundle.getInt("duration", 0)
            Utils.toMillis(durationInt.toDouble())
        }
        rating = Utils.getRating(bundle, "rating", ratingType)
        if (originalItem != null && originalItem != bundle) originalItem!!.putAll(bundle)
    }

    fun toMediaMetadata(): MediaMetadataCompat.Builder {
        val builder = MediaMetadataCompat.Builder()
        builder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
        builder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
        builder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
        builder.putString(MediaMetadataCompat.METADATA_KEY_DATE, date)
        builder.putString(MediaMetadataCompat.METADATA_KEY_GENRE, genre)
        //builder.putString(METADATA_KEY_MEDIA_URI, uri.toString());
        builder.putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, id)
        if (duration > 0) {
            builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
        }
//        if (artwork != null) {
//            builder.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, artwork.toString())
//        }
        if (rating != null) {
            builder.putRating(MediaMetadataCompat.METADATA_KEY_RATING, rating)
        }
        return builder
    }

    fun toQueueItem(): MediaSessionCompat.QueueItem {
        val descr = MediaDescriptionCompat.Builder()
            .setTitle(title)
            .setSubtitle(artist)
            .setMediaId(id) //.setMediaUri(uri)
            .setIconUri(artwork)
            .build()
        return MediaSessionCompat.QueueItem(descr, queueId)
    }

    fun toAudioItem(): TrackAudioItem {
        return TrackAudioItem(this, type, url, artist, title, album, artwork.toString(), duration,
            AudioItemOptions(headers, userAgent, resourceId)
        )
    }
}