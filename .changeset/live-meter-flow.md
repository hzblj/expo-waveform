---
"@hzblj/expo-waveform": minor
---

`LiveWaveformView` flows evenly: the display's frames move the bars at the pace levels usually come, `lag` levels behind the newest (1 by default), so levels that arrive in uneven bursts no longer make it stop and jump. It grows in from the edge its bars come in at instead of starting with a full row of silence. `flow` (`left`, `right`, `up`, `down`) picks the way the bars move, drawn natively in every direction, and `pace` gives a source's fixed rate.
