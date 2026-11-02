package com.lovegaoshi.kotlinaudio.player.components

import android.media.AudioManager
import com.lovegaoshi.kotlinaudio.models.AudioPlayerState
import com.lovegaoshi.kotlinaudio.models.FocusChangeData
import com.lovegaoshi.kotlinaudio.models.PlayerOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The focus policy of the player, driven the way AudioPlayer drives it: every player state change
 * goes through [FocusManager.onPlayerStateChanged], and APMFocusListener reports AUDIOFOCUS_LOSS /
 * AUDIOFOCUS_GAIN. The gateway stands in for the system focus stack.
 */
class FocusManagerTest {
    private class FakeSystem : AudioFocusGateway {
        var requests = 0
        var abandons = 0
        var grant = true
        var abandonResult = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        var lastWillPauseWhenDucked: Boolean? = null

        /** Whether our client is in the system focus stack. */
        var inStack = false

        /** A call is up: AudioManager's mode is not NORMAL. */
        var inCall = false

        override fun callActive(): Boolean = inCall

        override fun request(willPauseWhenDucked: Boolean): Int {
            requests++
            lastWillPauseWhenDucked = willPauseWhenDucked
            if (!grant) return AudioManager.AUDIOFOCUS_REQUEST_FAILED
            inStack = true
            return AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }

        override fun abandon(): Int {
            abandons++
            if (abandonResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) inStack = false
            return abandonResult
        }

        /** Another app takes AUDIOFOCUS_GAIN: the system drops us and sends AUDIOFOCUS_LOSS. */
        fun anotherAppTakesFocus(focus: FocusManager, playWhenReady: Boolean = true): FocusReaction? {
            inStack = false
            return focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS, playWhenReady, playWhenReady)
        }

        /** A call (or a voice note) takes AUDIOFOCUS_GAIN_TRANSIENT: we stay in the stack. */
        fun callStarts(
            focus: FocusManager,
            playWhenReady: Boolean = true,
            isPlaying: Boolean = playWhenReady,
        ): FocusReaction? {
            inCall = true
            return focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, playWhenReady, isPlaying)
        }

        /** The transient owner gives the focus back: AUDIOFOCUS_GAIN. */
        fun callEnds(focus: FocusManager, playWhenReady: Boolean = false): FocusReaction? {
            inCall = false
            return focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN, playWhenReady, playWhenReady)
        }

        /** A voice note or an assistant: a transient loss with no call. */
        fun voiceNoteStarts(focus: FocusManager, playWhenReady: Boolean = true): FocusReaction? =
            focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, playWhenReady, playWhenReady)

        /** A navigation prompt: AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK. */
        fun promptStarts(
            focus: FocusManager,
            playWhenReady: Boolean = true,
            isPlaying: Boolean = playWhenReady,
        ): FocusReaction? =
            focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, playWhenReady, isPlaying)
    }

    private fun manager(
        system: FakeSystem,
        alwaysPauseOnInterruption: Boolean = false,
        strict: Boolean = true,
    ) = FocusManager(system, PlayerOptions(alwaysPauseOnInterruption = alwaysPauseOnInterruption)).apply {
        this.strict = strict
    }

    /**
     * Drives the states with the player meant to play (playWhenReady true), as a play does.
     * Returns false if any of them was refused.
     */
    private fun FocusManager.enter(
        vararg states: AudioPlayerState,
        exoPlayerHandlesFocus: Boolean = false,
        playWhenReady: Boolean = true,
    ): Boolean = states.map { onPlayerStateChanged(it, exoPlayerHandlesFocus, playWhenReady) }.all { it }

    @Test
    fun playAfterPermanentLossAsksForTheFocusAgain() {
        val system = FakeSystem()
        val focus = manager(system)
        focus.enter(AudioPlayerState.BUFFERING, AudioPlayerState.READY, AudioPlayerState.PLAYING)
        assertEquals(1, system.requests)
        assertTrue(system.inStack)

        // An interstitial video (or another music app) takes the focus; the player pauses.
        system.anotherAppTakesFocus(focus)
        focus.enter(AudioPlayerState.PAUSED)
        assertFalse(focus.hasAudioFocus)

        // Play: ExoPlayer stays in STATE_READY while paused, so the player goes straight to
        // PLAYING. This used to leave us out of the focus stack for good.
        focus.enter(AudioPlayerState.PLAYING)
        assertEquals(2, system.requests)
        assertTrue(focus.hasAudioFocus)
        assertTrue(system.inStack)

        // And the next app that takes the focus reaches us again.
        system.anotherAppTakesFocus(focus)
        focus.enter(AudioPlayerState.PAUSED, AudioPlayerState.PLAYING)
        assertEquals(3, system.requests)
        assertTrue(system.inStack)
    }

    @Test
    fun resumingWhileHoldingTheFocusAsksNothing() {
        val system = FakeSystem()
        val focus = manager(system)
        focus.enter(AudioPlayerState.READY, AudioPlayerState.PLAYING)
        repeat(3) { focus.enter(AudioPlayerState.PAUSED, AudioPlayerState.PLAYING) }
        assertEquals(1, system.requests)
        assertEquals(0, system.abandons)
    }

    @Test
    fun transientLossLendsTheFocusAndGainGivesItBack() {
        val system = FakeSystem()
        val focus = manager(system)
        focus.enter(AudioPlayerState.READY, AudioPlayerState.PLAYING)
        // AUDIOFOCUS_LOSS_TRANSIENT (a call): the player pauses, the system keeps us in its stack.
        val loss = system.callStarts(focus)!!
        assertTrue(loss.pause)
        assertEquals(FocusChangeData(true, false, true), loss.report)
        focus.enter(AudioPlayerState.PAUSED)
        assertFalse(focus.hasAudioFocus)
        assertTrue(focus.lentOut)
        // AUDIOFOCUS_GAIN when the call ends: the focus is ours again, the player does not start
        // by itself (JS decides), and the resume asks for nothing.
        val gain = system.callEnds(focus)!!
        assertFalse(gain.pause)
        assertEquals(FocusChangeData(false, false, false), gain.report)
        assertTrue(focus.enter(AudioPlayerState.PLAYING))
        assertEquals(1, system.requests)
        assertTrue(focus.hasAudioFocus)
        assertFalse(focus.lentOut)
    }

    private fun FocusManager.duckLog(): MutableList<Boolean> {
        val log = mutableListOf<Boolean>()
        onDuckChanged = { log.add(it) }
        return log
    }

    @Test
    fun duckingKeepsTheFocusAndGainRestoresTheVolume() {
        val system = FakeSystem()
        val focus = manager(system)
        val duck = focus.duckLog()
        focus.enter(AudioPlayerState.READY, AudioPlayerState.PLAYING)
        // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK only lowers the volume and leaves the focus ours, so
        // playing on (or a pause/play while ducked) asks for nothing.
        val duckReaction = system.promptStarts(focus)!!
        assertFalse(duckReaction.pause)
        focus.enter(AudioPlayerState.PAUSED, AudioPlayerState.PLAYING)
        assertTrue(focus.ducked)
        system.callEnds(focus)
        assertEquals(1, system.requests)
        assertEquals(0, system.abandons)
        assertTrue(focus.hasAudioFocus)
        assertFalse(focus.ducked)
        assertEquals(listOf(true, false), duck)
    }

    @Test
    fun aTransientLossOnTopOfADuckStillEndsAtFullVolume() {
        val system = FakeSystem()
        val focus = manager(system)
        val duck = focus.duckLog()
        focus.enter(AudioPlayerState.READY, AudioPlayerState.PLAYING)
        system.promptStarts(focus) // a navigation prompt
        system.callStarts(focus) // then a call: AUDIOFOCUS_LOSS_TRANSIENT pauses
        focus.enter(AudioPlayerState.PAUSED)
        system.callEnds(focus) // the call ends
        focus.enter(AudioPlayerState.PLAYING)
        assertFalse(focus.ducked)
        assertEquals(listOf(true, false), duck)
    }

    @Test
    fun aPermanentLossEndsTheDuck() {
        val system = FakeSystem()
        val focus = manager(system)
        val duck = focus.duckLog()
        focus.enter(AudioPlayerState.READY, AudioPlayerState.PLAYING)
        system.promptStarts(focus)
        system.anotherAppTakesFocus(focus)
        focus.enter(AudioPlayerState.PAUSED, AudioPlayerState.PLAYING)
        assertFalse(focus.ducked)
        assertEquals(listOf(true, false), duck)
        assertEquals(2, system.requests)
    }

    @Test
    fun stoppingWhileDuckedRestoresTheVolume() {
        val system = FakeSystem()
        val focus = manager(system)
        val duck = focus.duckLog()
        focus.enter(AudioPlayerState.READY, AudioPlayerState.PLAYING)
        system.promptStarts(focus)
        // stop() gives the focus back: the GAIN that would end the duck never comes.
        focus.enter(AudioPlayerState.STOPPED)
        assertFalse(focus.ducked)
        focus.enter(AudioPlayerState.LOADING, AudioPlayerState.READY, AudioPlayerState.PLAYING)
        assertFalse(focus.ducked)
        assertEquals(listOf(true, false), duck)
    }

    @Test
    fun whenExoPlayerHandlesTheFocusPlayingAsksNothing() {
        val system = FakeSystem()
        val focus = manager(system)
        focus.enter(AudioPlayerState.READY, AudioPlayerState.PLAYING, exoPlayerHandlesFocus = true)
        assertEquals(1, system.requests)
        system.anotherAppTakesFocus(focus)
        // ExoPlayer's own client asks on play; a second request would take the focus from it.
        focus.enter(AudioPlayerState.PAUSED, AudioPlayerState.PLAYING, exoPlayerHandlesFocus = true)
        assertEquals(1, system.requests)
    }

    @Test
    fun stopGivesTheFocusBackAndTheNextTrackAsksAgain() {
        val system = FakeSystem()
        val focus = manager(system)
        focus.enter(AudioPlayerState.READY, AudioPlayerState.PLAYING, AudioPlayerState.STOPPED)
        assertEquals(1, system.abandons)
        assertFalse(focus.hasAudioFocus)
        assertFalse(system.inStack)

        focus.enter(AudioPlayerState.LOADING, AudioPlayerState.BUFFERING, AudioPlayerState.READY, AudioPlayerState.PLAYING)
        assertEquals(2, system.requests)
        assertTrue(system.inStack)
    }

    @Test
    fun idleAndErrorGiveTheFocusBackOnce() {
        val system = FakeSystem()
        val focus = manager(system)
        focus.enter(AudioPlayerState.READY, AudioPlayerState.ERROR, AudioPlayerState.IDLE, AudioPlayerState.STOPPED)
        assertEquals(1, system.abandons)
    }

    @Test
    fun aDeniedRequestRefusesToPlayAndIsRetriedOnTheNextPlay() {
        val system = FakeSystem()
        val focus = manager(system)
        system.grant = false
        system.inCall = true
        // A start during a call: the request is refused and the player must not play. It used to
        // play anyway, over the call.
        assertFalse(focus.onPlayerStateChanged(AudioPlayerState.READY, false, true))
        assertFalse(focus.onPlayerStateChanged(AudioPlayerState.PLAYING, false, true))
        assertEquals(2, system.requests)
        assertFalse(focus.hasAudioFocus)
        // Out of the stack: nothing will come back, so the refusal is final.
        assertEquals(FocusChangeData(true, true, true, denied = true), focus.refusal())

        system.grant = true
        system.inCall = false
        assertTrue(focus.enter(AudioPlayerState.PAUSED, AudioPlayerState.PLAYING))
        assertEquals(3, system.requests)
        assertTrue(focus.hasAudioFocus)
    }

    @Test
    fun aStreamErrorDuringACallKeepsThePlaceInTheStack() {
        val system = FakeSystem()
        val focus = manager(system)
        focus.enter(AudioPlayerState.READY, AudioPlayerState.PLAYING)
        system.callStarts(focus)
        focus.enter(AudioPlayerState.PAUSED)
        // The stream fails while the call is up. Giving the focus back here left nothing to bring
        // the music back after the call.
        focus.enter(AudioPlayerState.ERROR)
        assertEquals(0, system.abandons)
        assertTrue(focus.lentOut)
        assertTrue(system.inStack)
        // JS retries the track during the call: refused without asking, and not for good.
        system.grant = false
        assertFalse(focus.mayStart())
        assertEquals(1, system.requests)
        assertEquals(FocusChangeData(true, false, true, denied = true), focus.refusal())
        // The call ends: the focus is ours, the retry plays without asking.
        system.callEnds(focus)
        assertTrue(focus.enter(AudioPlayerState.LOADING, AudioPlayerState.READY, AudioPlayerState.PLAYING))
        assertEquals(1, system.requests)
    }

    @Test
    fun aStopDuringACallStillGivesTheFocusBack() {
        val system = FakeSystem()
        val focus = manager(system)
        focus.enter(AudioPlayerState.READY, AudioPlayerState.PLAYING)
        system.callStarts(focus)
        focus.enter(AudioPlayerState.STOPPED)
        assertEquals(1, system.abandons)
        assertFalse(focus.lentOut)
    }

    @Test
    fun anErrorWithTheFocusOursGivesItBack() {
        val system = FakeSystem()
        val focus = manager(system)
        focus.enter(AudioPlayerState.READY, AudioPlayerState.PLAYING, AudioPlayerState.ERROR)
        assertEquals(1, system.abandons)
        assertFalse(focus.hasAudioFocus)
    }

    @Test
    fun underTheOldContractAnErrorDuringACallGivesTheFocusBack() {
        val system = FakeSystem()
        val focus = manager(system, strict = false)
        focus.enter(AudioPlayerState.READY, AudioPlayerState.PLAYING)
        system.callStarts(focus)
        focus.enter(AudioPlayerState.ERROR)
        assertEquals(1, system.abandons)
    }

    @Test
    fun aRefusalWithNoCallUpLetsThePlayerStart() {
        val system = FakeSystem()
        val focus = manager(system)
        // Android 15 refuses the focus to an app that is neither on screen nor running a
        // foreground service: a Play from the lock screen or a car after the service went down.
        // media3 promotes the service only once playback starts, so refusing here would leave the
        // music stopped for good. PLAYING asks again once the service is promoted.
        system.grant = false
        assertTrue(focus.mayStart())
        assertTrue(focus.enter(AudioPlayerState.READY, AudioPlayerState.PLAYING))
        assertFalse(focus.hasAudioFocus)
        system.grant = true
        assertTrue(focus.enter(AudioPlayerState.PAUSED, AudioPlayerState.PLAYING))
        assertTrue(focus.hasAudioFocus)
    }

    @Test
    fun underTheOldContractNothingIsRefused() {
        val system = FakeSystem()
        val focus = manager(system, strict = false)
        system.grant = false
        system.inCall = true
        assertTrue(focus.mayStart())
        assertTrue(focus.enter(AudioPlayerState.READY, AudioPlayerState.PLAYING))
    }

    @Test
    fun underTheOldContractATransientLossIsNotAskedAboutAgain() {
        val system = FakeSystem()
        val focus = manager(system, strict = false)
        focus.enter(AudioPlayerState.READY, AudioPlayerState.PLAYING)
        system.callStarts(focus)
        focus.enter(AudioPlayerState.PAUSED, AudioPlayerState.PLAYING)
        assertEquals(1, system.requests)
        assertTrue(focus.lentOut)
    }

    @Test
    fun underTheOldContractABufferingTrackIsNotPaused() {
        val system = FakeSystem()
        val focus = manager(system, strict = false)
        focus.enter(AudioPlayerState.READY, AudioPlayerState.PLAYING, AudioPlayerState.BUFFERING)
        // A JS side that reads `paused` off its own state would never resume it: it keeps the old
        // behaviour until the app opts in (updateOptions android.strictAudioFocus).
        val loss = system.callStarts(focus, playWhenReady = true, isPlaying = false)!!
        assertFalse(loss.pause)
        assertEquals(true, loss.report.playWhenReady)
        // Playing, it pauses as it always did.
        system.callEnds(focus)
        assertTrue(system.callStarts(focus, playWhenReady = true, isPlaying = true)!!.pause)
    }

    @Test
    fun aPlayDuringACallIsRefusedAndTheCallStillEndsWithGain() {
        val system = FakeSystem()
        val focus = manager(system)
        focus.enter(AudioPlayerState.READY, AudioPlayerState.PLAYING)
        system.callStarts(focus)
        focus.enter(AudioPlayerState.PAUSED)
        // The user presses play during the call. Lent out is not leave to play: refused (the
        // system would refuse the request anyway while a call is up), without asking.
        system.grant = false
        assertFalse(focus.mayStart())
        assertFalse(focus.enter(AudioPlayerState.PLAYING))
        assertEquals(1, system.requests)
        assertFalse(focus.hasAudioFocus)
        assertTrue(focus.lentOut)
        // Still in the stack: the GAIN at the end of the call comes, so the refusal is not final
        // and the music the call interrupted may still resume then.
        assertEquals(FocusChangeData(true, false, true, denied = true), focus.refusal())
        system.callEnds(focus)
        assertTrue(focus.enter(AudioPlayerState.PLAYING))
        assertEquals(1, system.requests)
    }

    @Test
    fun aStartDuringAVoiceNoteDoesNotTakeTheFocusFromIt() {
        val system = FakeSystem()
        val focus = manager(system)
        focus.enter(AudioPlayerState.READY, AudioPlayerState.PLAYING)
        system.voiceNoteStarts(focus)
        focus.enter(AudioPlayerState.PAUSED)
        // A start now may be nobody's: a track that finishes loading, a pending crossfade, an
        // error retry. Asking would be granted and stop the voice note; it starts as it always
        // did, without asking, and the focus stays lent until the GAIN.
        assertTrue(focus.mayStart())
        assertTrue(focus.enter(AudioPlayerState.PLAYING))
        assertEquals(1, system.requests)
        assertTrue(focus.lentOut)
        system.callEnds(focus)
        assertTrue(focus.hasAudioFocus)
        assertFalse(focus.lentOut)
    }

    @Test
    fun aTransientLossWhileBufferingPauses() {
        val system = FakeSystem()
        val focus = manager(system)
        focus.enter(AudioPlayerState.READY, AudioPlayerState.PLAYING, AudioPlayerState.BUFFERING)
        // Buffering is not playing (isPlaying false) but the player is meant to play: a pause
        // decided on isPlaying let it start over the call as soon as its data arrived.
        val loss = system.callStarts(focus, playWhenReady = true, isPlaying = false)!!
        assertTrue(loss.pause)
        // And JS hears that it was meant to play, so it can resume it on GAIN.
        assertEquals(true, loss.report.playWhenReady)
    }

    @Test
    fun aTransientLossWhilePausedLeavesItPaused() {
        val system = FakeSystem()
        val focus = manager(system)
        focus.enter(AudioPlayerState.READY, AudioPlayerState.PLAYING, AudioPlayerState.PAUSED)
        val loss = system.callStarts(focus, playWhenReady = false)!!
        assertFalse(loss.pause)
        assertEquals(FocusChangeData(true, false, false), loss.report)
        assertTrue(focus.lentOut)
    }

    @Test
    fun aDuckThatPausesTreatsBufferingAsPlaying() {
        val system = FakeSystem()
        val focus = manager(system, alwaysPauseOnInterruption = true)
        focus.enter(AudioPlayerState.READY, AudioPlayerState.PLAYING, AudioPlayerState.BUFFERING)
        val loss = system.promptStarts(focus, playWhenReady = true, isPlaying = false)!!
        assertTrue(loss.pause)
        assertFalse(focus.ducked)
        assertTrue(focus.lentOut)
    }

    @Test
    fun aPermanentLossReportsTheIntentAndPauses() {
        val system = FakeSystem()
        val focus = manager(system)
        focus.enter(AudioPlayerState.READY, AudioPlayerState.PLAYING, AudioPlayerState.BUFFERING)
        val loss = system.anotherAppTakesFocus(focus, playWhenReady = true)!!
        assertTrue(loss.pause)
        assertEquals(FocusChangeData(true, true, true), loss.report)
        assertFalse(focus.lentOut)
    }

    @Test
    fun stoppingDuringACallLeavesTheStack() {
        val system = FakeSystem()
        val focus = manager(system)
        focus.enter(AudioPlayerState.READY, AudioPlayerState.PLAYING)
        system.callStarts(focus)
        // Lent out is still in the stack: stopping must give that back too, or a GAIN reaches a
        // player that has nothing to resume.
        focus.enter(AudioPlayerState.STOPPED)
        assertEquals(1, system.abandons)
        assertFalse(focus.lentOut)
        assertFalse(system.inStack)
    }

    @Test
    fun aTrackPreparedPausedDoesNotTakeTheFocus() {
        val system = FakeSystem()
        val focus = manager(system)
        // A track restored at launch, or loaded behind a pause, reaches READY not meant to play.
        // It used to ask for the focus there and stop whatever else was playing.
        assertTrue(focus.enter(AudioPlayerState.LOADING, AudioPlayerState.BUFFERING, AudioPlayerState.READY, playWhenReady = false))
        assertEquals(0, system.requests)
        assertFalse(focus.hasAudioFocus)
        // The play asks.
        assertTrue(focus.enter(AudioPlayerState.PLAYING))
        assertEquals(1, system.requests)
    }

    @Test
    fun pausedEndedAndBufferingLeaveTheFocusAlone() {
        val system = FakeSystem()
        val focus = manager(system)
        focus.enter(AudioPlayerState.READY)
        focus.enter(AudioPlayerState.PAUSED, AudioPlayerState.BUFFERING, AudioPlayerState.ENDED, AudioPlayerState.LOADING)
        assertEquals(1, system.requests)
        assertEquals(0, system.abandons)
        assertTrue(focus.hasAudioFocus)
    }

    @Test
    fun withExoPlayerHandlingTheFocusNothingIsRefused() {
        val system = FakeSystem()
        val focus = manager(system)
        system.grant = false
        // ExoPlayer asks and refuses by itself; this manager only keeps its listener registered.
        assertTrue(focus.enter(AudioPlayerState.READY, AudioPlayerState.PLAYING, exoPlayerHandlesFocus = true))
        assertTrue(focus.enter(AudioPlayerState.READY, exoPlayerHandlesFocus = true, playWhenReady = false))
        // And a call does not make READY ask again on top of ExoPlayer's own client.
        system.grant = true
        assertTrue(focus.enter(AudioPlayerState.READY, exoPlayerHandlesFocus = true))
        val requestsBefore = system.requests
        system.callStarts(focus)
        focus.enter(AudioPlayerState.READY, exoPlayerHandlesFocus = true)
        assertEquals(requestsBefore, system.requests)
    }

    @Test
    fun theRequestCarriesThePauseOnDuckSetting() {
        val system = FakeSystem()
        val focus = manager(system, alwaysPauseOnInterruption = true)
        focus.enter(AudioPlayerState.READY)
        assertEquals(true, system.lastWillPauseWhenDucked)

        focus.alwaysPauseOnInterruption = false
        system.anotherAppTakesFocus(focus)
        focus.enter(AudioPlayerState.PLAYING)
        assertEquals(false, system.lastWillPauseWhenDucked)
    }

    @Test
    fun aFailedAbandonDoesNotBlockTheNextRequest() {
        val system = FakeSystem()
        val focus = manager(system)
        system.abandonResult = AudioManager.AUDIOFOCUS_REQUEST_FAILED
        focus.enter(AudioPlayerState.READY, AudioPlayerState.STOPPED)
        assertFalse(focus.hasAudioFocus)
        focus.enter(AudioPlayerState.READY)
        assertEquals(2, system.requests)
        assertTrue(focus.hasAudioFocus)
    }
}
