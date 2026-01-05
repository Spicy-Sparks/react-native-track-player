//
//  EqualizerAudioTap.swift
//  SwiftAudioEx
//
//  iOS Equalizer implementation using biquad peaking filters
//

import Foundation
import AVFoundation
import Accelerate

/// 10-band parametric equalizer using cascaded biquad filters
/// Processes audio in real-time via MTAudioProcessingTap
public class EqualizerAudioTap: AudioTap {

    // MARK: - Constants

    /// Standard 10-band equalizer frequencies (Hz)
    public static let frequencies: [Float] = [
        32,     // Sub-bass
        64,     // Bass
        125,    // Low-bass
        250,    // Low-mid
        500,    // Mid
        1000,   // Mid
        2000,   // Upper-mid
        4000,   // Presence
        8000,   // Brilliance
        16000   // Air
    ]

    /// Number of equalizer bands
    public static let bandCount = 10

    /// Default Q factor for peaking filters (standard for graphic EQ)
    private static let defaultQ: Float = 1.41

    // MARK: - Properties

    /// Whether the equalizer is enabled
    public var isEnabled: Bool = true

    /// Gain values for each band in dB (-12 to +12)
    private var _gains: [Float] = Array(repeating: 0, count: bandCount)

    /// Thread-safe access to gains
    private let gainsLock = NSLock()

    /// Current sample rate (set during prepare)
    private var sampleRate: Float = 44100

    /// Biquad filter coefficients for each band [b0, b1, b2, a1, a2]
    private var coefficients: [[Float]] = []

    /// Filter state (delay elements) for each band, per channel
    /// Structure: [band][channel][z1, z2]
    private var filterStates: [[[Float]]] = []

    /// Number of audio channels
    private var channelCount: Int = 2

    /// Flag to indicate coefficients need recalculation
    private var needsUpdate: Bool = true

    // MARK: - Public API

    public override init() {
        super.init()
        // Initialize with flat response (all gains at 0)
        resetGains()
    }

    /// Set gain for a specific band
    /// - Parameters:
    ///   - band: Band index (0-9)
    ///   - gainDB: Gain in decibels (-12 to +12)
    public func setGain(band: Int, gainDB: Float) {
        guard band >= 0 && band < Self.bandCount else { return }

        let clampedGain = max(-12, min(12, gainDB))

        gainsLock.lock()
        _gains[band] = clampedGain
        needsUpdate = true
        gainsLock.unlock()
    }

    /// Set gains for all bands at once
    /// - Parameter gains: Array of 10 gain values in dB
    public func setAllGains(_ gains: [Float]) {
        guard gains.count == Self.bandCount else { return }

        gainsLock.lock()
        for i in 0..<Self.bandCount {
            _gains[i] = max(-12, min(12, gains[i]))
        }
        needsUpdate = true
        gainsLock.unlock()
    }

    /// Get current gain for a specific band
    public func getGain(band: Int) -> Float {
        guard band >= 0 && band < Self.bandCount else { return 0 }

        gainsLock.lock()
        let gain = _gains[band]
        gainsLock.unlock()
        return gain
    }

    /// Get all current gains
    public func getAllGains() -> [Float] {
        gainsLock.lock()
        let gains = _gains
        gainsLock.unlock()
        return gains
    }

    /// Reset all gains to 0 (flat response)
    public func resetGains() {
        gainsLock.lock()
        _gains = Array(repeating: 0, count: Self.bandCount)
        needsUpdate = true
        gainsLock.unlock()
    }

    // MARK: - AudioTap Overrides

    public override func initialize() {
        // Called when tap is attached
    }

    public override func finalize() {
        // Called when tap is detached
        filterStates = []
        coefficients = []
    }

    public override func prepare(description: AudioStreamBasicDescription) {
        sampleRate = Float(description.mSampleRate)
        channelCount = Int(description.mChannelsPerFrame)

        // Initialize filter states for each band and channel
        filterStates = Array(
            repeating: Array(
                repeating: [0, 0],  // z1, z2 delay elements
                count: channelCount
            ),
            count: Self.bandCount
        )

        // Calculate initial coefficients
        updateCoefficients()
    }

    public override func unprepare() {
        // Reset filter states
        for band in 0..<filterStates.count {
            for channel in 0..<filterStates[band].count {
                filterStates[band][channel] = [0, 0]
            }
        }
    }

    public override func process(numberOfFrames: Int, buffer: UnsafeMutableAudioBufferListPointer) {
        // Skip processing if disabled or no frames
        guard isEnabled && numberOfFrames > 0 else { return }

        // Check if coefficients need update
        gainsLock.lock()
        if needsUpdate {
            updateCoefficientsLocked()
            needsUpdate = false
        }
        let currentCoefficients = coefficients
        gainsLock.unlock()

        // Process each channel
        for channelIndex in 0..<min(buffer.count, channelCount) {
            guard let channelData = buffer[channelIndex].mData else { continue }

            let samples = channelData.assumingMemoryBound(to: Float.self)

            // Apply each band's filter in cascade
            for bandIndex in 0..<Self.bandCount {
                guard bandIndex < currentCoefficients.count else { continue }

                let coeff = currentCoefficients[bandIndex]
                guard coeff.count >= 5 else { continue }

                let b0 = coeff[0]
                let b1 = coeff[1]
                let b2 = coeff[2]
                let a1 = coeff[3]
                let a2 = coeff[4]

                // Get filter state for this band and channel
                var z1 = filterStates[bandIndex][channelIndex][0]
                var z2 = filterStates[bandIndex][channelIndex][1]

                // Process samples using Direct Form II Transposed
                for i in 0..<numberOfFrames {
                    let input = samples[i]
                    let output = b0 * input + z1
                    z1 = b1 * input - a1 * output + z2
                    z2 = b2 * input - a2 * output
                    samples[i] = output
                }

                // Save filter state
                filterStates[bandIndex][channelIndex][0] = z1
                filterStates[bandIndex][channelIndex][1] = z2
            }
        }
    }

    // MARK: - Private Methods

    private func updateCoefficients() {
        gainsLock.lock()
        updateCoefficientsLocked()
        gainsLock.unlock()
    }

    /// Calculate biquad coefficients for all bands (must be called with lock held)
    private func updateCoefficientsLocked() {
        coefficients = []

        for i in 0..<Self.bandCount {
            let freq = Self.frequencies[i]
            let gainDB = _gains[i]

            let coeff = calculatePeakingEQCoefficients(
                frequency: freq,
                gainDB: gainDB,
                q: Self.defaultQ,
                sampleRate: sampleRate
            )
            coefficients.append(coeff)
        }
    }

    /// Calculate biquad coefficients for a peaking EQ filter
    /// Based on Audio EQ Cookbook by Robert Bristow-Johnson
    /// - Parameters:
    ///   - frequency: Center frequency in Hz
    ///   - gainDB: Gain in decibels
    ///   - q: Q factor (bandwidth)
    ///   - sampleRate: Sample rate in Hz
    /// - Returns: Coefficients [b0, b1, b2, a1, a2] (normalized by a0)
    private func calculatePeakingEQCoefficients(
        frequency: Float,
        gainDB: Float,
        q: Float,
        sampleRate: Float
    ) -> [Float] {
        // If gain is essentially 0, return unity (pass-through)
        if abs(gainDB) < 0.01 {
            return [1, 0, 0, 0, 0]  // b0=1, rest=0 means y[n] = x[n]
        }

        let A = pow(10, gainDB / 40)  // sqrt(10^(dB/20))
        let w0 = 2 * Float.pi * frequency / sampleRate
        let cosW0 = cos(w0)
        let sinW0 = sin(w0)
        let alpha = sinW0 / (2 * q)

        // Peaking EQ coefficients
        let b0 = 1 + alpha * A
        let b1 = -2 * cosW0
        let b2 = 1 - alpha * A
        let a0 = 1 + alpha / A
        let a1 = -2 * cosW0
        let a2 = 1 - alpha / A

        // Normalize by a0
        return [
            b0 / a0,
            b1 / a0,
            b2 / a0,
            a1 / a0,
            a2 / a0
        ]
    }
}

// MARK: - Preset Support

extension EqualizerAudioTap {

    /// Predefined equalizer presets
    public enum Preset: String, CaseIterable {
        case flat = "Flat"
        case rock = "Rock"
        case pop = "Pop"
        case jazz = "Jazz"
        case classical = "Classical"
        case hiphop = "Hip Hop"
        case electronic = "Electronic"
        case acoustic = "Acoustic"
        case bass = "Bass Boost"
        case treble = "Treble Boost"
        case vocal = "Vocal"
        case loudness = "Loudness"

        /// Gain values for each preset (10 bands)
        public var gains: [Float] {
            switch self {
            case .flat:
                return [0, 0, 0, 0, 0, 0, 0, 0, 0, 0]
            case .rock:
                return [5, 4, 3, 1, -1, 0, 2, 3, 4, 4]
            case .pop:
                return [-1, 1, 3, 4, 3, 1, 0, 1, 2, 2]
            case .jazz:
                return [3, 2, 1, 2, -1, -1, 0, 1, 2, 3]
            case .classical:
                return [4, 3, 2, 1, -1, -1, 0, 2, 3, 4]
            case .hiphop:
                return [5, 5, 3, 1, -1, 0, 1, 0, 2, 3]
            case .electronic:
                return [4, 4, 2, 0, -2, -1, 0, 2, 4, 4]
            case .acoustic:
                return [3, 2, 1, 1, 0, 0, 1, 2, 2, 2]
            case .bass:
                return [6, 5, 4, 2, 0, 0, 0, 0, 0, 0]
            case .treble:
                return [0, 0, 0, 0, 0, 1, 2, 4, 5, 6]
            case .vocal:
                return [-2, -1, 0, 2, 4, 4, 3, 1, 0, -1]
            case .loudness:
                return [5, 4, 2, 0, -2, -2, 0, 2, 4, 5]
            }
        }
    }

    /// Apply a preset
    public func applyPreset(_ preset: Preset) {
        setAllGains(preset.gains)
    }

    /// Get all available preset names
    public static var presetNames: [String] {
        return Preset.allCases.map { $0.rawValue }
    }
}
