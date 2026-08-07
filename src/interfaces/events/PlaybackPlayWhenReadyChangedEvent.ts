export interface PlaybackPlayWhenReadyChangedEvent {
  /** Whether the player will play when it is ready to do so. */
  playWhenReady: boolean;
  /**
   * Whether this pause is the player reaching the end of the current media item,
   * as opposed to anything else that can pause playback (a call, another app
   * taking audio focus, a user tap).
   *
   * Android only — it is media3's
   * `PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM`, and it is the authoritative
   * end-of-item signal when the player is configured with
   * `pauseAtEndOfMediaItems` (which is how this fork hands every track transition
   * to the JS side). Undefined on iOS and on older Android builds, where the end
   * of a track has to be inferred from the position instead.
   */
  pausedBecauseReachedEnd?: boolean;
}
