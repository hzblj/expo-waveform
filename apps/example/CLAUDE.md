# example

The example app for `@hzblj/expo-waveform`, in the style of Lapz: Formula 1 team radio as waveforms you can scrub,
with every word marked. Built from `@hzblj/kara-ui` like every hzblj example. Private; never published.

| Path | What it is |
| --- | --- |
| `app/(tabs)/` | Native tabs (`Tabs` from `@hzblj/kara-ui/router`), each a stack with a large title: Radio, Styles, Pit wall, Lab. Route files only re-export a screen from `src/`. |
| `src/radio/` | The weekend's sessions as chips and their team radio, newest first: a card per message with a seekable waveform in the team's colour, the words as markers (transcribed on the device by `@hzblj/expo-speech`) and lit as they are said, through one shared expo-audio player. |
| `src/styles/` | Every prop on one message: variant, alignment, scale, noise floor, bars, cursor, markers, haptics, colours, and the code for what is shown. |
| `src/live/` | `live` waveforms: the microphone metered by expo-audio's recorder (a bar every 50 ms), then the take as a file; and the radio metered as it plays through expo-audio's sample listener, which works on a simulator. |
| `src/lab/` | `Waveform.loadAsync` on its own: a message or any URL, the resolution, the numbers, the time it took, the cache, `toBars` drawn in JS, and one source for each error code. |
| `src/f1/` | `radio.json`, one weekend captured from OpenF1 by `scripts/radio.mjs`, and `radio.ts`, its sessions, clips and drivers. |
| `src/ui/` | `Page`, `Section`, `TabStack`, `PlayButton`, `togglePlayback` (play from the start again once it has ended), `Caption` and `NativeCaption`. |
| `assets/icons/` | Material Symbols SVGs, turned into native assets by kara's plugin at prebuild. |
| `assets/icon.png`, `assets/adaptive-icon.png` | The app icon: nine rounded bars, the first five played, on Formula 1 red (iOS); the bars alone on `ICON_BACKGROUND` (Android). |

**Nothing asks OpenF1 while the app runs.** OpenF1 closes to the public during a live session (it answers 401 with a
link to buy a key, which must never reach the screen) and limits how often it is asked. The weekend is captured once
into `src/f1/radio.json` (`node scripts/radio.mjs 2025 "Abu Dhabi"`); to change it, run the script again when no session
is live. The recordings stream from Formula 1's own static files on livetiming.formula1.com, which stay open.

**No Formula 1 media in the repo.** The JSON holds names, numbers, times and URLs; the recordings and headshots load
from Formula 1 while the app runs.

**Native stacks take native text.** Inside an `Island` (a SwiftUI or Compose stack) use kara's `Text`
(`NativeCaption`), not `RnText`: a React Native text there has no size of its own and overlaps what follows.

**Native changes need a rebuild**: `yarn prebuild`, then a native run. Metro runs on port 8091 in this repo's sessions
(`npx expo start --dev-client --port 8091`, `adb reverse tcp:8091 tcp:8091` on Android).

**iOS 27 needs the scene life cycle.** Xcode 27's SDK asserts at launch on an app without `UIApplicationSceneManifest`,
which Expo 57's template lacks: `expo-build-properties` with `ios.enableSceneSupport` (in `app.config.ts`) starts React
Native in Expo's scene delegate instead.
