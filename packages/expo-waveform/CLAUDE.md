# @hzblj/expo-waveform

Native audio waveforms for Expo. An Expo module (`ExpoWaveform`: `ios/`, `android/`) with one view (`WaveformView`)
and two functions (`loadAsync`, `clearCacheAsync`), and its JS (`src/` → `build/`). No config plugin, no permissions.

## The shape

**One analysis, three readers.** A source is analysed natively into root mean squares of short stretches
(`WaveformAnalysis`: samples, sample duration, duration). The view draws them, `Waveform.loadAsync` returns them, and
`toBars` (`src/bars.ts`) turns them into heights the way the views do. `Bars` exists three times, in TypeScript, Swift and
Kotlin, with one shared fixture in each test suite (`theSameBarsAsJavaScript`): change the rule in all three.

**The store.** `WaveformStore` (Swift actor, Kotlin object) answers from memory (`AnalysisMemory`, about 8 MB, least
recently used out), then from disk (`expo-waveform/analyses/<sha256>.ewf`, at most 512 files), then analyses once per
key however many ask at the same time; a waiter that is cancelled leaves, and the last one to leave cancels the
analysis. The key is the URI and the options, plus a file's size and date (an edited file is analysed again) or, for
Android's bundled audio, the app's install time. `.ewf` is the same little-endian format on both platforms. Remote files
are downloaded for the analysis and deleted after it; the extension comes from the response's file name, the URL or the
MIME type, since AVFoundation goes by it.

**The view's state is native.** Props arrive one by one and are applied together in `didUpdateProps`
(`OnViewDidUpdateProps`). Paths are rebuilt only when what they are built from changes (`Built`: size, style, data,
markers, duration); a playback report moves the `Playhead` only when it differs from the last one, because the same
report again would move the playhead back. `useWaveformPlayback` (`src/playback.ts`) keeps re-renders down to play,
pause, rate, seek and drift (`nextPlayback`); the view dead-reckons between them.

**Seeking.** The touch is followed from the moment it lands (`touchesBegan` on iOS, `ACTION_DOWN` on Android): the
playhead goes under the finger at once (`onScrubStart`), follows it (`onScrub`) and seeks where it lifts (`onSeek`). A
sideways drag is claimed from a scroll view around (iOS: a pan the scroll view must wait for, with
`cancelsTouchesInView` off so the touches keep coming; Android: `requestDisallowInterceptTouchEvent`); a vertical one
is left to it, which cancels the touch and puts the playhead back without a seek. A touch that moves up or down first is
let go the same way even where nothing scrolls (a short list), so a vertical swipe never starts playback. After a seek the `Playhead` ignores reports
more than half a second from it for a second, so the player catching up doesn't make the playhead jump back.

**Live.** `samples` with `live`: one bar per level, right-anchored, the last `count + 8` levels drawn (the extra ones off
the left edge, so a scroll never uncovers a gap). `Bars.shift` finds how many levels were appended between two updates
(a growing array or a sliding window), and the bars scroll by that over the time since the previous update.

**`LiveWaveformView` follows a source natively.** A plain `UIView` / `View` (public, so other native code embeds it)
wrapped for React Native by `LiveWaveformComponent`. It keeps its levels itself, one bar per `append`. Each level sets
`LiveMotion` (`Core/LiveMotion.swift`, `LiveMotion.kt`): a straight line in time from where the meter is toward the
newest, at the measured (`LivePace`, over the last two seconds of arrivals, so a burst barely moves it) or given `pace`,
nudged by how far it is from `lag` levels behind; a meter that stood at the newest longer than a level waits until it is
`lag` behind again, one that only just got there moves on, slower. iOS hands the line to Core Animation, nothing per
frame on the main thread: a `transform` animation on the content layer with absolute `beginTime`s, so a commit that
lands late still follows the same line, and a busy main thread no longer stops the bars. Bars done growing sit in one
path; the ones still growing (`growIn`) are keyframed at 60 fps into a second shape layer until the last has grown (the
whole envelope, for `area` and `line`). `WaveformGeometry` draws every bar with the same segments whatever its radius
(`addBar`), or keyframed paths would not interpolate. Android draws the same line every frame from `Choreographer`. A
bar grows from the moment the meter is less than its width short of it (`entries`, kept once passed), so it grows while
it comes in at the edge. Nothing is drawn before the first level. `flow` lays the meter out flowing left in a canvas as
long as the flow (`canvasSize`) and turns it with `flowTransform` / `LiveFlowGeometry.matrix`, so every variant and
alignment works in every direction. `meter` is any object that publishes levels:
iOS observes the `expo.audioLevel` notification with it as the object (posted from any thread, observed on the main
queue); Android finds `addAudioLevelListener(DoubleConsumer): AutoCloseable` by reflection, subscribes while attached and
posts each level to the main thread. expo-speech's `Transcription` is such a source; neither package depends on the other.

## iOS (`ios/`)

`Core/` is plain Swift over Foundation, AVFoundation, Accelerate and CoreGraphics, compiled by the pod and by the SwiftPM
package in `Tests/` (`yarn test:ios`, on the Mac: the tests write audio with `AVAudioFile` and analyse it). The module
layer (`ExpoWaveformModule.swift`, `WaveformView.swift`) is the only code that imports ExpoModulesCore.

- **`WaveformAnalyzer`**: `AVAssetReaderTrackOutput` with no rate or channel count, so the file's own come out; the
  channels are averaged with vDSP. The converter's own mixdown (`AVNumberOfChannelsKey: 1`) adds them at -3 dB: a stereo
  sine of 0.8 measured 0.81 instead of 0.57.
- **`WaveformView`**: two layer groups, unplayed and played, each a markers shape under a bars shape, each masked; playback
  moves the masks and the cursor in a transaction without actions. Shape layers need `contentsScale` set, or they draw
  at 1x. Dynamic colours are resolved in `layoutSubviews` when the appearance changed (UIKit lays out again then), so no
  deprecated `traitCollectionDidChange`. The display link's preferred rate follows how fast the playhead moves in pixels.
- **`gestureRecognizerShouldBegin` is UIView's too**: it is an override, and UIKit asks the view as well as the delegate.
- Accessibility overrides `isAccessibilityElement`, `accessibilityTraits` (`.adjustable`), `accessibilityValue` and
  increment/decrement on the component view itself (`ExpoView` is the `RCTViewComponentView`).

## Android (`android/`)

- **`WaveformAnalyzer`**: `MediaExtractor` and `MediaCodec`, float PCM asked for, every encoding read (16-bit, float,
  8-bit, 24-bit packed, 32-bit). The accumulator is made from the first output format, not the file's: HE-AAC decodes
  at twice the rate the extractor reports. A decoder that never signals the end is let go after two seconds of nothing.
- **Sources**: a path or `file://`, `file:///android_asset/`, `content://`, `android.resource://`, `http(s)://`, or a bare
  name, which is a raw resource: what `Image.resolveAssetSource` gives for a `require()`d file in a release build.
- **`WaveformView`**: draws in `dispatchDraw` after `clipToPaddingBox`, so `borderRadius` clips the bars. Playback posts a
  `Choreographer` callback per frame and invalidates only when the playhead moved a pixel. Colours arrive as `Any`:
  Expo's colour type reads numbers and strings but not a `PlatformColor`'s `resource_paths`, which
  `ColorPropConverter.resolveResourcePath` resolves; they are resolved again on a configuration change (night mode).
- Touch: `requestDisallowInterceptTouchEvent` and `NativeGestureUtil.notifyNativeGestureStarted` once a drag is
  sideways, so neither a scroll view nor a JS `Pressable` around takes it. Haptics are `performHapticFeedback`, which
  follows the system's touch feedback setting.
- Accessibility: `SeekBar` class name, `RangeInfo` in seconds, `stateDescription` in words (`MeasureFormat`), scroll
  forward and backward, and set progress.
- `react-android` is a dependency for `ColorPropConverter` and `NativeGestureUtil`.
- JVM tests: `yarn test:android` (needs the example's prebuilt `android/`).

## Rules

- The documented defaults live in three places: the JSDoc in `src/types.ts`, the Swift module and the Kotlin module.
  Keep them in step.
- Docs copy (README, the JSDoc in `src/types.ts`, changesets) has no em dashes.
- `yarn lint`, `yarn test`, `yarn test:ios`, `yarn test:android`, `yarn pack:check` before a release.
