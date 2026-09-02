//
//  MusicHapticsProperty.swift
//  SwiftAudioEx
//

import Foundation
import MediaPlayer

/**
 The recording code that iOS 18's Music Haptics matches against to find the
 haptic track belonging to what we're playing.

 It lives outside `MediaItemProperty` because its key only exists from iOS 18
 on, and every case of that enum has to be usable on the deployment target.
 Setting it with a `nil` code removes the key, which is what clears the
 previous track's code when the new one has none — leaving a stale code behind
 would have the system buzzing the wrong song.
 */
@available(iOS 18.0, tvOS 18.0, macOS 15.0, *)
public struct MusicHapticsProperty: NowPlayingInfoKeyValue {

    private let internationalStandardRecordingCode: String?

    public init(internationalStandardRecordingCode: String?) {
        self.internationalStandardRecordingCode = internationalStandardRecordingCode
    }

    public func getKey() -> String {
        return MPNowPlayingInfoPropertyInternationalStandardRecordingCode
    }

    public func getValue() -> Any? {
        return internationalStandardRecordingCode
    }
}
