//
//  Track.swift
//  RNTrackPlayer
//
//  Created by David Chavez on 12.08.17.
//  Copyright © 2017 David Chavez. All rights reserved.
//

import Foundation
import MediaPlayer
import AVFoundation

class Track: AudioItem, TimePitching, AssetOptionsProviding, NormalizationGainProviding, MusicHapticsIdentifying {
    let url: MediaURL

    @objc var title: String?
    @objc var artist: String?

    var date: String?
    var desc: String?
    var genre: String?
    var duration: Double?
    var artworkURL: MediaURL?
    let headers: [String: Any]?
    var userAgent: String?
    let pitchAlgorithm: String?
    var isLiveStream: Bool?

    var album: String?
    var artwork: MPMediaItemArtwork?

    /// International Standard Recording Code of this recording. Handed to the
    /// Now Playing info so iOS 18's Music Haptics can find the haptic track that
    /// goes with it; tracks we can't identify simply don't get haptics.
    var isrc: String?

    /// When true, the track won't load or play when it becomes current.
    var notPlayable: Bool = false

    /// Per-track loudness normalization gain (linear, 1.0 = unity). Applied as an
    /// output gain by the player so all tracks play at a consistent level and hot
    /// masters don't clip over the Bluetooth/AAC path.
    var normalizationGain: Float = 1.0

    private var originalObject: [String: Any] = [:]

    init?(dictionary: [String: Any]) {
        guard let url = MediaURL(object: dictionary["url"]) else { return nil }
        self.url = url
        self.headers = dictionary["headers"] as? [String: Any]
        self.userAgent = dictionary["userAgent"] as? String
        self.pitchAlgorithm = dictionary["pitchAlgorithm"] as? String
        self.notPlayable = dictionary["notPlayable"] as? Bool ?? false

        updateMetadata(dictionary: dictionary);
    }


    // MARK: - Public Interface

    func toObject() -> [String: Any] {
        return originalObject
    }

    func updateMetadata(dictionary: [String: Any]) {
        self.title = (dictionary["title"] as? String) ?? self.title
        self.artist = (dictionary["artist"] as? String) ?? self.artist
        self.date = dictionary["date"] as? String
        self.album = dictionary["album"] as? String
        self.genre = dictionary["genre"] as? String
        self.desc = dictionary["description"] as? String
        self.duration = dictionary["duration"] as? Double
        self.artworkURL = MediaURL(object: dictionary["artwork"])
        self.isLiveStream = dictionary["isLiveStream"] as? Bool
        // Absent key keeps the current value, explicit NSNull clears it: metadata
        // updates arrive piecemeal, and a partial update must not silently drop
        // the code (nor keep a stale one once the caller says there is none).
        if let isrc = dictionary["isrc"] {
            self.isrc = isrc as? String
        }
        if let normalizationGain = dictionary["normalizationGain"] as? NSNumber {
            self.normalizationGain = normalizationGain.floatValue
        }
        if let notPlayable = dictionary["notPlayable"] as? Bool {
            self.notPlayable = notPlayable
        }

        self.originalObject = self.originalObject.merging(dictionary) { (_, new) in new }
    }

    // MARK: - AudioItem Protocol

    func getSourceUrl() -> String {
        return url.isLocal ? url.value.path : url.value.absoluteString
    }

    func getArtist() -> String? {
        return artist
    }

    func getTitle() -> String? {
        return title
    }

    func getAlbumTitle() -> String? {
        return album
    }
    
    func getDuration() -> Double? {
        return duration
    }

    func getInternationalStandardRecordingCode() -> String? {
        return isrc
    }

    func getSourceType() -> SourceType {
        return url.isLocal ? .file : .stream
    }

    func getArtwork(_ handler: @escaping (UIImage?) -> Void) {
        if let artworkURL = artworkURL?.value {
            if(self.artworkURL?.isLocal ?? false){
                let image = UIImage.init(contentsOfFile: artworkURL.path);
                handler(image);
            } else {
                URLSession.shared.dataTask(with: artworkURL, completionHandler: { (data, _, error) in
                    if let data = data, let artwork = UIImage(data: data), error == nil {
                        handler(artwork)
                    } else {
                        handler(nil)
                    }
                }).resume()
            }
        } else {
            handler(nil)
        }
    }

    // MARK: - TimePitching Protocol

    func getPitchAlgorithmType() -> AVAudioTimePitchAlgorithm {
        if let pitchAlgorithm = pitchAlgorithm {
            switch pitchAlgorithm {
            case PitchAlgorithm.linear.rawValue:
                return .varispeed
            case PitchAlgorithm.music.rawValue:
                return .spectral
            default: // voice
                return .timeDomain
            }
        }

        return .timeDomain
    }

    // MARK: - Authorizing Protocol

    func getAssetOptions() -> [String: Any] {
        var options: [String: Any] = [:]
        if let headers = headers {
            options["AVURLAssetHTTPHeaderFieldsKey"] = headers
        }
        if #available(iOS 16, *) {
            if let userAgent = userAgent {
                // there is now an official, working way to set the user-agent for every request
                // https://developer.apple.com/documentation/avfoundation/avurlassethttpuseragentkey
                options[AVURLAssetHTTPUserAgentKey] = userAgent
            }
        }
        return options
    }

    func getNormalizationGain() -> Float {
        return normalizationGain
    }

}
