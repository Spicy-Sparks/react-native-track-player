import AVFoundation
import Foundation
import React

@objc(RNTrackPlayerVideoView)
class RNTrackPlayerVideoView: UIView {
    // Use AVPlayerLayer as the backing layer
    override class var layerClass: AnyClass { AVPlayerLayer.self }

    private var playerLayer: AVPlayerLayer {
        return self.layer as! AVPlayerLayer
    }

    weak var bridge: RCTBridge?
    private var resizeMode: String = "contain" {
        didSet { updateResizeMode() }
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
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
