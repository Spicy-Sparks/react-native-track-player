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

class Track: NSObject {
    let id: String
    
    @objc var title: String
    @objc var artist: String
    
    var date: String?
    var desc: String?
    var genre: String?
    var duration: Double?
    var skipped: Bool = false
    var artworkURL: MediaURL?
    let headers: [String: Any]?
    let pitchAlgorithm: String?
    
    @objc var album: String?
    @objc var artwork: MPMediaItemArtwork?
    
    private var originalObject: [String: Any]
    
    init?(dictionary: [String: Any]) {
        
        let id = dictionary["id"] as? String
        let title = dictionary["title"] as? String
        let artist = dictionary["artist"] as? String
        
        self.id = id ?? "eSound"
        self.title = title ?? "eSound"
        self.artist = artist ?? "eSound"
        
        self.date = dictionary["date"] as? String
        self.album = dictionary["album"] as? String
        self.genre = dictionary["genre"] as? String
        self.desc = dictionary["description"] as? String
        self.duration = dictionary["duration"] as? Double
        self.headers = dictionary["headers"] as? [String: Any]
        self.artworkURL = MediaURL(object: dictionary["artwork"])
        self.pitchAlgorithm = dictionary["pitchAlgorithm"] as? String
        
        self.originalObject = dictionary
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
        
        self.originalObject = self.originalObject.merging(dictionary) { (_, new) in new }
    }
    
    // MARK: - AudioItem Protocol
    
    func getArtist() -> String? {
        return artist
    }
    
    func getTitle() -> String? {
        return title
    }
    
    func getAlbumTitle() -> String? {
        return album
    }

    
    func getArtwork(_ handler: @escaping (UIImage?) -> Void) {
        if let artworkURL = artworkURL {
            if (!artworkURL.isLocal) {
                URLSession.shared.dataTask(with: artworkURL.value, completionHandler: { (data, _, error) in
                    if let data = data, let artwork = UIImage(data: data), error == nil {
                        handler(artwork)
                    }
                    
                    handler(nil)
                }).resume()
            } else {
                if(FileManager.default.fileExists(atPath: artworkURL.value.absoluteString)){
                    let artwork = UIImage(contentsOfFile: artworkURL.value.absoluteString)
                    handler(artwork)
                }
            }
        
            handler(nil)
        }
    }
    
    // MARK: - Authorizing Protocol
    
    func getAssetOptions() -> [String: Any] {
        if let headers = headers {
            return ["AVURLAssetHTTPHeaderFieldsKey": headers]
        }
        
        return [:]
    }
    
}
