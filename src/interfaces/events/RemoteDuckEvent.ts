export interface RemoteDuckEvent {
  /**
   * On Android when true the player should pause playback, when false the
   * player may resume playback. On iOS when true the playback was paused and
   * when false the player may resume playback.
   **/
  paused: boolean;
  /**
   * Whether the interruption is permanent. On Android the player should stop
   * playback.
   **/
  permanent: boolean;
  /**
   * Whether this interruption is the audio route disappearing — CarPlay,
   * Bluetooth or headphones disconnecting — as opposed to another app or Siri
   * taking the audio.
   *
   * iOS only, and always alongside `permanent: true`: iOS posts no interruption
   * for a route change, so the player raises one itself (Apple's guidance is to
   * pause when the output goes away). Both arrive as permanent interruptions and
   * they call for opposite things afterwards — playback the route interrupted may
   * be resumed when the device comes back, playback another app took over may
   * not. Android reports the same thing as `pausedBecauseBecameNoisy` on
   * PlaybackPlayWhenReadyChanged.
   **/
  routeLost?: boolean;
}
