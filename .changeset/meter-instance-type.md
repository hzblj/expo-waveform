---
"@hzblj/expo-waveform": patch
---

`LiveWaveformView`'s `meter` is typed as a shared object instance, so a `Transcription` from `@hzblj/expo-speech` type-checks; it was typed as the class.
