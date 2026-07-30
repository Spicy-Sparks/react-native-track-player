@file: OptIn(UnstableApi::class) package com.lovegaoshi.kotlinaudio.player

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import androidx.annotation.CallSuper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Player.Listener
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.lovegaoshi.kotlinaudio.event.PlayerEventHolder
import com.lovegaoshi.kotlinaudio.models.AudioItem
import com.lovegaoshi.kotlinaudio.models.audioItem2MediaItem
import com.lovegaoshi.kotlinaudio.models.AudioItemTransitionReason
import com.lovegaoshi.kotlinaudio.models.AudioPlayerState
import com.lovegaoshi.kotlinaudio.models.mediaItem2AudioItem
import com.lovegaoshi.kotlinaudio.models.MediaSessionCallback
import com.lovegaoshi.kotlinaudio.models.PlayWhenReadyChangeData
import com.lovegaoshi.kotlinaudio.models.PlaybackError
import com.lovegaoshi.kotlinaudio.models.PlayerOptions
import com.lovegaoshi.kotlinaudio.models.PositionChangedReason
import com.lovegaoshi.kotlinaudio.models.setWakeMode
import com.lovegaoshi.kotlinaudio.player.components.APMRenderersFactory
import com.lovegaoshi.kotlinaudio.player.components.Cache
import com.lovegaoshi.kotlinaudio.player.components.FocusManager
import com.lovegaoshi.kotlinaudio.player.components.MediaFactory
import com.lovegaoshi.kotlinaudio.player.components.setupBuffer
import com.lovegaoshi.kotlinaudio.processors.BalanceAudioProcessor
import com.lovegaoshi.kotlinaudio.processors.EqualizerAudioProcessor
import com.lovegaoshi.kotlinaudio.processors.GainAudioProcessor
import com.lovegaoshi.kotlinaudio.processors.FFTEmitter
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.min

abstract class AudioPlayer internal constructor(
    private val context: Context,
    val options: PlayerOptions = PlayerOptions()
) {

    // for crossfading
    private var exoPlayer1: ExoPlayer
    private var exoPlayer2: ExoPlayer? = null
    private var loudnessEnhancers = ArrayList<LoudnessEnhancer>()
    private var equalizers = ArrayList<Equalizer>()
    private var currentExoPlayer = true

    // Crossfade finalization state. During a fade BOTH wrappers play; the fades
    // run on coroutines that only settle volumes when their timers elapse. A
    // user pause/stop in that window must SNAP the crossfade to its END state
    // (incoming wrapper -> full volume + active, outgoing wrapper -> 0 + paused)
    // rather than freezing both at a partial volume or leaving the outgoing
    // track audible. These refs let pause()/stop() do that.
    private var crossfadeFadeOutJob: Job? = null
    private var crossfadeFadeInJob: Job? = null
    private var crossfadeFadingOutPlayer: ExoPlayer? = null
    private var crossfadeFadingInPlayer: ExoPlayer? = null
    private var crossfadeTargetVolume: Float = 1f

    // Snap an in-flight crossfade to its completed state. Safe to call when no
    // crossfade is active (no-op). After this the active wrapper (`exoPlayer`)
    // holds the new track at full volume; the outgoing wrapper is silenced and
    // paused. Callers then apply pause()/stop() to the single active wrapper.
    private fun finalizeCrossfade() {
        if (!options.crossfade) return
        crossfadeFadeOutJob?.cancel()
        crossfadeFadeOutJob = null
        crossfadeFadeInJob?.cancel()
        crossfadeFadeInJob = null
        crossfadeFadingOutPlayer?.let { p ->
            // Only settle it if it's still the outgoing wrapper (a chained
            // crossfade may have re-promoted it).
            if (p !== exoPlayer) {
                p.volume = 0f
                p.pause()
            }
        }
        crossfadeFadingInPlayer?.let { p ->
            if (p === exoPlayer) p.volume = crossfadeTargetVolume
        }
        crossfadeFadingOutPlayer = null
        crossfadeFadingInPlayer = null
    }

    var exoPlayer: ExoPlayer
    var player: ForwardingPlayer
    private var playerListener = PlayerListener()
    private val scope = MainScope()
    private var cache: SimpleCache? = null
    val playerEventHolder = PlayerEventHolder()
    private val focusListener = APMFocusListener()
    private val focusManager = FocusManager(context, listener=focusListener, options=options)
    var fftEmitter: (DoubleArray) -> Unit = { v -> Timber.tag("APMFFT").d("FFT emitted $v") }
    private val balanceProcessor = BalanceAudioProcessor()
    private val equalizerProcessor = EqualizerAudioProcessor()
    private val gainProcessor = GainAudioProcessor()
    // Separate processor instances for the second ExoPlayer (crossfade).
    // Both players render audio simultaneously during crossfade, so sharing
    // a single processor causes BufferOverflowException.
    private val balanceProcessor2 = BalanceAudioProcessor()
    private val equalizerProcessor2 = EqualizerAudioProcessor()
    private val gainProcessor2 = GainAudioProcessor()

    var alwaysPauseOnInterruption: Boolean
        get() = focusManager.alwaysPauseOnInterruption
        set(v) { focusManager.alwaysPauseOnInterruption = v }

    open val currentItem: AudioItem?
        get() = mediaItem2AudioItem(exoPlayer.currentMediaItem)

    var playbackError: PlaybackError? = null
    var playerState: AudioPlayerState = AudioPlayerState.IDLE
        private set(value) {
            if (value != field) {
                field = value
                playerEventHolder.updateAudioPlayerState(value)
                
                // Always register audio focus listener to emit events to JS,
                // even when ExoPlayer handles focus internally (handleAudioFocus=true).
                // This ensures RemoteDuck events reach JS for proper resume handling,
                // especially on Android Auto where ExoPlayer's internal resume may fail.
                // if (!options.handleAudioFocus) {
                when (value) {
                    AudioPlayerState.IDLE,
                    AudioPlayerState.ERROR -> focusManager.abandonAudioFocusIfHeld()
                    AudioPlayerState.READY -> focusManager.requestAudioFocus()
                    else -> {}
                }
                // }
            }
        }

    var playWhenReady: Boolean
        get() = exoPlayer.playWhenReady
        set(value) {
            exoPlayer.playWhenReady = value
        }

    val duration: Long
        get() {
            return if (exoPlayer.duration == C.TIME_UNSET) 0
            else exoPlayer.duration
        }

    val isCurrentMediaItemLive: Boolean
        get() = exoPlayer.isCurrentMediaItemLive

    private var oldPosition = 0L

    val position: Long
        get() {
            return if (exoPlayer.currentPosition == C.INDEX_UNSET.toLong()) 0
            else exoPlayer.currentPosition
        }

    val bufferedPosition: Long
        get() {
            return if (exoPlayer.bufferedPosition == C.INDEX_UNSET.toLong()) 0
            else exoPlayer.bufferedPosition
        }

    // The logical (app-requested) volume, kept separate from the ExoPlayer output so
    // ducking can be applied and removed without decaying the base. Previously `volume`
    // read back exoPlayer.volume (already multiplied) and both setters re-multiplied, so
    // each audio-focus duck→restore cycle left the output at the ducked level and
    // compounded (1 → 0.2 → 0.04 …) — playback got permanently quieter after every
    // notification/transient-focus-loss.
    private var logicalVolume = 1f

    private var volumeMultiplier = 1f
        set(value) {
            field = value
            exoPlayer.volume = logicalVolume * value
        }

    var volume: Float
        get() = logicalVolume
        set(value) {
            logicalVolume = value
            exoPlayer.volume = value * volumeMultiplier
        }

    /**
     * fade volume of the current exoPlayer by a simple linear function.
     */
    fun fadeVolume(volume: Float = 1f, duration: Long = 500, interval: Long = 20L, callback: () -> Unit = { }): Deferred<Unit> {
        return scope.async {
            val volumeDiff = (volume - exoPlayer.volume) * interval / duration
            var fadeInDuration = duration
            while (fadeInDuration > 0) {
                fadeInDuration -= interval
                exoPlayer.volume += volumeDiff
                delay(interval)
            }
            exoPlayer.volume = volume
            callback()
            return@async
        }
    }

    var playbackSpeed: Float
        get() = exoPlayer.playbackParameters.speed
        set(value) {
            exoPlayer.setPlaybackSpeed(value)
        }

    var playbackPitch: Float
        get() = exoPlayer.playbackParameters.pitch
        set(v) {
            exoPlayer.playbackParameters = PlaybackParameters(playbackSpeed, v)
        }

    val isPlaying
        get() = exoPlayer.isPlaying

    private var wasDucking = false

    fun players (): List<ExoPlayer> {
        if (options.crossfade) {
            return listOf(exoPlayer1, exoPlayer2!!)
        }
        return listOf(exoPlayer)
    }

    fun setAudioOffload(offload: Boolean = true) {
        val audioOffloadPreferences =
            TrackSelectionParameters.AudioOffloadPreferences.Builder()
                .setAudioOffloadMode(
                    if (offload) TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
                    else TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED)
                // Add additional options as needed
                .setIsGaplessSupportRequired(true)
                .setIsSpeedChangeSupportRequired(true)
                .build()
        exoPlayer.trackSelectionParameters =
            exoPlayer.trackSelectionParameters
                .buildUpon()
                .setAudioOffloadPreferences(audioOffloadPreferences)
                .build()
    }

    private fun initExoPlayer(name: String, eqProcessor: EqualizerAudioProcessor = equalizerProcessor, balProcessor: BalanceAudioProcessor = balanceProcessor, gainProc: GainAudioProcessor = gainProcessor): ExoPlayer {
        // HACK: horrible memleak, but I cant think of how to track exoplayers
        val nameHolder = arrayOf("")
        val renderer = if (options.useFFTProcessor > 0) APMRenderersFactory(
            context, options.useFFTProcessor, object: FFTEmitter {
                override fun onSpectrumReady(spectrum: FloatArray, maxRawAmp: Float) {
                    return
                }
                override fun onFrequencyFFTReady(fft: DoubleArray, max: Float) {
                    if (this@AudioPlayer.exoPlayer.toString() == nameHolder[0]) {
                        fftEmitter(fft)
                    }
                }

        }, arrayOf(eqProcessor, balProcessor, gainProc)) else APMRenderersFactory(
            context, 0, null, arrayOf(eqProcessor, balProcessor, gainProc)
        )
        renderer.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        val mPlayer = ExoPlayer
            .Builder(context)
            .setRenderersFactory(renderer)
            .setHandleAudioBecomingNoisy(options.handleAudioBecomingNoisy)
            .setMediaSourceFactory(MediaFactory(context, cache))
            .setWakeMode(setWakeMode(options.wakeMode))
            .apply {
                setLoadControl(setupBuffer(options.bufferOptions))
            }
            .setSkipSilenceEnabled(options.skipSilence)
            .setName(name)
            .build()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(options.audioContentType)
            .build()
        mPlayer.setAudioAttributes(audioAttributes, options.handleAudioFocus)
        // Prevent ExoPlayer from auto-advancing to the next queue item when a track ends.
        // All track transitions are handled by the JS side via playNext() -> load().
        // This avoids ExoPlayer trying to load queue items that don't have a URL yet.
        mPlayer.pauseAtEndOfMediaItems = true
        nameHolder[0] = mPlayer.toString()
        // https://github.com/androidx/media/issues/2319
        mPlayer.addAnalyticsListener(AudioFxInitListener())
        return mPlayer
    }

    init {
        if (options.cacheSize > 0) {
            cache = Cache.initCache(context, options.cacheSize)
        }
        playerEventHolder.updateAudioPlayerState(AudioPlayerState.IDLE)
        exoPlayer1 = initExoPlayer("APM-Player1", equalizerProcessor, balanceProcessor, gainProcessor)
        if (options.crossfade) {
            exoPlayer2 = initExoPlayer("APM-Player2", equalizerProcessor2, balanceProcessor2, gainProcessor2)
        }
        exoPlayer = exoPlayer1
        player = if (options.nativeExample) ExampleForwardingPlayer(exoPlayer1, exoPlayer2) else APMForwardingPlayer(exoPlayer1, exoPlayer2)
        player.addListener(playerListener)

    }

    /**
     * Will replace the current item with a new one and load it into the player.
     * @param item The [AudioItem] to replace the current one.
     * @param playWhenReady Whether playback starts automatically.
     */
    open fun load(item: AudioItem, playWhenReady: Boolean = true) {
        exoPlayer.playWhenReady = playWhenReady
        load(item)
    }

    /**
     * Will replace the current item with a new one and load it into the player.
     * @param item The [AudioItem] to replace the current one.
     */
    open fun load(item: AudioItem) {
        setNormalizationGain(item.options?.normalizationGain ?: 1f)
        players().forEach { p -> p.addMediaItem(audioItem2MediaItem(item)) }
        exoPlayer.prepare()
    }

    fun setLoudnessEnhance(gain: Int) {
        loudnessEnhancers.forEach { l ->
            l.setTargetGain(gain)
            l.enabled = true
        }
    }

    fun setEqualizerPreset(preset: Int) {
        equalizers.forEach { equalizer ->
            equalizer.usePreset(preset.toShort())
            equalizer.enabled = true
        }
    }

    fun getCurrentEQPreset(): Int {
        if (equalizers.isEmpty()) {
            return -1
        }
        return equalizers[0].currentPreset.toInt()
    }

    fun getEqualizerPresets(): List<String> {
        if (equalizers.isEmpty()) {
            return arrayListOf()
        }
        return Array(equalizers[0].numberOfPresets.toInt()) { i -> i }
            .map { i -> equalizers[0].getPresetName(i.toShort()) }
    }

    // 8-band Software Equalizer API (cross-platform, biquad with coefficient smoothing)

    private fun forEachEqProcessor(action: (EqualizerAudioProcessor) -> Unit) {
        action(equalizerProcessor)
        if (options.crossfade) action(equalizerProcessor2)
    }

    fun setEqualizerEnabled(enabled: Boolean) {
        forEachEqProcessor { it.isEnabled = enabled }
    }

    fun getEqualizerEnabled(): Boolean = equalizerProcessor.isEnabled

    fun getEqualizerBandCount(): Int = EqualizerAudioProcessor.BAND_COUNT

    fun setEqualizerBand(band: Int, gainDB: Float) {
        forEachEqProcessor { it.setGain(band, gainDB) }
    }

    fun setEqualizerBands(gainsDB: List<Float>) {
        forEachEqProcessor { it.setAllGains(gainsDB) }
    }

    fun getEqualizerBands(): List<Float> {
        return equalizerProcessor.getAllGains().toList()
    }

    fun getEqualizerFrequencies(): List<Int> {
        return EqualizerAudioProcessor.FREQUENCIES.map { it.toInt() }
    }

    fun getEqualizerBandLevelRange(): List<Float> = listOf(-12f, 12f)

    fun resetEqualizer() {
        forEachEqProcessor { it.resetGains() }
    }

    // Bass Boost API (software DSP — matches iOS low shelf filter)

    fun setBassBoostEnabled(enabled: Boolean) {
        forEachEqProcessor { it.isBassBoostEnabled = enabled }
    }

    fun setBassBoostLevel(level: Float) {
        forEachEqProcessor { it.updateBassBoostLevel(level) }
    }

    // Loudness Enhancer API (software DSP — matches iOS low+high shelf)

    fun setLoudnessEnabled(enabled: Boolean) {
        forEachEqProcessor { it.isLoudnessEnabled = enabled }
    }

    fun setLoudnessLevel(level: Float) {
        forEachEqProcessor { it.updateLoudnessLevel(level) }
    }

    // Virtualizer API (software DSP — matches iOS all-pass stereo widener)

    fun setVirtualizerEnabled(enabled: Boolean) {
        forEachEqProcessor { it.isVirtualizerEnabled = enabled }
    }

    fun setVirtualizerLevel(level: Float) {
        forEachEqProcessor { it.updateVirtualizerLevel(level) }
    }

    // Balance API

    fun setBalance(balance: Float) {
        balanceProcessor.setBalance(balance)
        if (options.crossfade) balanceProcessor2.setBalance(balance)
    }

    fun getBalance(): Float = balanceProcessor.getBalance()

    // Per-track loudness normalization gain. Set on both players' processors
    // (like balance) so whichever wrapper is active applies the current track's
    // gain. 1.0 = unity.
    fun setNormalizationGain(gain: Float) {
        gainProcessor.setGain(gain)
        if (options.crossfade) gainProcessor2.setGain(gain)
    }

    fun getNormalizationGain(): Float = gainProcessor.getGain()

    /**
     * Get preset names for iOS compatibility (custom presets mapped to Android system presets)
     */
    fun getEqualizerPresetNames(): List<String> {
        // Must match iOS EqualizerAudioTap.Preset order
        return listOf(
            "eqAcoustic", "eqBassBooster", "eqBassReducer", "eqClassical",
            "eqDance", "eqDeep", "eqElectronic", "eqFlat",
            "eqHipHop", "eqJazz", "eqLatin", "eqLoudness",
            "eqLounge", "eqPiano", "eqPop", "eqRnb",
            "eqRock", "eqSmallSpeakers", "eqSpokenWord",
            "eqTrebleBooster", "eqTrebleReducer", "eqVocalBooster"
        )
    }

    /**
     * Apply a preset by index (matches iOS preset order).
     * 8 bands: 60, 150, 400, 1K, 2.5K, 6K, 12K, 16K — same as software EQ.
     */
    fun applyEqualizerPreset(presetIndex: Int) {
        val presets = listOf(
            listOf( 3f,  2f,  1f,  0f,  1f,  2f,  2f,  2f),  // Acoustic
            listOf( 6f,  5f,  3f,  1f,  0f,  0f,  0f,  0f),  // Bass Booster
            listOf(-6f, -5f, -3f, -1f,  0f,  0f,  0f,  0f),  // Bass Reducer
            listOf( 4f,  2f,  0f, -1f,  0f,  2f,  3f,  3f),  // Classical
            listOf( 5f,  4f,  1f,  0f,  2f,  4f,  3f,  2f),  // Dance
            listOf( 5f,  4f,  2f,  1f,  0f, -1f, -2f, -3f),  // Deep
            listOf( 4f,  3f,  0f, -1f,  0f,  3f,  4f,  4f),  // Electronic
            listOf( 0f,  0f,  0f,  0f,  0f,  0f,  0f,  0f),  // Flat
            listOf( 5f,  4f,  2f,  0f, -1f,  1f,  2f,  3f),  // Hip-Hop
            listOf( 3f,  2f,  1f,  0f, -1f,  1f,  2f,  2f),  // Jazz
            listOf( 4f,  3f,  0f, -1f,  0f,  2f,  4f,  3f),  // Latin
            listOf( 5f,  3f,  0f, -2f,  0f,  2f,  4f,  4f),  // Loudness
            listOf(-1f,  1f,  2f,  1f,  0f, -1f,  1f,  1f),  // Lounge
            listOf( 1f,  0f,  1f,  2f,  3f,  2f,  2f,  1f),  // Piano
            listOf(-1f,  1f,  3f,  3f,  2f,  1f,  1f,  1f),  // Pop
            listOf( 5f,  4f,  2f,  0f, -1f,  1f,  2f,  2f),  // R&B
            listOf( 4f,  3f,  0f, -1f,  1f,  3f,  4f,  3f),  // Rock
            listOf( 5f,  4f,  3f,  1f,  0f, -1f,  2f,  3f),  // Small Speakers
            listOf(-2f,  0f,  1f,  3f,  4f,  3f,  1f, -1f),  // Spoken Word
            listOf( 0f,  0f,  0f,  0f,  1f,  3f,  5f,  6f),  // Treble Booster
            listOf( 0f,  0f,  0f,  0f, -1f, -3f, -5f, -6f),  // Treble Reducer
            listOf(-2f, -1f,  1f,  3f,  4f,  3f,  1f,  0f)   // Vocal Booster
        )
        if (presetIndex < 0 || presetIndex >= presets.size) return
        setEqualizerBands(presets[presetIndex])
    }

    fun togglePlaying() {
        if (exoPlayer.isPlaying) {
            pause()
        } else {
            play()
        }
    }

    var skipSilence: Boolean
        get() = exoPlayer.skipSilenceEnabled
        set(value) {
            players().forEach { p -> p.skipSilenceEnabled = value }
        }

    fun play() {
        exoPlayer.play()
        if (currentItem != null) {
            exoPlayer.prepare()
        }
    }

    fun prepare() {
        if (currentItem != null) {
            exoPlayer.prepare()
        }
    }

    fun pause() {
        // If a crossfade is mid-flight, complete it to its end state first
        // (incoming track full volume + active, outgoing track silenced +
        // paused) so a single pause reliably stops ALL audio without leaving a
        // track playing or freezing volumes at a partial level.
        finalizeCrossfade()
        exoPlayer.pause()
    }

    /**
     * Stops playback, without clearing the active item. Calling this method will cause the playback
     * state to transition to AudioPlayerState.IDLE and the player will release the loaded media and
     * resources required for playback.
     */
    @CallSuper
    open fun stop() {
        playerState = AudioPlayerState.STOPPED
        if (options.crossfade) {
            crossfadeFadeOutJob?.cancel()
            crossfadeFadeOutJob = null
            crossfadeFadeInJob?.cancel()
            crossfadeFadeInJob = null
            crossfadeFadingOutPlayer = null
            crossfadeFadingInPlayer = null
            players().forEach { p ->
                p.playWhenReady = false
                p.stop()
            }
            return
        }
        exoPlayer.playWhenReady = false
        exoPlayer.stop()
    }

    @CallSuper
    open fun clear() {
        players().forEach { p -> p.clearMediaItems() }
    }

    /**
     * Pause playback whenever an item plays to its end.
     */
    fun setPauseAtEndOfItem(pause: Boolean) {
        exoPlayer.pauseAtEndOfMediaItems = pause
    }

    /**
     * Stops and destroys the player. Only call this when you are finished using the player, otherwise use [pause].
     */
    @CallSuper
    open fun destroy() {
        focusManager.abandonAudioFocusIfHeld()
        stop()

        players().forEach { p ->
            p.removeListener(playerListener)
            p.release()
        }
        equalizers.forEach { e -> e.release() }
        loudnessEnhancers.forEach { e -> e.release() }
        cache?.release()
        cache = null
    }

    open fun seek(duration: Long, unit: TimeUnit) {
        // A seek mid-crossfade would only move the active wrapper while the
        // outgoing wrapper keeps playing the old track at its fading volume.
        // Finalize the fade first so the seek acts on a single coherent active
        // wrapper (incoming at full volume, outgoing silenced + paused).
        finalizeCrossfade()
        val positionMs = TimeUnit.MILLISECONDS.convert(duration, unit)
        exoPlayer.seekTo(positionMs)
    }

    open fun seekBy(offset: Long, unit: TimeUnit) {
        // See seek(): finalize an in-flight crossfade before seeking so only the
        // active wrapper is affected.
        finalizeCrossfade()
        val positionMs = exoPlayer.currentPosition + TimeUnit.MILLISECONDS.convert(offset, unit)
        exoPlayer.seekTo(positionMs)
    }

    fun crossFadePrepare(previous: Boolean = false, seekTo: Double = 0.0) {
        if (!options.crossfade) { return }
        val mPlayer = if (currentExoPlayer) exoPlayer2!! else exoPlayer1
        val activeIndex = exoPlayer.currentMediaItemIndex
        // align playing index
        mPlayer.seekTo(activeIndex, C.TIME_UNSET)
        if (previous) { mPlayer.seekToPreviousMediaItem() }
        else { mPlayer.seekToNextMediaItem() }

        mPlayer.prepare()

        if (seekTo > 0) {
            mPlayer.seekTo((seekTo * 1000).toLong())
        }
    }

    /**
     * switches rotating exoplayers to achieve crossfade.
     * playerOperation:
     */
    fun switchExoPlayer(
        playerOperation: () -> Unit = ::play,
        fadeDuration: Long = 2500,
        fadeInterval: Long = 20,
        fadeToVolume: Float = 1f,
        waitUntil: Long = 0,
    ){
        if (!options.crossfade) {
            playerOperation()
            return
        }
        // Reject the swap if the wrapper about to be promoted isn't in a state
        // where playback can start. A stale JS prebuffer flag can otherwise
        // crossFade to an IDLE/ENDED wrapper and leave audio stuck at 00:00.
        // JS catches this throw and falls back to a cold TrackPlayer.load().
        val targetPlayer = if (currentExoPlayer) exoPlayer2!! else exoPlayer1
        if (targetPlayer.currentMediaItem == null
            || targetPlayer.playbackState == Player.STATE_IDLE
            || targetPlayer.playbackState == Player.STATE_ENDED) {
            throw IllegalStateException(
                "crossFade: inactive wrapper not ready (state=${targetPlayer.playbackState}, mediaItem=${targetPlayer.currentMediaItem?.mediaId})"
            )
        }
        scope.launch {
            val delayAmount = if (waitUntil == 0L) 0 else {
                0L.coerceAtLeast(waitUntil - player.currentPosition)
            }
            delay(delayAmount)

            val prevPlayer: Player
            if (currentExoPlayer) {
                currentExoPlayer = false
                exoPlayer = exoPlayer2!!
                prevPlayer = exoPlayer1
            } else {
                currentExoPlayer = true
                exoPlayer = exoPlayer1
                prevPlayer = exoPlayer2!!
            }

            prevPlayer.setAudioAttributes(prevPlayer.audioAttributes, false)
            player.switchCrossFadePlayer()

            crossfadeFadingOutPlayer = prevPlayer
            crossfadeTargetVolume = fadeToVolume
            crossfadeFadeOutJob = scope.launch {
                var fadeOutDuration = fadeDuration
                val startFadeOutTime = System.currentTimeMillis()
                val fadeFromVolume = prevPlayer.volume
                while (fadeOutDuration > 0) {
                    fadeOutDuration -= fadeInterval
                    // Guard: if prevPlayer was re-promoted to active by a
                    // subsequent crossFade, stop touching its volume — the
                    // new fade-in task owns it.
                    if (prevPlayer === exoPlayer) break
                    prevPlayer.volume = fadeFromVolume * (1 - min((System.currentTimeMillis() - startFadeOutTime), fadeDuration).toFloat() / fadeDuration)
                    delay(fadeInterval)
                }
                // Guard: only finalize/pause if prevPlayer is still the
                // inactive wrapper. Without this, an overlapping crossFade
                // sequence (rapid skips → chained crossfades) can have this
                // task pause the now-active wrapper after its timer fires.
                if (prevPlayer !== exoPlayer) {
                    prevPlayer.volume = 0f
                    prevPlayer.pause()
                }
            }
            // Capture the just-promoted wrapper. Without this the fade-in
            // loop reads the GLOBAL `exoPlayer` each iteration — a subsequent
            // crossFade would have this task start writing volume to the NEW
            // active wrapper, racing with the new fade-in task and producing
            // chaotic volumes (audible as "doubled audio").
            val newPlayer = exoPlayer
            crossfadeFadingInPlayer = newPlayer
            crossfadeFadeInJob = scope.launch {
                newPlayer.volume = 0f
                playerOperation()
                newPlayer.setAudioAttributes(newPlayer.audioAttributes, options.handleAudioFocus)
                if (fadeToVolume > 0) {
                    if (fadeDuration <= 0) {
                        // duration=0 means "instant swap": apply target volume
                        // directly. Without this the fade loop never runs and
                        // newPlayer stays at the volume=0f set above → silent
                        // audio after a prebuffered-fast-path crossFade.
                        newPlayer.volume = fadeToVolume
                    } else {
                        var fadeInDuration = fadeDuration
                        val startTime = System.currentTimeMillis()
                        while (fadeInDuration > 0) {
                            fadeInDuration -= fadeInterval
                            // Guard: if newPlayer was demoted to inactive, stop —
                            // the new fade-out task owns its volume.
                            if (newPlayer !== exoPlayer) break
                            newPlayer.volume = fadeToVolume * min((System.currentTimeMillis() - startTime), fadeDuration) / fadeDuration
                            delay(fadeInterval)
                        }
                    }
                }
            }
        }
    }

    inner class AudioFxInitListener: AnalyticsListener {
        @OptIn(UnstableApi::class)
        override fun onAudioSessionIdChanged(eventTime: AnalyticsListener.EventTime, audioSessionId: Int) {
            // Release old native effects before creating new ones
            loudnessEnhancers.forEach { try { it.release() } catch (_: Exception) {} }
            loudnessEnhancers.clear()
            equalizers.forEach { try { it.release() } catch (_: Exception) {} }
            equalizers.clear()

            // Native LoudnessEnhancer (only for setLoudnessEnhance legacy API)
            try {
                val enhancer = LoudnessEnhancer(audioSessionId)
                loudnessEnhancers.add(enhancer)
            } catch (e: RuntimeException) {
                Timber.tag("APMAudioFx").e("[AudioFx] failed to load loudnessEnhancer. it's fine if in dev!")
            }

            // Native Equalizer (only for setEqualizerPreset legacy API)
            try {
                val equalizer = Equalizer(0, audioSessionId)
                equalizers.add(equalizer)
            } catch (e: RuntimeException) {
                Timber.tag("APMAudioFx").e("[AudioFx] failed to load equalizer. it's fine if in dev!")
            }

            // Bass boost, loudness, virtualizer, EQ bands — all handled by
            // software EqualizerAudioProcessor (no native effects needed)
        }
    }

    /**
     * Hooks for the queue-aware subclass to intercept ExoPlayer reaching an item the
     * JS side has not resolved a source for yet (a `notPlayable` placeholder).
     *
     * The queue can legitimately hold such placeholders: the JS side seeds upcoming
     * items with an empty url and fills them in as it resolves them. `load()`/`play()`
     * already refuse to play one, but ExoPlayer's OWN automatic advance at the end of a
     * track does not go through those paths — it just prepares the next MediaItem, whose
     * empty URI ends in `FileNotFoundException: null` → `Source error`, and the session
     * is left dead until something reloads it by hand.
     *
     * Returning true means the subclass has taken over (it stops playback and asks the
     * JS side to resolve the item), so the default event handling must not run.
     */
    protected open fun onAutoTransitionToNotPlayableItem(): Boolean = false

    /** Same, as a safety net once the failed prepare has already surfaced as an error. */
    protected open fun onPlaybackErrorOnNotPlayableItem(): Boolean = false

    inner class PlayerListener : Listener {

        /**
         * Called when there is metadata associated with the current playback time.
         */
        override fun onMetadata(metadata: Metadata) {
            playerEventHolder.updateOnTimedMetadata(metadata)
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            playerEventHolder.updateOnCommonMetadata(mediaMetadata)
        }

        /**
         * A position discontinuity occurs when the playing period changes, the playback position
         * jumps within the period currently being played, or when the playing period has been
         * skipped or removed.
         */
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            this@AudioPlayer.oldPosition = oldPosition.positionMs

            when (reason) {
                Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> playerEventHolder.updatePositionChangedReason(
                    PositionChangedReason.AUTO(oldPosition.positionMs, newPosition.positionMs)
                )
                Player.DISCONTINUITY_REASON_SEEK -> playerEventHolder.updatePositionChangedReason(
                    PositionChangedReason.SEEK(oldPosition.positionMs, newPosition.positionMs)
                )
                Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> playerEventHolder.updatePositionChangedReason(
                    PositionChangedReason.SEEK_FAILED(
                        oldPosition.positionMs,
                        newPosition.positionMs
                    )
                )
                Player.DISCONTINUITY_REASON_REMOVE -> playerEventHolder.updatePositionChangedReason(
                    PositionChangedReason.QUEUE_CHANGED(
                        oldPosition.positionMs,
                        newPosition.positionMs
                    )
                )
                Player.DISCONTINUITY_REASON_SKIP -> playerEventHolder.updatePositionChangedReason(
                    PositionChangedReason.SKIPPED_PERIOD(
                        oldPosition.positionMs,
                        newPosition.positionMs
                    )
                )
                Player.DISCONTINUITY_REASON_INTERNAL -> playerEventHolder.updatePositionChangedReason(
                    PositionChangedReason.UNKNOWN(oldPosition.positionMs, newPosition.positionMs)
                )

                Player.DISCONTINUITY_REASON_SILENCE_SKIP -> playerEventHolder.updatePositionChangedReason(
                    PositionChangedReason.UNKNOWN(oldPosition.positionMs, newPosition.positionMs)
                )
            }
        }

        /**
         * Called when playback transitions to a media item or starts repeating a media item
         * according to the current repeat mode. Note that this callback is also called when the
         * playlist becomes non-empty or empty as a consequence of a playlist change.
         */
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            when (reason) {
                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> {
                    // ExoPlayer advanced on its own. If it landed on a placeholder whose
                    // source the JS side has not resolved yet, hand it back instead of
                    // letting it prepare an empty URI (see onAutoTransitionToNotPlayableItem).
                    if (onAutoTransitionToNotPlayableItem()) return
                    playerEventHolder.updateAudioItemTransition(
                        AudioItemTransitionReason.AUTO(oldPosition)
                    )
                }
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> playerEventHolder.updateAudioItemTransition(
                    AudioItemTransitionReason.QUEUE_CHANGED(oldPosition)
                )
                Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> {
                    // Seek to 0 to update MediaSession position when track loops
                    // This fixes the notification progress bar not resetting on loop
                    exoPlayer.seekTo(0)
                    playerEventHolder.updateAudioItemTransition(AudioItemTransitionReason.REPEAT(oldPosition))
                }
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> playerEventHolder.updateAudioItemTransition(
                    AudioItemTransitionReason.SEEK_TO_ANOTHER_AUDIO_ITEM(oldPosition)
                )
            }
        }

        /**
         * Called when the value returned from Player.getPlayWhenReady() changes.
         */
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val pausedBecauseReachedEnd = reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
            playerEventHolder.updatePlayWhenReadyChange(PlayWhenReadyChangeData(playWhenReady, pausedBecauseReachedEnd))
        }

        /**
         * The generic onEvents callback provides access to the Player object and specifies the set
         * of events that occurred together. It’s always called after the callbacks that correspond
         * to the individual events.
         */
        override fun onEvents(player: Player, events: Player.Events) {
            // Note that it is necessary to set `playerState` in order, since each mutation fires an
            // event.
            for (i in 0 until events.size()) {
                when (events[i]) {
                    Player.EVENT_PLAYBACK_STATE_CHANGED -> {
                        val state = when (player.playbackState) {
                            Player.STATE_BUFFERING -> AudioPlayerState.BUFFERING
                            Player.STATE_READY -> AudioPlayerState.READY
                            Player.STATE_IDLE ->
                                // Avoid transitioning to idle from error or stopped
                                if (
                                    playerState == AudioPlayerState.ERROR ||
                                    playerState == AudioPlayerState.STOPPED
                                )
                                    null
                                else
                                    AudioPlayerState.IDLE
                            Player.STATE_ENDED ->
                                if (player.mediaItemCount > 0) AudioPlayerState.ENDED
                                else AudioPlayerState.IDLE
                            else -> null // noop
                        }
                        if (state != null && state != playerState) {
                            playerState = state
                        }
                    }
                    Player.EVENT_MEDIA_ITEM_TRANSITION -> {
                        playbackError = null
                        if (currentItem != null) {
                            playerState = AudioPlayerState.LOADING
                            if (isPlaying) {
                                playerState = AudioPlayerState.READY
                                playerState = AudioPlayerState.PLAYING
                            }
                        }
                    }
                    Player.EVENT_PLAY_WHEN_READY_CHANGED -> {
                        if (!player.playWhenReady && playerState != AudioPlayerState.STOPPED) {
                            playerState = AudioPlayerState.PAUSED
                        }
                    }
                    Player.EVENT_IS_PLAYING_CHANGED -> {
                        if (player.isPlaying) {
                            playerState = AudioPlayerState.PLAYING
                        }
                    }
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // A failed prepare of an unresolved placeholder is not a real playback
            // failure: ask the JS side to resolve it rather than parking the player
            // (and the whole media session) in ERROR with no way back.
            if (onPlaybackErrorOnNotPlayableItem()) return
            val _playbackError = PlaybackError(
                error.errorCodeName
                    .replace("ERROR_CODE_", "")
                    .lowercase(Locale.getDefault())
                    .replace("_", "-"),
                error.message
            )
            playerEventHolder.updatePlaybackError(_playbackError)
            playbackError = _playbackError
            playerState = AudioPlayerState.ERROR
        }
    }


    private open inner class ExampleForwardingPlayer
        (val mPlayer1: ExoPlayer, val mPlayer2: ExoPlayer?): ForwardingPlayer(mPlayer1, mPlayer2) {
        override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
            mPlayer1.setMediaItems(mediaItems, resetPosition)
            mPlayer2?.setMediaItems(mediaItems, resetPosition)
        }
        override fun isCommandAvailable(command: Int): Boolean {
            if (options.alwaysShowNext) {
                return when (command) {
                    COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> true
                    COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> true
                    else -> super.isCommandAvailable(command)
                }
            }
            return super.isCommandAvailable(command)
        }

        override fun getAvailableCommands(): Player.Commands {
            if (options.alwaysShowNext) {
                return super.getAvailableCommands().buildUpon()
                    .add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()
            }
            return super.getAvailableCommands()
        }
    }

    private inner class APMForwardingPlayer
        (mPlayer1: ExoPlayer, mPlayer2: ExoPlayer?): ExampleForwardingPlayer(mPlayer1, mPlayer2) {
        override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
            // override setMediaItem handling to RNTP
            return
        }

        override fun addMediaItems(mediaItems: MutableList<MediaItem>) {
            // override setMediaItem handling to RNTP
            return
        }

        override fun addMediaItems(index: Int, mediaItems: MutableList<MediaItem>) {
            // override setMediaItem handling to RNTP
            return
        }

        override fun setMediaItems(
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ) {
            // override setMediaItem handling to RNTP
            return
        }

        override fun setMediaItems(mediaItems: MutableList<MediaItem>) {
            // override setMediaItem handling to RNTP
            return
        }

        override fun play() {
            playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.PLAY)
        }

        override fun pause() {
            playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.PAUSE)
        }

        // media3 dispatches the notification / lock-screen pause button as
        // setPlayWhenReady(false), NOT pause(); without this override it fell
        // through to the real ExoPlayer and never emitted PAUSE, so the WebView
        // audio kept playing while skip (seekToNext) worked (86capydre).
        override fun setPlayWhenReady(playWhenReady: Boolean) {
            playerEventHolder.updateOnPlayerActionTriggeredExternally(
                if (playWhenReady) MediaSessionCallback.PLAY else MediaSessionCallback.PAUSE
            )
        }

        override fun seekToNext() {
            playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.NEXT)
        }

        override fun seekToNextMediaItem() {
            playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.NEXT)
        }

        override fun seekToPrevious() {
            playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.PREVIOUS)
        }

        override fun seekToPreviousMediaItem() {
            playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.PREVIOUS)
        }

        override fun seekForward() {
            playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.FORWARD)
        }

        override fun seekBack() {
            playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.REWIND)
        }

        override fun stop() {
            playerEventHolder.updateOnPlayerActionTriggeredExternally(MediaSessionCallback.STOP)
        }

        override fun seekToDefaultPosition(mediaItemIndex: Int) {
            // Called when Android Auto user taps a queue item (COMMAND_SEEK_TO_MEDIA_ITEM).
            // Intercept to prevent ExoPlayer from trying to load tracks with empty URLs
            // (notPlayable). Emit PLAY_FROM_ID so the JS side resolves the source first.
            try {
                val mediaItem = this@AudioPlayer.exoPlayer.getMediaItemAt(mediaItemIndex)
                val mediaId = mediaItem?.mediaId
                if (!mediaId.isNullOrEmpty()) {
                    playerEventHolder.updateOnPlayerActionTriggeredExternally(
                        MediaSessionCallback.PLAY_FROM_ID(mediaId)
                    )
                    return
                }
            } catch (_: Exception) { }
            // Fallback: delegate to ExoPlayer
            super.seekToDefaultPosition(mediaItemIndex)
        }

        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            playerEventHolder.updateOnPlayerActionTriggeredExternally(
                MediaSessionCallback.SEEK(
                    positionMs
                )
            )
        }

        override fun seekTo(positionMs: Long) {
            playerEventHolder.updateOnPlayerActionTriggeredExternally(
                MediaSessionCallback.SEEK(
                    positionMs
                )
            )
        }
    }

    private inner class APMFocusListener: AudioManager.OnAudioFocusChangeListener {
        override fun onAudioFocusChange(focusChange: Int) {
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    playerEventHolder.updateOnAudioFocusChanged(isPaused = true, isPermanent = true)
                    pause()
                    focusManager.hasAudioFocus = false
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    playerEventHolder.updateOnAudioFocusChanged(isPaused = true, isPermanent = false)
                    if (isPlaying) {
                        wasDucking = false
                        pause()
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    playerEventHolder.updateOnAudioFocusChanged(isPaused = true, isPermanent = false)
                    if (alwaysPauseOnInterruption) {
                        if (isPlaying) {
                            wasDucking = false
                            pause()
                        }
                    } else {
                        wasDucking = true
                        volumeMultiplier = 0.2f
                    }
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    playerEventHolder.updateOnAudioFocusChanged(isPaused = false, isPermanent = false)
                    if (wasDucking) {
                        volumeMultiplier = 1f
                        wasDucking = false
                    }
                    focusManager.hasAudioFocus = true
                }
            }
        }
    }
}
