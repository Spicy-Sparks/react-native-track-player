import React from 'react';
import {
  StyleProp,
  ViewStyle,
  requireNativeComponent,
  Platform,
} from 'react-native';

interface VideoViewProps {
  /**
   * Style object that will be applied to the underlying native video view.
   */
  style?: StyleProp<ViewStyle>;
  /**
   * How the video should be resized to fit its container. On iOS this maps to
   * AVLayerVideoGravity, on Android to ExoPlayer's resize mode.
   * Defaults to "contain" (AspectFit).
   */
  resizeMode?: 'contain' | 'cover' | 'stretch' | 'none';
}

/**
 * VideoView is a convenience component that synchronises a <Video> component
 * with the currently playing track from react-native-track-player.
 *
 * It will automatically:
 * 1. Render nothing when the current track does not expose a video source.
 * 2. Pause/resume playback based on TrackPlayer's playback state.
 *
 * This makes it trivial to embed alongside your existing audio UI:
 *
 * ```tsx
 * <VideoView style={{ width: 300, height: 200 }} />
 * ```
 */
const NativeVideoView = requireNativeComponent<VideoViewProps>(
  Platform.select({
    ios: 'RNTrackPlayerVideoView',
    android: 'RNTrackPlayerVideoView',
    default: 'RNTrackPlayerVideoView',
  }) as string
);

export const VideoView: React.FC<VideoViewProps> = ({
  style,
  resizeMode = 'contain',
}: VideoViewProps) => {
  return <NativeVideoView style={style} resizeMode={resizeMode} />;
};

export default VideoView;
