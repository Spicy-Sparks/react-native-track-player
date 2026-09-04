//
//  QueuedAudioPlayer.swift
//  SwiftAudio
//
//  Created by Jørgen Henrichsen on 24/03/2018.
//

import Foundation
import MediaPlayer

/**
 An audio player that can keep track of a queue of AudioItems.
 */
public class QueuedAudioPlayer: AudioPlayer, QueueManagerDelegate {
    let queue: QueueManager = QueueManager<AudioItem>()
    fileprivate var lastIndex: Int = -1
    fileprivate var lastItem: AudioItem? = nil

    /**
     Stop at the end of an item instead of walking onto the next one by
     yourself. The iOS twin of Android's `pauseAtEndOfMediaItems`
     (`AudioPlayer.initExoPlayer`), and on for the same reason: every track
     transition belongs to the JS side, which resolves the source for the next
     item and hands it back with a `load()`.

     Android has behaved this way since the flag went in; iOS never has, and the
     asymmetry is what the JS side kept paying for. Its queue window is synced on
     both platforms, so here the player would reach the end of a track and move
     on by itself — sometimes onto an item whose source is not resolved yet, and
     always without telling core, which reconciles a native active-track change
     only for Android Auto. Core stayed pointed at the previous track while the
     player was already on the next one: the wrong song under the right metadata,
     a queue that no longer matches what is playing, and a transport that answers
     for a track nobody is listening to.

     Repeat-one never advances — it replays the item already loaded — so that
     branch keeps its native loop and never comes through here.

     OFF by default, unlike the Android side, and deliberately so: turning it on
     makes every iOS transition depend on the JS side answering the end of an
     item. That is what the JS side is written to do — and it now gets a positive
     reason to do it with — but a miss stops the queue where the old behaviour
     would have papered over it by walking on. Until that has been watched on a
     device, the app asks for it explicitly (`setupPlayer({
     pauseAtEndOfMediaItems: true })`) rather than getting it by upgrading.
     */
    public var pauseAtEndOfMediaItems: Bool = false

    /**
     The pause the player is about to emit is the end of an item, not something
     that interrupted it. Read once and cleared, so it can only ever explain the
     pause it was set for — the same distinction media3 draws on Android, where
     the reason travels with the event instead of being guessed from the
     position.
     */
    private var reachedEndOfItem: Bool = false

    /** Read the end-of-item reason for the pause being emitted, and clear it. */
    public func consumeReachedEndOfItem() -> Bool {
        let value = reachedEndOfItem
        reachedEndOfItem = false
        return value
    }

    /** Park on the finished item and let the JS side move the queue. */
    private func pauseAtEndOfItem() {
        reachedEndOfItem = true
        pause()
    }

    func findOrInsert(item: AudioItem) -> Int {
        var itemIndex = queue.items.firstIndex(where: {$0.getSourceUrl() == item.getSourceUrl()})
        if (itemIndex == nil) {
            add(item: item)
            itemIndex = queue.items.count - 1
        }
        queue.currentIndex = itemIndex!
        return itemIndex!
    }

    public func crossfadePrepare(item: AudioItem) {
        if (!self.crossfade) {
            return
        }
        self.crossfadeWrapper.normalizationGain = (item as? NormalizationGainProviding)?.getNormalizationGain() ?? 1.0
        self.crossfadeWrapper.load(
            from: item.getSourceUrl(),
            type: item.getSourceType(),
            playWhenReady: false,
            initialTime: (item as? InitialTiming)?.getInitialTime(),
            options:(item as? AssetOptionsProviding)?.getAssetOptions()
        )
        self.crossfadeItem = item
    }
    
    public func crossfadePrepare(previous: Bool = false) {
        let nextIndex = queue.peek(direction: previous ? -1 : 1)
        if (nextIndex < 0) {
            // TODO: should throw error instead
            return
        }
        self.crossfadePrepare(item: queue.items[nextIndex])
    }
    
    public func switchExoPlayer(
        fadeDuration: Int = 2500,
        fadeInterval: Int = 20,
        fadeToVolume: Float = 1
    ) {
        if (!self.crossfade || self.crossfadeItem == nil) {
            return
        }
        if (self.currentAVPlayer) {
            self.crossfadeWrapper = self.wrapper1
            self.wrapper = self.wrapper2!
        } else {
            self.crossfadeWrapper = self.wrapper2!
            self.wrapper = self.wrapper1
        }

        // switch the event emittting delegate
        self.wrapper.delegate = self
        self.crossfadeWrapper.delegate = nil

        // broadcast nowplaying to system
        self.findOrInsert(item: self.crossfadeItem!)
        loadNowPlayingMetaValues()
        emitCurrentItemEvent()

        self.crossfadeItem = nil
        self.currentAVPlayer = !self.currentAVPlayer

        // Update shared AVPlayer reference so VideoView shows the new player's video
        if let activeWrapper = self.wrapper as? AVPlayerWrapper {
            RNTrackPlayer.sharedAVPlayer = activeWrapper.player
            NotificationCenter.default.post(name: .RNTPPlayerRecreated, object: nil)
        }

        // Capture the just-demoted and just-promoted wrappers. Without this
        // the fade Tasks read `self.crossfadeWrapper` / `self.wrapper`
        // dynamically each iteration — a subsequent switchExoPlayer would
        // have these Tasks start writing volume to the wrong wrapper
        // (audible as "doubled audio").
        let prevWrapper = self.crossfadeWrapper
        let newWrapper = self.wrapper

        self.crossfadeFadingOutWrapper = prevWrapper
        self.crossfadeFadingInWrapper = newWrapper
        self.crossfadeTargetVolume = fadeToVolume

        // fade volume
        self.crossfadeFadeOutTask = Task {
            var fadeOutDuration = fadeDuration
            let startFadeOutTime = DispatchTime.now()
            let fadeFromVolume = prevWrapper.volume
            while (fadeOutDuration > 0) {
                fadeOutDuration -= fadeInterval
                // Guard: if prevWrapper was re-promoted to active by a
                // subsequent switchExoPlayer, stop touching its volume —
                // the new fade-in Task owns it.
                if prevWrapper === self.wrapper { break }
                let timeDiff = DispatchTime.now().uptimeNanoseconds - startFadeOutTime.uptimeNanoseconds
                let timeElapsed = Float(min(Int(timeDiff) / 1_000_000, fadeDuration))
                prevWrapper.volume = fadeFromVolume * (1 -  timeElapsed / Float(fadeDuration))
                try await Task.sleep(nanoseconds: UInt64(fadeInterval * 1000000))
            }
            // Finalize: once the fade-out completes, silence and PAUSE the
            // outgoing wrapper so it stops consuming/playing. Without this the
            // outgoing wrapper is left at ~0 volume but still playing (it keeps
            // running to its own end / counts as a second active player). Only
            // do this if it's still the outgoing wrapper (a chained crossfade
            // may have re-promoted it).
            if prevWrapper !== self.wrapper {
                prevWrapper.volume = 0
                prevWrapper.pause()
            }
        }

        self.crossfadeFadeInTask = Task {
            newWrapper.volume = 0
            if (fadeToVolume > 0) {
                newWrapper.play()
                if (fadeDuration <= 0) {
                    // duration=0 means "instant swap": apply target volume
                    // directly. Without this the fade loop never runs and
                    // newWrapper stays at volume=0 → silent audio.
                    newWrapper.volume = fadeToVolume
                } else {
                    var fadeInDuration = fadeDuration
                    let startTime = DispatchTime.now()
                    while (fadeInDuration > 0) {
                        fadeInDuration -= fadeInterval
                        // Guard: if newWrapper was demoted to inactive, stop —
                        // the new fade-out Task owns its volume.
                        if newWrapper !== self.wrapper { break }
                        let timeDiff = DispatchTime.now().uptimeNanoseconds - startTime.uptimeNanoseconds
                        let timeElapsed = Float(min(Int(timeDiff) / 1_000_000, fadeDuration))
                        newWrapper.volume = fadeToVolume * timeElapsed / Float(fadeDuration)
                        try await Task.sleep(nanoseconds: UInt64(fadeInterval * 1000000))
                    }
                }
            }
        }
    }
    
    public override init(
        nowPlayingInfoController: NowPlayingInfoControllerProtocol = NowPlayingInfoController(),
        remoteCommandController: RemoteCommandController = RemoteCommandController(),
        crossfade: Bool = false
    ) {
        super.init(nowPlayingInfoController: nowPlayingInfoController, remoteCommandController: remoteCommandController, crossfade: crossfade)
        queue.delegate = self
    }

    /// The repeat mode for the queue player.
    public var repeatMode: RepeatMode = .off

    public override var currentItem: AudioItem? {
        queue.current
    }

    /**
     The index of the current item.
     */
    public var currentIndex: Int {
        queue.currentIndex
    }

    override public func clear() {
        queue.clearQueue()
        super.clear()
    }

    /**
     All items currently in the queue.
     */
    public var items: [AudioItem] {
        queue.items
    }

    /**
     The previous items held by the queue.
     */
    public var previousItems: [AudioItem] {
        queue.previousItems
    }

    /**
     The upcoming items in the queue.
     */
    public var nextItems: [AudioItem] {
        queue.nextItems
    }

    /**
     Will replace the current item with a new one and load it into the player.

     - parameter item: The AudioItem to replace the current item.
     - parameter playWhenReady: Optional, whether to start playback when the item is ready.
     */
    public override func load(item: AudioItem, playWhenReady: Bool? = nil) {
        handlePlayWhenReady(playWhenReady) {
            queue.replaceCurrentItem(with: item)
        }
    }

    /**
     Add a single item to the queue.

     - parameter item: The item to add.
     - parameter playWhenReady: Optional, whether to start playback when the item is ready.
     */
    public func add(item: AudioItem, playWhenReady: Bool? = nil) {
        handlePlayWhenReady(playWhenReady) {
            queue.add(item)
        }
    }

    /**
     Add items to the queue.

     - parameter items: The items to add to the queue.
     - parameter playWhenReady: Optional, whether to start playback when the item is ready.
     */
    public func add(items: [AudioItem], playWhenReady: Bool? = nil) {
        handlePlayWhenReady(playWhenReady) {
            queue.add(items)
        }
    }

    public func add(items: [AudioItem], at index: Int) throws {
        try queue.add(items, at: index)
    }

    /**
     Step to the next item in the queue.
     */
    public func next() {
        let lastIndex = currentIndex
        let playbackWasActive = wrapper.playbackActive;
        _ = queue.next(wrap: repeatMode == .queue)
        if (playbackWasActive && lastIndex != currentIndex || repeatMode == .queue) {
            event.playbackEnd.emit(data: .skippedToNext)
        }
    }

    /**
     Step to the previous item in the queue.
     */
    public func previous() {
        let lastIndex = currentIndex
        let playbackWasActive = wrapper.playbackActive;
        _ = queue.previous(wrap: repeatMode == .queue)
        if (playbackWasActive && lastIndex != currentIndex || repeatMode == .queue) {
            event.playbackEnd.emit(data: .skippedToPrevious)
        }
    }

    /**
     Remove an item from the queue.

     - parameter index: The index of the item to remove.
     - throws: `AudioPlayerError.QueueError`
     */
    public func removeItem(at index: Int) throws {
        try queue.removeItem(at: index)
    }


    /**
     Jump to a certain item in the queue.

     - parameter index: The index of the item to jump to.
     - parameter playWhenReady: Optional, whether to start playback when the item is ready.
     - throws: `AudioPlayerError`
     */
    public func jumpToItem(atIndex index: Int, playWhenReady: Bool? = nil) throws {
        try handlePlayWhenReady(playWhenReady) {
            if (index == currentIndex) {
                seek(to: 0)
            } else {
                _ = try queue.jump(to: index)
            }
            event.playbackEnd.emit(data: .jumpedToIndex)
        }
    }

    /**
     Move an item in the queue from one position to another.

     - parameter fromIndex: The index of the item to move.
     - parameter toIndex: The index to move the item to.
     - throws: `AudioPlayerError.QueueError`
     */
    public func moveItem(fromIndex: Int, toIndex: Int) throws {
        try queue.moveItem(fromIndex: fromIndex, toIndex: toIndex)
    }

    /**
     Remove all upcoming items, those returned by `next()`
     */
    public func removeUpcomingItems() {
        queue.removeUpcomingItems()
    }

    /**
     Remove all previous items, those returned by `previous()`
     */
    public func removePreviousItems() {
        queue.removePreviousItems()
    }

    func replay() {
        seek(to: 0);
        play()
    }

    // MARK: - AVPlayerWrapperDelegate

    override func AVWrapperItemDidPlayToEndTime() {
        event.playbackEnd.emit(data: .playedUntilEnd)
        if (repeatMode == .track) {
            self.pause()

            // quick workaround for race condition - schedule a call after 2 frames
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.016 * 2) { [weak self] in self?.replay() }
        } else if (repeatMode == .queue) {
            if (pauseAtEndOfMediaItems) { pauseAtEndOfItem(); return }
            _ = queue.next(wrap: true)
        } else if (currentIndex != items.count - 1) {
            if (pauseAtEndOfMediaItems) { pauseAtEndOfItem(); return }
            _ = queue.next(wrap: false)
        } else {
            wrapper.state = .ended
        }
    }

    // MARK: - QueueManagerDelegate

    /**
     The queue moved onto a new current item.

     An item the JS side has not resolved a source for yet carries `notPlayable`
     and an empty URL, and it must NOT be handed to AVPlayer: loading an empty
     URL puts the player in a failed state with nothing to recover from, and the
     JS side never hears about it — `playback-error` on a source-less track is
     deliberately swallowed there, because the recovery is supposed to arrive as
     `notPlayableTrackActive`. On Android that event is raised from the three
     places an unresolved item can become current (`load`, auto-transition,
     playback error) and JS answers it with a `load()` of the real source. iOS
     had the event, the helper and the listener, but nothing that ever emitted
     it: the queue advanced onto the placeholder, loaded nothing, and playback
     died silently there.

     Which only bites with the app in the background — in the foreground the
     source is normally resolved before the transition happens.

     Nothing is paused or stopped here, unlike the Android auto-transition path:
     ExoPlayer prepares the NEXT item while the current one still plays, so it
     has running audio to stop, whereas here the item that just ended has already
     stopped the wrapper. Leaving playback state untouched also keeps this from
     emitting a spurious `playWhenReady: false`, which the JS side reads as the
     end of an item and would answer with a queue advance of its own — racing the
     one this event is asking for.
     */
    func onCurrentItemChanged() {
        let lastPosition = currentTime;
        if let currentItem = currentItem {
            if isTrackNotPlayable(currentItem) {
                event.notPlayableTrackActive.emit(
                    data: (item: currentItem, index: currentIndex == -1 ? nil : currentIndex)
                )
            } else {
                super.load(item: currentItem)
            }
        } else {
            super.clear()
        }
        emitCurrentItemEvent(lastPosition: lastPosition)
    }

    func emitCurrentItemEvent(lastPosition: Double = 0) {
        let currentItem = currentItem
        event.currentItem.emit(
            data: (
                item: currentItem,
                index: currentIndex == -1 ? nil : currentIndex,
                lastItem: lastItem,
                lastIndex: lastIndex == -1 ? nil : lastIndex,
                lastPosition: lastPosition
            )
        )
        lastItem = currentItem
        lastIndex = currentIndex
    }

    private func isTrackNotPlayable(_ item: AudioItem) -> Bool {
        let mirror = Mirror(reflecting: item)
        for child in mirror.children {
            if child.label == "notPlayable", let value = child.value as? Bool {
                return value
            }
        }
        return false
    }

    func onSkippedToSameCurrentItem() {
        if (wrapper.playbackActive) {
            replay()
        }
    }

    func onReceivedFirstItem() {
        try! queue.jump(to: 0)
    }
}
