import * as _TrackPlayer from './trackPlayer';

export * from './constants';
export * from './hooks';
export * from './interfaces';
export { VideoView } from './VideoView';

// Spread into plain object to avoid Metro/Babel interop issue
// with `import * as X; export default X` pattern
const TrackPlayer = { ..._TrackPlayer };
export default TrackPlayer;
