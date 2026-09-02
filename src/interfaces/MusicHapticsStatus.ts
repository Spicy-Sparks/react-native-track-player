/**
 * State of iOS 18's Music Haptics accessibility feature
 * (Settings > Accessibility > Music Haptics).
 */
export interface MusicHapticsStatus {
  /**
   * Whether haptics can happen here at all: iOS 18+, and an app whose
   * `Info.plist` declares `MusicHapticsSupported`. False everywhere else.
   */
  supported: boolean;
  /** Whether the user currently has it switched on. */
  active: boolean;
}
