import Foundation
import React

@objc(RNTrackPlayerVideoViewManager)
class RNTrackPlayerVideoViewManager: RCTViewManager {
    override static func requiresMainQueueSetup() -> Bool { true }

    override func view() -> UIView! {
        return RNTrackPlayerVideoView()
    }

    @objc override func setValue(_ value: Any!, forKey key: String!) {
        // No-op to silence undefined key warnings
    }
}
