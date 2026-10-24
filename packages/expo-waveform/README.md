# @hzblj/expo-waveform

[![npm](https://img.shields.io/npm/v/@hzblj/expo-waveform.svg)](https://www.npmjs.com/package/@hzblj/expo-waveform)
[![license](https://img.shields.io/npm/l/@hzblj/expo-waveform.svg)](https://github.com/hzblj/expo-waveform/blob/main/LICENSE)

Audio waveforms for Expo, drawn natively on iOS and Android. A file is analysed on the device and cached; the view follows playback at the display's frame rate, seeks by drag or tap with haptics, and meters a recording live.

- **Analysed on the device:** `AVAssetReader` on iOS and `MediaCodec` on Android decode the file off the main thread, and every short stretch becomes its loudness (a root mean square). Local files, remote URLs and bundled assets; mp3, m4a, aac, wav, flac, ogg, and the audio of a video.
- **Cached:** an analysis is kept in memory and on disk, keyed by the source and the options. A list of voice messages draws each one at once the second time; the same file asked for twice at once is analysed once.
- **A native view:** bars, a filled envelope or its outline, centred or on the bottom. Paths are built once per size; playback moves a mask (iOS) or a clip (Android), not the data, so it costs next to nothing.
- **Follows a player on its own:** `useWaveformPlayback` hands the view where expo-audio's or expo-video's player is, and the view moves the playhead between reports. The component re-renders on play, pause, seek and rate, not ten times a second.
- **Seeks:** the playhead jumps under the finger as it lands and follows it; lift to seek. A vertical drag is left to the scroll view around. Haptics as a drag starts and ends and at every marker it crosses.
- **Markers:** a transcript's words, chapters, loop points: a drag ticks at each, and they can show as dots below the bars or lines between them.
- **Live:** levels of your own (a recorder's meter) scroll in from the right, smoothly, one bar per level.
- **Accessible:** a seekable waveform is an adjustable control for VoiceOver and a seek bar for TalkBack.

## Install

```sh
npx expo install @hzblj/expo-waveform
```

Requires Expo SDK 57 or newer and a [development build](https://docs.expo.dev/develop/development-builds/introduction/). Expo Go doesn't include this native module: the view draws an empty box of its size there. No config plugin and no permissions.

## Draw a file

```tsx
import {WaveformView} from '@hzblj/expo-waveform'

<WaveformView source="https://example.com/radio.mp3" style={{height: 40}} />
```

A source is a `file://` or `content://` URI, an `http(s)://` URL (downloaded for the analysis, which is then kept), `{uri, headers}`, or a `require()`d asset. Until the analysis is there the view draws silence: a row of the shortest bars. When it arrives, the bars rise into place (not with Reduce Motion or with animations off).

## Follow playback

```tsx
import {useWaveformPlayback, WaveformView} from '@hzblj/expo-waveform'
import {useAudioPlayer} from 'expo-audio'

const VoiceMessage = ({url}: {url: string}) => {
  const player = useAudioPlayer(url)
  const playback = useWaveformPlayback(player)

  return (
    <WaveformView
      onSeek={time => player.seekTo(time)}
      playback={playback}
      progressColor="#e10600"
      source={url}
      style={{height: 40}}
    />
  )
}
```

`useWaveformPlayback` works with anything that has `currentTime`, `playing` and the events expo-audio's `AudioPlayer` or expo-video's `VideoPlayer` send. It changes only when something does: play or pause, the rate, a seek, or the time straying from where the view reckons it. Between those the view moves its playhead by itself, at the display's frame rate and no faster than it moves a pixel, so a podcast's slow playhead costs less than a voice message's quick one.

After `onSeek` the view holds the new position until the player reports it (or a second passes), so the playhead never jumps back while the player catches up. `onScrubStart` and `onScrub` report the finger as it goes, for a time label or a highlighted word under it, and `onScrubEnd` when it lifts. A touch that goes up or down first ends with `onScrubEnd(null)` and no `onSeek`, and the playhead goes back. For a progress of your own, pass `progress` from 0 to 1 instead of `playback`.

## Markers

```tsx
<WaveformView markers={transcript.words.map(word => word.start)} onSeek={seek} playback={playback} source={url} />
```

Seconds to mark. A drag ticks as it crosses one, so scrubbing through speech clicks word by word. By default they are not drawn: words are too many to show. `markerStyle="dot"` draws a dot below the bars at each (the bars give up a band of 6 points at the bottom), and `markerStyle="line"` a line a point wide across the height, showing in the gaps between the bars: for a few, like chapters. With [`@hzblj/expo-speech`](https://github.com/hzblj/expo-speech) the words come from the same file, transcribed on the device.

## Meter a recording

```tsx
import {RecordingPresets, useAudioRecorder, useAudioRecorderState} from 'expo-audio'

const recorder = useAudioRecorder({...RecordingPresets.HIGH_QUALITY, isMeteringEnabled: true})
const {metering} = useAudioRecorderState(recorder, 50)
const [levels, setLevels] = useState<number[]>([])

useEffect(() => {
  if (metering !== undefined) {
    // Decibels from -50 to 0 as a level from 0 to 1.
    setLevels(previous => [...previous.slice(-199), Math.max(0, (metering + 50) / 50)])
  }
}, [metering])

<WaveformView live samples={levels} style={{height: 64}} />
```

With `live`, every level is a bar, the newest at the right edge. A meter that appends (or keeps a window of the last few) scrolls smoothly by what it appended, over the time since its last update. Keep more levels than fit: the older ones run off the left edge. Once the recording is a file, draw it with `source`.

`samples` without `live` draws levels of your own across the width, as they are: `scale`, `noiseFloor` and `normalize` apply to a source only.

## Without a view

```tsx
import {toBars, Waveform} from '@hzblj/expo-waveform'

const {samples, sampleDuration, duration, fromCache} = await Waveform.loadAsync(url, {samplesPerSecond: 100})
const heights = toBars(samples, 64) // 64 heights from 0 to 1, for a drawing of your own

await Waveform.clearCacheAsync()
```

The same analysis the view draws, from the same cache. `samples` are root mean squares from 0 to 1, one per `sampleDuration` seconds (the last may cover less). A long file gets fewer per second, so an analysis keeps to `maxSamples` (20,000 by default, 80 KB). `toBars` is the rule the views draw by: each bar is the loudest stretch it covers, so a short burst isn't averaged away, in decibels above the noise floor, stretched so the loudest bar is full height.

## Props

| Prop | Default | Meaning |
| --- | --- | --- |
| `source` | | The audio to analyse and draw |
| `samples` | | Levels from 0 to 1 to draw instead |
| `live` | `false` | With `samples`: a bar per level, scrolling in from the right |
| `playback` | | Where playback is, from `useWaveformPlayback` |
| `progress` | | Or a progress of your own, from 0 to 1 |
| `markers` | | Seconds to mark: a drag ticks at each |
| `markerStyle` | `none` | `none`, `dot` (below the bars) or `line` (between them) |
| `variant` | `bars` | `bars`, `area` (the envelope filled) or `line` (its edges) |
| `barWidth`, `barGap` | `3`, `2` | In points. `area` and `line` take a point of the envelope every `barWidth + barGap` |
| `barRadius` | Half of `barWidth` | |
| `minBarHeight` | `2` | The shortest a bar gets, so silence still reads as a line |
| `lineWidth` | `2` | With `line` |
| `alignment` | `center` | `center`, or `bottom` for bars that grow up |
| `scale` | `logarithmic` | Decibels above `noiseFloor` (speech as it is heard), or `linear` amplitude |
| `noiseFloor` | `-50` | Decibels drawn as no height |
| `normalize` | `true` | Stretch the loudest moment to the full height |
| `color` | The text colour at 30 % | What hasn't played, and a live meter |
| `progressColor` | The accent | What has played |
| `markerColor`, `playedMarkerColor` | Those at half opacity | |
| `cursor`, `cursorColor` | `false`, `progressColor` | A line at the playhead |
| `seekable` | `true` with `onSeek` | Touch to seek |
| `haptics` | `true` | Ticks as a drag starts, ends and crosses a marker |
| `onLoad` | | `{duration, fromCache}` once the source is analysed |
| `onError` | | `{code, message}` |
| `onScrubStart`, `onScrub`, `onSeek` | | A time in seconds |
| `onScrubEnd` | | Where the finger lifted, or `null` when the touch was let go |

Colours take anything React Native does, `PlatformColor` and `DynamicColorIOS` included; they follow dark mode on both platforms. The defaults are the platform's: `tertiaryLabel` and the view's `tintColor` on iOS, the theme's primary text colour at 30 % and the system accent (Material You, from Android 12) on Android. A `borderRadius` on the view clips the bars.

## Accessibility

A seekable waveform is one element for VoiceOver and TalkBack: an adjustable control on iOS and a seek bar on Android, whose value is the time under the playhead in words ("1 minute, 5 seconds"). A swipe up or down (or TalkBack's scroll actions) moves it a twentieth of the audio, between 1 and 15 seconds, through `onSeek`. Give it an `accessibilityLabel` that says what it plays. A waveform that isn't seekable is an image of the audio and stays out of the way.

## Errors

`onError` and `Waveform.loadAsync`'s rejection carry a `code`:

| Code | Meaning |
| --- | --- |
| `audio` | The file is missing, has no audio track, or could not be decoded |
| `network` | A remote source could not be downloaded (or answered other than 2xx) |
| `unsupported` | A URI that is neither a file nor http(s) (`ph://`), or a format the device has no decoder for |
| `unknown` | Anything else |

## Platform behavior

| | iOS | Android |
| --- | --- | --- |
| Decoding | `AVAssetReader`, the file's own rate and channels | `MediaCodec`, 16-bit, float, 8-bit, 24-bit and 32-bit PCM |
| Channels | Averaged (the converter's own mixdown reads 3 dB louder) | Averaged |
| Drawing | `CAShapeLayer` paths, masks moved per frame by `CADisplayLink` | `Path` on a `Canvas`, redrawn by `Choreographer` when the playhead moves a pixel |
| Haptics | `UISelectionFeedbackGenerator` | `performHapticFeedback`: `DRAG_START` and `SEGMENT_FREQUENT_TICK` on Android 14, `GESTURE_END` from 11, `CLOCK_TICK` before |
| Bundled assets | The file in the app bundle | The raw resource a release build makes of it |
| Cache | `Caches/expo-waveform` | `cacheDir/expo-waveform` |

Decoding speed is the platform's: AVFoundation reads a few minutes of audio in a fraction of a second, Android's decoders take longer for long files. Either way it happens once per file.

## Example

The [example app](https://github.com/hzblj/expo-waveform/tree/main/apps/example) draws a Formula 1 weekend's team radio (captured from [OpenF1](https://openf1.org)), scrubbed word by word with the words lit as they are said, a playground for every prop, a live meter of the microphone and of the radio, and `loadAsync` on its own.

## License

MIT © Jan Blazej
