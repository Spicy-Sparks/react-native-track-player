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
 *  - Per-sample coefficient smoothing, two poles (click-free parameter changes)
 *  - Per-effect fade in/out when an effect is switched (~10 ms, click-free)
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
        private const val BB_MIN_DB = 0.0    // slider 0 = neutral (unity shelf)
        private const val BB_MAX_DB = 12.0   // match the EQ band boost ceiling
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
        private const val DC_R = 0.9995          // ~3.5 Hz cutoff at 44.1 kHz
        private const val SAFETY_CEILING = 0.794 // ≈ -2 dBFS ceiling (head-room for AAC inter-sample overshoot)
        private const val DENORM_THRESH = 1e-15

        private val UNITY = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0)

        // ═══════════ Filter Design (Audio EQ Cookbook) ═══════════
        // At 0 dB every design is still the filter, not UNITY: numerator and denominator come out
        // identical, so it passes the signal through exactly, and a level change toward or away from
        // 0 dB moves between two filters of the same shape. Smoothing toward UNITY dragged the poles
        // across the plane on the way (a loudness change to 0 clicked).

        fun peakingEQ(freq: Double, gainDB: Double, q: Double, sr: Double): DoubleArray {
            val A = 10.0.pow(gainDB / 40.0)
            val w0 = 2.0 * PI * freq / sr
            val sw = sin(w0); val cw = cos(w0)
            val al = sw / (2.0 * q)
            val b0 = 1.0 + al * A; val b1 = -2.0 * cw; val b2 = 1.0 - al * A
            val a0 = 1.0 + al / A; val a1 = -2.0 * cw; val a2 = 1.0 - al / A
            return doubleArrayOf(b0/a0, b1/a0, b2/a0, a1/a0, a2/a0)
        }

        fun lowShelf(freq: Double, gainDB: Double, q: Double, sr: Double): DoubleArray {
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

    // Every biquad has its target coefficients (Tgt, replaced by the setters on the UI thread),
    // the coefficients in use (Cur) and the first of the two smoothing poles they reach the target
    // through (Mid): see biquadBlock. The audio thread reads each target once per buffer (the *T
    // snapshots), so every channel and the advance after the buffer follow the same one.
    // EQ coefficients: [band][5]
    private var eqCur = Array(BAND_COUNT) { UNITY.copyOf() }
    private var eqMid = Array(BAND_COUNT) { UNITY.copyOf() }
    private var eqTgt = Array(BAND_COUNT) { UNITY.copyOf() }
    private val eqT = Array(BAND_COUNT) { UNITY }
    private var eqZ = Array(BAND_COUNT) { Array(2) { doubleArrayOf(0.0, 0.0) } }

    // Bass boost coefficients
    private var bbCur = UNITY.copyOf()
    private var bbMid = UNITY.copyOf()
    @Volatile private var bbTgt = UNITY.copyOf()
    private var bbT = UNITY
    private var bbZ = Array(2) { doubleArrayOf(0.0, 0.0) }

    // Loudness: low shelf + high shelf
    private var lnLoCur = UNITY.copyOf()
    private var lnLoMid = UNITY.copyOf()
    @Volatile private var lnLoTgt = UNITY.copyOf()
    private var lnLoT = UNITY
    private var lnHiCur = UNITY.copyOf()
    private var lnHiMid = UNITY.copyOf()
    @Volatile private var lnHiTgt = UNITY.copyOf()
    private var lnHiT = UNITY
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
    // Frames of fade-in left after a flush, and its length. See onFlush.
    private var fadeInLeft = 0
    private var fadeInFrames = 0

    // A format passed to configure() takes effect at the next flush, as the AudioProcessor
    // contract says: until then queueInput may still receive audio in the old format.
    private var pendingFormat: AudioProcessor.AudioFormat? = null

    // The format the signal state was last built for (null until then, and after a reset).
    private var activeFormat: AudioProcessor.AudioFormat? = null

    // queueEndOfStream() was called since the last flush: the sink drained us before flushing,
    // so the audio on either side of that flush is continuous (see onFlush).
    private var drainedBeforeFlush = false

    // Frames left in the smoothing tail after all effects turned off. While the
    // tail runs the chain keeps processing so coefficients can ramp to unity
    // (click-free); afterwards the processor hard-bypasses (bit-perfect copy).
    private var bypassTailFrames = 0

    // How much of the dry signal is in the output: 0 = the chain's output, 1 = bypass. It moves
    // toward its target by 1/bypassFadeFrames per frame (~10 ms end to end), across buffers, in
    // both directions: the end of the tail fades into bypass, an effect switched on fades the
    // chain back in, and a change of mind halfway turns around from where the mix is.
    private var bypassFadeFrames = 1
    private var bypassMix = 1.0

    // Each effect fades in and out of the chain on its own: 0 = out, 1 = in. The mix moves toward
    // the effect's switch by 1/bypassFadeFrames per frame (~10 ms), across buffers. Before, the
    // switch cut a filter in or out at a buffer boundary while the other effects kept playing,
    // and an effect switched back on restarted from the coefficients it was last left with.
    // While an effect is out its filter rests at its designed level with cleared state (see
    // restEffectsThatAreOut), so it fades back in at the level its slider shows.
    private var eqMix = 0.0
    private var bbMix = 0.0
    private var lnMix = 0.0
    private var vzMix = 0.0

    // The virtualizer level as applied. It follows virtualizerLevel through two one-pole stages
    // (~2 ms each, so it starts and settles without a kink): a step in the level moved the side
    // signal at once.
    private var vzLevelA = 0.5
    private var vzLevel = 0.5
    private var vzLevelCoeff = 1.0

    // Coefficient smoothing per sample, each of the two poles (~3 ms). It was a fixed 0.8% per
    // buffer: steps at every buffer boundary, and a speed that depended on the buffer size (an EQ
    // change took ~3 s to be heard in full with 1,024-frame buffers, ~2.8 ms with 1-frame ones).
    private var smoothCoeff = 1.0

    // The switches as read at the start of the buffer being processed: the UI thread can flip
    // them halfway through one.
    private var eqOn = false
    private var bbOn = false
    private var lnOn = false
    private var vzOn = false

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
            eqTgt[i] = peakingEQ(FREQUENCIES[i].toDouble(), 0.0, EQ_Q, sampleRate)
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
            pendingFormat = inputAudioFormat
            return inputAudioFormat
        }
        pendingFormat = null
        return AudioProcessor.AudioFormat.NOT_SET
    }

    // Sets up every format-dependent value. Runs from onFlush, never from onConfigure: between
    // configure() and flush() media3 may still queue audio in the OLD format, and swapping the
    // channel count or the delay line under it mis-framed that audio (mono -> stereo with an odd
    // sample count left bytes no frame could consume).
    private fun applyFormat(format: AudioProcessor.AudioFormat) {
        sampleRate = format.sampleRate.toDouble()
        channelCount = format.channelCount
        val nCh = channelCount

        // EQ
        for (i in 0 until BAND_COUNT) {
            eqTgt[i] = peakingEQ(FREQUENCIES[i].toDouble(), gains[i].toDouble(), EQ_Q, sampleRate)
            eqCur[i] = eqTgt[i].copyOf(); eqMid[i] = eqTgt[i].copyOf()
        }
        eqZ = Array(BAND_COUNT) { Array(nCh) { doubleArrayOf(0.0, 0.0) } }

        // Bass boost and loudness are rebuilt from their stored levels even while switched off:
        // a level set between configure() and this flush was designed for the old sample rate,
        // and switching the effect on later does not redesign it. Switching one on fades it in
        // through its mix (see eqMix), at the designed level.
        val g = BB_MIN_DB + (BB_MAX_DB - BB_MIN_DB) * bassBoostLevel.toDouble()
        bbTgt = lowShelf(BB_FREQ, g, BB_Q, sampleRate)
        bbCur = bbTgt.copyOf(); bbMid = bbTgt.copyOf()
        bbZ = Array(nCh) { doubleArrayOf(0.0, 0.0) }

        // Loudness
        lnLoTgt = lowShelf(LN_LO_FREQ, LN_LO_MAX_DB * loudnessLevel.toDouble(), LN_Q, sampleRate)
        lnHiTgt = highShelf(LN_HI_FREQ, LN_HI_MAX_DB * loudnessLevel.toDouble(), LN_Q, sampleRate)
        lnLoCur = lnLoTgt.copyOf(); lnLoMid = lnLoTgt.copyOf()
        lnHiCur = lnHiTgt.copyOf(); lnHiMid = lnHiTgt.copyOf()
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
        bypassFadeFrames = max(1, (sampleRate * 0.01).toInt())
        vzLevelCoeff = 1.0 - exp(-1.0 / (0.002 * sampleRate))
        smoothCoeff = 1.0 - exp(-1.0 / (0.003 * sampleRate))
        bypassMix = if (effectsActive()) 0.0 else 1.0
        settleEffects(isEnabled, isBassBoostEnabled, isLoudnessEnabled, isVirtualizerEnabled)

        fadeInFrames = max(1, (sampleRate * 0.003).toInt()) // ~3 ms
    }

    override fun onQueueEndOfStream() {
        drainedBeforeFlush = true
    }

    // Two kinds of flush reach this processor:
    //  - a discontinuity (a seek, a new item, a resume): media3 throws away what was queued and
    //    starts from somewhere else. Nothing was reset here before, so the limiter's look-ahead
    //    delay line (~1.5 ms, always running) played the tail of the PREVIOUS audio into the first
    //    frames of the new one and the filters kept its state: a "tsk" at the start of songs.
    //    Start clean, and fade the first ~3 ms in, because audio that begins mid-waveform (a seek,
    //    a saved position) clicks on its own.
    //  - a flush after a drain (queueEndOfStream first): a playback speed or skip-silence change,
    //    a reconfiguration that keeps the format, or an item that ended on its own. The audio is
    //    taken to be continuous, so the delay line and the filters carry on untouched; resetting
    //    them there cut ~1.5 ms out of the music. Known limit: media3 also drains before a
    //    timestamp discontinuity (an HLS splice), where the old ~1.5 ms tail then plays into the
    //    new audio unfaded; that is what every flush did before 4.1.89, and it is rarer than
    //    the speed changes it keeps clean.
    // A different format always starts clean: every buffer is rebuilt for it.
    override fun onFlush() {
        val drained = drainedBeforeFlush
        drainedBeforeFlush = false
        val format = pendingFormat
        pendingFormat = null
        if (format != null && format != activeFormat) {
            applyFormat(format)
            activeFormat = format
            fadeInLeft = fadeInFrames
            return
        }
        if (drained) return
        clearSignalState()
        fadeInLeft = fadeInFrames
    }

    override fun onReset() {
        pendingFormat = null
        activeFormat = null
        drainedBeforeFlush = false
        clearSignalState()
        fadeInLeft = fadeInFrames
    }

    private fun clearSignalState() {
        // A discontinuity starts clean (and faded in): no crossfade carries over it.
        bypassMix = if (effectsActive() || bypassTailFrames > 0) 0.0 else 1.0
        settleEffects(isEnabled, isBassBoostEnabled, isLoudnessEnabled, isVirtualizerEnabled)
        for (line in safetyDelay) line.fill(0.0)
        safetyPos = 0
        safetyGain = 1.0
        resetFilterState()
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
        eqOn = isEnabled; bbOn = isBassBoostEnabled; lnOn = isLoudnessEnabled; vzOn = isVirtualizerEnabled
        snapshotTargets()
        val effectsActive = (eqOn && hasNonFlatGains) || bbOn || lnOn || vzOn

        // Run the colouring chain when effects are active, plus a short tail
        // afterwards: the effect switched off last fades out of the chain (see
        // eqMix) before the chain crossfades into bypass. The safety limiter
        // below is independent of this gate.
        if (effectsActive) {
            bypassTailFrames = (sampleRate * 0.1).toInt() // ~100 ms
        } else if (bypassTailFrames > 0) {
            val bytesPerSample = if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) 2 else 4
            bypassTailFrames = max(0, bypassTailFrames - size / (bytesPerSample * nCh))
        }
        // Bypass and the chain are crossfaded instead of switched (see bypassMix). Switching
        // straight to dry at the end of the tail jumped by whatever the chain still changed (the
        // DC blocker alone: ~1,600 LSB on a 20 Hz tone). The chain keeps running until the mix is
        // all dry; the filters are cleared then.
        val mixTarget = if (effectsActive || bypassTailFrames > 0) 0.0 else 1.0
        val runChain = mixTarget == 0.0 || bypassMix < 1.0
        val mixing = bypassMix != mixTarget

        // The coefficients of the effects in the chain move toward their targets inside the chain
        // (see applyChain). With the chain bypassed nothing is heard through it, so every effect
        // jumps to its settled state.
        if (runChain) {
            restEffectsThatAreOut()
        } else {
            settleEffects(eqOn, bbOn, lnOn, vzOn)
        }

        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> processInt16(inputBuffer, output, nCh, size, runChain, if (mixing) mixTarget else -1.0)
            C.ENCODING_PCM_FLOAT -> processFloat32(inputBuffer, output, nCh, size, runChain, if (mixing) mixTarget else -1.0)
        }
        if (mixing && bypassMix >= 1.0) resetFilterState()
        output.flip()
    }

    // ═══════════════ Processing: Float32 path ═══════════════

    private fun processFloat32(input: ByteBuffer, output: ByteBuffer, nCh: Int, size: Int, runEffects: Boolean, mixTarget: Double) {
        val frameCount = size / (4 * nCh)

        // Read all samples into a working buffer [channel][frame]
        val buf = Array(nCh) { DoubleArray(frameCount) }
        for (frame in 0 until frameCount) {
            for (ch in 0 until nCh) {
                buf[ch][frame] = input.float.toDouble()
            }
        }

        val dry = if (mixTarget >= 0.0) Array(nCh) { buf[it].copyOf() } else null

        if (runEffects) applyChain(buf, nCh, frameCount)

        if (dry != null) crossfadeTo(buf, dry, nCh, frameCount, mixTarget)

        // 6. Transparent true-peak safety limiter (always) + write output
        applySafetyLimiter(buf, nCh, frameCount)
        for (frame in 0 until frameCount) {
            for (ch in 0 until nCh) {
                output.putFloat(buf[ch][frame].toFloat())
            }
        }
    }

    // ═══════════════ Processing: Int16 path ═══════════════

    private fun processInt16(input: ByteBuffer, output: ByteBuffer, nCh: Int, size: Int, runEffects: Boolean, mixTarget: Double) {
        val frameCount = size / (2 * nCh)

        val buf = Array(nCh) { DoubleArray(frameCount) }
        for (frame in 0 until frameCount) {
            for (ch in 0 until nCh) {
                buf[ch][frame] = input.short.toDouble() / 32768.0
            }
        }

        val dry = if (mixTarget >= 0.0) Array(nCh) { buf[it].copyOf() } else null

        if (runEffects) applyChain(buf, nCh, frameCount)

        if (dry != null) crossfadeTo(buf, dry, nCh, frameCount, mixTarget)

        // 6. Transparent true-peak safety limiter (always) + write output
        applySafetyLimiter(buf, nCh, frameCount)
        for (frame in 0 until frameCount) {
            for (ch in 0 until nCh) {
                output.putShort((buf[ch][frame] * 32768.0).coerceIn(-32768.0, 32767.0).toInt().toShort())
            }
        }
    }

    // The colouring chain, in place: EQ bands, bass boost, loudness (low + high shelf),
    // virtualizer (stereo only), DC blocker. Each effect goes through stage() with its own mix.
    // The filters' coefficients move toward their targets sample by sample inside biquadBlock, and
    // are then advanced by the same steps (advanceCoeffs).
    private fun applyChain(buf: Array<DoubleArray>, nCh: Int, frameCount: Int) {
        eqMix = stage(buf, nCh, frameCount, eqMix, eqOn) {
            for (ch in 0 until nCh) {
                val chIdx = ch.coerceAtMost(eqZ[0].size - 1)
                for (band in 0 until BAND_COUNT) {
                    biquadBlock(buf[ch], frameCount, eqCur[band], eqMid[band], eqT[band], eqZ[band][chIdx])
                }
            }
        }
        for (band in 0 until BAND_COUNT) advanceCoeffs(eqCur[band], eqMid[band], eqT[band], frameCount)
        bbMix = stage(buf, nCh, frameCount, bbMix, bbOn) {
            for (ch in 0 until nCh) {
                biquadBlock(buf[ch], frameCount, bbCur, bbMid, bbT, bbZ[ch.coerceAtMost(bbZ.size - 1)])
            }
        }
        advanceCoeffs(bbCur, bbMid, bbT, frameCount)
        lnMix = stage(buf, nCh, frameCount, lnMix, lnOn) {
            for (ch in 0 until nCh) {
                val chIdx = ch.coerceAtMost(lnLoZ.size - 1)
                biquadBlock(buf[ch], frameCount, lnLoCur, lnLoMid, lnLoT, lnLoZ[chIdx])
                biquadBlock(buf[ch], frameCount, lnHiCur, lnHiMid, lnHiT, lnHiZ[chIdx])
            }
        }
        advanceCoeffs(lnLoCur, lnLoMid, lnLoT, frameCount)
        advanceCoeffs(lnHiCur, lnHiMid, lnHiT, frameCount)
        vzMix = if (nCh >= 2) {
            stage(buf, nCh, frameCount, vzMix, vzOn) { applyVirtualizer(buf[0], buf[1], frameCount) }
        } else {
            if (vzOn) 1.0 else 0.0
        }
        for (ch in 0 until nCh) {
            applyDCBlock(buf[ch], frameCount, ch.coerceAtMost(dcXprev.size - 1))
        }
    }

    // Runs one effect over the buffer, faded by its mix, and returns the new mix. All in: the
    // effect alone. All out: skipped. In between, the effect's output is blended with its input
    // while the mix moves one step per frame toward the switch.
    private inline fun stage(
        buf: Array<DoubleArray>, nCh: Int, frameCount: Int, mix: Double, on: Boolean, effect: () -> Unit
    ): Double {
        val target = if (on) 1.0 else 0.0
        if (mix == target) {
            if (on) effect()
            return mix
        }
        val dry = Array(nCh) { buf[it].copyOf() }
        effect()
        val step = 1.0 / bypassFadeFrames
        var m = mix
        for (frame in 0 until frameCount) {
            m = if (target > m) min(target, m + step) else max(target, m - step)
            val w = sCurve(m)
            for (ch in 0 until nCh) buf[ch][frame] = dry[ch][frame] + (buf[ch][frame] - dry[ch][frame]) * w
        }
        return m
    }

    // The fades move linearly but are heard through this S-curve (smoothstep): flat at both ends, so
    // a fade does not start or stop with a kink.
    private fun sCurve(x: Double): Double = x * x * (3.0 - 2.0 * x)

    // An effect that is out of the chain rests at its designed level with cleared state, so it
    // fades back in at the level its slider shows instead of where it was last left.
    private fun restEffectsThatAreOut() {
        if (!eqOn && eqMix == 0.0) {
            settleEQCoeffs()
            for (band in 0 until BAND_COUNT) for (z in eqZ[band]) z.fill(0.0)
        }
        if (!bbOn && bbMix == 0.0) {
            bbT.copyInto(bbCur); bbT.copyInto(bbMid)
            for (z in bbZ) z.fill(0.0)
        }
        if (!lnOn && lnMix == 0.0) {
            settleLnCoeffs()
            for (z in lnLoZ) z.fill(0.0)
            for (z in lnHiZ) z.fill(0.0)
        }
        if (!vzOn && vzMix == 0.0) {
            vzLevelA = virtualizerLevel.toDouble(); vzLevel = vzLevelA
            for (s in apStateL) s.fill(0.0)
            for (s in apStateR) s.fill(0.0)
        }
    }

    // Puts every effect where its switch and level say, with no fade: for when nothing is heard
    // through the chain (bypassed) or the audio starts over (a new format, a discontinuity).
    private fun settleEffects(eq: Boolean, bb: Boolean, ln: Boolean, vz: Boolean) {
        eqMix = if (eq) 1.0 else 0.0
        bbMix = if (bb) 1.0 else 0.0
        lnMix = if (ln) 1.0 else 0.0
        vzMix = if (vz) 1.0 else 0.0
        snapshotTargets()
        settleEQCoeffs()
        bbT.copyInto(bbCur); bbT.copyInto(bbMid)
        settleLnCoeffs()
        vzLevelA = virtualizerLevel.toDouble(); vzLevel = vzLevelA
    }

    // Reads each target once: the setters replace them from the UI thread.
    private fun snapshotTargets() {
        for (band in 0 until BAND_COUNT) eqT[band] = eqTgt[band]
        bbT = bbTgt; lnLoT = lnLoTgt; lnHiT = lnHiTgt
    }

    private fun settleEQCoeffs() {
        for (band in 0 until BAND_COUNT) { eqT[band].copyInto(eqCur[band]); eqT[band].copyInto(eqMid[band]) }
    }

    private fun settleLnCoeffs() {
        lnLoT.copyInto(lnLoCur); lnLoT.copyInto(lnLoMid)
        lnHiT.copyInto(lnHiCur); lnHiT.copyInto(lnHiMid)
    }

    // Moves bypassMix toward its target one step per frame and blends the chain's output with the
    // dry signal by it.
    private fun crossfadeTo(buf: Array<DoubleArray>, dry: Array<DoubleArray>, nCh: Int, frameCount: Int, target: Double) {
        val step = 1.0 / bypassFadeFrames
        var mix = bypassMix
        for (frame in 0 until frameCount) {
            mix = if (target > mix) min(target, mix + step) else max(target, mix - step)
            val w = sCurve(mix)
            for (ch in 0 until nCh) buf[ch][frame] = buf[ch][frame] * (1.0 - w) + dry[ch][frame] * w
        }
        bypassMix = mix
    }

    private fun effectsActive(): Boolean = (isEnabled && hasNonFlatGains) ||
        isBassBoostEnabled || isLoudnessEnabled || isVirtualizerEnabled

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
            // The fade-in after a flush goes on the INPUT of the delay line. Applied to its
            // output it ran out while the cleared line was still emitting zeros, so the first
            // real sample came out at 50% gain at 44.1 kHz, and at full gain at 8 kHz.
            if (fadeInLeft > 0) {
                val ramp = 1.0 - fadeInLeft.toDouble() / fadeInFrames
                for (ch in 0 until nCh) buf[ch][frame] *= ramp
                fadeInLeft--
            }
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
        val target = virtualizerLevel.toDouble()
        val k = vzLevelCoeff
        var lvlA = vzLevelA
        var lvl = vzLevel

        for (i in 0 until count) {
            lvlA += k * (target - lvlA)
            lvl += k * (lvlA - lvl)
            val width = 1.0 + lvl * 1.5    // 1.0 (normal) to 2.5 (wide)
            val apMix = 0.5 + lvl * 0.5    // 0.5 (moderate) to 1.0 (full phase shift)
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
        vzLevelA = lvlA
        vzLevel = lvl
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
    // While the coefficients `c` are not yet at the target `t`, they move toward it every sample
    // through two one-pole stages (`m` is the first): smoothly, with no kink where a change starts.
    // The caller then advances the stored coefficients by the same steps (advanceCoeffs), so every
    // channel runs the same trajectory and the next buffer continues it.
    private fun biquadBlock(samples: DoubleArray, count: Int, c: DoubleArray, m: DoubleArray, t: DoubleArray, z: DoubleArray) {
        var b0 = c[0]; var b1 = c[1]; var b2 = c[2]; var a1 = c[3]; var a2 = c[4]
        var z1 = z[0]; var z2 = z[1]
        if (settled(c, m, t)) {
            for (i in 0 until count) {
                val x = samples[i]
                val y = b0 * x + z1
                z1 = b1 * x - a1 * y + z2
                z2 = b2 * x - a2 * y
                samples[i] = y
            }
        } else {
            val k = smoothCoeff
            val t0 = t[0]; val t1 = t[1]; val t2 = t[2]; val t3 = t[3]; val t4 = t[4]
            var m0 = m[0]; var m1 = m[1]; var m2 = m[2]; var m3 = m[3]; var m4 = m[4]
            for (i in 0 until count) {
                m0 += k * (t0 - m0); b0 += k * (m0 - b0)
                m1 += k * (t1 - m1); b1 += k * (m1 - b1)
                m2 += k * (t2 - m2); b2 += k * (m2 - b2)
                m3 += k * (t3 - m3); a1 += k * (m3 - a1)
                m4 += k * (t4 - m4); a2 += k * (m4 - a2)
                val x = samples[i]
                val y = b0 * x + z1
                z1 = b1 * x - a1 * y + z2
                z2 = b2 * x - a2 * y
                samples[i] = y
            }
        }
        // Flush denormals
        if (abs(z1) < DENORM_THRESH) z1 = 0.0
        if (abs(z2) < DENORM_THRESH) z2 = 0.0
        z[0] = z1; z[1] = z2
    }

    // ═══════════════ Coefficient smoothing ═══════════════

    private fun settled(c: DoubleArray, m: DoubleArray, t: DoubleArray): Boolean =
        c[0] == t[0] && c[1] == t[1] && c[2] == t[2] && c[3] == t[3] && c[4] == t[4] &&
            m[0] == t[0] && m[1] == t[1] && m[2] == t[2] && m[3] == t[3] && m[4] == t[4]

    // Moves a biquad's coefficients `n` samples toward the target: the steps biquadBlock took inside
    // the buffer. Once they are within 1e-9 of it (far below anything a sample can show, and above
    // where the steps stop moving them at any sample rate) they snap onto it, and biquadBlock goes
    // back to fixed coefficients.
    private fun advanceCoeffs(c: DoubleArray, m: DoubleArray, t: DoubleArray, n: Int) {
        if (settled(c, m, t)) return
        val k = smoothCoeff
        val t0 = t[0]; val t1 = t[1]; val t2 = t[2]; val t3 = t[3]; val t4 = t[4]
        var m0 = m[0]; var m1 = m[1]; var m2 = m[2]; var m3 = m[3]; var m4 = m[4]
        var c0 = c[0]; var c1 = c[1]; var c2 = c[2]; var c3 = c[3]; var c4 = c[4]
        for (i in 0 until n) {
            m0 += k * (t0 - m0); c0 += k * (m0 - c0)
            m1 += k * (t1 - m1); c1 += k * (m1 - c1)
            m2 += k * (t2 - m2); c2 += k * (m2 - c2)
            m3 += k * (t3 - m3); c3 += k * (m3 - c3)
            m4 += k * (t4 - m4); c4 += k * (m4 - c4)
        }
        val eps = 1e-9
        if (abs(t0 - c0) < eps && abs(t1 - c1) < eps && abs(t2 - c2) < eps && abs(t3 - c3) < eps &&
            abs(t4 - c4) < eps && abs(t0 - m0) < eps && abs(t1 - m1) < eps && abs(t2 - m2) < eps &&
            abs(t3 - m3) < eps && abs(t4 - m4) < eps) {
            t.copyInto(c); t.copyInto(m)
            return
        }
        c[0] = c0; c[1] = c1; c[2] = c2; c[3] = c3; c[4] = c4
        m[0] = m0; m[1] = m1; m[2] = m2; m[3] = m3; m[4] = m4
    }

}
