export interface RemotePlayEvent {
  /**
   * True if the play command was issued by the OS within a short window
   * after a new audio output device became available (e.g. CarPlay or
   * Bluetooth connect). Lets the JS layer distinguish a user tap on the
   * lockscreen / CarPlay play button from an OS-initiated auto-resume.
   */
  autoResume?: boolean;
}
