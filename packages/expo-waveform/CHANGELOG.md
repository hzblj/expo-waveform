# @hzblj/expo-waveform

## 0.4.0

### Minor Changes

- [`54e2b94`](https://github.com/hzblj/expo-waveform/commit/54e2b94c685c4ac5044971bdcf7aca9591aff3c7) Thanks [@hzblj](https://github.com/hzblj)! - `LiveWaveformView` keeps flowing while the main thread is busy: on iOS Core Animation moves the bars along an even line toward the newest level, so the meter no longer stops and jumps while the app works, and its speed no longer pulses with every level. `lag` is 2 by default, room for a level that comes late. `growIn` grows each new bar from its shortest to its level as it comes in at the edge, with a `duration` and an `easing` (a name or a cubic Bézier's control points).

## 0.3.0

### Minor Changes

- [`ca908e4`](https://github.com/hzblj/expo-waveform/commit/ca908e409ad7605121512e4d1038b3ee75b3a850) Thanks [@hzblj](https://github.com/hzblj)! - `LiveWaveformView` flows evenly: the display's frames move the bars at the pace levels usually come, `lag` levels behind the newest (1 by default), so levels that arrive in uneven bursts no longer make it stop and jump. It grows in from the edge its bars come in at instead of starting with a full row of silence. `flow` (`left`, `right`, `up`, `down`) picks the way the bars move, drawn natively in every direction, and `pace` gives a source's fixed rate.

### Patch Changes

- [`1468d4b`](https://github.com/hzblj/expo-waveform/commit/1468d4b17af97d415f0063ad642547e87e1f5e5a) Thanks [@hzblj](https://github.com/hzblj)! - `LiveWaveformView` follows its `meter`: the shared object goes to the native view by its id, since Fabric props cannot carry the object itself and the meter never started. Its bars scroll at the levels' usual pace, a little slower than they come, so they flow on without stopping when a level comes late or two come at once.

## 0.2.1

### Patch Changes

- [`b3076ca`](https://github.com/hzblj/expo-waveform/commit/b3076cae7d071fd4e7de7d9799fbaf8cfccfccdd) Thanks [@hzblj](https://github.com/hzblj)! - `LiveWaveformView`'s `meter` is typed as a shared object instance, so a `Transcription` from `@hzblj/expo-speech` type-checks; it was typed as the class.

## 0.2.0

### Minor Changes

- [`433f22c`](https://github.com/hzblj/expo-waveform/commit/433f22cb836eefd5dcbc238222e403766c743c10) Thanks [@hzblj](https://github.com/hzblj)! - `LiveWaveformView` draws a live meter that follows its source natively: `meter` takes a shared object that publishes levels (`@hzblj/expo-speech`'s `Transcription` for its microphone), with no re-render and no JavaScript per level. Native code can embed it too: a plain `UIView` on iOS and a plain `View` on Android.

## 0.1.2

### Patch Changes

- [`67a90d6`](https://github.com/hzblj/expo-waveform/commit/67a90d6ef59754f73e1b8525fb52428c7e5842ed) Thanks [@hzblj](https://github.com/hzblj)! - The README shows the example app on iOS and Android.

## 0.1.1

### Patch Changes

- [`bc55379`](https://github.com/hzblj/expo-waveform/commit/bc55379becbe11d1e7dc6a86b7f36fac2c82c684) Thanks [@hzblj](https://github.com/hzblj)! - The LICENSE is the complete MIT license, with the limitation of liability it was missing.
