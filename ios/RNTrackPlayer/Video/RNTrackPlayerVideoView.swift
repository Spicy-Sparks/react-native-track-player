import AVFoundation
import Foundation
import React

@objc(RNTrackPlayerVideoView)
class RNTrackPlayerVideoView: UIView {
    private var playerLayer: AVPlayerLayer?
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
        playerLayer?.frame = bounds
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        attachPlayerIfNeeded()
    }

    private func attachPlayerIfNeeded() {
        guard playerLayer == nil else { return }
        guard let bridge = self.reactSuperview()?.bridge ?? (self.reactViewController()?.bridge) else { return }
        guard let module = bridge.module(forName: "RNTrackPlayer") as? RNTrackPlayer else { return }
        guard let avPlayer = module.avPlayer else { return }

        let layer = AVPlayerLayer(player: avPlayer)
        layer.frame = bounds
        self.layer.addSublayer(layer)
        self.playerLayer = layer
        updateResizeMode()
    }

    private func updateResizeMode() {
        guard let layer = playerLayer else { return }
        switch resizeMode {
        case "cover": layer.videoGravity = .resizeAspectFill
        case "stretch": layer.videoGravity = .resize
        case "none": layer.videoGravity = .resizeAspect
        default: layer.videoGravity = .resizeAspect // contain
        }
    }

    @objc func setResizeMode(_ mode: NSString) {
        resizeMode = mode as String
    }
}
