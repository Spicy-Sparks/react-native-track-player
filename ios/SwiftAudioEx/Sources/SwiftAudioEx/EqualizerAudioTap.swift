//
//  EqualizerAudioTap.swift
//  SwiftAudioEx
//
//  Professional 10-band parametric EQ with audio effects.
//
//  Quality features:
//  - Float64 arithmetic in all IIR paths (eliminates rounding artefacts)
//  - Per-sample coefficient smoothing, two poles (click-free parameter changes)
//  - Fades (~10 ms) when an effect is switched and between the chain and bypass
//  - DC blocking filter (removes sub-sonic drift from cascaded IIRs)
//  - Envelope-following true-peak limiter (transparent ceiling protection)
//  - All-pass-based stereo virtualizer (natural phase-width, no comb filtering)
//

import Foundation
import AVFoundation

public class EqualizerAudioTap: AudioTap {

    // MARK: - Constants

    /// 8-band equalizer frequencies (Hz), log-spaced for full-spectrum coverage
    public static let frequencies: [Float] = [
        60, 150, 400, 1000, 2500, 6000, 12000, 16000
    ]
    public static let bandCount = 8

    /// Q factor for peaking filters (standard for 1-octave graphic EQ)
    private static let eqQ: Double = 1.41

    /// Passthrough biquad coefficients [b0, b1, b2, a1, a2]
    private static let kUnity: [Double] = [1, 0, 0, 0, 0]

    /// One biquad's coefficients (b0, b1, b2, a1, a2) as designed, before they are written in place.
    private typealias Coeffs = (Double, Double, Double, Double, Double)

    // MARK: - Parameter Lock

    /// Single lock protecting all mutable parameters shared between threads
    private let paramLock = NSLock()

    // MARK: - EQ Bands

    /// Whether the 10-band EQ is active
    public var isEnabled: Bool = true

    /// Per-band gain in dB (-12 to +12), protected by paramLock
    private var _gains: [Float] = Array(repeating: 0, count: bandCount)
    private var needsEQUpdate: Bool = true

    /// True when at least one EQ band deviates from 0 dB. A flat curve is a
    /// mathematical passthrough (unity biquads), so it must not count as an
    /// active effect — see `process` bypass logic. Updated under paramLock.
    private var hasNonFlatGains: Bool = false

    // MARK: - Bass Boost (always-positive low shelf)

    public var isBassBoostEnabled: Bool = false
    public var bassBoostLevel: Float = 0.5
    private static let bbFreq: Double = 150
    private static let bbMinDB: Double = 6     // slider 0 = gentle warmth
    private static let bbMaxDB: Double = 24    // slider 1 = full boost
    private static let bbQ: Double = 0.8
    private var needsBBUpdate: Bool = true

    // MARK: - Loudness Enhancer (low shelf + high shelf)

    public var isLoudnessEnabled: Bool = false
    public var loudnessLevel: Float = 0.5
    private static let lnLoFreq: Double = 200
    private static let lnLoMaxDB: Double = 15
    private static let lnHiFreq: Double = 3000
    private static let lnHiMaxDB: Double = 10
    private static let lnQ: Double = 0.7
    private var needsLnUpdate: Bool = true

    // MARK: - Balance (L/R panning)

    public var balance: Float = 0.0  // -1.0 (full left) to 1.0 (full right)

    // MARK: - Virtualizer (cross-channel all-pass stereo widener)

    public var isVirtualizerEnabled: Bool = false
    public var virtualizerLevel: Float = 0.5
    /// All-pass center frequencies — different per channel for cross-channel decorrelation
    private static let apFreqsL: [Double] = [250, 630, 1500, 3200, 7500]
    private static let apFreqsR: [Double] = [160, 420, 1000, 2200, 5000, 9500]

    // MARK: - Audio-Thread State (only accessed from process callback)

    private var sampleRate: Double = 44100
    private var channelCount: Int = 2

    // Every biquad has its target coefficients, the coefficients in use (Cur) and the first of the
    // two smoothing poles they reach the target through (Mid): see biquadD. Plus its filter states.
    // EQ
    private var eqCur: [[Double]] = []     // [band][5]
    private var eqMid: [[Double]] = []     // [band][5]
    private var eqTgt: [[Double]] = []     // [band][5]
    private var eqZ: [[[Double]]] = []     // [band][channel][2] (z1,z2)

    // Bass boost
    private var bbCur: [Double] = kUnity
    private var bbMid: [Double] = kUnity
    private var bbTgt: [Double] = kUnity
    private var bbZ: [[Double]] = []       // [channel][2]

    // Loudness
    private var lnLoCur: [Double] = kUnity
    private var lnLoMid: [Double] = kUnity
    private var lnLoTgt: [Double] = kUnity
    private var lnHiCur: [Double] = kUnity
    private var lnHiMid: [Double] = kUnity
    private var lnHiTgt: [Double] = kUnity
    private var lnLoZ: [[Double]] = []     // [channel][2]
    private var lnHiZ: [[Double]] = []

    // Virtualizer: first-order all-pass coefficients and states (per channel)
    private var apCoeffsL: [Double] = []   // left channel sections
    private var apCoeffsR: [Double] = []   // right channel sections
    private var apStateL: [[Double]] = []  // [section][2: xPrev, yPrev]
    private var apStateR: [[Double]] = []  // [section][2: xPrev, yPrev]

    // DC blocker: y[n] = x[n] - x[n-1] + R*y[n-1]
    private static let dcR: Double = 0.9995 // ~3.5 Hz cutoff at 44.1 kHz
    private var dcXprev: [Double] = []     // per channel
    private var dcYprev: [Double] = []

    // Always-on transparent true-peak safety limiter.
    // Caps output peaks below full scale so the downstream Bluetooth/AAC encoder
    // (e.g. AirPods) cannot inter-sample clip on hot masters. It runs on EVERY
    // block — even when no EQ effect is active — but stays transparent below the
    // ceiling (gain == 1.0), so it never colours normal audio. The look-ahead
    // lets the gain ramp down BEFORE a peak reaches the output, avoiding the bass
    // distortion a zero-latency feedback limiter produces.
    private static let safetyCeiling: Double = 0.794   // ≈ -2 dBFS ceiling (head-room for AAC inter-sample overshoot)
    private var safetyLookahead: Int = 0
    private var safetyDelay: [[Double]] = []           // [channel][lookahead] circular delay line
    private var safetyPos: Int = 0
    private var safetyGain: Double = 1.0
    private var safetyAttCoeff: Double = 0              // computed from look-ahead length
    private var safetyRelCoeff: Double = 0              // computed from sample rate

    // Coefficient smoothing: per sample, each of the two poles (computed from sample rate)
    private var smoothAlpha: Double = 0.004

    // Frames left in the tail after all effects turned off. While the tail runs
    // the chain keeps processing so the effect switched off last fades out (see
    // eqMix); afterwards the chain crossfades into bit-perfect passthrough.
    private var bypassTailFrames: Int = 0

    // How much of the dry signal is in the output: 0 = the chain's output, 1 = bypass. It moves
    // toward its target by 1/fadeFrames per frame (~10 ms end to end), across buffers, both ways.
    // Switching straight to dry at the end of the tail jumped by whatever the chain still changed
    // (the DC blocker alone, on bass-heavy audio).
    private var fadeFrames: Int = 441
    private var bypassMix: Double = 1.0

    // Each effect fades in and out of the chain on its own: 0 = out, 1 = in, at the same rate.
    // Before, switching one effect while others kept playing cut its filter in or out at a buffer
    // boundary. While an effect is out its filter rests at its designed level with cleared state
    // (see restEffectsThatAreOut), so it fades back in at the level its slider shows.
    private var eqMix: Double = 0
    private var bbMix: Double = 0
    private var lnMix: Double = 0
    private var vzMix: Double = 0

    // The virtualizer level and the channel gains of the balance as applied. Each follows its
    // setting through two one-pole stages (so it starts and settles without a kink): a step moved
    // the side signal, or a whole channel's level, at once. ~2 ms each for the level, ~10 ms each
    // for the channel gains. The gains are smoothed, not the balance: the pan law min(1, 1∓b) has a
    // corner at the centre, so a smoothed balance crossing it put a kink in a channel's gain.
    private var levelCoeff: Double = 1
    private var balanceCoeff: Double = 1
    private var vzLevelA: Double = 0.5
    private var vzLevel: Double = 0.5
    private var gainLA: Double = 1
    private var gainL: Double = 1
    private var gainRA: Double = 1
    private var gainR: Double = 1

    // The switches as read at the start of the buffer being processed: the UI thread can flip them
    // halfway through one.
    private var eqOn = false
    private var bbOn = false
    private var lnOn = false
    private var vzOn = false

    // Dry copies for the fades, [channel][frame]. Grown when a bigger buffer arrives.
    private var chainDry: [[Float]] = []
    private var stageDry: [[Float]] = []

    // hasNonFlatGains as last read by the audio thread (see process: it does not wait for the lock).
    private var nonFlatGains = false

    // Tap lifecycle tracking
    private var activeTapCount: Int = 0

    // ════════════════════════ Public API ═════════════════════════

    public override init() {
        super.init()
    }

    /// Set gain for a specific band in dB (-12 to +12)
    public func setGain(band: Int, gainDB: Float) {
        guard band >= 0, band < Self.bandCount else { return }
        paramLock.lock()
        _gains[band] = max(-12, min(12, gainDB))
        hasNonFlatGains = _gains.contains { $0 != 0 }
        needsEQUpdate = true
        paramLock.unlock()
    }

    /// Set all 10 band gains at once
    public func setAllGains(_ gains: [Float]) {
        guard gains.count == Self.bandCount else { return }
        paramLock.lock()
        for i in 0..<Self.bandCount { _gains[i] = max(-12, min(12, gains[i])) }
        hasNonFlatGains = _gains.contains { $0 != 0 }
        needsEQUpdate = true
        paramLock.unlock()
    }

    /// Get current gain for a band
    public func getGain(band: Int) -> Float {
        guard band >= 0, band < Self.bandCount else { return 0 }
        paramLock.lock(); defer { paramLock.unlock() }
        return _gains[band]
    }

    /// Get all current gains
    public func getAllGains() -> [Float] {
        paramLock.lock(); defer { paramLock.unlock() }
        return _gains
    }

    /// Reset all gains to 0 dB (flat)
    public func resetGains() {
        paramLock.lock()
        _gains = Array(repeating: 0, count: Self.bandCount)
        hasNonFlatGains = false
        needsEQUpdate = true
        paramLock.unlock()
    }

    /// Update bass boost intensity (0.0 = 6 dB, 1.0 = 24 dB)
    public func updateBassBoostLevel(_ level: Float) {
        paramLock.lock()
        bassBoostLevel = max(0, min(1, level))
        needsBBUpdate = true
        paramLock.unlock()
    }

    /// Update loudness enhancer intensity (0.0 = off, 1.0 = full)
    public func updateLoudnessLevel(_ level: Float) {
        paramLock.lock()
        loudnessLevel = max(0, min(1, level))
        needsLnUpdate = true
        paramLock.unlock()
    }

    /// Update virtualizer width (0.0 = subtle, 1.0 = maximum)
    public func updateVirtualizerLevel(_ level: Float) {
        virtualizerLevel = max(0, min(1, level))
    }

    // ═══════════════════ AudioTap Lifecycle ══════════════════════

    public override func initialize() {
        activeTapCount += 1
    }

    public override func finalize() {
        activeTapCount -= 1
        if activeTapCount <= 0 {
            eqCur = []; eqMid = []; eqTgt = []; eqZ = []
            bbZ = []; lnLoZ = []; lnHiZ = []
            apStateL = []; apStateR = []; apCoeffsL = []; apCoeffsR = []
            dcXprev = []; dcYprev = []
            safetyDelay = []
            chainDry = []; stageDry = []
            activeTapCount = 0
        }
    }

    public override func prepare(description: AudioStreamBasicDescription) {
        sampleRate = Double(description.mSampleRate)
        channelCount = Int(description.mChannelsPerFrame)
        let ch = channelCount
        // Every coefficient set and filter state gets its own storage: the render thread writes them
        // in place (and now also while bypassed), and shared copy-on-write storage would be
        // allocated there at the first write.
        // (Not a [0, 0] literal: the optimizer turns it into one shared constant buffer.)
        func zStates(_ count: Int) -> [[Double]] { (0..<count).map { _ in [Double](repeating: 0, count: 2) } }
        func own(_ c: [Double]) -> [Double] { c.map { $0 } }

        // Coefficient smoothing: two poles of ~3 ms each, adapted to sample rate
        smoothAlpha = 1.0 - exp(-1.0 / (0.003 * sampleRate))

        // The targets are rewritten in place by process() (see setCoeffs), so they need storage of
        // their own too. Cur and Mid snap to them (no smoothing ramp on prepare).
        // ── EQ bands ──
        eqTgt = (0..<Self.bandCount).map { _ in own(Self.kUnity) }
        eqZ   = (0..<Self.bandCount).map { _ in zStates(ch) }
        refreshEQTargets()
        eqCur = eqTgt.map(own)
        eqMid = eqTgt.map(own)

        // ── Bass boost ──
        bbZ = zStates(ch)
        bbTgt = own(Self.kUnity)
        refreshBBTarget()
        bbCur = own(bbTgt)
        bbMid = own(bbTgt)

        // ── Loudness ──
        lnLoZ = zStates(ch)
        lnHiZ = zStates(ch)
        lnLoTgt = own(Self.kUnity)
        lnHiTgt = own(Self.kUnity)
        refreshLnTargets()
        lnLoCur = own(lnLoTgt)
        lnLoMid = own(lnLoTgt)
        lnHiCur = own(lnHiTgt)
        lnHiMid = own(lnHiTgt)

        // ── Virtualizer all-pass (cross-channel) ──
        apCoeffsL = Self.apFreqsL.map { f in
            let omega = tan(Double.pi * f / sampleRate)
            return (1.0 - omega) / (1.0 + omega)
        }
        apCoeffsR = Self.apFreqsR.map { f in
            let omega = tan(Double.pi * f / sampleRate)
            return (1.0 - omega) / (1.0 + omega)
        }
        apStateL = zStates(Self.apFreqsL.count)
        apStateR = zStates(Self.apFreqsR.count)

        // ── DC blocker ──
        dcXprev = Array(repeating: 0, count: ch)
        dcYprev = Array(repeating: 0, count: ch)

        // ── Transparent true-peak safety limiter ──
        safetyLookahead = max(32, Int(sampleRate * 0.0015))     // ~1.5 ms look-ahead
        safetyDelay = (0..<ch).map { _ in [Double](repeating: 0, count: safetyLookahead) }
        safetyPos = 0
        safetyGain = 1.0
        // Attack reaches the target within ~one look-ahead window so a peak is
        // fully tamed by the time it exits the delay line.
        safetyAttCoeff = 1.0 - exp(-3.0 / Double(safetyLookahead))
        safetyRelCoeff = 1.0 - exp(-1.0 / (0.100 * sampleRate))  // 100 ms release

        // ── Bypass tail and fades ──
        bypassTailFrames = 0
        fadeFrames = max(1, Int(sampleRate * 0.01))
        levelCoeff = 1.0 - exp(-1.0 / (0.002 * sampleRate))
        balanceCoeff = 1.0 - exp(-1.0 / (0.010 * sampleRate))
        paramLock.lock()
        nonFlatGains = hasNonFlatGains
        let nonFlat = nonFlatGains
        paramLock.unlock()
        let balance = Double(max(-1, min(1, self.balance)))
        let active = (isEnabled && nonFlat) || isBassBoostEnabled || isLoudnessEnabled
            || isVirtualizerEnabled || balance != 0
        bypassMix = active ? 0 : 1
        settleEffects(eq: isEnabled, bb: isBassBoostEnabled, ln: isLoudnessEnabled,
                      vz: isVirtualizerEnabled, balance: balance)
        // Separate storage for every channel of each copy: shared (copy-on-write) storage would be
        // detached, i.e. allocated, on the render thread at the first fade. Sized for the largest
        // buffer the tap was told to expect, so process() never has to grow them.
        let frames = max(4096, maxFrames)
        chainDry = (0..<ch).map { _ in [Float](repeating: 0, count: frames) }
        stageDry = (0..<ch).map { _ in [Float](repeating: 0, count: frames) }
    }

    public override func unprepare() {
        resetFilterState()
    }

    /// Zero all filter memory so the next processed buffer starts clean.
    /// Called on unprepare and when the smoothing tail ends after bypass.
    private func resetFilterState() {
        // In place: a new [0, 0] array per state allocated on the render thread.
        for b in 0..<eqZ.count {
            for c in 0..<eqZ[b].count { clearState(&eqZ[b][c]) }
        }
        for c in 0..<bbZ.count   { clearState(&bbZ[c]) }
        for c in 0..<lnLoZ.count { clearState(&lnLoZ[c]) }
        for c in 0..<lnHiZ.count { clearState(&lnHiZ[c]) }
        for s in 0..<apStateL.count { clearState(&apStateL[s]) }
        for s in 0..<apStateR.count { clearState(&apStateR[s]) }
        for c in 0..<dcXprev.count { dcXprev[c] = 0; dcYprev[c] = 0 }
    }

    // ══════════════════════ Processing ═══════════════════════════

    public override func process(numberOfFrames: Int, buffer: UnsafeMutableAudioBufferListPointer) {
        guard numberOfFrames > 0 else { return }

        let n = numberOfFrames
        let nCh = min(buffer.count, channelCount)

        // ── 1. Absorb pending parameter changes (briefly hold lock) ──
        // Also while bypassed, so the targets an effect is settled on are current. Never waits for
        // the UI thread: if a setter holds the lock, the changes are picked up on the next buffer.
        if paramLock.try() {
            if needsEQUpdate { refreshEQTargetsLocked(); needsEQUpdate = false }
            if needsBBUpdate { refreshBBTargetLocked();  needsBBUpdate = false }
            if needsLnUpdate { refreshLnTargetsLocked(); needsLnUpdate = false }
            nonFlatGains = hasNonFlatGains
            paramLock.unlock()
        }
        let nonFlat = nonFlatGains

        eqOn = isEnabled; bbOn = isBassBoostEnabled; lnOn = isLoudnessEnabled; vzOn = isVirtualizerEnabled
        let balTarget = Double(max(-1, min(1, balance)))

        // Only effects that actually modify the signal count as active. An
        // enabled EQ with a flat curve is unity passthrough, so it must NOT
        // pull the EQ chain / DC blocker in — they would colour pristine audio
        // for every user who never touched the equalizer. NOTE: the transparent
        // safety limiter at the end runs regardless (see applySafetyLimiter) to
        // protect the Bluetooth/AAC path from inter-sample clipping.
        let effectsActive = (eqOn && nonFlat) || bbOn || lnOn || vzOn || balTarget != 0

        // Run the colouring chain when effects are active, plus a short tail
        // afterwards (see bypassTailFrames). The chain and bypass crossfade
        // (see bypassMix): the chain keeps running until the mix is all dry.
        if effectsActive {
            bypassTailFrames = Int(sampleRate * 0.1) // ~100 ms
        } else if bypassTailFrames > 0 {
            bypassTailFrames = max(0, bypassTailFrames - n)
        }
        let mixTarget: Double = (effectsActive || bypassTailFrames > 0) ? 0 : 1
        let runChain = mixTarget == 0 || bypassMix < 1
        let mixing = bypassMix != mixTarget

        // Passthrough: skip the colouring chain but STILL apply the safety
        // limiter so hot masters can't inter-sample clip over Bluetooth/AAC.
        // Nothing is heard through the chain, so every effect jumps to its
        // settled state.
        if !runChain {
            settleEffects(eq: eqOn, bb: bbOn, ln: lnOn, vz: vzOn, balance: balTarget)
            applySafetyLimiter(numberOfFrames: n, buffer: buffer, nCh: nCh)
            return
        }
        restEffectsThatAreOut()
        if mixing { copyIn(buffer, nCh, n, into: &chainDry) }

        // ── 2–5. The filters, Float64. Their coefficients move toward the targets sample by sample
        // inside biquadD; afterwards they are advanced by the same n steps (advanceCoeffs). Moving
        // them once per buffer put the whole change of a large buffer at its first sample.
        let eqBands = min(Self.bandCount, eqCur.count, eqMid.count, eqTgt.count, eqZ.count)

        // ── 3. EQ bands (cascaded peaking filters) ──
        eqMix = stage(buffer, nCh, n, eqMix, eqOn) {
            for ch in 0..<nCh {
                guard let data = buffer[ch].mData else { continue }
                let samples = data.assumingMemoryBound(to: Float.self)
                for band in 0..<eqBands where ch < eqZ[band].count {
                    biquadD(samples, n, eqCur[band], eqMid[band], eqTgt[band], &eqZ[band][ch])
                }
            }
        }
        for band in 0..<eqBands { advanceCoeffs(&eqCur[band], &eqMid[band], eqTgt[band], n) }

        // ── 4. Bass boost (low shelf) ──
        bbMix = stage(buffer, nCh, n, bbMix, bbOn) {
            for ch in 0..<nCh {
                guard ch < bbZ.count, let data = buffer[ch].mData else { continue }
                biquadD(data.assumingMemoryBound(to: Float.self), n, bbCur, bbMid, bbTgt, &bbZ[ch])
            }
        }
        advanceCoeffs(&bbCur, &bbMid, bbTgt, n)

        // ── 5. Loudness enhancer (low shelf + high shelf) ──
        lnMix = stage(buffer, nCh, n, lnMix, lnOn) {
            for ch in 0..<nCh {
                guard ch < lnLoZ.count, ch < lnHiZ.count,
                      let data = buffer[ch].mData else { continue }
                let samples = data.assumingMemoryBound(to: Float.self)
                biquadD(samples, n, lnLoCur, lnLoMid, lnLoTgt, &lnLoZ[ch])
                biquadD(samples, n, lnHiCur, lnHiMid, lnHiTgt, &lnHiZ[ch])
            }
        }
        advanceCoeffs(&lnLoCur, &lnLoMid, lnLoTgt, n)
        advanceCoeffs(&lnHiCur, &lnHiMid, lnHiTgt, n)

        // ── 6. Virtualizer (mid-side + cross-channel all-pass, stereo only) ──
        if nCh >= 2
            && apCoeffsL.count == Self.apFreqsL.count
            && apCoeffsR.count == Self.apFreqsR.count,
           let lD = buffer[0].mData, let rD = buffer[1].mData
        {
            let L = lD.assumingMemoryBound(to: Float.self)
            let R = rD.assumingMemoryBound(to: Float.self)
            vzMix = stage(buffer, nCh, n, vzMix, vzOn) { applyVirtualizer(L, R, n) }
        } else {
            vzMix = vzOn ? 1 : 0
        }

        // ── 7. DC blocker (removes sub-sonic drift, all active channels) ──
        for ch in 0..<nCh {
            guard ch < dcXprev.count, let data = buffer[ch].mData else { continue }
            let samples = data.assumingMemoryBound(to: Float.self)
            var xp = dcXprev[ch], yp = dcYprev[ch]
            let r = Self.dcR
            for i in 0..<n {
                let x = Double(samples[i])
                let y = x - xp + r * yp
                xp = x; yp = y
                samples[i] = Float(y)
            }
            dcXprev[ch] = xp; dcYprev[ch] = yp
        }

        // ── 8. Balance (L/R panning, stereo only) ──
        // Each channel's gain follows the pan law's value for the balance set (see gainL).
        let tgtL = Self.panGainL(balTarget), tgtR = Self.panGainR(balTarget)
        if nCh >= 2 && (tgtL != 1 || tgtR != 1 || gainLA != 1 || gainL != 1 || gainRA != 1 || gainR != 1),
           let lD = buffer[0].mData, let rD = buffer[1].mData
        {
            let L = lD.assumingMemoryBound(to: Float.self)
            let R = rD.assumingMemoryBound(to: Float.self)
            let k = balanceCoeff
            var la = gainLA, l = gainL, ra = gainRA, r = gainR
            for i in 0..<n {
                la += k * (tgtL - la)
                l += k * (la - l)
                ra += k * (tgtR - ra)
                r += k * (ra - r)
                L[i] = Float(Double(L[i]) * l)
                R[i] = Float(Double(R[i]) * r)
            }
            // Back at the centre: stop applying a gain a hair under 1.
            if tgtL == 1 && tgtR == 1
                && abs(1 - la) < 1e-6 && abs(1 - l) < 1e-6 && abs(1 - ra) < 1e-6 && abs(1 - r) < 1e-6 {
                la = 1; l = 1; ra = 1; r = 1
            }
            gainLA = la; gainL = l; gainRA = ra; gainR = r
        }

        // ── Crossfade with the dry input while entering or leaving bypass ──
        if mixing {
            crossfadeWithDry(buffer, nCh, n, target: mixTarget)
            if bypassMix >= 1 { resetFilterState() }
        }

        // ── 9. Transparent true-peak safety limiter (also runs on passthrough) ──
        applySafetyLimiter(numberOfFrames: n, buffer: buffer, nCh: nCh)
    }

    // ═══════════════ Internal: Fades ═══════════════

    /// The balance's pan law: each channel at full level up to the centre, then down to 0 at the far side.
    private static func panGainL(_ b: Double) -> Double { min(1.0, 1.0 - b) }
    private static func panGainR(_ b: Double) -> Double { min(1.0, 1.0 + b) }

    /// The fades move linearly but are heard through this S-curve (smoothstep):
    /// flat at both ends, so a fade does not start or stop with a kink.
    private static func sCurve(_ x: Double) -> Double { x * x * (3 - 2 * x) }

    /// `mix` moved `frames` steps of 1/fadeFrames toward `target`.
    private func advance(_ mix: Double, toward target: Double, frames: Int) -> Double {
        let step = 1.0 / Double(fadeFrames)
        var m = mix
        for _ in 0..<frames {
            if m == target { break }
            m = target > m ? min(target, m + step) : max(target, m - step)
        }
        return m
    }

    /// Runs one effect in place, faded by its mix, and returns the new mix. All in:
    /// the effect alone. All out: skipped. In between, the effect's output is blended
    /// with its input while the mix moves one step per frame toward the switch.
    private func stage(_ buffer: UnsafeMutableAudioBufferListPointer, _ nCh: Int, _ n: Int,
                       _ mix: Double, _ on: Bool, _ effect: () -> Void) -> Double {
        let target: Double = on ? 1 : 0
        if mix == target {
            if on { effect() }
            return mix
        }
        copyIn(buffer, nCh, n, into: &stageDry)
        effect()
        let step = 1.0 / Double(fadeFrames)
        for ch in 0..<nCh {
            guard ch < stageDry.count, let data = buffer[ch].mData else { continue }
            let samples = data.assumingMemoryBound(to: Float.self)
            stageDry[ch].withUnsafeBufferPointer { dry in
                var m = mix
                for i in 0..<n {
                    m = target > m ? min(target, m + step) : max(target, m - step)
                    let w = Self.sCurve(m)
                    let d = Double(dry[i])
                    samples[i] = Float(d + (Double(samples[i]) - d) * w)
                }
            }
        }
        return advance(mix, toward: target, frames: n)
    }

    /// Blends the chain's output with the dry input by bypassMix, moving it toward `target`.
    private func crossfadeWithDry(_ buffer: UnsafeMutableAudioBufferListPointer, _ nCh: Int, _ n: Int,
                                  target: Double) {
        let step = 1.0 / Double(fadeFrames)
        let start = bypassMix
        for ch in 0..<nCh {
            guard ch < chainDry.count, let data = buffer[ch].mData else { continue }
            let samples = data.assumingMemoryBound(to: Float.self)
            chainDry[ch].withUnsafeBufferPointer { dry in
                var m = start
                for i in 0..<n {
                    m = target > m ? min(target, m + step) : max(target, m - step)
                    let w = Self.sCurve(m)
                    samples[i] = Float(Double(samples[i]) * (1 - w) + Double(dry[i]) * w)
                }
            }
        }
        bypassMix = advance(start, toward: target, frames: n)
    }

    /// Copies the first `n` frames of every channel into `dst`, growing it if needed.
    private func copyIn(_ buffer: UnsafeMutableAudioBufferListPointer, _ nCh: Int, _ n: Int,
                        into dst: inout [[Float]]) {
        if dst.count < nCh {
            dst.append(contentsOf: [[Float]](repeating: [], count: nCh - dst.count))
        }
        for ch in 0..<nCh {
            if dst[ch].count < n { dst[ch] = [Float](repeating: 0, count: max(n, 4096)) }
            guard let data = buffer[ch].mData else { continue }
            let src = data.assumingMemoryBound(to: Float.self)
            dst[ch].withUnsafeMutableBufferPointer { d in
                d.baseAddress!.update(from: src, count: n)
            }
        }
    }

    /// An effect that is out of the chain rests at its designed level with cleared
    /// state, so it fades back in at the level its slider shows instead of where it
    /// was last left.
    private func restEffectsThatAreOut() {
        if !eqOn && eqMix == 0 {
            settleEQCoeffs()
            for b in 0..<eqZ.count { for c in 0..<eqZ[b].count { clearState(&eqZ[b][c]) } }
        }
        if !bbOn && bbMix == 0 {
            copyCoeffs(&bbCur, bbTgt); copyCoeffs(&bbMid, bbTgt)
            for c in 0..<bbZ.count { clearState(&bbZ[c]) }
        }
        if !lnOn && lnMix == 0 {
            settleLnCoeffs()
            for c in 0..<lnLoZ.count { clearState(&lnLoZ[c]) }
            for c in 0..<lnHiZ.count { clearState(&lnHiZ[c]) }
        }
        if !vzOn && vzMix == 0 {
            vzLevelA = Double(virtualizerLevel); vzLevel = vzLevelA
            for s in 0..<apStateL.count { clearState(&apStateL[s]) }
            for s in 0..<apStateR.count { clearState(&apStateR[s]) }
        }
    }

    /// Puts every effect where its switch and level say, with no fade: for when
    /// nothing is heard through the chain (bypassed) or the stream starts over.
    private func settleEffects(eq: Bool, bb: Bool, ln: Bool, vz: Bool, balance: Double) {
        eqMix = eq ? 1 : 0
        bbMix = bb ? 1 : 0
        lnMix = ln ? 1 : 0
        vzMix = vz ? 1 : 0
        settleEQCoeffs()
        copyCoeffs(&bbCur, bbTgt); copyCoeffs(&bbMid, bbTgt)
        settleLnCoeffs()
        vzLevelA = Double(virtualizerLevel); vzLevel = vzLevelA
        gainLA = Self.panGainL(balance); gainL = gainLA
        gainRA = Self.panGainR(balance); gainR = gainRA
    }

    private func settleEQCoeffs() {
        for b in 0..<min(eqCur.count, eqMid.count, eqTgt.count) {
            copyCoeffs(&eqCur[b], eqTgt[b]); copyCoeffs(&eqMid[b], eqTgt[b])
        }
    }

    private func settleLnCoeffs() {
        copyCoeffs(&lnLoCur, lnLoTgt); copyCoeffs(&lnLoMid, lnLoTgt)
        copyCoeffs(&lnHiCur, lnHiTgt); copyCoeffs(&lnHiMid, lnHiTgt)
    }

    /// Element by element, so the audio thread does not allocate a new array, and only where a
    /// value changes: writing to storage still shared with another array would detach (allocate) it.
    private func copyCoeffs(_ cur: inout [Double], _ tgt: [Double]) {
        for k in 0..<min(cur.count, tgt.count) where cur[k] != tgt[k] { cur[k] = tgt[k] }
    }

    private func clearState(_ z: inout [Double]) {
        for k in 0..<z.count where z[k] != 0 { z[k] = 0 }
    }

    /// Mid-side widening plus cross-channel all-pass decorrelation, at the
    /// smoothed virtualizer level.
    private func applyVirtualizer(_ L: UnsafeMutablePointer<Float>, _ R: UnsafeMutablePointer<Float>, _ n: Int) {
        let target = Double(virtualizerLevel)
        let k = levelCoeff
        var lvlA = vzLevelA, lvl = vzLevel

        for i in 0..<n {
            lvlA += k * (target - lvlA)
            lvl += k * (lvlA - lvl)
            let width = 1.0 + lvl * 1.5    // 1.0 (normal) to 2.5 (wide)
            let apMix = 0.5 + lvl * 0.5    // 0.5 (moderate) to 1.0 (full phase shift)
            let l = Double(L[i]), r = Double(R[i])

            // Mid-side decomposition + widening
            let mid  = (l + r) * 0.5
            let side = (l - r) * 0.5
            let wL = mid + side * width
            let wR = mid - side * width

            // Cascade all-pass on LEFT channel (creates phase shift at apFreqsL)
            var sigL = wL
            for s in 0..<apCoeffsL.count {
                let a = apCoeffsL[s]
                let y = a * sigL + apStateL[s][0] - a * apStateL[s][1]
                apStateL[s][0] = sigL
                apStateL[s][1] = y
                sigL = y
            }

            // Cascade all-pass on RIGHT channel (different frequencies → apFreqsR)
            var sigR = wR
            for s in 0..<apCoeffsR.count {
                let a = apCoeffsR[s]
                let y = a * sigR + apStateR[s][0] - a * apStateR[s][1]
                apStateR[s][0] = sigR
                apStateR[s][1] = y
                sigR = y
            }

            // Cross-channel decorrelation: each channel gets its own phase-shifted version
            L[i] = Float(wL * (1.0 - apMix) + sigL * apMix)
            R[i] = Float(wR * (1.0 - apMix) + sigR * apMix)
        }
        vzLevelA = lvlA; vzLevel = lvl
    }

    /// Transparent look-ahead peak limiter. Linked across channels (one shared
    /// gain preserves the stereo image). Below the ceiling the gain stays at 1.0
    /// so it does not colour the signal; the look-ahead delay lets the gain ramp
    /// down before a peak reaches the output, taming loud transients without the
    /// harmonic distortion a zero-latency feedback limiter adds to bass. Runs on
    /// every block (effects on or off) to guarantee head-room for the downstream
    /// Bluetooth/AAC encoder.
    private func applySafetyLimiter(numberOfFrames: Int, buffer: UnsafeMutableAudioBufferListPointer, nCh: Int) {
        guard safetyLookahead > 0, nCh > 0, safetyDelay.count >= nCh else { return }
        let ceil = Self.safetyCeiling
        let attC = safetyAttCoeff, relC = safetyRelCoeff

        // The channel pointers live on the stack: an array here was a heap allocation on every
        // render callback.
        withUnsafeTemporaryAllocation(of: UnsafeMutablePointer<Float>.self, capacity: nCh) { ptrs in
            for ch in 0..<nCh {
                guard let data = buffer[ch].mData else { return }
                (ptrs.baseAddress! + ch).initialize(to: data.assumingMemoryBound(to: Float.self))
            }

            var pos = safetyPos
            var g = safetyGain
            for i in 0..<numberOfFrames {
                // Future peak across channels (the sample about to enter the delay).
                var peak = 0.0
                for ch in 0..<nCh { peak = max(peak, abs(Double(ptrs[ch][i]))) }
                let tgt = peak > ceil ? ceil / peak : 1.0
                // Fast (attack) when reducing gain, slow (release) when recovering.
                g += (tgt - g) * (tgt < g ? attC : relC)

                for ch in 0..<nCh {
                    let delayed = safetyDelay[ch][pos]          // output: input from `lookahead` frames ago
                    safetyDelay[ch][pos] = Double(ptrs[ch][i])  // store current input
                    ptrs[ch][i] = Float(delayed * g)
                }
                pos += 1
                if pos >= safetyLookahead { pos = 0 }
            }
            safetyPos = pos
            safetyGain = g
        }
    }

    // ═══════════════ Internal: Biquad Processing ════════════════

    /// Process Float32 samples through a biquad filter using Float64 arithmetic.
    /// Direct Form II Transposed for numerical stability.
    ///
    /// While its coefficients `c` are not yet at the target `t`, they move toward it every sample
    /// through two one-pole stages (`m` is the first): smoothly, with no kink where a change starts.
    /// The caller then advances the stored coefficients by the same steps (advanceCoeffs), so every
    /// channel runs the same trajectory and the next buffer continues it.
    private func biquadD(
        _ samples: UnsafeMutablePointer<Float>, _ count: Int,
        _ c: [Double], _ m: [Double], _ t: [Double], _ z: inout [Double]
    ) {
        guard c.count >= 5, m.count >= 5, t.count >= 5, z.count >= 2 else { return }
        var b0 = c[0], b1 = c[1], b2 = c[2], a1 = c[3], a2 = c[4]
        var z1 = z[0], z2 = z[1]

        if Self.settled(c, m, t) {
            for i in 0..<count {
                let x = Double(samples[i])
                let y = b0 * x + z1
                z1 = b1 * x - a1 * y + z2
                z2 = b2 * x - a2 * y
                samples[i] = Float(y)
            }
        } else {
            let k = smoothAlpha
            let t0 = t[0], t1 = t[1], t2 = t[2], t3 = t[3], t4 = t[4]
            var m0 = m[0], m1 = m[1], m2 = m[2], m3 = m[3], m4 = m[4]
            for i in 0..<count {
                m0 += k * (t0 - m0); b0 += k * (m0 - b0)
                m1 += k * (t1 - m1); b1 += k * (m1 - b1)
                m2 += k * (t2 - m2); b2 += k * (m2 - b2)
                m3 += k * (t3 - m3); a1 += k * (m3 - a1)
                m4 += k * (t4 - m4); a2 += k * (m4 - a2)
                let x = Double(samples[i])
                let y = b0 * x + z1
                z1 = b1 * x - a1 * y + z2
                z2 = b2 * x - a2 * y
                samples[i] = Float(y)
            }
        }

        // Flush denormals to zero (prevents FPU slowdown on some architectures)
        if z1.magnitude < 1e-15 { z1 = 0 }
        if z2.magnitude < 1e-15 { z2 = 0 }

        z[0] = z1; z[1] = z2
    }

    // ═══════════════ Internal: Coefficient Smoothing ════════════

    private static func settled(_ c: [Double], _ m: [Double], _ t: [Double]) -> Bool {
        c[0] == t[0] && c[1] == t[1] && c[2] == t[2] && c[3] == t[3] && c[4] == t[4]
            && m[0] == t[0] && m[1] == t[1] && m[2] == t[2] && m[3] == t[3] && m[4] == t[4]
    }

    /// Moves a biquad's coefficients `n` samples toward the target: the steps biquadD took inside
    /// the buffer. Once they are within 1e-9 of it (far below anything a Float sample can show, and
    /// above where the steps stop moving them at any sample rate) they snap onto it, and biquadD goes
    /// back to fixed coefficients. In place: the render thread must not allocate.
    private func advanceCoeffs(_ c: inout [Double], _ m: inout [Double], _ t: [Double], _ n: Int) {
        guard c.count >= 5, m.count >= 5, t.count >= 5, !Self.settled(c, m, t) else { return }
        let k = smoothAlpha
        let t0 = t[0], t1 = t[1], t2 = t[2], t3 = t[3], t4 = t[4]
        var m0 = m[0], m1 = m[1], m2 = m[2], m3 = m[3], m4 = m[4]
        var c0 = c[0], c1 = c[1], c2 = c[2], c3 = c[3], c4 = c[4]
        for _ in 0..<n {
            m0 += k * (t0 - m0); c0 += k * (m0 - c0)
            m1 += k * (t1 - m1); c1 += k * (m1 - c1)
            m2 += k * (t2 - m2); c2 += k * (m2 - c2)
            m3 += k * (t3 - m3); c3 += k * (m3 - c3)
            m4 += k * (t4 - m4); c4 += k * (m4 - c4)
        }
        let eps = 1e-9
        if abs(t0 - c0) < eps && abs(t1 - c1) < eps && abs(t2 - c2) < eps && abs(t3 - c3) < eps
            && abs(t4 - c4) < eps && abs(t0 - m0) < eps && abs(t1 - m1) < eps && abs(t2 - m2) < eps
            && abs(t3 - m3) < eps && abs(t4 - m4) < eps {
            copyCoeffs(&c, t); copyCoeffs(&m, t)
            return
        }
        c[0] = c0; c[1] = c1; c[2] = c2; c[3] = c3; c[4] = c4
        m[0] = m0; m[1] = m1; m[2] = m2; m[3] = m3; m[4] = m4
    }

    /// Writes a designed coefficient set into `dst` in place: process() calls this when a level
    /// changes, and a new array would be allocated on the render thread.
    private func setCoeffs(_ dst: inout [Double], _ c: Coeffs) {
        guard dst.count >= 5 else { return }
        if dst[0] != c.0 { dst[0] = c.0 }
        if dst[1] != c.1 { dst[1] = c.1 }
        if dst[2] != c.2 { dst[2] = c.2 }
        if dst[3] != c.3 { dst[3] = c.3 }
        if dst[4] != c.4 { dst[4] = c.4 }
    }

    // ═══════════════ Internal: Target Coefficient Calc ══════════

    private func refreshEQTargets() {
        paramLock.lock()
        refreshEQTargetsLocked()
        needsEQUpdate = false  // just rebuilt from the current values: process() need not redo it
        paramLock.unlock()
    }

    /// Must be called with paramLock held
    private func refreshEQTargetsLocked() {
        for i in 0..<min(Self.bandCount, eqTgt.count, _gains.count) {
            setCoeffs(&eqTgt[i], Self.peakingEQ(
                freq: Double(Self.frequencies[i]),
                gainDB: Double(_gains[i]),
                q: Self.eqQ, sr: sampleRate
            ))
        }
    }

    private func refreshBBTarget() {
        paramLock.lock()
        refreshBBTargetLocked()
        needsBBUpdate = false  // just rebuilt from the current values: process() need not redo it
        paramLock.unlock()
    }

    /// Must be called with paramLock held
    private func refreshBBTargetLocked() {
        let g = Self.bbMinDB + (Self.bbMaxDB - Self.bbMinDB) * Double(bassBoostLevel)
        setCoeffs(&bbTgt, Self.lowShelf(freq: Self.bbFreq, gainDB: g, q: Self.bbQ, sr: sampleRate))
    }

    private func refreshLnTargets() {
        paramLock.lock()
        refreshLnTargetsLocked()
        needsLnUpdate = false  // just rebuilt from the current values: process() need not redo it
        paramLock.unlock()
    }

    /// Must be called with paramLock held
    private func refreshLnTargetsLocked() {
        let lo = Self.lnLoMaxDB * Double(loudnessLevel)
        let hi = Self.lnHiMaxDB * Double(loudnessLevel)
        setCoeffs(&lnLoTgt, Self.lowShelf(freq: Self.lnLoFreq,  gainDB: lo, q: Self.lnQ, sr: sampleRate))
        setCoeffs(&lnHiTgt, Self.highShelf(freq: Self.lnHiFreq, gainDB: hi, q: Self.lnQ, sr: sampleRate))
    }

    // ═══════════════ Filter Design (Audio EQ Cookbook) ═══════════
    // All calculations in Float64 for maximum precision.
    // Reference: Robert Bristow-Johnson's Audio EQ Cookbook
    // At 0 dB every design is still the filter, not [1, 0, 0, 0, 0]: numerator and denominator come
    // out identical, so it passes the signal through exactly, and a level change toward or away from
    // 0 dB moves between two filters of the same shape. Smoothing toward [1, 0, 0, 0, 0] dragged the
    // poles across the plane on the way (hundreds of LSB on a loudness change to 0).

    /// Peaking EQ (parametric bell)
    private static func peakingEQ(freq: Double, gainDB: Double, q: Double, sr: Double) -> Coeffs {
        let A  = pow(10.0, gainDB / 40.0)
        let w0 = 2.0 * Double.pi * freq / sr
        let cw = cos(w0), sw = sin(w0)
        let al = sw / (2.0 * q)

        let b0 =  1.0 + al * A
        let b1 = -2.0 * cw
        let b2 =  1.0 - al * A
        let a0 =  1.0 + al / A
        let a1 = -2.0 * cw
        let a2 =  1.0 - al / A

        return (b0/a0, b1/a0, b2/a0, a1/a0, a2/a0)
    }

    /// Low-shelf filter
    private static func lowShelf(freq: Double, gainDB: Double, q: Double, sr: Double) -> Coeffs {
        let A  = pow(10.0, gainDB / 40.0)
        let w0 = 2.0 * Double.pi * freq / sr
        let cw = cos(w0), sw = sin(w0)
        let al = sw / (2.0 * q)
        let s2a = 2.0 * sqrt(A) * al

        let b0 = A * ((A + 1) - (A - 1) * cw + s2a)
        let b1 = 2 * A * ((A - 1) - (A + 1) * cw)
        let b2 = A * ((A + 1) - (A - 1) * cw - s2a)
        let a0 = (A + 1) + (A - 1) * cw + s2a
        let a1 = -2 * ((A - 1) + (A + 1) * cw)
        let a2 = (A + 1) + (A - 1) * cw - s2a

        return (b0/a0, b1/a0, b2/a0, a1/a0, a2/a0)
    }

    /// High-shelf filter
    private static func highShelf(freq: Double, gainDB: Double, q: Double, sr: Double) -> Coeffs {
        let A  = pow(10.0, gainDB / 40.0)
        let w0 = 2.0 * Double.pi * freq / sr
        let cw = cos(w0), sw = sin(w0)
        let al = sw / (2.0 * q)
        let s2a = 2.0 * sqrt(A) * al

        let b0 = A * ((A + 1) + (A - 1) * cw + s2a)
        let b1 = -2 * A * ((A - 1) + (A + 1) * cw)
        let b2 = A * ((A + 1) + (A - 1) * cw - s2a)
        let a0 = (A + 1) - (A - 1) * cw + s2a
        let a1 = 2 * ((A - 1) - (A + 1) * cw)
        let a2 = (A + 1) - (A - 1) * cw - s2a

        return (b0/a0, b1/a0, b2/a0, a1/a0, a2/a0)
    }
}

// MARK: - Preset Support

extension EqualizerAudioTap {

    /// Predefined equalizer presets
    public enum Preset: String, CaseIterable {
        case acoustic = "eqAcoustic"
        case bass = "eqBassBooster"
        case bassReducer = "eqBassReducer"
        case classical = "eqClassical"
        case dance = "eqDance"
        case deep = "eqDeep"
        case electronic = "eqElectronic"
        case flat = "eqFlat"
        case hiphop = "eqHipHop"
        case jazz = "eqJazz"
        case latin = "eqLatin"
        case loudness = "eqLoudness"
        case lounge = "eqLounge"
        case piano = "eqPiano"
        case pop = "eqPop"
        case rnb = "eqRnb"
        case rock = "eqRock"
        case smallSpeakers = "eqSmallSpeakers"
        case spokenWord = "eqSpokenWord"
        case treble = "eqTrebleBooster"
        case trebleReducer = "eqTrebleReducer"
        case vocal = "eqVocalBooster"

        /// Gain values per preset (8 bands: 60, 150, 400, 1K, 2.5K, 6K, 12K, 16K)
        public var gains: [Float] {
            switch self {
            case .acoustic:
                return [ 3,  2,  1,  0,  1,  2,  2,  2]
            case .bass:
                return [ 6,  5,  3,  1,  0,  0,  0,  0]
            case .bassReducer:
                return [-6, -5, -3, -1,  0,  0,  0,  0]
            case .classical:
                return [ 4,  2,  0, -1,  0,  2,  3,  3]
            case .dance:
                return [ 5,  4,  1,  0,  2,  4,  3,  2]
            case .deep:
                return [ 5,  4,  2,  1,  0, -1, -2, -3]
            case .electronic:
                return [ 4,  3,  0, -1,  0,  3,  4,  4]
            case .flat:
                return [ 0,  0,  0,  0,  0,  0,  0,  0]
            case .hiphop:
                return [ 5,  4,  2,  0, -1,  1,  2,  3]
            case .jazz:
                return [ 3,  2,  1,  0, -1,  1,  2,  2]
            case .latin:
                return [ 4,  3,  0, -1,  0,  2,  4,  3]
            case .loudness:
                return [ 5,  3,  0, -2,  0,  2,  4,  4]
            case .lounge:
                return [-1,  1,  2,  1,  0, -1,  1,  1]
            case .piano:
                return [ 1,  0,  1,  2,  3,  2,  2,  1]
            case .pop:
                return [-1,  1,  3,  3,  2,  1,  1,  1]
            case .rnb:
                return [ 5,  4,  2,  0, -1,  1,  2,  2]
            case .rock:
                return [ 4,  3,  0, -1,  1,  3,  4,  3]
            case .smallSpeakers:
                return [ 5,  4,  3,  1,  0, -1,  2,  3]
            case .spokenWord:
                return [-2,  0,  1,  3,  4,  3,  1, -1]
            case .treble:
                return [ 0,  0,  0,  0,  1,  3,  5,  6]
            case .trebleReducer:
                return [ 0,  0,  0,  0, -1, -3, -5, -6]
            case .vocal:
                return [-2, -1,  1,  3,  4,  3,  1,  0]
            }
        }
    }

    /// Apply a built-in preset
    public func applyPreset(_ preset: Preset) {
        setAllGains(preset.gains)
    }

    /// Get all available preset names
    public static var presetNames: [String] {
        return Preset.allCases.map { $0.rawValue }
    }
}
