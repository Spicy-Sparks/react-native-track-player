//
//  Metadata.swift
//  RNTrackPlayer
//
//  Created by David Chavez on 23.06.19.
//  Copyright © 2019 David Chavez. All rights reserved.
//

import Foundation
import MediaPlayer

struct Metadata {
    private static var currentImageTask: URLSessionDataTask?

    static func update(for player: AudioPlayer, with metadata: [String: Any]) {
        currentImageTask?.cancel()
        var ret: [NowPlayingInfoKeyValue] = []
        
        if let title = metadata["title"] as? String {
            ret.append(MediaItemProperty.title(title))
        }
        
        if let artist = metadata["artist"] as? String {
            ret.append(MediaItemProperty.artist(artist))
        }
        
        if let album = metadata["album"] as? String {
            ret.append(MediaItemProperty.albumTitle(album))
        }
        
        if let duration = metadata["duration"] as? Double {
            ret.append(MediaItemProperty.duration(duration))
        }
        
        if let elapsedTime = metadata["elapsedTime"] as? Double {
            ret.append(NowPlayingInfoProperty.elapsedPlaybackTime(elapsedTime))
        }

        if let isLiveStream = metadata["isLiveStream"] as? Bool {
            ret.append(NowPlayingInfoProperty.isLiveStream(isLiveStream))
        }

        // Only when the caller mentions the code at all: these updates are
        // partial (a lock-screen repaint may carry title + artwork and nothing
        // else), and clearing an ISRC nobody talked about would cut the haptics
        // off mid-song.
        if #available(iOS 18.0, tvOS 18.0, macOS 15.0, *), let isrc = metadata["isrc"] {
            ret.append(
                MusicHapticsProperty(internationalStandardRecordingCode: isrc as? String)
            )
        }
        
        player.nowPlayingInfoController.set(keyValues: ret)
        
        if let artworkURL = MediaURL(object: metadata["artwork"]) {
            currentImageTask = URLSession.shared.dataTask(with: artworkURL.value, completionHandler: { [weak player] (data, _, _) in
                // URLSession can't handle file:// → read local thumbs directly from disk
                let data = data ?? (artworkURL.isLocal ? try? Data(contentsOf: artworkURL.value) : nil)
                if let data = data, let image = UIImage(data: data) {
                    let artwork = MPMediaItemArtwork(boundsSize: image.size, requestHandler: { (size) -> UIImage in
                        return image
                    })
                    player?.nowPlayingInfoController.set(keyValue: MediaItemProperty.artwork(artwork))
                }
            })
            
            currentImageTask?.resume()
        } else {
            player.nowPlayingInfoController.set(keyValue: MediaItemProperty.artwork(nil))
        }
    }
}
