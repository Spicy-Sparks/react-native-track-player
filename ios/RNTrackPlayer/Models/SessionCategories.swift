//
//  SessionCategory.swift
//  RNTrackPlayer
//
//  Created by Thomas Hessler on 3/12/19.
//  Copyright © 2019 David Chavez. All rights reserved.
//

import Foundation
import MediaPlayer
import AVFoundation

enum SessionCategory : String {
    
    case playAndRecord, multiRoute, playback, ambient, soloAmbient
    
    func mapConfigToAVAudioSessionCategory() -> AVAudioSession.Category {
        switch self {
        case .playAndRecord:
            return .playAndRecord
        case .multiRoute:
            return .multiRoute
        case .playback:
            return .playback
        case .ambient:
            return .ambient
        case .soloAmbient:
            return .soloAmbient
        }
    }
}

enum SessionCategoryOptions : String {
    
    case mixWithOthers, duckOthers, interruptSpokenAudioAndMixWithOthers, allowBluetooth, allowBluetoothA2DP, allowAirPlay, defaultToSpeaker

    func mapConfigToAVAudioSessionCategoryOptions() -> AVAudioSession.CategoryOptions? {
        switch self {
        case .mixWithOthers:
            return .mixWithOthers
        case .duckOthers:
            return .duckOthers
        case .interruptSpokenAudioAndMixWithOthers:
            if #available(iOS 9.0, *) {
                return .interruptSpokenAudioAndMixWithOthers
            } else {
                // Fallback on earlier versions
            }
        case .allowBluetooth:
            return .allowBluetooth
        case .allowBluetoothA2DP:
            if #available(iOS 10.0, *) {
                return .allowBluetoothA2DP
            } else {
                return .none
            }
        case .allowAirPlay:
            if #available(iOS 10.0, *) {
                return .allowAirPlay
            } else {
               return .none
            }
        case .defaultToSpeaker:
            return .defaultToSpeaker
        }
        
        return .none
    }
}

enum SessionCategoryMode : String {
    
    case `default`, gameChat, measurement, moviePlayback, spokenAudio, videoChat, videoRecording, voiceChat, voicePrompt
    
    func mapConfigToAVAudioSessionCategoryMode() -> AVAudioSession.Mode {
        switch self {
        case .default:
            return .default
        case .gameChat:
            return .gameChat
        case .measurement:
            return .measurement
        case .moviePlayback:
            return .moviePlayback
        case .spokenAudio:
            if #available(iOS 9.0, *) {
                return .spokenAudio
            } else {
                return .default
            }
        case .videoChat:
            return .videoChat
        case .videoRecording:
            return .videoRecording
        case .voiceChat:
            return .voiceChat
        case .voicePrompt:
            if #available(iOS 12.0, *) {
                return .voicePrompt
            } else {
                // Do Nothing
                return .default
            }
        }
    }
}

