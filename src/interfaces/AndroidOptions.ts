import type { AppKilledPlaybackBehavior } from '../constants';

export interface AndroidOptions {
  /**
   * parses embedded artwork on local files to a dedicated png path.
   * This is only necessary for certain MediaBrowsers, namely Android Auto to
   * read embedded artwork.
   * @default false
   */

  androidParseEmbeddedArtwork?: boolean;
  /**
   * Whether the audio playback notification is also removed when the playback
   * stops. **If `stoppingAppPausesPlayback` is set to false, this will be
   * ignored.**
   */
  appKilledPlaybackBehavior?: AppKilledPlaybackBehavior;

  /**
   * Whether the remote-duck event will be triggered on every interruption
   */
  alwaysPauseOnInterruption?: boolean;

  /**
   * The stricter audio-focus contract (with `autoHandleInterruptions: false`).
   * Turn it on only when the app handles the `RemoteDuck` fields that come with
   * it (`denied`, `playWhenReady`):
   * - a start while a call is up does not happen: the system refuses the focus,
   *   the player stays paused and emits `RemoteDuck` with `denied: true`;
   * - a start during a transient loss asks for the focus again;
   * - a transient loss pauses a track meant to play, also while it buffers, and
   *   `playWhenReady` tells the app it was meant to play, so it can resume it
   *   when the focus comes back.
   * @default false
   */
  strictAudioFocus?: boolean;

  /**
   * Time in seconds to wait once the player should transition to not
   * considering the service as in the foreground. If playback resumes within
   * this grace period, the service remains in the foreground state.
   * Defaults to 5 seconds.
   */
  stopForegroundGracePeriod?: number;

  /**
   * https://developer.android.com/media/media3/exoplayer/track-selection#audioOffload
   */
  audioOffload?: boolean;

  /**
   * enables exoplayer's skipSilence parser
   * @default false
   */
  androidSkipSilence?: boolean;

  /**
   * enables exoplayer's shuffle mode
   * @default false
   */
  shuffle?: boolean;
}
