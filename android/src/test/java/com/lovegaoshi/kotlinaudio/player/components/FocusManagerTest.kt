package com.lovegaoshi.kotlinaudio.player.components

import android.media.AudioManager
import com.lovegaoshi.kotlinaudio.models.AudioPlayerState
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
        fun anotherAppTakesFocus(focus: FocusManager) {
            inStack = false
            focus.onFocusLostPermanently()
        }
    }

    private fun manager(
        system: FakeSystem,
        alwaysPauseOnInterruption: Boolean = false,
    ) = FocusManager(system, PlayerOptions(alwaysPauseOnInterruption = alwaysPauseOnInterruption))

    private fun FocusManager.enter(vararg states: AudioPlayerState, exoPlayerHandlesFocus: Boolean = false) {
        states.forEach { onPlayerStateChanged(it, exoPlayerHandlesFocus) }
    }

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
    fun transientLossKeepsTheFocusAndResumesOnGain() {
        val system = FakeSystem()
        val focus = manager(system)
        focus.enter(AudioPlayerState.READY, AudioPlayerState.PLAYING)
        // AUDIOFOCUS_LOSS_TRANSIENT (a call): the player pauses, the system keeps us in its stack.
        focus.enter(AudioPlayerState.PAUSED)
        assertTrue(focus.hasAudioFocus)
        // AUDIOFOCUS_GAIN when the call ends: the player resumes without a new request.
        focus.onFocusGained()
        focus.enter(AudioPlayerState.PLAYING)
        assertEquals(1, system.requests)
        assertTrue(focus.hasAudioFocus)
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
        focus.onDuck()
        focus.enter(AudioPlayerState.PAUSED, AudioPlayerState.PLAYING)
        assertTrue(focus.ducked)
        focus.onFocusGained()
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
        focus.onDuck() // a navigation prompt
        focus.enter(AudioPlayerState.PAUSED) // then a call: AUDIOFOCUS_LOSS_TRANSIENT pauses
        focus.onFocusGained() // the call ends
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
        focus.onDuck()
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
        focus.onDuck()
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
    fun aDeniedRequestIsRetriedOnTheNextPlay() {
        val system = FakeSystem()
        val focus = manager(system)
        system.grant = false
        focus.enter(AudioPlayerState.READY, AudioPlayerState.PLAYING)
        assertEquals(2, system.requests)
        assertFalse(focus.hasAudioFocus)

        system.grant = true
        focus.enter(AudioPlayerState.PAUSED, AudioPlayerState.PLAYING)
        assertEquals(3, system.requests)
        assertTrue(focus.hasAudioFocus)
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
