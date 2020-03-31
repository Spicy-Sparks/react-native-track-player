//
//  RNTrackPlayer.swift
//  RNTrackPlayer
//
//  Created by David Chavez on 13.08.17.
//  Copyright © 2017 David Chavez. All rights reserved.
//

import Foundation
import MediaPlayer


public class RNTrackPlayer: RCTEventEmitter {
    
    // MARK: - Attributes
    
    private var hasInitialized = false
    
    // MARK: - Lifecycle Methods
    
    deinit {
        reset(resolve: { _ in }, reject: { _, _, _  in })
    }
    
    private var currentTrack: Track? = nil
    
    private var previousArtworkUrl : String? = nil
    
    private var placeHolderImageArtwork : MPMediaItemArtwork? = nil
    
    // MARK: - RCTEventEmitter
    
    override public static func requiresMainQueueSetup() -> Bool {
        return true;
    }
    
    @objc(constantsToExport)
    override public func constantsToExport() -> [AnyHashable: Any] {
        return [
            "STATE_NONE": PlayState.none,
            "STATE_PLAYING": PlayState.playing,
            "STATE_PAUSED": PlayState.paused,
            "STATE_STOPPED": PlayState.stopped,

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
        ]
    }
    
    @objc(supportedEvents)
    override public func supportedEvents() -> [String] {
        return [
            "playback-queue-ended",
            "playback-state",
            "playback-error",
            "playback-track-changed",
            
            "remote-play-pause",
            "remote-stop",
            "remote-pause",
            "remote-play",
            "remote-duck",
            "remote-next",
            "remote-seek",
            "remote-previous",
            "remote-jump-forward",
            "remote-jump-backward",
            "remote-like",
            "remote-dislike",
            "remote-bookmark",
        ]
    }
    
    func setupInterruptionHandling() {
        let notificationCenter = NotificationCenter.default
        notificationCenter.removeObserver(self)
        notificationCenter.addObserver(self,
                                       selector: #selector(handleInterruption),
                                       name: AVAudioSession.interruptionNotification,
                                       object: nil)
    }
    
    @objc func handleInterruption(notification: Notification) {
        guard let userInfo = notification.userInfo,
            let typeValue = userInfo[AVAudioSessionInterruptionTypeKey] as? UInt,
            let type = AVAudioSession.InterruptionType(rawValue: typeValue) else {
                return
        }
        if type == .began {
            // Interruption began, take appropriate actions
            self.sendEvent(withName: "remote-duck", body: [
                "paused": true
                ])
        }
        else if type == .ended {
            if let optionsValue = userInfo[AVAudioSessionInterruptionOptionKey] as? UInt {
                let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)
                if options.contains(.shouldResume) {
                    // Interruption Ended - playback should resume
                    self.sendEvent(withName: "remote-duck", body: [
                        "paused": false
                        ])
                } else {
                    // Interruption Ended - playback should NOT resume
                    self.sendEvent(withName: "remote-duck", body: [
                        "permanent": true
                        ])
                }
            }
        }
    }

    private func setupPlayer() {
        if hasInitialized {
            return
        }
        
        setupInterruptionHandling();
        
        let center = MPRemoteCommandCenter.shared()

            if #available(iOS 9.1, *) {
                center.changePlaybackPositionCommand.addTarget { (commandEvent) -> MPRemoteCommandHandlerStatus in
                        if let event = commandEvent as? MPChangePlaybackPositionCommandEvent {
                        self.sendEvent(withName: "remote-seek", body: ["position": event.positionTime])
                        return MPRemoteCommandHandlerStatus.success
                    }
                    
                    return MPRemoteCommandHandlerStatus.commandFailed
                }
            }
            
            center.playCommand.addTarget { (commandEvent) -> MPRemoteCommandHandlerStatus in
                self.sendEvent(withName: "remote-play", body: nil)
                return MPRemoteCommandHandlerStatus.success
            }
            
            
            center.pauseCommand.addTarget { (commandEvent) -> MPRemoteCommandHandlerStatus in
                self.sendEvent(withName: "remote-pause", body: nil)
                return MPRemoteCommandHandlerStatus.success
            }
            
            center.nextTrackCommand.addTarget { (commandEvent) -> MPRemoteCommandHandlerStatus in
                self.sendEvent(withName: "remote-next", body: nil)
                return MPRemoteCommandHandlerStatus.success
            }
            
            center.previousTrackCommand.addTarget { (commandEvent) -> MPRemoteCommandHandlerStatus in
                self.sendEvent(withName: "remote-previous", body: nil)
                return MPRemoteCommandHandlerStatus.success
            }
            
            center.skipBackwardCommand.addTarget { (commandEvent) -> MPRemoteCommandHandlerStatus in
                    if let command = commandEvent.command as? MPSkipIntervalCommand,
                        let interval = command.preferredIntervals.first {
                        self.sendEvent(withName: "remote-jump-backward", body: ["interval": interval])
                        return MPRemoteCommandHandlerStatus.success
                    }
              
                return MPRemoteCommandHandlerStatus.commandFailed
            }
            
            center.skipForwardCommand.addTarget { (commandEvent) -> MPRemoteCommandHandlerStatus in
                   if let command = commandEvent.command as? MPSkipIntervalCommand,
                        let interval = command.preferredIntervals.first {
                        self.sendEvent(withName: "remote-jump-forward", body: ["interval": interval])
                        return MPRemoteCommandHandlerStatus.success
                    }
                    
                    return MPRemoteCommandHandlerStatus.commandFailed
            }
        
            center.stopCommand.addTarget { (commandEvent) -> MPRemoteCommandHandlerStatus in
                self.sendEvent(withName: "remote-stop", body: nil)
                return MPRemoteCommandHandlerStatus.success
            }
            
            center.togglePlayPauseCommand.addTarget { (commandEvent) -> MPRemoteCommandHandlerStatus in
                    self.sendEvent(withName: "remote-play-pause", body: nil)
                           return MPRemoteCommandHandlerStatus.success
            }
            
            
            center.likeCommand.addTarget { (commandEvent) -> MPRemoteCommandHandlerStatus in
                self.sendEvent(withName: "remote-like", body: nil)
                return MPRemoteCommandHandlerStatus.success
            }
            
            center.dislikeCommand.addTarget { (commandEvent) -> MPRemoteCommandHandlerStatus in
                self.sendEvent(withName: "remote-dislike", body: nil)
                return MPRemoteCommandHandlerStatus.success
            }
            
            center.bookmarkCommand.addTarget { (commandEvent) -> MPRemoteCommandHandlerStatus in
                self.sendEvent(withName: "remote-bookmark", body: nil)
                return MPRemoteCommandHandlerStatus.success
            }
                
        hasInitialized = true
    }
    
    @objc(reset:rejecter:)
    public func reset(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        print("Resetting player.")
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        resolve(NSNull())
        DispatchQueue.main.async {
            UIApplication.shared.endReceivingRemoteControlEvents();
        }
    }
    
    @objc(updateOptions:resolve:rejecter:)
    public func update(options: [String: Any], resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        let capabilitiesStr = options["capabilities"] as? [String]
        let capabilities = capabilitiesStr?.compactMap { Capability(rawValue: $0) } ?? []
        
        let jumpInterval = options["jumpInterval"] as? NSNumber
        let likeOptions = options["likeOptions"] as? [String: Any]
        let dislikeOptions = options["dislikeOptions"] as? [String: Any]
        let bookmarkOptions = options["bookmarkOptions"] as? [String: Any]
        
        let center = MPRemoteCommandCenter.shared()
        
        
        if #available(iOS 9.1, *) {
            center.changePlaybackPositionCommand.isEnabled = capabilities.contains(.seek)
        }
        
        center.togglePlayPauseCommand.isEnabled = capabilities.contains(.play)
        
        center.playCommand.isEnabled = capabilities.contains(.play)
        center.pauseCommand.isEnabled = capabilities.contains(.pause)
        center.nextTrackCommand.isEnabled = capabilities.contains(.next)
        center.previousTrackCommand.isEnabled = capabilities.contains(.previous)
        
        center.skipBackwardCommand.isEnabled = capabilities.contains(.jumpBackward)
        center.skipBackwardCommand.preferredIntervals = [jumpInterval ?? 15]
        
        center.skipForwardCommand.isEnabled = capabilities.contains(.jumpForward)
        center.skipForwardCommand.preferredIntervals = [jumpInterval ?? 15]
        
        center.stopCommand.isEnabled = capabilities.contains(.stop)
        
        center.likeCommand.isEnabled = likeOptions?["isActive"] as? Bool ?? false//capabilities.contains(.like)
        center.likeCommand.localizedTitle = likeOptions?["title"] as? String ?? "Like"
        center.likeCommand.localizedShortTitle = likeOptions?["title"] as? String ?? "Like"
        
        center.dislikeCommand.isEnabled = dislikeOptions?["isActive"] as? Bool ?? false//capabilities.contains(.like)
        center.dislikeCommand.localizedTitle = dislikeOptions?["title"] as? String ?? "Dislike"
        center.dislikeCommand.localizedShortTitle = dislikeOptions?["title"] as? String ?? "Dislike"
        
        center.bookmarkCommand.isEnabled = bookmarkOptions?["isActive"] as? Bool ?? false//capabilities.contains(.like)
        center.bookmarkCommand.localizedTitle = bookmarkOptions?["title"] as? String ?? "Bookmark"
        center.bookmarkCommand.localizedShortTitle = bookmarkOptions?["title"] as? String ?? "Bookmark"
        
        
        //load placeholder
        if(placeHolderImageArtwork == nil && options["placeholderImage"] != nil){
            let placeHolderImage : UIImage = RCTConvert.uiImage(options["placeholderImage"])
            
            if #available(iOS 10.0, *) {
                placeHolderImageArtwork = MPMediaItemArtwork.init(boundsSize: placeHolderImage.size, requestHandler: { (size) -> UIImage in
                    return placeHolderImage
                })
            } else {
                placeHolderImageArtwork = MPMediaItemArtwork(image: placeHolderImage)
            }
        }
        
        resolve(nil)
    }
    
    @objc(setNowPlaying:resolve:rejecter:)
    public func setNowPlaying(trackDict: [String: Any], resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        
        if(!hasInitialized){
            setupPlayer()
        }
        
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
            UIApplication.shared.beginReceivingRemoteControlEvents();
        }

        currentTrack = Track(dictionary: trackDict)
        updatePlayback(properties: trackDict, resolve: resolve, reject: reject)
        
        
    }
    
    @objc(updatePlayback:resolve:reject:)
    public func updatePlayback(properties: [String: Any], resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
        /*guard let track = player.queueManager.items.first(where: { ($0 as! Track).id == trackId }) as? Track
            else {
                reject("track_not_in_queue", "Given track ID was not found in queue", nil)
                return
        }*/
        
        let center = MPNowPlayingInfoCenter.default()
        
        let state = properties["state"] as? PlayState ?? PlayState.none
        
        currentTrack?.updateMetadata(dictionary: properties)
        
        updateMetadata(properties: properties)
        
        let commandCenter = MPRemoteCommandCenter.shared()
        
        if(state == PlayState.stopped){
            commandCenter.stopCommand.isEnabled = false
        }
        
        if #available(iOS 13.0, *) {
            if (state == PlayState.playing) {
                center.playbackState = MPNowPlayingPlaybackState.playing
            } else if (state == PlayState.paused) {
                center.playbackState = MPNowPlayingPlaybackState.paused;
            } else if (state == PlayState.stopped) {
                center.playbackState = MPNowPlayingPlaybackState.stopped;
            }
        }
        
        resolve(nil)
    }
    
    @objc(updateMetadataForTrack:)
    public func updateMetadata(properties: [String: Any]) {
        
        let center = MPNowPlayingInfoCenter.default()
        
        if(center.nowPlayingInfo == nil){
            center.nowPlayingInfo = [
                MPMediaItemPropertyTitle: "",
                MPMediaItemPropertyArtist: "",
                MPMediaItemPropertyAlbumTitle: "",
                MPMediaItemPropertyPlaybackDuration: 0,
                MPNowPlayingInfoPropertyElapsedPlaybackTime: 0,
            ]
        }
        
        var newNowPlaying = center.nowPlayingInfo
        
        newNowPlaying![MPMediaItemPropertyTitle] = currentTrack?.title ?? center.nowPlayingInfo![MPMediaItemPropertyTitle]
        newNowPlaying![MPMediaItemPropertyArtist] = currentTrack?.artist ?? center.nowPlayingInfo![MPMediaItemPropertyArtist]
        newNowPlaying![MPMediaItemPropertyAlbumTitle] = currentTrack?.album ?? center.nowPlayingInfo![MPMediaItemPropertyAlbumTitle]
        newNowPlaying![MPMediaItemPropertyPlaybackDuration] = currentTrack?.duration ?? center.nowPlayingInfo![MPMediaItemPropertyPlaybackDuration]
        newNowPlaying![MPNowPlayingInfoPropertyElapsedPlaybackTime] = properties["elapsedTime"] ?? center.nowPlayingInfo![MPNowPlayingInfoPropertyElapsedPlaybackTime]
        
        let newArtworkUrl = properties["artwork"] as? String
        
        //add placeholder while image is loading
        if(newArtworkUrl != nil){
            newNowPlaying![MPMediaItemPropertyArtwork] = placeHolderImageArtwork
        }
        
        MPNowPlayingInfoCenter.default().nowPlayingInfo = newNowPlaying
        
        updateArtworkIfNeeded(artworkUrl: newArtworkUrl, newNowPlaying: newNowPlaying!)
        
        
    }
    
    private func updateArtworkIfNeeded(artworkUrl: String?, newNowPlaying: [String: Any]){
        
        if(artworkUrl == nil || artworkUrl == "" || artworkUrl == self.previousArtworkUrl){
            return
        }
                
        self.previousArtworkUrl = artworkUrl
        
        DispatchQueue.global(qos: .default).async {
        
            if(self.previousArtworkUrl != artworkUrl){
                return
            }
            
            self.currentTrack?.getArtwork { image in
                    if let image = image {
                        
                        DispatchQueue.main.async {
                            
                            var artwork : MPMediaItemArtwork;
                            
                            if #available(iOS 10.0, *) {
                                artwork = MPMediaItemArtwork.init(boundsSize: image.size, requestHandler: { (size) -> UIImage in
                                    return image
                                })
                            } else {
                                artwork = MPMediaItemArtwork(image: image)
                            }
                            
                            MPNowPlayingInfoCenter.default().nowPlayingInfo![MPMediaItemPropertyArtwork] = artwork
                        }
                    }
                }
        }
    }
}
