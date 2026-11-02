import { State } from '../constants';
import type { PlaybackErrorEvent } from './events';

/**
 * Android only, from getPlaybackState (not on the playback-state event): where
 * the player stands with the audio focus. "held" — it has it; "lent" — a
 * transient loss lent it to another app, which owes it back; "none" — it is out
 * of the focus stack. Under android.strictAudioFocus a track prepared paused
 * does not take it. Absent on iOS and on builds before 4.1.97.
 */
export type AudioFocusState = 'held' | 'lent' | 'none';

export type PlaybackState = (
  | {
      state: Exclude<State, State.Error>;
    }
  | {
      state: State.Error;
      error: PlaybackErrorEvent;
    }
) & { audioFocus?: AudioFocusState };
