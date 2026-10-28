---
"@hzblj/expo-waveform": minor
---

`LiveWaveformView` draws a live meter that follows its source natively: `meter` takes a shared object that publishes levels (`@hzblj/expo-speech`'s `Transcription` for its microphone), with no re-render and no JavaScript per level. Native code can embed it too: a plain `UIView` on iOS and a plain `View` on Android.
