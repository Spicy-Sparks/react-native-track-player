@file: OptIn(UnstableApi::class) package com.lovegaoshi.kotlinaudio.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.IllegalSeekPositionException
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.lovegaoshi.kotlinaudio.models.*
import com.doublesymmetry.trackplayer.model.TrackAudioItem
import timber.log.Timber
import java.util.*
import kotlin.math.max
import kotlin.math.min

class QueuedAudioPlayer(
    private val context: Context,
    options: PlayerOptions = PlayerOptions()
) : AudioPlayer(context, options) {

    var parseEmbeddedArtwork: Boolean = false

    /**
     * Callback invoked when a track with notPlayable=true becomes current.
     * The player will stay on this track but won't load or play it.
     */
    var onNotPlayableTrackActive: ((index: Int, item: AudioItem) -> Unit)? = null

    private val queue = LinkedList<MediaItem>()

    private fun parseAudioItem(audioItem: AudioItem): MediaItem {
        return audioItem2MediaItem(audioItem, if (parseEmbeddedArtwork) context else null)
    }

    var repeatMode: RepeatMode
        get() {
            return when (exoPlayer.repeatMode) {
                Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                else -> RepeatMode.OFF
            }
        }
        set(value) {
            when (value) {
                RepeatMode.ALL -> players().forEach { p -> p.repeatMode = Player.REPEAT_MODE_ALL }
                RepeatMode.ONE -> players().forEach { p -> p.repeatMode = Player.REPEAT_MODE_ONE }
                RepeatMode.OFF -> players().forEach { p -> p.repeatMode = Player.REPEAT_MODE_OFF }
            }
            // `pauseAtEndOfMediaItems` is on for every other mode (see
            // AudioPlayer.initExoPlayer) so ExoPlayer never advances by itself into a
            // queue item whose URL the JS side hasn't resolved yet. REPEAT_MODE_ONE
            // never advances — it replays the item already loaded — so the guard buys
            // nothing there and breaks the loop instead: media3 pauses at the end of
            // EVERY repetition, wraps the position back to the start and hands JS a
            // bare `playWhenReady:false`. That reads as "something paused us", not
            // "the item ended", so the track played through once and then sat paused
            // at position 0 waiting for a manual tap. Let repeat-one loop natively.
            players().forEach { p -> p.pauseAtEndOfMediaItems = value != RepeatMode.ONE }
        }

    val currentIndex
        get() = exoPlayer.currentMediaItemIndex

    var shuffleMode
        get() = exoPlayer.shuffleModeEnabled
        set(v) {
            players().forEach{ p -> p.shuffleModeEnabled = v }
        }

    override val currentItem: AudioItem?
        get() = mediaItem2AudioItem(queue.getOrNull(currentIndex))

    val nextIndex: Int?
        get() {
            return if (exoPlayer.nextMediaItemIndex == C.INDEX_UNSET) null
            else exoPlayer.nextMediaItemIndex
        }

    val previousIndex: Int?
        get() {
            return if (exoPlayer.previousMediaItemIndex == C.INDEX_UNSET) null
            else exoPlayer.previousMediaItemIndex
        }

    val items: List<AudioItem>
        get() = queue.map { mediaItem2AudioItem(it)!! }

    val previousItems: List<AudioItem>
        get() {
            return if (queue.isEmpty()) emptyList()
            else queue
                .subList(0, exoPlayer.currentMediaItemIndex)
                .map { mediaItem2AudioItem(it)!! }
        }

    val nextItems: List<AudioItem>
        get() {
            return if (queue.isEmpty()) emptyList()
            else queue
                .subList(exoPlayer.currentMediaItemIndex, queue.lastIndex)
                .map { mediaItem2AudioItem(it)!! }
        }

    val nextItem: AudioItem?
        get() = items.getOrNull(currentIndex + 1)

    val previousItem: AudioItem?
        get() = items.getOrNull(currentIndex - 1)

    /**
     * ExoPlayer advanced by itself onto an item the JS side has not resolved a source for
     * yet. Stop before it prepares that empty URI — preparing it fails with
     * `FileNotFoundException: null` → `Source error` and leaves the player (and the media
     * session the system shows) stuck in ERROR — and ask the JS side to resolve it, which
     * answers with a load() of the real source so playback continues.
     *
     * This only bites with the app backgrounded: in the foreground the JS side normally
     * fills the url in before the transition happens, which is why it looks fine on screen
     * and dies with the screen off.
     */
    override fun onAutoTransitionToNotPlayableItem(): Boolean {
        val item = currentItem
        if (item !is TrackAudioItem || !item.notPlayable) return false
        Timber.tag("APMQueue").d("autoTransition on unresolved item: index=%d queue=%d", currentIndex, queue.size)
        exoPlayer.playWhenReady = false
        exoPlayer.stop()
        onNotPlayableTrackActive?.invoke(currentIndex, item)
        return true
    }

    override fun onPlaybackErrorOnNotPlayableItem(): Boolean {
        val item = currentItem
        if (item !is TrackAudioItem || !item.notPlayable) return false
        Timber.tag("APMQueue").d("playbackError on unresolved item: index=%d queue=%d", currentIndex, queue.size)
        exoPlayer.playWhenReady = false
        onNotPlayableTrackActive?.invoke(currentIndex, item)
        return true
    }

    override fun load(item: AudioItem, playWhenReady: Boolean) {
        // Check if item is notPlayable
        if (item is TrackAudioItem && item.notPlayable) {
            onNotPlayableTrackActive?.invoke(currentIndex, item)
            return
        }
        load(item)
        exoPlayer.playWhenReady = playWhenReady
    }

    override fun load(item: AudioItem) {
        // Check if item is notPlayable
        if (item is TrackAudioItem && item.notPlayable) {
            onNotPlayableTrackActive?.invoke(currentIndex, item)
            return
        }
        setNormalizationGain(item.options?.normalizationGain ?: 1f)
        if (queue.isEmpty()) {
            add(item)
        } else {
            val newMediaItem = parseAudioItem(item)
            val idx = currentIndex
            // Same track, freshly re-resolved URL (a signed stream link renewed after
            // a reconnect) must not be told apart from a genuinely different track at
            // this index — both call load() with the queue non-empty. mediaId is the
            // stable content identity (see createMinimalTrackFromQueueItem — it's
            // `${itemType}-${item.id}`, never derived from the URL), so it survives a
            // re-resolution unchanged and is the right thing to compare, not the URL.
            //
            // Losing this distinction cost the resume position. The original code
            // unconditionally reset the ACTIVE wrapper to this item's default start
            // (TIME_UNSET, i.e. 0) right here, trusting the caller's own
            // seekTo(resumePosition) — issued moments later — to correct it before
            // playback actually starts. That trust wasn't safe: replaceMediaItem() can
            // itself drop the current position when the underlying source genuinely
            // changes (a fresh signed URL is, from the engine's perspective, a new
            // source even for the same song), so by the time the caller's seek lands
            // there may be nothing left to correct TO. Measured on a Pixel 6a: a track
            // that lost its connection at 170s came back playing from ~0, not 170s.
            //
            // Fix at the source instead of downstream: capture the wrapper's own
            // position before touching anything, and when this is the same track,
            // reassert THAT position in the exact call this used to zero it — no
            // second round trip for the caller's seek to race.
            val previousMediaId = if (idx in queue.indices) queue[idx].mediaId else null
            val isSameTrackRefreshed = previousMediaId != null && previousMediaId == newMediaItem.mediaId
            val positionToRestoreMs = if (isSameTrackRefreshed) exoPlayer.currentPosition else C.TIME_UNSET
            if (idx in queue.indices) {
                queue[idx] = newMediaItem
            }
            players().forEach { p ->
                p.replaceMediaItem(idx, newMediaItem)
                // Only seek the ACTIVE wrapper. Seeking the inactive too would
                // drag it out of any prebuffered position set by crossFadePrepare,
                // causing the next crossFade-swap to land on the wrong content.
                if (p === exoPlayer) {
                    p.seekTo(idx, positionToRestoreMs)
                }
            }
            exoPlayer.prepare()
        }
    }

    /**
     * Add a single item to the queue. If the AudioPlayer has no item loaded, it will load the `item`.
     * @param item The [AudioItem] to add.
     */
    fun add(item: AudioItem, playWhenReady: Boolean) {
        exoPlayer.playWhenReady = playWhenReady
        add(item)
    }

    /**
     * Add a single item to the queue. If the AudioPlayer has no item loaded, it will load the `item`.
     * @param item The [AudioItem] to add.
     */
    fun add(item: AudioItem) {
        val mediaSource = parseAudioItem(item)
        queue.add(mediaSource)
        players().forEach { p -> p.addMediaItem(mediaSource) }
        exoPlayer.prepare()
    }

    /**
     * Add multiple items to the queue. If the AudioPlayer has no item loaded, it will load the first item in the list.
     * @param items The [AudioItem]s to add.
     * @param playWhenReady Whether playback starts automatically.
     */
    fun add(items: List<AudioItem>, playWhenReady: Boolean) {
        exoPlayer.playWhenReady = playWhenReady
        add(items)
    }

    /**
     * Add multiple items to the queue. If the AudioPlayer has no item loaded, it will load the first item in the list.
     * @param items The [AudioItem]s to add.
     */
    fun add(items: List<AudioItem>) {
        val mediaSources = items.map { parseAudioItem(it) }
        queue.addAll(mediaSources)
        players().forEach { p -> p.addMediaItems(mediaSources) }
        exoPlayer.prepare()
    }


    /**
     * Add multiple items to the queue.
     * @param items The [AudioItem]s to add.
     * @param atIndex  Index to insert items at, if no items loaded this will not automatically start playback.
     */
    fun add(items: List<AudioItem>, atIndex: Int) {
        val mediaSources = items.map { parseAudioItem(it) }
        queue.addAll(atIndex, mediaSources)
        players().forEach { p -> p.addMediaItems(atIndex, mediaSources) }
        exoPlayer.prepare()
    }

    /**
     * Remove an item from the queue.
     * @param index The index of the item to remove.
     */
    fun remove(index: Int) {
        queue.removeAt(index)
        players().forEach { p -> p.removeMediaItem(index) }
    }

    /**
     * Remove items from the queue.
     * @param indexes The indexes of the items to remove.
     */
    fun remove(indexes: List<Int>) {
        val sorted = indexes.toMutableList()
        // Sort the indexes in descending order so we can safely remove them one by one
        // without having the next index possibly newly pointing to another item than intended:
        sorted.sortDescending()
        sorted.forEach {
            remove(it)
        }
    }

    /**
     * Skip to the next item in the queue, which may depend on the current repeat mode.
     * Does nothing if there is no next item to skip to.
     */
    fun next() {
        exoPlayer.seekToNextMediaItem()
        exoPlayer.prepare()
    }

    /**
     * Skip to the previous item in the queue, which may depend on the current repeat mode.
     * Does nothing if there is no previous item to skip to.
     */
    fun previous() {
        exoPlayer.seekToPreviousMediaItem()
        exoPlayer.prepare()
    }

    /**
     * Move an item in the queue from one position to another.
     * @param fromIndex The index of the item ot move.
     * @param toIndex The index to move the item to. If the index is larger than the size of the queue, the item is moved to the end of the queue instead.
     */
    fun move(fromIndex: Int, toIndex: Int) {
        players().forEach { p -> p.moveMediaItem(fromIndex, toIndex) }
        val item = queue[fromIndex]
        queue.removeAt(fromIndex)
        queue.add(max(0, min(items.size, if (toIndex > fromIndex) toIndex else toIndex - 1)), item)
    }

    /**
     * Jump to an item in the queue.
     * @param index the index to jump to
     * @param playWhenReady Whether playback starts automatically.
     */
    fun jumpToItem(index: Int, playWhenReady: Boolean) {
        exoPlayer.playWhenReady = playWhenReady
        jumpToItem(index)
    }

    /**
     * Jump to an item in the queue.
     * @param index the index to jump to
     */
    fun jumpToItem(index: Int) {
        try {
            // Check if the target item is notPlayable
            val item = items.getOrNull(index)
            if (item is TrackAudioItem && item.notPlayable) {
                exoPlayer.seekTo(index, C.TIME_UNSET)
                onNotPlayableTrackActive?.invoke(index, item)
                return
            }
            exoPlayer.seekTo(index, C.TIME_UNSET)
            exoPlayer.prepare()
        } catch (e: IllegalSeekPositionException) {
            throw Error("This item index $index does not exist. The size of the queue is ${queue.size} items.")
        }
    }

    /**
     * Replaces item at index in queue.
     */
    fun replaceItem(index: Int, item: AudioItem) {
        val mediaSource = parseAudioItem(item)
        queue[index] = mediaSource
        players().forEach { p -> p.replaceMediaItem(index, mediaSource) }
    }

    /**
     * Removes all the upcoming items, if any (the ones returned by [next]).
     */
    fun removeUpcomingItems() {
        if (queue.lastIndex == -1 || currentIndex == -1) return
        val lastIndex = queue.lastIndex + 1
        val fromIndex = currentIndex + 1

        players().forEach { p -> p.removeMediaItems(fromIndex, lastIndex) }
        queue.subList(fromIndex, lastIndex).clear()
    }

    /**
     * Removes all the previous items, if any (the ones returned by [previous]).
     */
    fun removePreviousItems() {
        players().forEach { p -> p.removeMediaItems(0, currentIndex) }
        queue.subList(0, currentIndex).clear()
    }

    override fun destroy() {
        queue.clear()
        super.destroy()
    }

    override fun clear() {
        queue.clear()
        super.clear()
    }
}
