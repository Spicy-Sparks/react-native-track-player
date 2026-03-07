//
//  AudioTap.swift
//
//
//  Created by Brandon Sneed on 3/31/24.
//

import Foundation
import AVFoundation

/**
 Subclass this and set the AudioPlayer's `audioTap` property to start receiving the
 audio stream.
 */
open class AudioTap {
    // Called at tap initialization for a given player item. Use this to setup anything you might need.
    open func initialize() { print("audioTap: initialize") }
    // Called at teardown of the internal tap.  Use this to reset any memory buffers you have created, etc.
    open func finalize() { print("audioTap: finalize") }
    // Called just before playback so you can perform setup based on the stream description.
    open func prepare(description: AudioStreamBasicDescription) { print("audioTap: prepare") }
    // Called just before finalize.
    open func unprepare() { print("audioTap: unprepare") }
    /**
     Called periodically during audio stream              – playback.
     
     Example:
     
     ```
     func process(numberOfFrames: Int, buffer: UnsafeMutableAudioBufferListPointer) {
         for channel in buffer {
             // process audio samples here
             //memset(channel.mData, 0, Int(channel.mDataByteSize))
         }
     }
     ```
    */
    open func process(numberOfFrames: Int, buffer: UnsafeMutableAudioBufferListPointer) { print("audioTap: process") }
}

extension AVPlayerWrapper {
    internal func attachTap(_ tap: AudioTap?, to item: AVPlayerItem) {
        guard let tap else { return }

        // Try sync first (works for local/progressive files)
        if let track = item.asset.tracks(withMediaType: .audio).first {
            applyTap(tap, to: item, track: track)
            return
        }

        // Async fallback for HLS streams where tracks aren't immediately available
        Task {
            guard let tracks = try? await item.asset.loadTracks(withMediaType: .audio),
                  let track = tracks.first else { return }
            await MainActor.run {
                self.applyTap(tap, to: item, track: track)
            }
        }
    }

    private func applyTap(_ tap: AudioTap, to item: AVPlayerItem, track: AVAssetTrack) {
        let audioMix = AVMutableAudioMix()
        let params = AVMutableAudioMixInputParameters(track: track)

        let client = UnsafeMutableRawPointer(Unmanaged.passRetained(tap).toOpaque())

        var callbacks = MTAudioProcessingTapCallbacks(version: kMTAudioProcessingTapCallbacksVersion_0, clientInfo: client)
        { tapRef, clientInfo, tapStorageOut in
            guard let clientInfo else { return }
            tapStorageOut.pointee = clientInfo
            let audioTap = Unmanaged<AudioTap>.fromOpaque(clientInfo).takeUnretainedValue()
            audioTap.initialize()
        } finalize: { tapRef in
            let audioTap = Unmanaged<AudioTap>.fromOpaque(MTAudioProcessingTapGetStorage(tapRef)).takeUnretainedValue()
            audioTap.finalize()
            Unmanaged.passUnretained(audioTap).release()
        } prepare: { tapRef, maxFrames, processingFormat in
            let audioTap = Unmanaged<AudioTap>.fromOpaque(MTAudioProcessingTapGetStorage(tapRef)).takeUnretainedValue()
            audioTap.prepare(description: processingFormat.pointee)
        } unprepare: { tapRef in
            let audioTap = Unmanaged<AudioTap>.fromOpaque(MTAudioProcessingTapGetStorage(tapRef)).takeUnretainedValue()
            audioTap.unprepare()
        } process: { tapRef, numberFrames, flags, bufferListInOut, numberFramesOut, flagsOut in
            guard noErr == MTAudioProcessingTapGetSourceAudio(tapRef, numberFrames, bufferListInOut, flagsOut, nil, numberFramesOut) else {
                return
            }
            let audioTap = Unmanaged<AudioTap>.fromOpaque(MTAudioProcessingTapGetStorage(tapRef)).takeUnretainedValue()
            audioTap.process(numberOfFrames: numberFrames, buffer: UnsafeMutableAudioBufferListPointer(bufferListInOut))
        }

        var tapRef: MTAudioProcessingTap?
        let error = MTAudioProcessingTapCreate(kCFAllocatorDefault, &callbacks, kMTAudioProcessingTapCreationFlag_PreEffects, &tapRef)
        if error != noErr { return }

        params.audioTapProcessor = tapRef
        audioMix.inputParameters = [params]
        item.audioMix = audioMix
    }
}
