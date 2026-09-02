/**
 * State of iOS 18's Music Haptics accessibility feature
 * (Settings > Accessibility > Music Haptics).
 */
export interface MusicHapticsStatus {
  /** Whether this OS knows the feature at all — false everywhere but iOS 18+. */
  supported: boolean;
  /** Whether the user currently has it switched on. */
  active: boolean;
}
