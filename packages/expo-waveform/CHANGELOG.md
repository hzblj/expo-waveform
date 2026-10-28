# @hzblj/expo-waveform

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
