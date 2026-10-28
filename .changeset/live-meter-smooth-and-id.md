---
"@hzblj/expo-waveform": patch
---

`LiveWaveformView` follows its `meter`: the shared object goes to the native view by its id, since Fabric props cannot carry the object itself and the meter never started. Its bars scroll at the levels' usual pace, a little slower than they come, so they flow on without stopping when a level comes late or two come at once.
