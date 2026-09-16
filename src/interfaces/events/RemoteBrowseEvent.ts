export interface RemoteBrowseEvent {
  /** The browsable media id */
  mediaId: string;
  /**
   * Android only. Set on the last request for a node the car is still waiting
   * for: publish whatever the build produced, an error included, because the
   * car is answered with the current tree right after.
   */
  final?: boolean;
}
