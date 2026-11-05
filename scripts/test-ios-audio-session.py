#!/usr/bin/env python3
"""Execute the production RNTrackPlayer audio-session methods with Swift test doubles.

No AVPlayer or head-unit behavior is simulated. These checks cover bridge control
flow, category-before-activation ordering, and the interruption event contract.
"""
from pathlib import Path
import subprocess
import tempfile
import sys

root = Path(__file__).resolve().parents[1]
source = (Path(sys.argv[1]) if len(sys.argv) > 1 else root / 'ios/RNTrackPlayer/RNTrackPlayer.swift').read_text()

def method(signature):
    start = source.index(signature)
    opening = source.index('{', start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end].replace('public func ', 'func ').replace('private func ', 'func ')

methods = [method('private func configureAudioSession()'),
           method('public func play(resolve:'),
           method('public func setPlayWhenReady(playWhenReady:'),
           method('public func handleInterruption(type:')]
if 'private func playWithAudioSession()' in source:
    methods.append(method('private func playWithAudioSession()'))

harness = r'''
import Foundation

typealias RCTPromiseResolveBlock = (Any?) -> Void
typealias RCTPromiseRejectBlock = (String?, String?, Error?) -> Void
enum InterruptionType { case began; case ended(shouldResume: Bool) }
enum EventType { case RemoteDuck }
enum TestFailure: Error { case category }
final class AVAudioSession {
    enum Category { case playback }
    enum Mode { case `default` }
    enum RouteSharingPolicy { case `default` }
    struct CategoryOptions: OptionSet { let rawValue: Int }
    static let shared = AVAudioSession()
    static func sharedInstance() -> AVAudioSession { shared }
    var calls: [String] = []
    var rejectCategory = false
    func setCategory(_ category: Category, mode: Mode, options: CategoryOptions) throws {
        calls.append("category")
        if rejectCategory { throw TestFailure.category }
    }
    func setCategory(_ category: Category, mode: Mode, policy: RouteSharingPolicy, options: CategoryOptions) throws {
        try setCategory(category, mode: mode, options: options)
    }
}
final class SessionController {
    func activateSession() throws { AVAudioSession.shared.calls.append("activate") }
    func deactivateSession() throws { AVAudioSession.shared.calls.append("deactivate") }
}
final class Player {
    var currentItem: Int? = 1
    var onChange: (() -> Void)?
    var playWhenReady = false {
        didSet { if oldValue != playWhenReady { onChange?() } }
    }
    func play() { playWhenReady = true }
}
final class Module {
    let player = Player()
    let audioSessionController = SessionController()
    let sessionCategory = AVAudioSession.Category.playback
    let sessionCategoryMode = AVAudioSession.Mode.default
    let sessionCategoryPolicy = AVAudioSession.RouteSharingPolicy.default
    let sessionCategoryOptions = AVAudioSession.CategoryOptions(rawValue: 0)
    var shouldResumePlaybackAfterInterruptionEnds = false
    var events: [[String: Any]] = []
    init() { player.onChange = { [unowned self] in self.configureAudioSession() } }
    func rejectWhenNotInitialized(reject: RCTPromiseRejectBlock) -> Bool { false }
    func emit(event: EventType, body: Any? = nil) { events.append(body as? [String: Any] ?? [:]) }
__METHODS__
}
var failures = 0
var cases = 0
func check(_ name: String, _ condition: Bool) {
    cases += 1
    if condition { print("PASS: \(name)") }
    else { failures += 1; print("FAIL: \(name)") }
}
func fresh() -> Module {
    AVAudioSession.shared.calls = []
    AVAudioSession.shared.rejectCategory = false
    return Module()
}
let resolve: RCTPromiseResolveBlock = { _ in }
let reject: RCTPromiseRejectBlock = { _, _, _ in }
var m = fresh()
m.play(resolve: resolve, reject: reject)
check("first play configures the music category before activating", AVAudioSession.shared.calls == ["category", "activate"])
m = fresh(); m.player.playWhenReady = true; AVAudioSession.shared.calls = []
m.play(resolve: resolve, reject: reject)
check("repeated explicit play restores an interrupted session", AVAudioSession.shared.calls == ["category", "activate"])
m = fresh(); m.player.playWhenReady = true; AVAudioSession.shared.calls = []
m.setPlayWhenReady(playWhenReady: true, resolve: resolve, reject: reject)
check("repeated true setter restores the session", AVAudioSession.shared.calls == ["category", "activate"])
m = fresh(); AVAudioSession.shared.rejectCategory = true
m.play(resolve: resolve, reject: reject)
check("a rejected music category never activates the previous category", AVAudioSession.shared.calls == ["category"])
m = fresh(); m.player.playWhenReady = true; AVAudioSession.shared.calls = []
m.handleInterruption(type: .began)
check("interruption carries native play intent before JS pause races", m.events.last?["playWhenReady"] as? Bool == true)
m = fresh(); m.handleInterruption(type: .began)
check("an already paused interruption reports false play intent", m.events.last?["playWhenReady"] as? Bool == false)
m = fresh(); m.player.playWhenReady = true; AVAudioSession.shared.calls = []
m.shouldResumePlaybackAfterInterruptionEnds = true
m.handleInterruption(type: .ended(shouldResume: true))
check("native auto-resume restores a still-true play intent", AVAudioSession.shared.calls == ["category", "activate"])
m = fresh(); m.player.playWhenReady = true; AVAudioSession.shared.calls = []
m.setPlayWhenReady(playWhenReady: false, resolve: resolve, reject: reject)
check("explicit pause does not reactivate audio", AVAudioSession.shared.calls.isEmpty && !m.player.playWhenReady)
m = fresh(); m.player.currentItem = nil
m.play(resolve: resolve, reject: reject)
check("empty queue deactivates instead of acquiring audio", AVAudioSession.shared.calls == ["deactivate"])
m = fresh(); m.shouldResumePlaybackAfterInterruptionEnds = true
m.handleInterruption(type: .ended(shouldResume: false))
check("non-resumable interruption never activates audio", AVAudioSession.shared.calls.isEmpty && m.events.last?["permanent"] as? Bool == true)
print("\(cases - failures)/\(cases) checks passed")
exit(failures == 0 ? 0 : 1)
'''.replace('__METHODS__', '\n'.join(methods))
with tempfile.TemporaryDirectory(prefix='rntp-audio-session-') as temp:
    swift = Path(temp) / 'main.swift'
    binary = Path(temp) / 'audio-session-test'
    swift.write_text(harness)
    subprocess.run(['swiftc', str(swift), '-o', str(binary)], check=True)
    sys.exit(subprocess.run([str(binary)]).returncode)
