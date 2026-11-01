import AVFoundation
import Foundation
import React

@objc(RNTrackPlayerVideoView)
class RNTrackPlayerVideoView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }

    private var playerLayer: AVPlayerLayer {
        return self.layer as! AVPlayerLayer
    }

    weak var bridge: RCTBridge?

    // Views on screen. While none is, the player holds HLS video to its
    // smallest rendition (AudioPlayer.videoOnScreen).
    private static var shown = Set<ObjectIdentifier>()
    static var anyShown: Bool { !shown.isEmpty }

    private var inBackground = UIApplication.shared.applicationState == .background
    private var resizeMode: String = "contain" {
        didSet { updateResizeMode() }
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
        // From creation, not from the first attach: a view that never got the
        // player still has to know when the app goes to the background.
        NotificationCenter.default.addObserver(self,
                                               selector: #selector(appDidEnterBackground),
                                               name: UIApplication.didEnterBackgroundNotification,
                                               object: nil)
        NotificationCenter.default.addObserver(self,
                                               selector: #selector(appWillEnterForeground),
                                               name: UIApplication.willEnterForegroundNotification,
                                               object: nil)
    }

    deinit {
        if RNTrackPlayerVideoView.shown.remove(ObjectIdentifier(self)) != nil {
            NotificationCenter.default.post(name: .RNTPVideoOnScreenChanged, object: nil)
        }
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
    }

    override func layoutSubviews() {
        super.layoutSubviews()
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        attachPlayerIfNeeded()
        updateShown()
    }

    // On screen means in a window while the app is not in the background:
    // Home and the lock screen leave the view in its window, and a video nobody
    // sees must not keep streaming at full quality behind the music.
    private func updateShown() {
        let onScreen = window != nil && !inBackground
        let id = ObjectIdentifier(self)
        if onScreen == RNTrackPlayerVideoView.shown.contains(id) { return }
        if onScreen {
            RNTrackPlayerVideoView.shown.insert(id)
        } else {
            RNTrackPlayerVideoView.shown.remove(id)
        }
        NotificationCenter.default.post(name: .RNTPVideoOnScreenChanged, object: nil)
    }

    private func attachPlayerIfNeeded() {
        guard playerLayer.player == nil else { return }
        guard let avPlayer = RNTrackPlayer.sharedAVPlayer else { return }
        playerLayer.player = avPlayer
        updateResizeMode()

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(onPlayerRecreated),
            name: .RNTPPlayerRecreated,
            object: nil
        )
    }

    @objc private func onPlayerRecreated() {
        playerLayer.player = RNTrackPlayer.sharedAVPlayer
    }

    @objc private func appDidEnterBackground() {
        inBackground = true
        playerLayer.player = nil
        updateShown()
    }

    @objc private func appWillEnterForeground() {
        inBackground = false
        if window != nil {
            playerLayer.player = RNTrackPlayer.sharedAVPlayer
        }
        updateShown()
    }

    private func updateResizeMode() {
        switch resizeMode {
        case "cover": playerLayer.videoGravity = .resizeAspectFill
        case "stretch": playerLayer.videoGravity = .resize
        case "none": playerLayer.videoGravity = .resizeAspect
        default: playerLayer.videoGravity = .resizeAspect // contain
        }
    }

    @objc func setResizeMode(_ mode: NSString) {
        resizeMode = mode as String
    }
}

extension Notification.Name {
    static let RNTPVideoOnScreenChanged = Notification.Name("RNTPVideoOnScreenChanged")
}
