//
//  RNTrackPlayerBridge.m
//  RNTrackPlayerBridge
//
//  Created by David Chavez on 7/1/17.
//  Copyright © 2017 David Chavez. All rights reserved.
//

#import "RNTrackPlayerBridge.h"
#import <React/RCTBridgeModule.h>
#import <React/RCTConvert.h>

@interface RCT_EXTERN_REMAP_MODULE(TrackPlayerModule, RNTrackPlayer, NSObject)

RCT_EXTERN_METHOD(setupPlayer);

RCT_EXTERN_METHOD(updateOptions:(NSDictionary *)options);

RCT_EXTERN_METHOD(setNowPlaying:(NSDictionary *)object);

RCT_EXTERN_METHOD(updatePlayback:(NSDictionary *)properties);

RCT_EXTERN_METHOD(reset:(RCTPromiseResolveBlock)resolve
rejecter:(RCTPromiseRejectBlock)reject);

RCT_EXTERN_METHOD(updateMetadataForTrack:properties:(NSDictionary *)properties);

@end
