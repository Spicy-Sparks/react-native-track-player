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
import com.google.android.exoplayer2.upstream.RawResourceDataSource
import com.guichaguri.trackplayer.service.Utils
import com.guichaguri.trackplayer.service.Utils.bundleToJson
import org.json.JSONObject

/**
 * @author Guichaguri
 */
class Track(context: Context?, bundle: Bundle, ratingType: Int) {
    var url: Uri? = null
    var id: String?
    var resourceId: Int? = 0
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
        Log.d("convertedMediaItems", "convertedMediaItems setNowPlaying TRACK")
        id = bundle.getString("id")
        Log.d("convertedMediaItems", "convertedMediaItems setNowPlaying TRACK id: " + id)
        val trackType = bundle.getString("type", "default")
        Log.d("convertedMediaItems", "convertedMediaItems setNowPlaying TRACK trackType: " + trackType)
        for (t in MediaType.values()) {
            Log.d("convertedMediaItems", "convertedMediaItems setNowPlaying TRACK t: " + t)
            if (t.name.equals(trackType, ignoreCase = true)) {
                type = t
                break
            }
        }
        Log.d("convertedMediaItems", "convertedMediaItems setNowPlaying TRACK after for")
        url = if (resourceId == 0 && context != null) {
            resourceId = null
            Log.d("convertedMediaItems", "convertedMediaItems setNowPlaying TRACK pre get")
            Utils.getUri(context, bundle, "sourceData")
        } else {
            RawResourceDataSource.buildRawResourceUri(resourceId!!)
        }
        Log.d("convertedMediaItems", "convertedMediaItems setNowPlaying TRACK url: " + url)
        contentType = bundle.getString("contentType")
        userAgent = bundle.getString("userAgent")
        val httpHeaders = bundle.getBundle("headers")
        if (httpHeaders != null) {
            headers = HashMap()
            for (header in httpHeaders.keySet()) {
                (headers as HashMap<String, String?>)[header] = httpHeaders.getString(header)
            }
        }
        Log.d("convertedMediaItems", "convertedMediaItems setNowPlaying setMetadata PRE")
        setMetadata(context, bundle, ratingType)
        Log.d("convertedMediaItems", "convertedMediaItems setNowPlaying setMetadata POST")
        queueId = System.currentTimeMillis()
        json = bundleToJson(bundle)
        originalItem = bundle
        Log.d("convertedMediaItems", "convertedMediaItems setNowPlaying setMetadata END")
    }

    fun setMetadata(context: Context?, bundle: Bundle, ratingType: Int) {
        Log.d("convertedMediaItems", "convertedMediaItems setNowPlaying setMetadata BEGIN IN")
        artwork = context?.let { Utils.getUri(it, bundle, "artwork") }
        Log.d("convertedMediaItems", "convertedMediaItems setNowPlaying setMetadata artwork: " + artwork)
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
        Log.d("convertedMediaItems", "convertedMediaItems setNowPlaying setMetadata duration: " + duration)
        rating = Utils.getRating(bundle, "rating", ratingType)
        Log.d("convertedMediaItems", "convertedMediaItems setNowPlaying setMetadata rating: " + rating)
        if (originalItem != null && originalItem != bundle) originalItem!!.putAll(bundle)
        Log.d("convertedMediaItems", "convertedMediaItems setNowPlaying setMetadata END IN")
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
        if (artwork != null) {
            builder.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, artwork.toString())
        }
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
        return TrackAudioItem(this, type, url.toString(), artist, title, album, artwork.toString(), duration,
            AudioItemOptions(headers, userAgent, resourceId)
        )
    }
}