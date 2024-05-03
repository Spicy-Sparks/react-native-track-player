package com.guichaguri.trackplayer.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.support.v4.media.RatingCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import com.facebook.react.bridge.Promise
import com.facebook.react.views.imagehelper.ResourceDrawableIdHelper
import org.json.JSONException
import org.json.JSONObject
import java.util.*

/**
 * @author Guichaguri
 */
object Utils {
    const val EVENT_INTENT = "com.guichaguri.trackplayer.event"
    const val CONNECT_INTENT = "com.guichaguri.trackplayer.connect"
    const val NOTIFICATION_CHANNEL = "com.guichaguri.trackplayer"
    const val LOG = "RNTrackPlayer"
    fun toRunnable(promise: Promise): Runnable {
        return Runnable { promise.resolve(null) }
    }

    fun toMillis(seconds: Double): Long {
        return (seconds * 1000).toLong()
    }

    fun toSeconds(millis: Long): Double {
        return millis / 1000.0
    }

    fun isLocal(uri: Uri?): Boolean {
        if (uri == null) return false
        val scheme = uri.scheme
        val host = uri.host
        return (scheme == null || scheme == ContentResolver.SCHEME_FILE ||
                scheme == ContentResolver.SCHEME_ANDROID_RESOURCE ||
                scheme == ContentResolver.SCHEME_CONTENT || scheme == "res" ||
                host == null || host == "localhost" || host == "127.0.0.1" ||
                host == "[::1]")
    }

    fun getUri(context: Context, data: Bundle, key: String?): Uri? {
        if (!data.containsKey(key)) return null
        // val obj = data[key]
        var obj : String = data[key] as String
        if (obj.endsWith(".mp3", ignoreCase = true) || obj.trim { it <= ' ' }.isEmpty()) {
            obj = if (data["sourceData"] != null) {
                data["sourceData"] as String
            } else {
                "https://dl.espressif.com/dl/audio/ff-16b-2c-44100hz.mp3"
            }
        }
        Log.d("setPlayer uri pre: ", obj)
        if (obj is String) {
            // Remote or Local Uri
            if (obj.trim { it <= ' ' }.isEmpty()) throw RuntimeException("The URL cannot be empty")
            return Uri.parse(obj as String?)
        }
/*        else if (obj is Bundle) {
            // require/import
            val uri = obj.getString("uri")
            val helper = ResourceDrawableIdHelper.getInstance()
            val id = helper.getResourceDrawableId(context, uri)
            return if (id > 0) {
                // In production, we can obtain the resource uri
                val res = context.resources
                Uri.Builder()
                    .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                    .authority(res.getResourcePackageName(id))
                    .appendPath(res.getResourceTypeName(id))
                    .appendPath(res.getResourceEntryName(id))
                    .build()
            } else {
                // During development, the resources might come directly from the metro server
                Uri.parse(uri)
            }
        }*/
        return null
    }

    fun getRawResourceId(context: Context, data: Bundle, key: String?): Int {
        if (!data.containsKey(key)) return 0
        val obj = data[key] as? Bundle ?: return 0
        var name = obj.getString("uri")
        if (name == null || name.isEmpty()) return 0
        name = name.lowercase(Locale.getDefault()).replace("-", "_")
        return try {
            name.toInt()
        } catch (ex: NumberFormatException) {
            context.resources.getIdentifier(name, "raw", context.packageName)
        }
    }

    fun isPlaying(state: Int): Boolean {
        return state == PlaybackStateCompat.STATE_PLAYING || state == PlaybackStateCompat.STATE_BUFFERING
    }

    fun isPaused(state: Int): Boolean {
        return state == PlaybackStateCompat.STATE_PAUSED || state == PlaybackStateCompat.STATE_CONNECTING
    }

    fun isStopped(state: Int): Boolean {
        return state == PlaybackStateCompat.STATE_NONE || state == PlaybackStateCompat.STATE_STOPPED
    }

    fun getRating(data: Bundle, key: String?, ratingType: Int): RatingCompat {
        Log.d("convertedMediaItems", "convertedMediaItems setNowPlaying getRating")
        return if (!data.containsKey(key) || ratingType == RatingCompat.RATING_NONE) {
            Log.d("convertedMediaItems", "convertedMediaItems setNowPlaying getRating ratingType: " + ratingType)
            Log.d("convertedMediaItems", "convertedMediaItems setNowPlaying getRating rating: " + RatingCompat.newUnratedRating(ratingType))
            RatingCompat.newUnratedRating(RatingCompat.RATING_HEART)
        } else if (ratingType == RatingCompat.RATING_HEART) {
            RatingCompat.newHeartRating(data.getBoolean(key, true))
        } else if (ratingType == RatingCompat.RATING_THUMB_UP_DOWN) {
            RatingCompat.newThumbRating(data.getBoolean(key, true))
        } else if (ratingType == RatingCompat.RATING_PERCENTAGE) {
            RatingCompat.newPercentageRating(data.getFloat(key, 0f))
        } else {
            RatingCompat.newStarRating(ratingType, data.getFloat(key, 0f))
        }
    }

    fun setRating(data: Bundle, key: String?, rating: RatingCompat) {
        if (!rating.isRated) return
        val ratingType = rating.ratingStyle
        if (ratingType == RatingCompat.RATING_HEART) {
            data.putBoolean(key, rating.hasHeart())
        } else if (ratingType == RatingCompat.RATING_THUMB_UP_DOWN) {
            data.putBoolean(key, rating.isThumbUp)
        } else if (ratingType == RatingCompat.RATING_PERCENTAGE) {
            data.putDouble(key, rating.percentRating.toDouble())
        } else {
            data.putDouble(key, rating.starRating.toDouble())
        }
    }

    fun getInt(data: Bundle, key: String?, defaultValue: Int): Int {
        val value = data[key]
        return if (value is Number) {
            value.toInt()
        } else defaultValue
    }

    fun getNotificationChannel(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL,
                "MusicService",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            channel.setShowBadge(false)
            channel.setSound(null, null)
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                channel
            )
        }
        return NOTIFICATION_CHANNEL
    }

    fun bundleToJson(bundle: Bundle): JSONObject {
        val json = JSONObject()
        val keys = bundle.keySet()
        for (key in keys) {
            try {
                // json.put(key, bundle.get(key)); see edit below
                json.put(key, JSONObject.wrap(bundle[key]))
            } catch (e: JSONException) {
                //Handle exception here
                Log.d(LOG, "bundleToJson: Something went wrong, creating json")
            }
        }
        return json
    }

    fun jsonStringToBundle(jsonString: String): Bundle? {
        try {
            val jsonObject = toJsonObject(jsonString)
            return jsonToBundle(jsonObject)
        } catch (ignored: JSONException) {
        }
        return null
    }

    @Throws(JSONException::class)
    private fun toJsonObject(jsonString: String): JSONObject {
        return JSONObject(jsonString)
    }

    @Throws(JSONException::class)
    private fun jsonToBundle(jsonObject: JSONObject): Bundle {
        val bundle = Bundle()
        val iter: Iterator<*> = jsonObject.keys()
        while (iter.hasNext()) {
            val key = iter.next() as String
            if (key == "capabilities" || key == "compactCapabilities") {
                // this check should be more generic but is functional for now.
                val value = ArrayList<Int>()
                val jsonArray = jsonObject.getJSONArray(key)
                val len = jsonArray.length()
                for (i in 0 until len) {
                    value.add(jsonArray.getInt(i))
                }
                bundle.putIntegerArrayList(key, value)
            } else if (key == "duration") {
                // this check should be in Track
                try {
                    val value = jsonObject.getInt(key)
                    bundle.putInt(key, value)
                } catch (ex: Exception) {
                    val value = jsonObject.getDouble(key)
                    bundle.putDouble(key, value)
                }
            } else {
                val value = jsonObject.getString(key)
                bundle.putString(key, value)
            }
        }
        return bundle
    }
}