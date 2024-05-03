package com.guichaguri.trackplayer.service.metadata

import android.annotation.SuppressLint
import android.os.Bundle
import android.provider.MediaStore
import android.support.v4.media.RatingCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.guichaguri.trackplayer.module.MusicEvents
import com.guichaguri.trackplayer.service.MusicManager
import com.guichaguri.trackplayer.service.MusicService
import com.guichaguri.trackplayer.service.Utils

/**
 * @author Guichaguri
 */
class ButtonEvents(service: MusicService, manager: MusicManager) : MediaSessionCompat.Callback() {
    private val service: MusicService
    private val manager: MusicManager

    init {
        this.service = service
        this.manager = manager
    }

    override fun onPlay() {
        manager.onPlay()
        manager.setState(PlaybackStateCompat.STATE_PLAYING, -1)
        service.emit(MusicEvents.BUTTON_PLAY, null)
    }

    override fun onPause() {
        manager.onPause()
        manager.setState(PlaybackStateCompat.STATE_PAUSED, -1)
        service.emit(MusicEvents.BUTTON_PAUSE, null)
    }

    override fun onStop() {
        manager.onStop()
        manager.setState(PlaybackStateCompat.STATE_STOPPED, -1)
        service.emit(MusicEvents.BUTTON_STOP, null)
    }

    override fun onPlayFromMediaId(mediaId: String, extras: Bundle) {
        val bundle = Bundle()
        bundle.putString("id", mediaId)
        service.emit(MusicEvents.BUTTON_PLAY_FROM_ID, bundle)
    }

    @SuppressLint("InlinedApi")
    override fun onPlayFromSearch(query: String, extras: Bundle) {
        val bundle = Bundle()
        bundle.putString("query", query)
        if (extras.containsKey(MediaStore.EXTRA_MEDIA_FOCUS)) {
            var focus = extras.getString(MediaStore.EXTRA_MEDIA_FOCUS)
            if (MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE == focus) {
                focus = "artist"
            } else if (MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE == focus) {
                focus = "album"
            } else if (MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE == focus) {
                focus = "playlist"
            } else if (MediaStore.Audio.Genres.ENTRY_CONTENT_TYPE == focus) {
                focus = "genre"
            } else if (MediaStore.Audio.Media.ENTRY_CONTENT_TYPE == focus) {
                focus = "title"
            }
            bundle.putString("focus", focus)
        }
        if (extras.containsKey(MediaStore.EXTRA_MEDIA_TITLE)) bundle.putString(
            "title", extras.getString(
                MediaStore.EXTRA_MEDIA_TITLE
            )
        )
        if (extras.containsKey(MediaStore.EXTRA_MEDIA_ARTIST)) bundle.putString(
            "artist", extras.getString(
                MediaStore.EXTRA_MEDIA_ARTIST
            )
        )
        if (extras.containsKey(MediaStore.EXTRA_MEDIA_ALBUM)) bundle.putString(
            "album", extras.getString(
                MediaStore.EXTRA_MEDIA_ALBUM
            )
        )
        if (extras.containsKey(MediaStore.EXTRA_MEDIA_GENRE)) bundle.putString(
            "genre", extras.getString(
                MediaStore.EXTRA_MEDIA_GENRE
            )
        )
        if (extras.containsKey(MediaStore.EXTRA_MEDIA_PLAYLIST)) bundle.putString(
            "playlist", extras.getString(
                MediaStore.EXTRA_MEDIA_PLAYLIST
            )
        )
        service.emit(MusicEvents.BUTTON_PLAY_FROM_SEARCH, bundle)
    }

    override fun onSkipToQueueItem(id: Long) {
        /*List<Track> tracks = manager.getPlayback().getQueue();

        for(Track track : tracks) {
            if(track.queueId != id) continue;

            Bundle bundle = new Bundle();
            bundle.putString("id", track.id);
            service.emit(MusicEvents.BUTTON_SKIP, bundle);
            break;
        }*/
    }

    override fun onSkipToPrevious() {
        service.emit(MusicEvents.BUTTON_SKIP_PREVIOUS, null)
    }

    override fun onSkipToNext() {
        service.emit(MusicEvents.BUTTON_SKIP_NEXT, null)
    }

    override fun onRewind() {
        val bundle = Bundle()
        bundle.putInt("interval", manager.metadata.jumpInterval)
        service.emit(MusicEvents.BUTTON_JUMP_BACKWARD, bundle)
    }

    override fun onFastForward() {
        val bundle = Bundle()
        bundle.putInt("interval", manager.metadata.jumpInterval)
        service.emit(MusicEvents.BUTTON_JUMP_FORWARD, bundle)
    }

    override fun onSeekTo(pos: Long) {
        val bundle = Bundle()
        bundle.putDouble("position", Utils.toSeconds(pos))
        service.emit(MusicEvents.BUTTON_SEEK_TO, bundle)
    }

    override fun onSetRating(rating: RatingCompat) {
        val bundle = Bundle()
        Utils.setRating(bundle, "rating", rating)
        service.emit(MusicEvents.BUTTON_SET_RATING, bundle)
    }
}