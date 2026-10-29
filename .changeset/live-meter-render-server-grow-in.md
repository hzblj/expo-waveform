---
"@hzblj/expo-waveform": minor
---

`LiveWaveformView` keeps flowing while the main thread is busy: on iOS Core Animation moves the bars along an even line toward the newest level, so the meter no longer stops and jumps while the app works, and its speed no longer pulses with every level. `lag` is 2 by default, room for a level that comes late. `growIn` grows each new bar from its shortest to its level as it comes in at the edge, with a `duration` and an `easing` (a name or a cubic Bézier's control points).
