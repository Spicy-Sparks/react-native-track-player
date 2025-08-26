import Foundation
import React

@objc(RNTrackPlayerVideoViewManager)
class RNTrackPlayerVideoViewManager: RCTViewManager {
    override static func requiresMainQueueSetup() -> Bool { true }

    override func view() -> UIView! {
        let v = RNTrackPlayerVideoView()
        v.bridge = self.bridge
        return v
    }

    @objc override func setValue(_ value: Any!, forKey key: String!) {
        // No-op to silence undefined key warnings
    }
}
