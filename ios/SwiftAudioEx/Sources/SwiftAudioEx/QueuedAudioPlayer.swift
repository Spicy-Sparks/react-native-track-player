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
            _ = queue.next(wrap: true)
        } else if (currentIndex != items.count - 1) {
            _ = queue.next(wrap: false)
        } else {
            wrapper.state = .ended
        }
    }

    // MARK: - QueueManagerDelegate

    func onCurrentItemChanged() {
        let lastPosition = currentTime;
        if let currentItem = currentItem {
            super.load(item: currentItem)
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
