# 4.1.99: audio session restoration (124ckhdg447)

`play()` and `setPlayWhenReady(true)` restore the configured music audio session
when the native play intent was already true. AVPlayer may retain that intent
through an OS interruption, so a change-event-only activation misses the resume.
The music category, mode, route policy and options are now applied before
activation; a category failure does not activate the previous category.
Interruption-began events carry native `playWhenReady` to preserve buffering intent.

Validation: `python3 scripts/test-ios-audio-session.py` compiles the production
method bodies with Swift control-flow doubles. The original methods fail seven
of ten checks; the new methods pass all ten, including explicit pause, empty
queue, rejected category and non-resumable interruption. TypeScript typecheck
and package build pass. The combined Lyra QA iPhoneOS application builds with
these sources against the current React Native and iOS SDK.

These checks prove the native restoration paths. They do not certify the report
of Volkswagen MIB2 remaining in Telephone mode after a physical phone call.
The report's prior Android task is unrelated; this change is tracked separately.
No JavaScript/native API is added and the package is backward compatible.

Reference: https://developer.apple.com/documentation/avfaudio/avaudiosession
