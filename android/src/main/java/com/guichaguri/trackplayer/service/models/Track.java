package com.guichaguri.trackplayer.service.models;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.RatingCompat;
import android.support.v4.media.session.MediaSessionCompat.QueueItem;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource;
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource;
import com.google.android.exoplayer2.upstream.*;
import com.google.android.exoplayer2.util.Util;
import com.guichaguri.trackplayer.service.Utils;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static android.support.v4.media.MediaMetadataCompat.*;
import static com.guichaguri.trackplayer.service.Utils.bundleToJson;

/**
 * @author Guichaguri
 */
public class Track {
    public String id;
    public int resourceId;

    public TrackType type = TrackType.DEFAULT;

    public String contentType;
    public String userAgent;

    public Uri artwork;

    public String title;
    public String artist;
    public String album;
    public String date;
    public String genre;
    public long duration;
    public Bundle originalItem;
    public JSONObject json;

    public RatingCompat rating;

    public Map<String, String> headers;

    public final long queueId;

    public Track(Context context, Bundle bundle, int ratingType) {
        id = bundle.getString("id");

        String trackType = bundle.getString("type", "default");

        for(TrackType t : TrackType.values()) {
            if(t.name.equalsIgnoreCase(trackType)) {
                type = t;
                break;
            }
        }

        contentType = bundle.getString("contentType");
        userAgent = bundle.getString("userAgent");

        Bundle httpHeaders = bundle.getBundle("headers");
        if(httpHeaders != null) {
            headers = new HashMap<>();
            for(String header : httpHeaders.keySet()) {
                headers.put(header, httpHeaders.getString(header));
            }
        }

        setMetadata(context, bundle, ratingType);

        queueId = System.currentTimeMillis();
        json = bundleToJson(bundle);
        originalItem = bundle;
    }

    public void setMetadata(Context context, Bundle bundle, int ratingType) {
        artwork = Utils.getUri(context, bundle, "artwork");

        title = bundle.getString("title");
        artist = bundle.getString("artist");
        album = bundle.getString("album");
        date = bundle.getString("date");
        genre = bundle.getString("genre");
        duration = Utils.toMillis(bundle.getDouble("duration", 0));

        rating = Utils.getRating(bundle, "rating", ratingType);

        if (originalItem != null && originalItem != bundle)
            originalItem.putAll(bundle);
    }

    public MediaMetadataCompat.Builder toMediaMetadata() {
        MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder();

        builder.putString(METADATA_KEY_TITLE, title);
        builder.putString(METADATA_KEY_ARTIST, artist);
        builder.putString(METADATA_KEY_ALBUM, album);
        builder.putString(METADATA_KEY_DATE, date);
        builder.putString(METADATA_KEY_GENRE, genre);
        //builder.putString(METADATA_KEY_MEDIA_URI, uri.toString());
        builder.putString(METADATA_KEY_MEDIA_ID, id);

        if (duration > 0) {
            builder.putLong(METADATA_KEY_DURATION, duration);
        }

        if (artwork != null) {
            builder.putString(METADATA_KEY_ART_URI, artwork.toString());
        }

        if (rating != null) {
            builder.putRating(METADATA_KEY_RATING, rating);
        }

        return builder;
    }

    public QueueItem toQueueItem() {
        MediaDescriptionCompat descr = new MediaDescriptionCompat.Builder()
                .setTitle(title)
                .setSubtitle(artist)
                .setMediaId(id)
                //.setMediaUri(uri)
                .setIconUri(artwork)
                .build();

        return new QueueItem(descr, queueId);
    }
}
