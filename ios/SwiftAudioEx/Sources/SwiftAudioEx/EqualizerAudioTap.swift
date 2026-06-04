//
//  EqualizerAudioTap.swift
//  SwiftAudioEx
//
//  Professional 10-band parametric EQ with audio effects.
//
//  Quality features:
//  - Float64 arithmetic in all IIR paths (eliminates rounding artefacts)
//  - Per-block coefficient smoothing (click-free parameter changes)
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

    // EQ: current (smoothed) and target coefficients, plus filter states
    private var eqCur: [[Double]] = []     // [band][5]
    private var eqTgt: [[Double]] = []     // [band][5]
    private var eqZ: [[[Double]]] = []     // [band][channel][2] (z1,z2)

    // Bass boost
    private var bbCur: [Double] = kUnity
    private var bbTgt: [Double] = kUnity
    private var bbZ: [[Double]] = []       // [channel][2]

    // Loudness
    private var lnLoCur: [Double] = kUnity
    private var lnLoTgt: [Double] = kUnity
    private var lnHiCur: [Double] = kUnity
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

    // Envelope-following peak limiter
    private static let limThreshold: Double = 0.89  // ~ -1 dBFS
    private var limGain: Double = 1.0
    private var limAttCoeff: Double = 0    // computed from sample rate
    private var limRelCoeff: Double = 0

    // Coefficient smoothing alpha (computed from sample rate)
    private var smoothAlpha: Double = 0.004

    // Frames left in the smoothing tail after all effects turned off. While the
    // tail runs the chain keeps processing so coefficients can ramp to unity
    // (click-free); afterwards the tap hard-bypasses (bit-perfect passthrough).
    private var bypassTailFrames: Int = 0

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
            eqCur = []; eqTgt = []; eqZ = []
            bbZ = []; lnLoZ = []; lnHiZ = []
            apStateL = []; apStateR = []; apCoeffsL = []; apCoeffsR = []
            dcXprev = []; dcYprev = []
            activeTapCount = 0
        }
    }

    public override func prepare(description: AudioStreamBasicDescription) {
        sampleRate = Double(description.mSampleRate)
        channelCount = Int(description.mChannelsPerFrame)
        let ch = channelCount
        let z2 = [Double](repeating: 0, count: 2)

        // Smoothing alpha: ~5 ms time constant, adapted to sample rate
        smoothAlpha = 1.0 - exp(-1.0 / (0.005 * sampleRate))

        // ── EQ bands ──
        eqCur = Array(repeating: Self.kUnity, count: Self.bandCount)
        eqTgt = Array(repeating: Self.kUnity, count: Self.bandCount)
        eqZ   = Array(repeating: Array(repeating: z2, count: ch), count: Self.bandCount)
        refreshEQTargets()
        eqCur = eqTgt  // snap to initial values (no smoothing ramp on first prepare)

        // ── Bass boost ──
        bbZ = Array(repeating: z2, count: ch)
        refreshBBTarget()
        bbCur = bbTgt

        // ── Loudness ──
        lnLoZ = Array(repeating: z2, count: ch)
        lnHiZ = Array(repeating: z2, count: ch)
        refreshLnTargets()
        lnLoCur = lnLoTgt
        lnHiCur = lnHiTgt

        // ── Virtualizer all-pass (cross-channel) ──
        apCoeffsL = Self.apFreqsL.map { f in
            let omega = tan(Double.pi * f / sampleRate)
            return (1.0 - omega) / (1.0 + omega)
        }
        apCoeffsR = Self.apFreqsR.map { f in
            let omega = tan(Double.pi * f / sampleRate)
            return (1.0 - omega) / (1.0 + omega)
        }
        apStateL = Array(repeating: z2, count: Self.apFreqsL.count)
        apStateR = Array(repeating: z2, count: Self.apFreqsR.count)

        // ── DC blocker ──
        dcXprev = Array(repeating: 0, count: ch)
        dcYprev = Array(repeating: 0, count: ch)

        // ── Limiter ──
        limGain = 1.0
        limAttCoeff = 1.0 - exp(-1.0 / (0.0005 * sampleRate))  // 0.5 ms attack
        limRelCoeff = 1.0 - exp(-1.0 / (0.050  * sampleRate))   // 50 ms release

        // ── Bypass tail ──
        bypassTailFrames = 0
    }

    public override func unprepare() {
        resetFilterState()
    }

    /// Zero all filter memory so the next processed buffer starts clean.
    /// Called on unprepare and when the smoothing tail ends after bypass.
    private func resetFilterState() {
        for b in 0..<eqZ.count {
            for c in 0..<eqZ[b].count { eqZ[b][c] = [0, 0] }
        }
        for c in 0..<bbZ.count   { bbZ[c]   = [0, 0] }
        for c in 0..<lnLoZ.count { lnLoZ[c] = [0, 0] }
        for c in 0..<lnHiZ.count { lnHiZ[c] = [0, 0] }
        for s in 0..<apStateL.count { apStateL[s] = [0, 0] }
        for s in 0..<apStateR.count { apStateR[s] = [0, 0] }
        for c in 0..<dcXprev.count { dcXprev[c] = 0; dcYprev[c] = 0 }
        limGain = 1.0
    }

    // ══════════════════════ Processing ═══════════════════════════

    public override func process(numberOfFrames: Int, buffer: UnsafeMutableAudioBufferListPointer) {
        guard numberOfFrames > 0 else { return }

        // Only effects that actually modify the signal count as active. An
        // enabled EQ with a flat curve is unity passthrough, so it must NOT
        // pull the DC blocker and limiter into the chain — they would color
        // pristine audio for every user who never touched the equalizer
        // (the always-on limiter audibly compressed loud masters, heard as
        // distortion on full-range outputs like AirPods).
        let effectsActive = (isEnabled && hasNonFlatGains)
            || isBassBoostEnabled || isLoudnessEnabled || isVirtualizerEnabled
            || balance != 0

        if effectsActive {
            // Arm the smoothing tail so that when effects turn off we keep
            // processing briefly (coefficients ramp to unity, limiter
            // releases) instead of bypassing with an audible click.
            bypassTailFrames = Int(sampleRate * 0.1) // ~100 ms
        } else {
            if bypassTailFrames <= 0 { return } // fully neutral: bit-perfect passthrough
            bypassTailFrames -= numberOfFrames
            if bypassTailFrames <= 0 {
                resetFilterState() // next activation starts from clean state
                return
            }
        }

        let nCh = min(buffer.count, channelCount)

        // ── 1. Absorb pending parameter changes (briefly hold lock) ──
        paramLock.lock()
        if needsEQUpdate { refreshEQTargetsLocked(); needsEQUpdate = false }
        if needsBBUpdate { refreshBBTargetLocked();  needsBBUpdate = false }
        if needsLnUpdate { refreshLnTargetsLocked(); needsLnUpdate = false }
        paramLock.unlock()

        // ── 2. Smooth coefficients toward targets ──
        // Uses exponential convergence equivalent to per-sample smoothing
        let sf = 1.0 - pow(1.0 - smoothAlpha, Double(numberOfFrames))

        if isEnabled {
            smoothCoeffs2D(&eqCur, toward: eqTgt, factor: sf)
        }
        if isBassBoostEnabled { smoothCoeffs(&bbCur, toward: bbTgt, factor: sf) }
        if isLoudnessEnabled {
            smoothCoeffs(&lnLoCur, toward: lnLoTgt, factor: sf)
            smoothCoeffs(&lnHiCur, toward: lnHiTgt, factor: sf)
        }

        // ── 3. EQ bands (10 cascaded peaking filters, Float64) ──
        if isEnabled {
            for ch in 0..<nCh {
                guard let data = buffer[ch].mData else { continue }
                let samples = data.assumingMemoryBound(to: Float.self)
                for band in 0..<Self.bandCount {
                    guard band < eqCur.count, band < eqZ.count, ch < eqZ[band].count else { continue }
                    biquadD(samples, numberOfFrames, eqCur[band], &eqZ[band][ch])
                }
            }
        }

        // ── 4. Bass boost (low shelf, Float64) ──
        if isBassBoostEnabled {
            for ch in 0..<nCh {
                guard ch < bbZ.count, let data = buffer[ch].mData else { continue }
                biquadD(data.assumingMemoryBound(to: Float.self), numberOfFrames, bbCur, &bbZ[ch])
            }
        }

        // ── 5. Loudness enhancer (low shelf + high shelf, Float64) ──
        if isLoudnessEnabled {
            for ch in 0..<nCh {
                guard ch < lnLoZ.count, ch < lnHiZ.count,
                      let data = buffer[ch].mData else { continue }
                let samples = data.assumingMemoryBound(to: Float.self)
                biquadD(samples, numberOfFrames, lnLoCur, &lnLoZ[ch])
                biquadD(samples, numberOfFrames, lnHiCur, &lnHiZ[ch])
            }
        }

        // ── 6. Virtualizer (mid-side + cross-channel all-pass, stereo only) ──
        if isVirtualizerEnabled && nCh >= 2
            && apCoeffsL.count == Self.apFreqsL.count
            && apCoeffsR.count == Self.apFreqsR.count
        {
            guard let lD = buffer[0].mData, let rD = buffer[1].mData else { return }
            let L = lD.assumingMemoryBound(to: Float.self)
            let R = rD.assumingMemoryBound(to: Float.self)
            let lvl   = Double(virtualizerLevel)
            let width = 1.0 + lvl * 1.5    // 1.0 (normal) to 2.5 (wide)
            let apMix = 0.5 + lvl * 0.5    // 0.5 (moderate) to 1.0 (full phase shift)

            for i in 0..<numberOfFrames {
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
        }

        // ── 7. DC blocker (removes sub-sonic drift, all active channels) ──
        for ch in 0..<nCh {
            guard ch < dcXprev.count, let data = buffer[ch].mData else { continue }
            let samples = data.assumingMemoryBound(to: Float.self)
            var xp = dcXprev[ch], yp = dcYprev[ch]
            let r = Self.dcR
            for i in 0..<numberOfFrames {
                let x = Double(samples[i])
                let y = x - xp + r * yp
                xp = x; yp = y
                samples[i] = Float(y)
            }
            dcXprev[ch] = xp; dcYprev[ch] = yp
        }

        // ── 8. Balance (L/R panning, stereo only) ──
        if balance != 0 && nCh >= 2 {
            guard let lD = buffer[0].mData, let rD = buffer[1].mData else { return }
            let L = lD.assumingMemoryBound(to: Float.self)
            let R = rD.assumingMemoryBound(to: Float.self)
            let bal = Double(max(-1, min(1, balance)))
            let lGain = Float(min(1.0, 1.0 - bal))
            let rGain = Float(min(1.0, 1.0 + bal))
            for i in 0..<numberOfFrames {
                L[i] *= lGain
                R[i] *= rGain
            }
        }

        // ── 9. True-peak limiter (linked stereo, transparent) ──
        // Tracks signal peaks and applies smooth gain reduction only when needed.
        // Does NOT color the audio below the threshold (gain stays 1.0).
        let thresh = Self.limThreshold
        let attC = limAttCoeff, relC = limRelCoeff

        if nCh >= 2 {
            guard let lD = buffer[0].mData, let rD = buffer[1].mData else { return }
            let L = lD.assumingMemoryBound(to: Float.self)
            let R = rD.assumingMemoryBound(to: Float.self)
            for i in 0..<numberOfFrames {
                let peak = max(abs(Double(L[i])), abs(Double(R[i])))
                let tgt  = peak > thresh ? thresh / peak : 1.0
                limGain += (tgt - limGain) * (tgt < limGain ? attC : relC)
                L[i] = Float(Double(L[i]) * limGain)
                R[i] = Float(Double(R[i]) * limGain)
            }
        } else if nCh == 1 {
            guard let data = buffer[0].mData else { return }
            let S = data.assumingMemoryBound(to: Float.self)
            for i in 0..<numberOfFrames {
                let peak = abs(Double(S[i]))
                let tgt  = peak > thresh ? thresh / peak : 1.0
                limGain += (tgt - limGain) * (tgt < limGain ? attC : relC)
                S[i] = Float(Double(S[i]) * limGain)
            }
        }
    }

    // ═══════════════ Internal: Biquad Processing ════════════════

    /// Process Float32 samples through a biquad filter using Float64 arithmetic.
    /// Direct Form II Transposed for numerical stability.
    private func biquadD(
        _ samples: UnsafeMutablePointer<Float>, _ count: Int,
        _ c: [Double], _ z: inout [Double]
    ) {
        guard c.count >= 5, z.count >= 2 else { return }
        let b0 = c[0], b1 = c[1], b2 = c[2], a1 = c[3], a2 = c[4]
        var z1 = z[0], z2 = z[1]

        for i in 0..<count {
            let x = Double(samples[i])
            let y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            samples[i] = Float(y)
        }

        // Flush denormals to zero (prevents FPU slowdown on some architectures)
        if z1.magnitude < 1e-15 { z1 = 0 }
        if z2.magnitude < 1e-15 { z2 = 0 }

        z[0] = z1; z[1] = z2
    }

    // ═══════════════ Internal: Coefficient Smoothing ════════════

    /// Smoothly interpolate a 1D coefficient vector toward target
    private func smoothCoeffs(_ cur: inout [Double], toward tgt: [Double], factor sf: Double) {
        for k in 0..<min(cur.count, tgt.count) {
            cur[k] += (tgt[k] - cur[k]) * sf
        }
    }

    /// Smoothly interpolate a 2D coefficient array toward target
    private func smoothCoeffs2D(_ cur: inout [[Double]], toward tgt: [[Double]], factor sf: Double) {
        for i in 0..<min(cur.count, tgt.count) {
            for k in 0..<min(cur[i].count, tgt[i].count) {
                cur[i][k] += (tgt[i][k] - cur[i][k]) * sf
            }
        }
    }

    // ═══════════════ Internal: Target Coefficient Calc ══════════

    private func refreshEQTargets() {
        paramLock.lock()
        refreshEQTargetsLocked()
        paramLock.unlock()
    }

    /// Must be called with paramLock held
    private func refreshEQTargetsLocked() {
        eqTgt = (0..<Self.bandCount).map { i in
            Self.peakingEQ(
                freq: Double(Self.frequencies[i]),
                gainDB: Double(_gains[i]),
                q: Self.eqQ, sr: sampleRate
            )
        }
    }

    private func refreshBBTarget() {
        paramLock.lock()
        refreshBBTargetLocked()
        paramLock.unlock()
    }

    /// Must be called with paramLock held
    private func refreshBBTargetLocked() {
        let g = Self.bbMinDB + (Self.bbMaxDB - Self.bbMinDB) * Double(bassBoostLevel)
        bbTgt = Self.lowShelf(freq: Self.bbFreq, gainDB: g, q: Self.bbQ, sr: sampleRate)
    }

    private func refreshLnTargets() {
        paramLock.lock()
        refreshLnTargetsLocked()
        paramLock.unlock()
    }

    /// Must be called with paramLock held
    private func refreshLnTargetsLocked() {
        let lo = Self.lnLoMaxDB * Double(loudnessLevel)
        let hi = Self.lnHiMaxDB * Double(loudnessLevel)
        lnLoTgt = Self.lowShelf(freq: Self.lnLoFreq,  gainDB: lo, q: Self.lnQ, sr: sampleRate)
        lnHiTgt = Self.highShelf(freq: Self.lnHiFreq, gainDB: hi, q: Self.lnQ, sr: sampleRate)
    }

    // ═══════════════ Filter Design (Audio EQ Cookbook) ═══════════
    // All calculations in Float64 for maximum precision.
    // Reference: Robert Bristow-Johnson's Audio EQ Cookbook

    /// Peaking EQ (parametric bell)
    private static func peakingEQ(freq: Double, gainDB: Double, q: Double, sr: Double) -> [Double] {
        if abs(gainDB) < 0.01 { return kUnity }
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

        return [b0/a0, b1/a0, b2/a0, a1/a0, a2/a0]
    }

    /// Low-shelf filter
    private static func lowShelf(freq: Double, gainDB: Double, q: Double, sr: Double) -> [Double] {
        if abs(gainDB) < 0.01 { return kUnity }
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

        return [b0/a0, b1/a0, b2/a0, a1/a0, a2/a0]
    }

    /// High-shelf filter
    private static func highShelf(freq: Double, gainDB: Double, q: Double, sr: Double) -> [Double] {
        if abs(gainDB) < 0.01 { return kUnity }
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

        return [b0/a0, b1/a0, b2/a0, a1/a0, a2/a0]
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
