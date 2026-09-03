//
//  RNTrackPlayer.swift
//  RNTrackPlayer
//
//  Created by David Chavez on 13.08.17.
//  Copyright © 2017 David Chavez. All rights reserved.
//

import Foundation
import MediaPlayer
import MediaAccessibility
import React

@objc public protocol RNTPDelegate {
    func sendEvent(name: String, body: Any)
}

@objc(RNTrackPlayer)
public class RNTrackPlayer: NSObject, AudioSessionControllerDelegate {

    // MARK: - Shared AVPlayer
    // Exposed for external UI like VideoView that need the video layer from the single player instance.
    @objc public static var sharedAVPlayer: AVPlayer?

    // Expose underlying AVPlayer for video rendering
    @objc public var avPlayer: AVPlayer? {
        if let w = player.wrapper as? AVPlayerWrapper {
            return w.player
        }
        return nil
    }

    // newarch swift event emitter
    @objc public weak var delegate: RNTPDelegate? = nil
    // MARK: - Attributes
    private var hasInitialized = false
    private var crossfadeWarmedUp = false
    private let player = QueuedAudioPlayer()
    private let audioSessionController = AudioSessionController.shared
    // Two EQ taps — one per crossfade wrapper. They hold independent filter/limiter state but
    // are kept in sync via `forEachEqualizerTap` so user-visible settings stay identical across
    // a crossfade. Mirrors Android's equalizerProcessor / equalizerProcessor2 design.
    private let equalizerTap = EqualizerAudioTap()
    private let equalizerTap2 = EqualizerAudioTap()

    private func forEachEqualizerTap(_ block: (EqualizerAudioTap) -> Void) {
        block(equalizerTap)
        block(equalizerTap2)
    }
    private var shouldEmitProgressEvent: Bool = false
    private var shouldResumePlaybackAfterInterruptionEnds: Bool = false
    private var forwardJumpInterval: NSNumber? = nil;
    private var backwardJumpInterval: NSNumber? = nil;
    private var sessionCategory: AVAudioSession.Category = .playback
    private var sessionCategoryMode: AVAudioSession.Mode = .default
    private var sessionCategoryPolicy: AVAudioSession.RouteSharingPolicy = .default
    // Default to .allowAirPlay + .allowBluetoothA2DP so routing to AirPlay
    // receivers and high-quality Bluetooth outputs works out of the box on the
    // .playback category. Without these, setCategory(_:options:) below is
    // called with an explicit empty set, which suppresses the implicit iOS
    // defaults and silently disables AirPlay/A2DP routing (manifests as e.g.
    // AirPlay to WIIM Ultra not working). Callers can still override by
    // passing iosCategoryOptions from JS.
    private var sessionCategoryOptions: AVAudioSession.CategoryOptions = [.allowAirPlay, .allowBluetoothA2DP]

    // Marks the brief window after an audio route change (e.g. CarPlay/Bluetooth connect)
    // during which iOS may auto-issue a play command. Used to tag RemotePlay with
    // `autoResume: true` so JS can ignore it if the user had explicitly paused.
    private var routeChangeWindowEndAt: Date?
    private static let routeChangeWindowSeconds: TimeInterval = 2.0

    // MARK: - Lifecycle Methods

    public override init() {
        super.init()
        audioSessionController.delegate = self
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleAudioRouteChange(_:)),
            name: AVAudioSession.routeChangeNotification,
            object: nil
        )
        if #available(iOS 18.0, tvOS 18.0, macOS 15.0, *) {
            NotificationCenter.default.addObserver(
                self,
                selector: #selector(handleMusicHapticsActiveStatusChange),
                name: MAMusicHapticsManager.activeStatusDidChangeNotification,
                object: nil
            )
        }
        player.playWhenReady = false;
        player.event.receiveChapterMetadata.addListener(self, handleAudioPlayerChapterMetadataReceived)
        player.event.receiveTimedMetadata.addListener(self, handleAudioPlayerTimedMetadataReceived)
        player.event.receiveCommonMetadata.addListener(self, handleAudioPlayerCommonMetadataReceived)
        player.event.stateChange.addListener(self, handleAudioPlayerStateChange)
        player.event.fail.addListener(self, handleAudioPlayerFailed)
        player.event.currentItem.addListener(self, handleAudioPlayerCurrentItemChange)
        player.event.secondElapse.addListener(self, handleAudioPlayerSecondElapse)
        player.event.playWhenReadyChange.addListener(self, handlePlayWhenReadyChange)
        player.event.notPlayableTrackActive.addListener(self, handleNotPlayableTrackActive)

        // Store global reference to the underlying AVPlayer so that other native views can reuse it.
        if let wrapper = player.wrapper as? AVPlayerWrapper {
            RNTrackPlayer.sharedAVPlayer = wrapper.player
        }
    }

    deinit {
        NotificationCenter.default.removeObserver(self, name: AVAudioSession.routeChangeNotification, object: nil)
        if #available(iOS 18.0, tvOS 18.0, macOS 15.0, *) {
            NotificationCenter.default.removeObserver(
                self,
                name: MAMusicHapticsManager.activeStatusDidChangeNotification,
                object: nil
            )
        }
        reset(resolve: { _ in }, reject: { _, _, _  in })

        RNTrackPlayer.sharedAVPlayer = nil
    }

    private func emit(event: EventType, body: Any? = nil) {
        delegate?.sendEvent(name: event.rawValue, body: body)
    }

    @objc private func handleMusicHapticsActiveStatusChange() {
        emit(event: EventType.MusicHapticsActiveChanged, body: musicHapticsStatus())
    }

    /// The app's own opt-in. iOS reports the user's setting to anybody who asks,
    /// but an app that hasn't declared the key gets no haptics out of it — so
    /// without this an app would go looking up recording codes for a feature
    /// that can never fire.
    private static let appSupportsMusicHaptics: Bool = {
        return (Bundle.main.object(forInfoDictionaryKey: "MusicHapticsSupported") as? Bool) ?? false
    }()

    /// Whether Music Haptics can happen here at all — a new enough OS, and an app
    /// that asked for it — and whether the user has it switched on right now
    /// (Settings > Accessibility > Music Haptics).
    private func musicHapticsStatus() -> [String: Any] {
        if #available(iOS 18.0, tvOS 18.0, macOS 15.0, *), RNTrackPlayer.appSupportsMusicHaptics {
            return [
                "supported": true,
                "active": MAMusicHapticsManager.shared.isActive
            ]
        }
        return ["supported": false, "active": false]
    }

    @objc private func handleAudioRouteChange(_ notification: Notification) {
        guard
            let userInfo = notification.userInfo,
            let reasonValue = userInfo[AVAudioSessionRouteChangeReasonKey] as? UInt,
            let reason = AVAudioSession.RouteChangeReason(rawValue: reasonValue)
        else { return }
        switch reason {
        case .newDeviceAvailable:
            // A device connected (e.g. CarPlay/Bluetooth). Open a brief window during
            // which an iOS-issued RemotePlay is tagged `autoResume`, so playback can
            // resume automatically on reconnect.
            routeChangeWindowEndAt = Date().addingTimeInterval(RNTrackPlayer.routeChangeWindowSeconds)
        case .oldDeviceUnavailable:
            // The active output device went away (CarPlay/Bluetooth/headphones
            // disconnected, e.g. the car was switched off). iOS does not post an
            // interruption for this, so without handling it playback keeps running on
            // the built-in speaker. Per Apple's guidance, pause. Emitted as a permanent
            // duck so JS pauses but does NOT auto-resume on interruption-end; the
            // reconnect resume is preserved via the separate newDeviceAvailable ->
            // RemotePlay(autoResume) path above.
            //
            // `routeLost` is what separates this from the other permanent duck —
            // another app or Siri taking the audio for good. The two call for
            // opposite things on reconnect: playback the route interrupted may
            // resume, playback somebody else took over may not, and without the tag
            // JS saw one event for both. Android's equivalent is
            // `pausedBecauseBecameNoisy` on PlaybackPlayWhenReadyChanged.
            emit(event: EventType.RemoteDuck, body: ["paused": true, "permanent": true, "routeLost": true])
        default:
            break
        }
    }

    private var isInRouteChangeWindow: Bool {
        guard let endAt = routeChangeWindowEndAt else { return false }
        return Date() < endAt
    }

    private func emitRemotePlay() {
        let autoResume = isInRouteChangeWindow
        emit(event: EventType.RemotePlay, body: ["autoResume": autoResume])
    }

    // MARK: - AudioSessionControllerDelegate

    public func handleInterruption(type: InterruptionType) {
        switch type {
        case .began:
            // Interruption began, take appropriate actions (save state, update user interface)
            emit(event: EventType.RemoteDuck, body: [
                "paused": true
            ])
        case let .ended(shouldResume):
            if shouldResume {
                if (shouldResumePlaybackAfterInterruptionEnds) {
                    player.play()
                }
                // Interruption Ended - playback should resume
                emit(event: EventType.RemoteDuck, body: [
                    "paused": false
                ])
            } else {
                // Interruption Ended - playback should NOT resume
                emit(event: EventType.RemoteDuck, body: [
                    "paused": true,
                    "permanent": true
                ])
            }
        }
    }

    // MARK: - Bridged Methods

    private func rejectWhenNotInitialized(reject: RCTPromiseRejectBlock) -> Bool {
        let rejected = !hasInitialized;
        if (rejected) {
            reject("player_not_initialized", "The player is not initialized. Call setupPlayer first.", nil)
        }
        return rejected;
    }

    private func rejectWhenTrackIndexOutOfBounds(
        index: Int,
        min: Int? = nil,
        max : Int? = nil,
        message : String? = "The track index is out of bounds",
        reject: RCTPromiseRejectBlock
    ) -> Bool {
        let rejected = index < (min ?? 0) || index > (max ?? player.items.count - 1);
        if (rejected) {
            reject("index_out_of_bounds", message, nil)
        }
        return rejected
    }

    @objc(setupPlayer:resolver:rejecter:)
    public func setupPlayer(config: [String: Any], resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if hasInitialized {
            reject("player_already_initialized", "The player has already been initialized via setupPlayer.", nil)
            return
        }

        let useFFTLength = config["useFFTProcessor"] as? Int

        // configure buffer size
        if let bufferDuration = config["minBuffer"] as? TimeInterval {
            player.bufferDuration = bufferDuration
        }

        if let autoHandleInterruptions = config["autoHandleInterruptions"] as? Bool {
            self.shouldResumePlaybackAfterInterruptionEnds = autoHandleInterruptions
        }

        // configure crossfade BEFORE attaching audio taps so wrapper2 exists when we set them
        if let crossfade = config["crossfade"] as? Bool, crossfade {
            player.crossfade = true
            player.wrapper2 = AVPlayerWrapper()
            player.crossfadeWrapper = player.wrapper2!
        }

        // configure the audio tap. Pair semantics: each wrapper gets its own instance so filter/
        // FFT state is not raced during a crossfade (both wrappers process audio simultaneously).
        if let fftLength = useFFTLength {
            let waveTap1 = WaveformAudioTap(mFFTLength: fftLength, mEmit: { data in
                self.emit(event: EventType.FFTUpdated, body: data)
            })
            let waveTap2: WaveformAudioTap? = player.crossfade
                ? WaveformAudioTap(mFFTLength: fftLength, mEmit: { data in
                    self.emit(event: EventType.FFTUpdated, body: data)
                })
                : nil
            player.setAudioTaps(primary: waveTap1, secondary: waveTap2)
        } else {
            // Always attach equalizer tap — transparent pass-through when no effects are active.
            // This avoids audio glitches from re-attaching the tap when effects are toggled.
            player.setAudioTaps(primary: equalizerTap, secondary: equalizerTap2)
        }

        // configure wether player waits to play (deprecated)
        if let waitForBuffer = config["waitForBuffer"] as? Bool {
            player.automaticallyWaitsToMinimizeStalling = waitForBuffer
        }

        // configure wether control center metdata should auto update
        player.automaticallyUpdateNowPlayingInfo = config["autoUpdateMetadata"] as? Bool ?? true

        // configure audio session - category, options & mode
        if
            let sessionCategoryStr = config["iosCategory"] as? String,
            let mappedCategory = SessionCategory(rawValue: sessionCategoryStr) {
            sessionCategory = mappedCategory.mapConfigToAVAudioSessionCategory()
        }

        if
            let sessionCategoryModeStr = config["iosCategoryMode"] as? String,
            let mappedCategoryMode = SessionCategoryMode(rawValue: sessionCategoryModeStr) {
            sessionCategoryMode = mappedCategoryMode.mapConfigToAVAudioSessionCategoryMode()
        }

        if
            let sessionCategoryPolicyStr = config["iosCategoryPolicy"] as? String,
            let mappedCategoryPolicy = SessionCategoryPolicy(rawValue: sessionCategoryPolicyStr) {
            sessionCategoryPolicy = mappedCategoryPolicy.mapConfigToAVAudioSessionCategoryPolicy()
        }

        // Only override the default when iosCategoryOptions is explicitly
        // provided. Passing nil from JS must NOT collapse to an empty set,
        // otherwise the .allowAirPlay/.allowBluetoothA2DP defaults declared
        // in the property would be wiped out and AirPlay/A2DP routing would
        // silently break.
        if let sessionCategoryOptsStr = config["iosCategoryOptions"] as? [String] {
            let mappedCategoryOpts = sessionCategoryOptsStr.compactMap { SessionCategoryOptions(rawValue: $0)?.mapConfigToAVAudioSessionCategoryOptions() }
            sessionCategoryOptions = AVAudioSession.CategoryOptions(mappedCategoryOpts)
        }

        configureAudioSession()

        // setup event listeners
        player.remoteCommandController.handleChangePlaybackPositionCommand = { [weak self] event in
            if let event = event as? MPChangePlaybackPositionCommandEvent {
                self?.emit(event: EventType.RemoteSeek, body: ["position": event.positionTime])
                return MPRemoteCommandHandlerStatus.success
            }

            return MPRemoteCommandHandlerStatus.commandFailed
        }

        player.remoteCommandController.handleNextTrackCommand = { [weak self] _ in
            self?.emit(event: EventType.RemoteNext)
            return MPRemoteCommandHandlerStatus.success
        }

        player.remoteCommandController.handlePauseCommand = { [weak self] _ in
            self?.emit(event: EventType.RemotePause)
            return MPRemoteCommandHandlerStatus.success
        }

        player.remoteCommandController.handlePlayCommand = { [weak self] _ in
            self?.emitRemotePlay()
            return MPRemoteCommandHandlerStatus.success
        }

        player.remoteCommandController.handlePreviousTrackCommand = { [weak self] _ in
            self?.emit(event: EventType.RemotePrevious)
            return MPRemoteCommandHandlerStatus.success
        }

        player.remoteCommandController.handleSkipBackwardCommand = { [weak self] event in
            if let command = event.command as? MPSkipIntervalCommand,
               let interval = command.preferredIntervals.first {
                self?.emit(event: EventType.RemoteJumpBackward, body: ["interval": interval])
                return MPRemoteCommandHandlerStatus.success
            }

            return MPRemoteCommandHandlerStatus.commandFailed
        }

        player.remoteCommandController.handleSkipForwardCommand = { [weak self] event in
            if let command = event.command as? MPSkipIntervalCommand,
               let interval = command.preferredIntervals.first {
                self?.emit(event: EventType.RemoteJumpForward, body: ["interval": interval])
                return MPRemoteCommandHandlerStatus.success
            }

            return MPRemoteCommandHandlerStatus.commandFailed
        }

        player.remoteCommandController.handleStopCommand = { [weak self] _ in
            self?.emit(event: EventType.RemoteStop)
            return MPRemoteCommandHandlerStatus.success
        }

        player.remoteCommandController.handleTogglePlayPauseCommand = { [weak self] _ in
            // Decide play-vs-pause from `playWhenReady` (the app's playback intent),
            // NOT the KVO-derived `playerState`. `playWhenReady` is the exact signal
            // that drives MPNowPlayingInfoPropertyPlaybackRate, so the headset/lock-
            // screen toggle always matches the state iOS is presenting. `playerState`
            // can transiently report `.paused` (or a non-`.playing` value) mid-buffer
            // or during a route change while the app is really playing, which made a
            // pause press fall into the `emitRemotePlay()` branch — starting music
            // instead of pausing it.
            if self?.player.playWhenReady == true {
                self?.emit(event: EventType.RemotePause)
            } else {
                self?.emitRemotePlay()
            }

            return MPRemoteCommandHandlerStatus.success
        }

        player.remoteCommandController.handleLikeCommand = { [weak self] _ in
            self?.emit(event: EventType.RemoteLike)
            return MPRemoteCommandHandlerStatus.success
        }

        player.remoteCommandController.handleDislikeCommand = { [weak self] _ in
            self?.emit(event: EventType.RemoteDislike)
            return MPRemoteCommandHandlerStatus.success
        }

        player.remoteCommandController.handleBookmarkCommand = { [weak self] _ in
            self?.emit(event: EventType.RemoteBookmark)
            return MPRemoteCommandHandlerStatus.success
        }

        hasInitialized = true
        resolve(NSNull())
    }


    private func configureAudioSession() {

        // deactivate the session when there is no current item to be played
        if (player.currentItem == nil) {
            try? audioSessionController.deactivateSession()
            return
        }
        
        // activate the audio session when there is an item to be played
        // and the player has been configured to start when it is ready loading:
        if (player.playWhenReady) {
            try? audioSessionController.activateSession()
            if #available(iOS 11.0, *) {
                try? AVAudioSession.sharedInstance().setCategory(sessionCategory, mode: sessionCategoryMode, policy: sessionCategoryPolicy, options: sessionCategoryOptions)
            } else {
                try? AVAudioSession.sharedInstance().setCategory(sessionCategory, mode: sessionCategoryMode, options: sessionCategoryOptions)
            }
        }
    }

    @objc(isServiceRunning:rejecter:)
    public func isServiceRunning(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        // TODO That is probably always true
        resolve(player != nil)
    }

    @objc(updateOptions:resolver:rejecter:)
    public func update(options: [String: Any], resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        var capabilitiesStr = options["capabilities"] as? [String] ?? []
        if (capabilitiesStr.contains("play") && capabilitiesStr.contains("pause")) {
            capabilitiesStr.append("togglePlayPause");
        }

        forwardJumpInterval = options["forwardJumpInterval"] as? NSNumber ?? forwardJumpInterval
        backwardJumpInterval = options["backwardJumpInterval"] as? NSNumber ?? backwardJumpInterval

        player.remoteCommands = capabilitiesStr
            .compactMap { Capability(rawValue: $0) }
            .map { capability in
                capability.mapToPlayerCommand(
                    forwardJumpInterval: forwardJumpInterval,
                    backwardJumpInterval: backwardJumpInterval,
                    likeOptions: options["likeOptions"] as? [String: Any],
                    dislikeOptions: options["dislikeOptions"] as? [String: Any],
                    bookmarkOptions: options["bookmarkOptions"] as? [String: Any]
                )
            }

        configureProgressUpdateEvent(
            interval: ((options["progressUpdateEventInterval"] as? NSNumber) ?? 0).doubleValue
        )

        resolve(NSNull())
    }

    private func configureProgressUpdateEvent(interval: Double) {
        shouldEmitProgressEvent = interval > 0
        self.player.timeEventFrequency = shouldEmitProgressEvent
            ? .custom(time: CMTime(seconds: interval, preferredTimescale: 1000))
            : .everySecond
    }

    @objc(add:before:resolver:rejecter:)
    public func add(
        trackDicts: [[String: Any]],
        before trackIndex: Int,
        resolve: RCTPromiseResolveBlock,
        reject: RCTPromiseRejectBlock
    ) {
        // -1 means no index was passed and therefore should be inserted at the end.
        let index = trackIndex == -1 ? player.items.count : trackIndex;
        if (rejectWhenNotInitialized(reject: reject)) { return }
        if (rejectWhenTrackIndexOutOfBounds(
            index: index,
            max: player.items.count,
            reject: reject
        )) { return }

        var tracks = [Track]()
        for trackDict in trackDicts {
            guard let track = Track(dictionary: trackDict) else {
                reject("invalid_track_object", "Track is missing a required key", nil)
                return
            }

            tracks.append(track)
        }

        try? player.add(
            items: tracks,
            at: index
        )
        resolve(index)
    }

    @objc(load:resolver:rejecter:)
    public func load(
        trackDict: [String: Any],
        resolve: RCTPromiseResolveBlock,
        reject: RCTPromiseRejectBlock
    ) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        guard let track = Track(dictionary: trackDict) else {
            reject("invalid_track_object", "Track is missing a required key", nil)
            return
        }

        player.load(item: track)

        // Warm up the crossfade player on first load so the audio pipeline
        // is ready and the first crossfade transition is smooth
        if player.crossfade && !crossfadeWarmedUp {
            crossfadeWarmedUp = true
            player.crossfadePrepare(item: track)
            player.crossfadeItem = nil
        }

        resolve(player.currentIndex)
    }

    @objc(remove:resolver:rejecter:)
    public func remove(tracks indexes: [Int], resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }
        for index in indexes {
            if (rejectWhenTrackIndexOutOfBounds(index: index, message: "One or more of the indexes were out of bounds.", reject: reject)) {
                return
            }
        }

        // Sort the indexes in descending order so we can safely remove them one by one
        // without having the next index possibly newly pointing to another item than intended:
        for index in indexes.sorted().reversed() {
            try? player.removeItem(at: index)
        }

        resolve(NSNull())
    }

    @objc(move:toIndex:resolver:rejecter:)
    public func move(
        fromIndex: Int,
        toIndex: Int,
        resolve: RCTPromiseResolveBlock,
        reject: RCTPromiseRejectBlock
    ) {
        if (rejectWhenNotInitialized(reject: reject)) { return }
        if (rejectWhenTrackIndexOutOfBounds(
            index: fromIndex,
            message: "The fromIndex is out of bounds",
            reject: reject)
        ) { return }
        if (rejectWhenTrackIndexOutOfBounds(
            index: toIndex,
            max: Int.max,
            message: "The toIndex is out of bounds",
            reject: reject)
        ) { return }
        try? player.moveItem(fromIndex: fromIndex, toIndex: toIndex)
        resolve(NSNull())
    }


    @objc(removeUpcomingTracks:rejecter:)
    public func removeUpcomingTracks(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        player.removeUpcomingItems()
        resolve(NSNull())
    }

    @objc(skip:initialTime:resolver:rejecter:)
    public func skip(
        to trackIndex: Int,
        initialTime: Double,
        resolve: RCTPromiseResolveBlock,
        reject: RCTPromiseRejectBlock
    ) {
        let index = trackIndex;
        if (rejectWhenTrackIndexOutOfBounds(index: index, reject: reject)) { return }

        if (rejectWhenNotInitialized(reject: reject)) { return }

        print("Skipping to track:", index)
        try? player.jumpToItem(atIndex: index, playWhenReady: player.playerState == .playing)

        // if an initialTime is passed the seek to it
        if (initialTime >= 0) {
            self.seekTo(time: initialTime, resolve: resolve, reject: reject)
        } else {
            resolve(NSNull())
        }
    }

    @objc(skipToNext:resolver:rejecter:)
    public func skipToNext(
        initialTime: Double,
        resolve: RCTPromiseResolveBlock,
        reject: RCTPromiseRejectBlock
    ) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        player.next()

        // if an initialTime is passed the seek to it
        if (initialTime >= 0) {
            self.seekTo(time: initialTime, resolve: resolve, reject: reject)
        } else {
            resolve(NSNull())
        }
    }

    @objc(skipToPrevious:resolver:rejecter:)
    public func skipToPrevious(
        initialTime: Double,
        resolve: RCTPromiseResolveBlock,
        reject: RCTPromiseRejectBlock
    ) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        player.previous()

        // if an initialTime is passed the seek to it
        if (initialTime >= 0) {
            self.seekTo(time: initialTime, resolve: resolve, reject: reject)
        } else {
            resolve(NSNull())
        }
    }

    @objc(crossfadePrepare:seekTo:resolver:rejecter:)
    public func crossfadePrepare(previous: Bool, seekTo: NSNumber, resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }
        player.crossfadePrepare(previous: previous)
        resolve(NSNull())
    }

    @objc(switchExoPlayer:fadeInterval:fadeToVolume:waitUntil:resolver:rejecter:)
    public func switchExoPlayer(fadeDuration: Double, fadeInterval: Double, fadeToVolume: Double, waitUntil: NSNumber, resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }
        player.switchExoPlayer(fadeDuration: Int(fadeDuration), fadeInterval: Int(fadeInterval), fadeToVolume: Float(fadeToVolume))
        resolve(NSNull())
    }

    @objc(reset:rejecter:)
    public func reset(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        player.stop()
        player.clear()
        resolve(NSNull())
    }

    @objc(play:rejecter:)
    public func play(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }
        player.play()
        resolve(NSNull())
    }

    @objc(pause:rejecter:)
    public func pause(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        player.pause()
        resolve(NSNull())
    }

    @objc(setPlayWhenReady:resolver:rejecter:)
    public func setPlayWhenReady(playWhenReady: Bool, resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }
        player.playWhenReady = playWhenReady
        resolve(NSNull())
    }

    @objc(getPlayWhenReady:rejecter:)
    public func getPlayWhenReady(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }
        resolve(player.playWhenReady)
    }

    @objc(stop:rejecter:)
    public func stop(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        player.stop()
        resolve(NSNull())
    }

    @objc(seekTo:resolver:rejecter:)
    public func seekTo(time: Double, resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        player.seek(to: time)
        resolve(NSNull())
    }

    @objc(seekBy:resolver:rejecter:)
    public func seekBy(offset: Double, resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        player.seek(by: offset)
        resolve(NSNull())
    }

    @objc(retry:rejecter:)
    public func retry(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }
        player.reload(startFromCurrentTime: true)
        resolve(NSNull())
    }

    @objc(setRepeatMode:resolver:rejecter:)
    public func setRepeatMode(repeatMode: Int, resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        player.repeatMode = RepeatMode(rawValue: repeatMode) ?? .off
        resolve(NSNull())
    }

    @objc(getRepeatMode:rejecter:)
    public func getRepeatMode(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        resolve(player.repeatMode.rawValue)
    }

    @objc(setVolume:resolver:rejecter:)
    public func setVolume(level: Float, resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        player.volume = level
        resolve(NSNull())
    }

    @objc(getVolume:rejecter:)
    public func getVolume(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        resolve(player.volume)
    }

    @objc(setRate:resolver:rejecter:)
    public func setRate(rate: Float, resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        player.rate = rate
        resolve(NSNull())
    }

    @objc(getRate:rejecter:)
    public func getRate(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        resolve(player.rate)
    }

    @objc(getTrack:resolver:rejecter:)
    public func getTrack(index: Int, resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        if (index >= 0 && index < player.items.count) {
            let track = player.items[index]
            resolve((track as? Track)?.toObject())
        } else {
            resolve(NSNull())
        }
    }

    @objc(getQueue:rejecter:)
    public func getQueue(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        let serializedQueue = player.items.map { ($0 as! Track).toObject() }
        resolve(serializedQueue)
    }

    @objc(setQueue:resolver:rejecter:)
    public func setQueue(
        trackDicts: [[String: Any]],
        resolve: RCTPromiseResolveBlock,
        reject: RCTPromiseRejectBlock
    ) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        var tracks = [Track]()
        for trackDict in trackDicts {
            guard let track = Track(dictionary: trackDict) else {
                reject("invalid_track_object", "Track is missing a required key", nil)
                return
            }

            tracks.append(track)
        }
        player.clear()
        try? player.add(items: tracks)
        resolve(index)
    }

    @objc(getActiveTrack:rejecter:)
    public func getActiveTrack(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        let index = player.currentIndex
        if (index >= 0 && index < player.items.count) {
            let track = player.items[index]
            resolve((track as? Track)?.toObject())
        } else {
            resolve(NSNull())
        }
    }

    @objc(getActiveTrackIndex:rejecter:)
    public func getActiveTrackIndex(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        let index = player.currentIndex
        if index < 0 || index >= player.items.count {
            resolve(NSNull())
        } else {
            resolve(index)
        }
    }

    @objc(getDuration:rejecter:)
    public func getDuration(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        resolve(player.duration)
    }

    @objc(getBufferedPosition:rejecter:)
    public func getBufferedPosition(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        resolve(player.bufferedPosition)
    }

    @objc(getPosition:rejecter:)
    public func getPosition(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        resolve(player.currentTime)
    }

    @objc(getProgress:rejecter:)
    public func getProgress(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }
        resolve([
            "position": player.currentTime,
            "duration": player.duration,
            "buffered": player.bufferedPosition
        ])
    }

    @objc(getPlaybackState:rejecter:)
    public func getPlaybackState(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }
        resolve(getPlaybackStateBodyKeyValues(state: player.playerState))
    }

    @objc(updateMetadataForTrack:metadata:resolver:rejecter:)
    public func updateMetadata(for trackIndex: Int, metadata: [String: Any], resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        let index = trackIndex;
        if (rejectWhenNotInitialized(reject: reject)) { return }
        if (rejectWhenTrackIndexOutOfBounds(index: index, reject: reject)) { return }

        let track : Track = player.items[index] as! Track;
        track.updateMetadata(dictionary: metadata)

        if (player.currentIndex == index) {
            Metadata.update(for: player, with: metadata)
        }

        resolve(NSNull())
    }

    @objc(clearNowPlayingMetadata:rejecter:)
    public func clearNowPlayingMetadata(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        player.nowPlayingInfoController.clear()
        resolve(NSNull())
    }

    @objc(updateNowPlayingMetadata:resolver:rejecter:)
    public func updateNowPlayingMetadata(metadata: [String: Any], resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        Metadata.update(for: player, with: metadata)
        resolve(NSNull())
    }

    private func getPlaybackStateErrorKeyValues() -> Dictionary<String, Any> {
        switch player.playbackError {
            case .failedToLoadKeyValue: return [
                "message": "Failed to load resource",
                "code": "ios_failed_to_load_resource"
            ]
            case .invalidSourceUrl: return [
                "message": "The source url was invalid",
                "code": "ios_invalid_source_url"
            ]
            case .notConnectedToInternet: return [
                "message": "A network resource was requested, but an internet connection has not been established and can’t be established automatically.",
                "code": "ios_not_connected_to_internet"
            ]
            case .playbackFailed: return [
                "message": "Playback of the track failed",
                "code": "ios_playback_failed"
            ]
            case .itemWasUnplayable: return [
                "message": "The track could not be played",
                "code": "ios_track_unplayable"
            ]
            default: return [
                "message": "A playback error occurred",
                "code": "ios_playback_error"
            ]
        }
    }

    private func getPlaybackStateBodyKeyValues(state: AudioPlayerState) -> Dictionary<String, Any> {
        var body: Dictionary<String, Any> = ["state": State.fromPlayerState(state: state).rawValue]
        if (state == AudioPlayerState.failed) {
            body["error"] = getPlaybackStateErrorKeyValues()
        }
        return body
    }

    // MARK: - QueuedAudioPlayer Event Handlers

    func handleAudioPlayerStateChange(state: AVPlayerWrapperState) {
        emit(event: EventType.PlaybackState, body: getPlaybackStateBodyKeyValues(state: state))
        if (state == .ended) {
            emit(event: EventType.PlaybackQueueEnded, body: [
                "track": player.currentIndex,
                "position": player.currentTime,
            ] as [String : Any])
        }
    }
    
    func handleAudioPlayerCommonMetadataReceived(metadata: [AVMetadataItem]) {
        let commonMetadata = MetadataAdapter.convertToCommonMetadata(metadata: metadata, skipRaw: true)
        emit(event: EventType.MetadataCommonReceived, body: ["metadata": commonMetadata])
    }
    
    func handleAudioPlayerChapterMetadataReceived(metadata: [AVTimedMetadataGroup]) {
        let metadataItems = MetadataAdapter.convertToGroupedMetadata(metadataGroups: metadata);
        emit(event: EventType.MetadataChapterReceived, body:  ["metadata": metadataItems])
    }

    func handleAudioPlayerTimedMetadataReceived(metadata: [AVTimedMetadataGroup]) {
        let metadataItems = MetadataAdapter.convertToGroupedMetadata(metadataGroups: metadata);
        emit(event: EventType.MetadataTimedReceived, body: ["metadata": metadataItems])
        
        // SwiftAudioEx was updated to return the array of timed metadata
        // Until we have support for that in RNTP, we take the first item to keep existing behaviour.
        let metadata = metadata.first?.items ?? []
        let metadataItem = MetadataAdapter.legacyConversion(metadata: metadata)
        emit(event: EventType.PlaybackMetadataReceived, body: metadataItem)
    }

    func handleAudioPlayerFailed(error: Error?) {
        emit(event: EventType.PlaybackError, body: ["error": error?.localizedDescription])
    }

    func handleAudioPlayerCurrentItemChange(
        item: AudioItem?,
        index: Int?,
        lastItem: AudioItem?,
        lastIndex: Int?,
        lastPosition: Double?
    ) {
        if let item = item {
            DispatchQueue.main.async {
                UIApplication.shared.beginReceivingRemoteControlEvents();
            }
            // Update now playing controller with isLiveStream option from track
            if self.player.automaticallyUpdateNowPlayingInfo {
                let isTrackLiveStream = (item as? Track)?.isLiveStream ?? false
                self.player.nowPlayingInfoController.set(keyValue: NowPlayingInfoProperty.isLiveStream(isTrackLiveStream))
            }
        } else {
            DispatchQueue.main.async {
                UIApplication.shared.endReceivingRemoteControlEvents();
            }
        }

        if ((item != nil && lastItem == nil) || item == nil) {
            configureAudioSession();
        }

        var a: Dictionary<String, Any> = ["lastPosition": lastPosition ?? 0]
        if let lastIndex = lastIndex {
            a["lastIndex"] = lastIndex
        }

        if let lastTrack = (lastItem as? Track)?.toObject() {
            a["lastTrack"] = lastTrack
        }

        if let index = index {
            a["index"] = index
        }

        if let track = (item as? Track)?.toObject() {
            a["track"] = track
        }
        emit(event: EventType.PlaybackActiveTrackChanged, body: a)

        // deprecated:
        var b: Dictionary<String, Any> = ["position": lastPosition ?? 0]
        if let lastIndex = lastIndex {
            b["lastIndex"] = lastIndex
        }
        if let index = index {
            b["nextTrack"] = index
        }
        emit(event: EventType.PlaybackTrackChanged, body: b)
    }

    func handleAudioPlayerSecondElapse(seconds: Double) {
        // because you cannot prevent the `event.secondElapse` from firing
        // do not emit an event if `progressUpdateEventInterval` is nil
        // additionally, there are certain instances in which this event is emitted
        // _after_ a manipulation to the queu causing no currentItem to exist (see reset)
        // in which case we shouldn't emit anything or we'll get an exception.
        if !shouldEmitProgressEvent || player.currentItem == nil { return }
        emit(
            event: EventType.PlaybackProgressUpdated,
            body: [
                "position": player.currentTime,
                "duration": player.duration,
                "buffered": player.bufferedPosition,
                "track": player.currentIndex,
            ]
        )
    }

    func handlePlayWhenReadyChange(playWhenReady: Bool) {
        configureAudioSession();
        emit(
            event: EventType.PlaybackPlayWhenReadyChanged,
            body: [
                "playWhenReady": playWhenReady
            ]
        )
    }

    func handleNotPlayableTrackActive(item: AudioItem?, index: Int?) {
        var body: Dictionary<String, Any> = [:]
        if let index = index {
            body["index"] = index
        }
        if let track = (item as? Track)?.toObject() {
            body["track"] = track
        }
        emit(event: EventType.PlaybackNotPlayableTrackActive, body: body)
    }

    // MARK: - NotPlayable Track Control

    @objc(setTrackPlayable:playable:resolver:rejecter:)
    public func setTrackPlayable(
        trackIndex: Int,
        playable: Bool,
        resolve: RCTPromiseResolveBlock,
        reject: RCTPromiseRejectBlock
    ) {
        if (rejectWhenNotInitialized(reject: reject)) { return }
        if (rejectWhenTrackIndexOutOfBounds(index: trackIndex, reject: reject)) { return }

        let track: Track = player.items[trackIndex] as! Track
        let wasNotPlayable = track.notPlayable
        track.notPlayable = !playable

        // If current track: notPlayable -> playable, load it
        if wasNotPlayable && playable && player.currentIndex == trackIndex {
            player.load(item: track)
        }
        // If current track: playable -> notPlayable, unload it
        else if !wasNotPlayable && !playable && player.currentIndex == trackIndex {
            player.wrapper.unload()
            player.wrapper.state = .idle
            handleNotPlayableTrackActive(item: track, index: trackIndex)
        }

        resolve(NSNull())
    }

    // MARK: - iOS Equalizer Methods

    @objc(setEqualizerEnabled:resolver:rejecter:)
    public func setEqualizerEnabled(enabled: Bool, resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        forEachEqualizerTap { $0.isEnabled = enabled }

        resolve(NSNull())
    }

    // MARK: - Music Haptics

    @objc(getMusicHapticsStatus:rejecter:)
    public func getMusicHapticsStatus(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        resolve(musicHapticsStatus())
    }

    @objc(setNowPlayingRecordingCode:resolver:rejecter:)
    public func setNowPlayingRecordingCode(
        isrc: String,
        resolve: RCTPromiseResolveBlock,
        reject: RCTPromiseRejectBlock
    ) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        // Empty means "no code for what is playing now" rather than nil: the
        // bridge types this argument as non-optional, so the empty string is how
        // the JS side says to clear it.
        let code = isrc.isEmpty ? nil : isrc

        // Also onto the queue item, not just the Now Playing dictionary: the
        // player rewrites that dictionary from the current item whenever it
        // reloads its metadata, and a code only written to the dictionary would
        // be wiped by the next such reload.
        (player.currentItem as? Track)?.isrc = code
        if #available(iOS 18.0, tvOS 18.0, macOS 15.0, *) {
            player.nowPlayingInfoController.set(
                keyValue: MusicHapticsProperty(internationalStandardRecordingCode: code)
            )
        }
        resolve(NSNull())
    }

    @objc(isMusicHapticTrackAvailable:resolver:rejecter:)
    public func isMusicHapticTrackAvailable(
        isrc: String,
        resolve: @escaping RCTPromiseResolveBlock,
        reject: RCTPromiseRejectBlock
    ) {
        guard #available(iOS 18.0, tvOS 18.0, macOS 15.0, *) else {
            resolve(false)
            return
        }
        MAMusicHapticsManager.shared.checkHapticTrackAvailabilityForMedia(matchingCode: isrc) { available in
            resolve(available)
        }
    }

    @objc(getEqualizerEnabled:rejecter:)
    public func getEqualizerEnabled(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        resolve(equalizerTap.isEnabled)
    }

    @objc(setEqualizerBand:gain:resolver:rejecter:)
    public func setEqualizerBand(band: Int, gain: Float, resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        forEachEqualizerTap { $0.setGain(band: band, gainDB: gain) }
        resolve(NSNull())
    }

    @objc(setEqualizerBands:resolver:rejecter:)
    public func setEqualizerBands(gains: [NSNumber], resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        let floatGains = gains.map { $0.floatValue }
        forEachEqualizerTap { $0.setAllGains(floatGains) }
        resolve(NSNull())
    }

    @objc(getEqualizerBands:rejecter:)
    public func getEqualizerBands(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        resolve(equalizerTap.getAllGains())
    }

    @objc(getEqualizerFrequencies:rejecter:)
    public func getEqualizerFrequencies(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        resolve(EqualizerAudioTap.frequencies)
    }

    @objc(applyEqualizerPreset:resolver:rejecter:)
    public func applyEqualizerPreset(presetIndex: Int, resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        let presets = EqualizerAudioTap.Preset.allCases
        guard presetIndex >= 0 && presetIndex < presets.count else {
            reject("invalid_preset", "Preset index out of bounds", nil)
            return
        }

        let preset = presets[presetIndex]
        forEachEqualizerTap { $0.applyPreset(preset) }
        resolve(NSNull())
    }

    @objc(getEqualizerPresetNames:rejecter:)
    public func getEqualizerPresetNames(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        resolve(EqualizerAudioTap.presetNames)
    }

    @objc(resetEqualizer:rejecter:)
    public func resetEqualizer(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }

        forEachEqualizerTap { $0.resetGains() }
        resolve(NSNull())
    }

    // MARK: - Audio Effects

    @objc(setBassBoostEnabled:resolver:rejecter:)
    public func setBassBoostEnabled(enabled: Bool, resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }
        forEachEqualizerTap { $0.isBassBoostEnabled = enabled }
        resolve(NSNull())
    }

    @objc(setLoudnessEnabled:resolver:rejecter:)
    public func setLoudnessEnabled(enabled: Bool, resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }
        forEachEqualizerTap { $0.isLoudnessEnabled = enabled }
        resolve(NSNull())
    }

    @objc(setVirtualizerEnabled:resolver:rejecter:)
    public func setVirtualizerEnabled(enabled: Bool, resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }
        forEachEqualizerTap { $0.isVirtualizerEnabled = enabled }
        resolve(NSNull())
    }

    @objc(setBassBoostLevel:resolver:rejecter:)
    public func setBassBoostLevel(level: Float, resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }
        forEachEqualizerTap { $0.updateBassBoostLevel(level) }
        resolve(NSNull())
    }

    @objc(setLoudnessLevel:resolver:rejecter:)
    public func setLoudnessLevel(level: Float, resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }
        forEachEqualizerTap { $0.updateLoudnessLevel(level) }
        resolve(NSNull())
    }

    @objc(setVirtualizerLevel:resolver:rejecter:)
    public func setVirtualizerLevel(level: Float, resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }
        forEachEqualizerTap { $0.updateVirtualizerLevel(level) }
        resolve(NSNull())
    }

    @objc(setBalance:resolver:rejecter:)
    public func setBalance(balance: Float, resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        if (rejectWhenNotInitialized(reject: reject)) { return }
        let clamped = max(-1, min(1, balance))
        forEachEqualizerTap { $0.balance = clamped }
        resolve(NSNull())
    }
}

extension RNTrackPlayer {
    @objc
    public static var constantsToExport: [AnyHashable: Any] {
            return [
                "STATE_NONE": State.none.rawValue,
                "STATE_READY": State.ready.rawValue,
                "STATE_PLAYING": State.playing.rawValue,
                "STATE_PAUSED": State.paused.rawValue,
                "STATE_STOPPED": State.stopped.rawValue,
                "STATE_BUFFERING": State.buffering.rawValue,
                "STATE_LOADING": State.loading.rawValue,
                "STATE_ERROR": State.error.rawValue,

                "TRACK_PLAYBACK_ENDED_REASON_END": PlaybackEndedReason.playedUntilEnd.rawValue,
                "TRACK_PLAYBACK_ENDED_REASON_JUMPED": PlaybackEndedReason.jumpedToIndex.rawValue,
                "TRACK_PLAYBACK_ENDED_REASON_NEXT": PlaybackEndedReason.skippedToNext.rawValue,
                "TRACK_PLAYBACK_ENDED_REASON_PREVIOUS": PlaybackEndedReason.skippedToPrevious.rawValue,
                "TRACK_PLAYBACK_ENDED_REASON_STOPPED": PlaybackEndedReason.playerStopped.rawValue,

                "PITCH_ALGORITHM_LINEAR": PitchAlgorithm.linear.rawValue,
                "PITCH_ALGORITHM_MUSIC": PitchAlgorithm.music.rawValue,
                "PITCH_ALGORITHM_VOICE": PitchAlgorithm.voice.rawValue,

                "CAPABILITY_PLAY": Capability.play.rawValue,
                "CAPABILITY_PLAY_FROM_ID": "NOOP",
                "CAPABILITY_PLAY_FROM_SEARCH": "NOOP",
                "CAPABILITY_PAUSE": Capability.pause.rawValue,
                "CAPABILITY_STOP": Capability.stop.rawValue,
                "CAPABILITY_SEEK_TO": Capability.seek.rawValue,
                "CAPABILITY_SKIP": "NOOP",
                "CAPABILITY_SKIP_TO_NEXT": Capability.next.rawValue,
                "CAPABILITY_SKIP_TO_PREVIOUS": Capability.previous.rawValue,
                "CAPABILITY_SET_RATING": "NOOP",
                "CAPABILITY_JUMP_FORWARD": Capability.jumpForward.rawValue,
                "CAPABILITY_JUMP_BACKWARD": Capability.jumpBackward.rawValue,
                "CAPABILITY_LIKE": Capability.like.rawValue,
                "CAPABILITY_DISLIKE": Capability.dislike.rawValue,
                "CAPABILITY_BOOKMARK": Capability.bookmark.rawValue,
                "CAPABILITY_SHUFFLE": "NOOP",

                "REPEAT_OFF": RepeatMode.off.rawValue,
                "REPEAT_TRACK": RepeatMode.track.rawValue,
                "REPEAT_QUEUE": RepeatMode.queue.rawValue,
            ]
        }

    @objc
    public static var supportedEvents: [String] {
        return EventType.allRawValues()
    }
}
