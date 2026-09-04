import type {
  AndroidAudioContentType,
  IOSCategory,
  IOSCategoryMode,
  IOSCategoryOptions,
} from '../constants';

export interface PlayerOptions {
  /**
   * Minimum duration of media that the player will attempt to buffer in seconds.
   *
   * Supported on Android & iOS.
   *
   * @throws Will throw on Android if min buffer is higher than max buffer.
   * @default 50
   */
  minBuffer?: number;
  /**
   * Maximum duration of media that the player will attempt to buffer in seconds.
   * Max buffer may not be lower than min buffer.
   *
   * Supported on Android only.
   *
   * @throws Will throw if max buffer is lower than min buffer.
   * @default 50
   */
  maxBuffer?: number;
  /**
   * Duration in seconds that should be kept in the buffer behind the current
   * playhead time.
   *
   * Supported on Android only.
   *
   * @default 0
   */
  backBuffer?: number;
  /**
   * Duration of media in seconds that must be buffered for playback to start or
   * resume following a user action such as a seek.
   *
   * Supported on Android only.
   *
   * @default 2.5
   */
  playBuffer?: number;
  /**
   * Maximum cache size in kilobytes.
   *
   * Supported on Android only.
   *
   * @default 0
   */
  maxCacheSize?: number;
  /**
   * [AVAudioSession.Category](https://developer.apple.com/documentation/avfoundation/avaudiosession/1616615-category)
   * for iOS. Sets on `play()`.
   */
  iosCategory?: IOSCategory;
  /**
   * (iOS only) The audio session mode, together with the audio session category,
   * indicates to the system how you intend to use audio in your app. You can use
   * a mode to configure the audio system for specific use cases such as video
   * recording, voice or video chat, or audio analysis.
   * Sets on `play()`.
   *
   * See https://developer.apple.com/documentation/avfoundation/avaudiosession/1616508-mode
   */
  iosCategoryMode?: IOSCategoryMode;
  /**
   * [AVAudioSession.CategoryOptions](https://developer.apple.com/documentation/avfoundation/avaudiosession/1616503-categoryoptions) for iOS.
   * Sets on `play()`.
   */
  iosCategoryOptions?: IOSCategoryOptions[];
  /**
   * (Android only) The audio content type indicates to the android system how
   * you intend to use audio in your app.
   *
   * With `autoHandleInterruptions: true` and
   * `androidAudioContentType: AndroidAudioContentType.Speech`, the audio will be
   * paused during short interruptions, such as when a message arrives.
   * Otherwise the playback volume is reduced while the notification is playing.
   *
   * @default AndroidAudioContentType.Music
   */
  androidAudioContentType?: AndroidAudioContentType;
  /**
   * auto pause playback when playback device changes from headset to speaker.
   * @default true
   */
  androidHandleAudioBecomingNoisy?: boolean;
  /**
   * always show next and previous as android player command. this overrides
   * exoplayer disabling the next button on playmode != all and at queue's end.
   * @default true
   */
  androidAlwaysShowNext?: boolean;
  /**
   * enables exoplayer's skipSilence parser
   * @default false
   */
  androidSkipSilence?: boolean;
  /**
   * set android exoplayer wake mode. 1 is WAKE_MODE_LOCAL, 2 is WAKE_MODE_NETWORK,
   * and others is WAKE_MODE_NONE. Default is WAKE_MODE_NETWORK because this is
   * a network-streaming audio player — without it, ExoPlayer's own NONE default
   * causes ~10-minute Bluetooth-playback stalls during Android Doze (CPU
   * suspends, buffer drains, audio pauses cyclically).
   * @default 2
   */
  androidWakeMode?: number;
  /**
   * Indicates whether the player should automatically delay playback in order to minimize stalling.
   * Defaults to `true`.
   * @deprecated This option has been nominated for removal in a future version
   * of RNTP. If you have this set to `true`, you can safely remove this from
   * the options. If you are setting this to `false` and have a reason for that,
   * please post a comment in the following discussion: https://github.com/doublesymmetry/react-native-track-player/pull/1695
   * and describe why you are doing so.
   */
  waitForBuffer?: boolean;
  /**
   * Indicates whether the player should automatically update now playing metadata data in control center / notification.
   * Defaults to `true`.
   */
  autoUpdateMetadata?: boolean;
  /**
   * Indicates whether the player should automatically handle audio interruptions.
   * Defaults to `false`.
   */
  autoHandleInterruptions?: boolean;
  /**
   * Stop at the end of an item instead of walking onto the next one, leaving
   * every track transition to the JS side — which is how Android has always
   * behaved (`pauseAtEndOfMediaItems`, always on there). iOS advanced its own
   * queue instead: sometimes onto an item whose source was not resolved yet, and
   * always without telling the JS side, which then held a queue that no longer
   * matched what was playing.
   *
   * The pause that replaces the advance carries `pausedBecauseReachedEnd: true`
   * on `playback-play-when-ready-changed`, so the end of an item is a fact
   * rather than something read off the position.
   *
   * ios only, and off by default until it has been watched on a device: with it
   * on, a JS side that misses the end of an item stops the queue where the old
   * behaviour would have walked past the miss.
   *
   * Defaults to `false`.
   */
  pauseAtEndOfMediaItems?: boolean;
  /**
   * enables crossfade. android only.
   * Defaults to `false`.
   */
  crossfade?: boolean;
  /**
   * applies an FFT processor with the given sampling size. android only.
   * Defaults to 0 (disables it).
   */
  useFFTProcessor?: number;
}
