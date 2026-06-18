package com.lovegaoshi.kotlinaudio.processors

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.*

/**
 * Software audio effects processor matching iOS EqualizerAudioTap.
 * All processing in Float64 for maximum precision.
 *
 * Features:
 *  - 8-band parametric EQ (peaking biquad filters)
 *  - Bass boost (low shelf biquad)
 *  - Loudness enhancer (low shelf + high shelf biquad)
 *  - Stereo virtualizer (mid-side + cross-channel all-pass)
 *  - Per-block coefficient smoothing (click-free parameter changes)
 *  - DC blocking filter (removes sub-sonic drift)
 *  - Transparent look-ahead true-peak safety limiter (always on)
 *  - Denormal flush (prevents FPU slowdown on ARM)
 */
@UnstableApi
class EqualizerAudioProcessor : BaseAudioProcessor() {

    companion object {
        // ── EQ ──
        const val BAND_COUNT = 8
        val FREQUENCIES = floatArrayOf(60f, 150f, 400f, 1000f, 2500f, 6000f, 12000f, 16000f)
        private const val EQ_Q = 1.4

        // ── Bass Boost (low shelf) ──
        private const val BB_FREQ = 150.0
        private const val BB_MIN_DB = 6.0    // slider 0 = gentle warmth
        private const val BB_MAX_DB = 24.0   // slider 1 = full boost
        private const val BB_Q = 0.8

        // ── Loudness Enhancer (low shelf + high shelf) ──
        private const val LN_LO_FREQ = 200.0
        private const val LN_LO_MAX_DB = 15.0
        private const val LN_HI_FREQ = 3000.0
        private const val LN_HI_MAX_DB = 10.0
        private const val LN_Q = 0.7

        // ── Virtualizer (cross-channel all-pass) ──
        private val AP_FREQS_L = doubleArrayOf(250.0, 630.0, 1500.0, 3200.0, 7500.0)
        private val AP_FREQS_R = doubleArrayOf(160.0, 420.0, 1000.0, 2200.0, 5000.0, 9500.0)

        // ── Processing constants ──
        private const val SMOOTH_ALPHA = 0.008
        private const val DC_R = 0.9995          // ~3.5 Hz cutoff at 44.1 kHz
        private const val SAFETY_CEILING = 0.794 // ≈ -2 dBFS ceiling (head-room for AAC inter-sample overshoot)
        private const val DENORM_THRESH = 1e-15

        private val UNITY = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0)

        // ═══════════ Filter Design (Audio EQ Cookbook) ═══════════

        fun peakingEQ(freq: Double, gainDB: Double, q: Double, sr: Double): DoubleArray {
            if (abs(gainDB) < 0.01) return UNITY.copyOf()
            val A = 10.0.pow(gainDB / 40.0)
            val w0 = 2.0 * PI * freq / sr
            val sw = sin(w0); val cw = cos(w0)
            val al = sw / (2.0 * q)
            val b0 = 1.0 + al * A; val b1 = -2.0 * cw; val b2 = 1.0 - al * A
            val a0 = 1.0 + al / A; val a1 = -2.0 * cw; val a2 = 1.0 - al / A
            return doubleArrayOf(b0/a0, b1/a0, b2/a0, a1/a0, a2/a0)
        }

        fun lowShelf(freq: Double, gainDB: Double, q: Double, sr: Double): DoubleArray {
            if (abs(gainDB) < 0.01) return UNITY.copyOf()
            val A = 10.0.pow(gainDB / 40.0)
            val w0 = 2.0 * PI * freq / sr
            val cw = cos(w0); val sw = sin(w0)
            val al = sw / (2.0 * q)
            val s2a = 2.0 * sqrt(A) * al
            val b0 = A * ((A+1) - (A-1)*cw + s2a)
            val b1 = 2*A * ((A-1) - (A+1)*cw)
            val b2 = A * ((A+1) - (A-1)*cw - s2a)
            val a0 = (A+1) + (A-1)*cw + s2a
            val a1 = -2.0 * ((A-1) + (A+1)*cw)
            val a2 = (A+1) + (A-1)*cw - s2a
            return doubleArrayOf(b0/a0, b1/a0, b2/a0, a1/a0, a2/a0)
        }

        fun highShelf(freq: Double, gainDB: Double, q: Double, sr: Double): DoubleArray {
            if (abs(gainDB) < 0.01) return UNITY.copyOf()
            val A = 10.0.pow(gainDB / 40.0)
            val w0 = 2.0 * PI * freq / sr
            val cw = cos(w0); val sw = sin(w0)
            val al = sw / (2.0 * q)
            val s2a = 2.0 * sqrt(A) * al
            val b0 = A * ((A+1) + (A-1)*cw + s2a)
            val b1 = -2*A * ((A-1) + (A+1)*cw)
            val b2 = A * ((A+1) + (A-1)*cw - s2a)
            val a0 = (A+1) - (A-1)*cw + s2a
            val a1 = 2.0 * ((A-1) - (A+1)*cw)
            val a2 = (A+1) - (A-1)*cw - s2a
            return doubleArrayOf(b0/a0, b1/a0, b2/a0, a1/a0, a2/a0)
        }
    }

    // ═══════════════════ Public state ═══════════════════

    // EQ
    @Volatile var isEnabled = false
    private val gains = FloatArray(BAND_COUNT)

    // True when at least one EQ band deviates from 0 dB. A flat curve is a
    // mathematical passthrough (unity biquads), so it must not count as an
    // active effect — see queueInput bypass logic.
    @Volatile private var hasNonFlatGains = false

    // Bass Boost
    @Volatile var isBassBoostEnabled = false
    @Volatile var bassBoostLevel = 0.5f   // 0..1

    // Loudness
    @Volatile var isLoudnessEnabled = false
    @Volatile var loudnessLevel = 0.5f    // 0..1

    // Virtualizer
    @Volatile var isVirtualizerEnabled = false
    @Volatile var virtualizerLevel = 0.5f // 0..1

    // ═══════════════════ Internal state ═══════════════════

    private var sampleRate = 44100.0
    private var channelCount = 2

    // EQ coefficients: [band][5]
    private var eqCur = Array(BAND_COUNT) { UNITY.copyOf() }
    private var eqTgt = Array(BAND_COUNT) { UNITY.copyOf() }
    private var eqZ = Array(BAND_COUNT) { Array(2) { doubleArrayOf(0.0, 0.0) } }

    // Bass boost coefficients
    private var bbCur = UNITY.copyOf()
    private var bbTgt = UNITY.copyOf()
    private var bbZ = Array(2) { doubleArrayOf(0.0, 0.0) }

    // Loudness: low shelf + high shelf
    private var lnLoCur = UNITY.copyOf()
    private var lnLoTgt = UNITY.copyOf()
    private var lnHiCur = UNITY.copyOf()
    private var lnHiTgt = UNITY.copyOf()
    private var lnLoZ = Array(2) { doubleArrayOf(0.0, 0.0) }
    private var lnHiZ = Array(2) { doubleArrayOf(0.0, 0.0) }

    // Virtualizer: first-order all-pass coefficients and states
    private var apCoeffsL = DoubleArray(0)
    private var apCoeffsR = DoubleArray(0)
    private var apStateL = Array(0) { doubleArrayOf(0.0, 0.0) }
    private var apStateR = Array(0) { doubleArrayOf(0.0, 0.0) }

    // DC blocker
    private var dcXprev = DoubleArray(2)
    private var dcYprev = DoubleArray(2)

    // Always-on transparent true-peak safety limiter. Caps output peaks below
    // full scale so the downstream Bluetooth/AAC encoder (e.g. AirPods) cannot
    // inter-sample clip on hot masters. Runs on EVERY block — even in passthrough
    // — but stays transparent below the ceiling (gain == 1.0). The look-ahead
    // lets the gain ramp down before a peak reaches the output, avoiding the bass
    // distortion a zero-latency feedback limiter produces.
    private var safetyLookahead = 0
    private var safetyDelay = Array(2) { DoubleArray(0) } // [channel][lookahead] circular delay line
    private var safetyPos = 0
    private var safetyGain = 1.0
    private var safetyAttCoeff = 0.0
    private var safetyRelCoeff = 0.0

    // Frames left in the smoothing tail after all effects turned off. While the
    // tail runs the chain keeps processing so coefficients can ramp to unity
    // (click-free); afterwards the processor hard-bypasses (bit-perfect copy).
    private var bypassTailFrames = 0

    // ═══════════════════ EQ API ═══════════════════

    fun setGain(band: Int, gainDB: Float) {
        if (band !in 0 until BAND_COUNT) return
        gains[band] = gainDB.coerceIn(-12f, 12f)
        eqTgt[band] = peakingEQ(FREQUENCIES[band].toDouble(), gains[band].toDouble(), EQ_Q, sampleRate)
        hasNonFlatGains = gains.any { it != 0f }
    }

    fun setAllGains(gainsDB: List<Float>) {
        for (i in 0 until minOf(gainsDB.size, BAND_COUNT)) {
            gains[i] = gainsDB[i].coerceIn(-12f, 12f)
            eqTgt[i] = peakingEQ(FREQUENCIES[i].toDouble(), gains[i].toDouble(), EQ_Q, sampleRate)
        }
        hasNonFlatGains = gains.any { it != 0f }
    }

    fun getAllGains(): FloatArray = gains.copyOf()

    fun resetGains() {
        for (i in 0 until BAND_COUNT) {
            gains[i] = 0f
            eqTgt[i] = UNITY.copyOf()
        }
        hasNonFlatGains = false
    }

    // ═══════════════════ Bass Boost API ═══════════════════

    fun updateBassBoostLevel(level: Float) {
        bassBoostLevel = level.coerceIn(0f, 1f)
        val g = BB_MIN_DB + (BB_MAX_DB - BB_MIN_DB) * bassBoostLevel.toDouble()
        bbTgt = lowShelf(BB_FREQ, g, BB_Q, sampleRate)
    }

    // ═══════════════════ Loudness API ═══════════════════

    fun updateLoudnessLevel(level: Float) {
        loudnessLevel = level.coerceIn(0f, 1f)
        val lo = LN_LO_MAX_DB * loudnessLevel.toDouble()
        val hi = LN_HI_MAX_DB * loudnessLevel.toDouble()
        lnLoTgt = lowShelf(LN_LO_FREQ, lo, LN_Q, sampleRate)
        lnHiTgt = highShelf(LN_HI_FREQ, hi, LN_Q, sampleRate)
    }

    // ═══════════════════ Virtualizer API ═══════════════════

    fun updateVirtualizerLevel(level: Float) {
        virtualizerLevel = level.coerceIn(0f, 1f)
    }

    // ═══════════════════ Pipeline ═══════════════════

    // Zero all filter memory so the next processed buffer starts clean.
    // Called when the smoothing tail ends after all effects turn off.
    private fun resetFilterState() {
        for (band in 0 until BAND_COUNT) for (ch in eqZ[band].indices) { eqZ[band][ch][0] = 0.0; eqZ[band][ch][1] = 0.0 }
        for (ch in bbZ.indices) { bbZ[ch][0] = 0.0; bbZ[ch][1] = 0.0 }
        for (ch in lnLoZ.indices) { lnLoZ[ch][0] = 0.0; lnLoZ[ch][1] = 0.0 }
        for (ch in lnHiZ.indices) { lnHiZ[ch][0] = 0.0; lnHiZ[ch][1] = 0.0 }
        for (s in apStateL.indices) { apStateL[s][0] = 0.0; apStateL[s][1] = 0.0 }
        for (s in apStateR.indices) { apStateR[s][0] = 0.0; apStateR[s][1] = 0.0 }
        for (ch in dcXprev.indices) { dcXprev[ch] = 0.0; dcYprev[ch] = 0.0 }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        ) {
            sampleRate = inputAudioFormat.sampleRate.toDouble()
            channelCount = inputAudioFormat.channelCount
            val nCh = channelCount

            // EQ
            for (i in 0 until BAND_COUNT) {
                eqTgt[i] = peakingEQ(FREQUENCIES[i].toDouble(), gains[i].toDouble(), EQ_Q, sampleRate)
                eqCur[i] = eqTgt[i].copyOf()
            }
            eqZ = Array(BAND_COUNT) { Array(nCh) { doubleArrayOf(0.0, 0.0) } }

            // Bass boost
            if (isBassBoostEnabled) {
                val g = BB_MIN_DB + (BB_MAX_DB - BB_MIN_DB) * bassBoostLevel.toDouble()
                bbTgt = lowShelf(BB_FREQ, g, BB_Q, sampleRate)
            }
            bbCur = bbTgt.copyOf()
            bbZ = Array(nCh) { doubleArrayOf(0.0, 0.0) }

            // Loudness
            if (isLoudnessEnabled) {
                lnLoTgt = lowShelf(LN_LO_FREQ, LN_LO_MAX_DB * loudnessLevel.toDouble(), LN_Q, sampleRate)
                lnHiTgt = highShelf(LN_HI_FREQ, LN_HI_MAX_DB * loudnessLevel.toDouble(), LN_Q, sampleRate)
            }
            lnLoCur = lnLoTgt.copyOf(); lnHiCur = lnHiTgt.copyOf()
            lnLoZ = Array(nCh) { doubleArrayOf(0.0, 0.0) }
            lnHiZ = Array(nCh) { doubleArrayOf(0.0, 0.0) }

            // Virtualizer all-pass coefficients
            apCoeffsL = DoubleArray(AP_FREQS_L.size) { i ->
                val omega = tan(PI * AP_FREQS_L[i] / sampleRate)
                (1.0 - omega) / (1.0 + omega)
            }
            apCoeffsR = DoubleArray(AP_FREQS_R.size) { i ->
                val omega = tan(PI * AP_FREQS_R[i] / sampleRate)
                (1.0 - omega) / (1.0 + omega)
            }
            apStateL = Array(AP_FREQS_L.size) { doubleArrayOf(0.0, 0.0) }
            apStateR = Array(AP_FREQS_R.size) { doubleArrayOf(0.0, 0.0) }

            // DC blocker
            dcXprev = DoubleArray(nCh)
            dcYprev = DoubleArray(nCh)

            // Transparent true-peak safety limiter
            safetyLookahead = max(32, (sampleRate * 0.0015).toInt()) // ~1.5 ms look-ahead
            safetyDelay = Array(nCh) { DoubleArray(safetyLookahead) }
            safetyPos = 0
            safetyGain = 1.0
            // Attack reaches the target within ~one look-ahead window.
            safetyAttCoeff = 1.0 - exp(-3.0 / safetyLookahead.toDouble())
            safetyRelCoeff = 1.0 - exp(-1.0 / (0.100 * sampleRate)) // 100 ms release

            // Bypass tail
            bypassTailFrames = 0

            return inputAudioFormat
        }
        return AudioProcessor.AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val nCh = channelCount
        val size = inputBuffer.remaining()
        val output = replaceOutputBuffer(size)

        // Only effects that actually modify the signal count as active. An
        // enabled EQ with a flat curve is unity passthrough, so it must NOT
        // pull the EQ chain / DC blocker in — they would color pristine audio
        // even though the user set no boost at all. NOTE: the transparent safety
        // limiter runs regardless (see applySafetyLimiter) to protect the
        // Bluetooth/AAC path from inter-sample clipping.
        val effectsActive = (isEnabled && hasNonFlatGains) ||
            isBassBoostEnabled || isLoudnessEnabled || isVirtualizerEnabled

        // Run the colouring chain when effects are active, plus a short tail
        // afterwards so coefficients can ramp to unity (click-free). The safety
        // limiter below is independent of this gate.
        var runChain = effectsActive
        if (effectsActive) {
            bypassTailFrames = (sampleRate * 0.1).toInt() // ~100 ms
        } else if (bypassTailFrames > 0) {
            runChain = true
            val bytesPerSample = if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) 2 else 4
            bypassTailFrames -= size / (bytesPerSample * nCh)
            if (bypassTailFrames <= 0) {
                bypassTailFrames = 0
                runChain = false
                resetFilterState()
            }
        }

        // Smooth all coefficients toward targets (only while the chain runs)
        if (runChain) {
            if (isEnabled) smoothCoeffs2D(eqCur, eqTgt)
            if (isBassBoostEnabled) smoothCoeffs(bbCur, bbTgt)
            if (isLoudnessEnabled) {
                smoothCoeffs(lnLoCur, lnLoTgt)
                smoothCoeffs(lnHiCur, lnHiTgt)
            }
        }

        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> processInt16(inputBuffer, output, nCh, size, runChain)
            C.ENCODING_PCM_FLOAT -> processFloat32(inputBuffer, output, nCh, size, runChain)
        }
        output.flip()
    }

    // ═══════════════ Processing: Float32 path ═══════════════

    private fun processFloat32(input: ByteBuffer, output: ByteBuffer, nCh: Int, size: Int, runEffects: Boolean) {
        val frameCount = size / (4 * nCh)

        // Read all samples into a working buffer [channel][frame]
        val buf = Array(nCh) { DoubleArray(frameCount) }
        for (frame in 0 until frameCount) {
            for (ch in 0 until nCh) {
                buf[ch][frame] = input.float.toDouble()
            }
        }

        if (runEffects) {
            // 1. EQ bands
            if (isEnabled) {
                for (ch in 0 until nCh) {
                    val chIdx = ch.coerceAtMost(eqZ[0].size - 1)
                    for (band in 0 until BAND_COUNT) {
                        biquadBlock(buf[ch], frameCount, eqCur[band], eqZ[band][chIdx])
                    }
                }
            }

            // 2. Bass boost
            if (isBassBoostEnabled) {
                for (ch in 0 until nCh) {
                    val chIdx = ch.coerceAtMost(bbZ.size - 1)
                    biquadBlock(buf[ch], frameCount, bbCur, bbZ[chIdx])
                }
            }

            // 3. Loudness (low shelf + high shelf)
            if (isLoudnessEnabled) {
                for (ch in 0 until nCh) {
                    val chIdx = ch.coerceAtMost(lnLoZ.size - 1)
                    biquadBlock(buf[ch], frameCount, lnLoCur, lnLoZ[chIdx])
                    biquadBlock(buf[ch], frameCount, lnHiCur, lnHiZ[chIdx])
                }
            }

            // 4. Virtualizer (stereo only)
            if (isVirtualizerEnabled && nCh >= 2) {
                applyVirtualizer(buf[0], buf[1], frameCount)
            }

            // 5. DC blocker
            for (ch in 0 until nCh) {
                val chIdx = ch.coerceAtMost(dcXprev.size - 1)
                applyDCBlock(buf[ch], frameCount, chIdx)
            }
        }

        // 6. Transparent true-peak safety limiter (always) + write output
        applySafetyLimiter(buf, nCh, frameCount)
        for (frame in 0 until frameCount) {
            for (ch in 0 until nCh) {
                output.putFloat(buf[ch][frame].toFloat())
            }
        }
    }

    // ═══════════════ Processing: Int16 path ═══════════════

    private fun processInt16(input: ByteBuffer, output: ByteBuffer, nCh: Int, size: Int, runEffects: Boolean) {
        val frameCount = size / (2 * nCh)

        val buf = Array(nCh) { DoubleArray(frameCount) }
        for (frame in 0 until frameCount) {
            for (ch in 0 until nCh) {
                buf[ch][frame] = input.short.toDouble() / 32768.0
            }
        }

        if (runEffects) {
            // 1. EQ bands
            if (isEnabled) {
                for (ch in 0 until nCh) {
                    val chIdx = ch.coerceAtMost(eqZ[0].size - 1)
                    for (band in 0 until BAND_COUNT) {
                        biquadBlock(buf[ch], frameCount, eqCur[band], eqZ[band][chIdx])
                    }
                }
            }

            // 2. Bass boost
            if (isBassBoostEnabled) {
                for (ch in 0 until nCh) {
                    val chIdx = ch.coerceAtMost(bbZ.size - 1)
                    biquadBlock(buf[ch], frameCount, bbCur, bbZ[chIdx])
                }
            }

            // 3. Loudness
            if (isLoudnessEnabled) {
                for (ch in 0 until nCh) {
                    val chIdx = ch.coerceAtMost(lnLoZ.size - 1)
                    biquadBlock(buf[ch], frameCount, lnLoCur, lnLoZ[chIdx])
                    biquadBlock(buf[ch], frameCount, lnHiCur, lnHiZ[chIdx])
                }
            }

            // 4. Virtualizer (stereo only)
            if (isVirtualizerEnabled && nCh >= 2) {
                applyVirtualizer(buf[0], buf[1], frameCount)
            }

            // 5. DC blocker
            for (ch in 0 until nCh) {
                val chIdx = ch.coerceAtMost(dcXprev.size - 1)
                applyDCBlock(buf[ch], frameCount, chIdx)
            }
        }

        // 6. Transparent true-peak safety limiter (always) + write output
        applySafetyLimiter(buf, nCh, frameCount)
        for (frame in 0 until frameCount) {
            for (ch in 0 until nCh) {
                output.putShort((buf[ch][frame] * 32768.0).coerceIn(-32768.0, 32767.0).toInt().toShort())
            }
        }
    }

    // ═══════════════ Safety limiter ═══════════════

    /**
     * Transparent look-ahead peak limiter. One shared gain across channels
     * preserves the stereo image; below the ceiling the gain stays at 1.0 so it
     * does not colour the signal. The look-ahead delay lets the gain ramp down
     * before a peak reaches the output, taming loud transients without the bass
     * distortion a zero-latency feedback limiter adds. Runs on every block to
     * guarantee head-room for the downstream Bluetooth/AAC encoder.
     */
    private fun applySafetyLimiter(buf: Array<DoubleArray>, nCh: Int, frameCount: Int) {
        if (safetyLookahead <= 0 || nCh <= 0 || safetyDelay.size < nCh) return
        val ceil = SAFETY_CEILING
        val attC = safetyAttCoeff; val relC = safetyRelCoeff
        var pos = safetyPos
        var g = safetyGain
        for (frame in 0 until frameCount) {
            // Future peak across channels (the sample about to enter the delay).
            var peak = 0.0
            for (ch in 0 until nCh) peak = max(peak, abs(buf[ch][frame]))
            val tgt = if (peak > ceil) ceil / peak else 1.0
            // Fast (attack) when reducing gain, slow (release) when recovering.
            g += (tgt - g) * if (tgt < g) attC else relC
            for (ch in 0 until nCh) {
                val delayed = safetyDelay[ch][pos]      // output: input from `lookahead` frames ago
                safetyDelay[ch][pos] = buf[ch][frame]   // store current input
                buf[ch][frame] = delayed * g
            }
            pos++
            if (pos >= safetyLookahead) pos = 0
        }
        safetyPos = pos
        safetyGain = g
    }

    // ═══════════════ Virtualizer ═══════════════

    private fun applyVirtualizer(L: DoubleArray, R: DoubleArray, count: Int) {
        val lvl = virtualizerLevel.toDouble()
        val width = 1.0 + lvl * 1.5    // 1.0 (normal) to 2.5 (wide)
        val apMix = 0.5 + lvl * 0.5    // 0.5 (moderate) to 1.0 (full phase shift)

        for (i in 0 until count) {
            val l = L[i]; val r = R[i]

            // Mid-side decomposition + widening
            val mid  = (l + r) * 0.5
            val side = (l - r) * 0.5
            val wL = mid + side * width
            val wR = mid - side * width

            // Cascade all-pass on LEFT channel
            var sigL = wL
            for (s in apCoeffsL.indices) {
                val a = apCoeffsL[s]
                val y = a * sigL + apStateL[s][0] - a * apStateL[s][1]
                apStateL[s][0] = sigL
                apStateL[s][1] = y
                sigL = y
            }

            // Cascade all-pass on RIGHT channel
            var sigR = wR
            for (s in apCoeffsR.indices) {
                val a = apCoeffsR[s]
                val y = a * sigR + apStateR[s][0] - a * apStateR[s][1]
                apStateR[s][0] = sigR
                apStateR[s][1] = y
                sigR = y
            }

            // Cross-channel decorrelation
            L[i] = wL * (1.0 - apMix) + sigL * apMix
            R[i] = wR * (1.0 - apMix) + sigR * apMix
        }
    }

    // ═══════════════ DC Blocker ═══════════════

    private fun applyDCBlock(samples: DoubleArray, count: Int, ch: Int) {
        var xp = dcXprev[ch]; var yp = dcYprev[ch]
        for (i in 0 until count) {
            val x = samples[i]
            val y = x - xp + DC_R * yp
            xp = x; yp = y
            samples[i] = y
        }
        dcXprev[ch] = xp; dcYprev[ch] = yp
    }

    // ═══════════════ Biquad processing ═══════════════

    /** Process a block of Float64 samples through a biquad filter (Direct Form II Transposed) */
    private fun biquadBlock(samples: DoubleArray, count: Int, c: DoubleArray, z: DoubleArray) {
        val b0 = c[0]; val b1 = c[1]; val b2 = c[2]; val a1 = c[3]; val a2 = c[4]
        var z1 = z[0]; var z2 = z[1]
        for (i in 0 until count) {
            val x = samples[i]
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            samples[i] = y
        }
        // Flush denormals
        if (abs(z1) < DENORM_THRESH) z1 = 0.0
        if (abs(z2) < DENORM_THRESH) z2 = 0.0
        z[0] = z1; z[1] = z2
    }

    // ═══════════════ Coefficient smoothing ═══════════════

    private fun smoothCoeffs(cur: DoubleArray, tgt: DoubleArray) {
        for (k in cur.indices) {
            cur[k] += SMOOTH_ALPHA * (tgt[k] - cur[k])
        }
    }

    private fun smoothCoeffs2D(cur: Array<DoubleArray>, tgt: Array<DoubleArray>) {
        for (i in cur.indices) {
            val c = cur[i]; val t = tgt[i]
            for (k in c.indices) {
                c[k] += SMOOTH_ALPHA * (t[k] - c[k])
            }
        }
    }
}
