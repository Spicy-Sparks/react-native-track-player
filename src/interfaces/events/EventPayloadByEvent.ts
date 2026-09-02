import { Event } from '../../constants';

import type { PlaybackState } from '../PlaybackState';
import type { Track } from '../Track';
import type { MusicHapticsStatus } from '../MusicHapticsStatus';
import type { PlaybackActiveTrackChangedEvent } from './PlaybackActiveTrackChangedEvent';
import type { PlaybackErrorEvent } from './PlaybackErrorEvent';
import type { PlaybackMetadataReceivedEvent } from './PlaybackMetadataReceivedEvent';
import type { AudioMetadataReceivedEvent } from './AudioMetadataReceivedEvent';
import type { AudioCommonMetadataReceivedEvent } from './AudioMetadataReceivedEvent';
import type { PlaybackPlayWhenReadyChangedEvent } from './PlaybackPlayWhenReadyChangedEvent';
import type { PlaybackProgressUpdatedEvent } from './PlaybackProgressUpdatedEvent';
import type { PlaybackQueueEndedEvent } from './PlaybackQueueEndedEvent';
import type { PlaybackTrackChangedEvent } from './PlaybackTrackChangedEvent';
import { PlayerErrorEvent } from './PlayerErrorEvent';
import type { RemoteDuckEvent } from './RemoteDuckEvent';
import type { RemoteJumpBackwardEvent } from './RemoteJumpBackwardEvent';
import type { RemoteJumpForwardEvent } from './RemoteJumpForwardEvent';
import type { RemotePlayEvent } from './RemotePlayEvent';
import type { RemotePlayIdEvent } from './RemotePlayIdEvent';
import type { RemotePlaySearchEvent } from './RemotePlaySearchEvent';
import type { RemoteSearchEvent } from './RemoteSearchEvent';
import type { RemoteSeekEvent } from './RemoteSeekEvent';
import type { RemoteSetRatingEvent } from './RemoteSetRatingEvent';
import type { RemoteSkipEvent } from './RemoteSkipEvent';
import type { PlaybackAnimatedVolumeChangedEvent } from './PlaybackAnimatedVolumeChangedEvent';
import type { RemoteBrowseEvent } from './RemoteBrowseEvent';
import type { RemoteCustomActionEvent } from './RemoteCustomActionEvent';
import type { PlaybackResumeEvent } from './PlaybackResumeEvent';
import type {
  ControllerConnectedEvent,
  ControllerDisconnectedEvent,
} from './ControllerConnectedEvent';
import type { FFTUpdateEvent } from './FFTUpdateEvent';

export type EventPayloadByEvent = {
  [Event.PlayerError]: PlayerErrorEvent;
  [Event.PlaybackState]: PlaybackState;
  [Event.PlaybackError]: PlaybackErrorEvent;
  [Event.PlaybackQueueEnded]: PlaybackQueueEndedEvent;
  [Event.PlaybackTrackChanged]: PlaybackTrackChangedEvent;
  [Event.PlaybackActiveTrackChanged]: PlaybackActiveTrackChangedEvent;
  [Event.PlaybackMetadataReceived]: PlaybackMetadataReceivedEvent;
  [Event.PlaybackPlayWhenReadyChanged]: PlaybackPlayWhenReadyChangedEvent;
  [Event.PlaybackProgressUpdated]: PlaybackProgressUpdatedEvent;
  [Event.RemotePlay]: RemotePlayEvent;
  [Event.RemotePlayPause]: never;
  [Event.RemotePlayId]: RemotePlayIdEvent;
  [Event.RemotePlaySearch]: RemotePlaySearchEvent;
  [Event.RemoteSearch]: RemoteSearchEvent;
  [Event.RemotePause]: never;
  [Event.RemoteStop]: never;
  [Event.RemoteSkip]: RemoteSkipEvent;
  [Event.RemoteNext]: never;
  [Event.RemotePrevious]: never;
  [Event.RemoteJumpForward]: RemoteJumpForwardEvent;
  [Event.RemoteJumpBackward]: RemoteJumpBackwardEvent;
  [Event.RemoteSeek]: RemoteSeekEvent;
  [Event.RemoteSetRating]: RemoteSetRatingEvent;
  [Event.RemoteDuck]: RemoteDuckEvent;
  [Event.RemoteLike]: never;
  [Event.RemoteDislike]: never;
  [Event.RemoteBookmark]: never;
  [Event.PlaybackAnimatedVolumeChanged]: PlaybackAnimatedVolumeChangedEvent;
  [Event.RemoteBrowse]: RemoteBrowseEvent;
  [Event.PlaybackResume]: PlaybackResumeEvent;
  [Event.RemoteCustomAction]: RemoteCustomActionEvent;
  [Event.RemoteShuffle]: never;
  [Event.MetadataChapterReceived]: AudioMetadataReceivedEvent;
  [Event.MetadataTimedReceived]: AudioMetadataReceivedEvent;
  [Event.MetadataCommonReceived]: AudioCommonMetadataReceivedEvent;
  [Event.connectorConnected]: ControllerConnectedEvent;
  [Event.connectorDisconnected]: ControllerDisconnectedEvent;
  [Event.fftUpdate]: FFTUpdateEvent;
  /** The native player reached a queue item flagged `notPlayable` — it did not play it
   *  and hands it back so the app can resolve a source for it. */
  [Event.PlaybackNotPlayableTrackActive]: { index: number; track?: Track };
  [Event.MusicHapticsActiveChanged]: MusicHapticsStatus;
};

// eslint-disable-next-line
type Simplify<T> = { [KeyType in keyof T]: T[KeyType] } & {};

export type EventPayloadByEventWithType = {
  [K in keyof EventPayloadByEvent]: EventPayloadByEvent[K] extends never
    ? { type: K }
    : Simplify<EventPayloadByEvent[K] & { type: K }>;
};
