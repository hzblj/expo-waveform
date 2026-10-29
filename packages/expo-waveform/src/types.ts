import type {SharedObject} from 'expo'
import type {ColorValue, ViewProps} from 'react-native'

/** An audio file: a `file://` or `content://` URI, an `http(s)://` URL (downloaded once, then kept), or a `require()`d asset. */
export type WaveformSource = string | number | {uri: string; headers?: Record<string, string>}

/**
 * Where playback is: what `useWaveformPlayback` reads from a player. Between two of these, the view moves its playhead
 * by itself, so a player that reports a few times a second still draws a smooth playhead.
 */
export type WaveformPlayback = {
  /** Seconds into the audio. */
  currentTime: number
  /** The audio's length in seconds. The view's own analysis is used when this is 0 or unset. */
  duration?: number
  playing: boolean
  /** @default 1 */
  rate?: number
}

/**
 * - `bars`: rounded bars, one per `barWidth + barGap` points.
 * - `area`: the envelope as one filled shape, through a point every `barWidth + barGap`.
 * - `line`: the envelope's edges, stroked `lineWidth` wide.
 */
export type WaveformVariant = 'bars' | 'area' | 'line'

/**
 * How loudness becomes height. `logarithmic` (decibels above `noiseFloor`) shows speech the way it is heard;
 * `linear` shows the signal's amplitude, where quiet passages nearly vanish.
 */
export type WaveformScale = 'logarithmic' | 'linear'

/** Bars grow both ways from the middle, or up from the bottom. */
export type WaveformAlignment = 'center' | 'bottom'

/**
 * - `none`: not drawn; a drag still ticks at each one.
 * - `dot`: a dot below the bars at each one, in a band at the bottom of the view.
 * - `line`: a thin line across the height, showing in the gaps between the bars. For a few: chapters, loop points.
 */
export type WaveformMarkerStyle = 'none' | 'dot' | 'line'

export type WaveformViewProps = Omit<ViewProps, 'children'> & {
  /** The audio to draw, analysed natively and cached. Use `samples` instead for levels of your own. */
  source?: WaveformSource | null
  /**
   * Levels from 0 to 1 to draw instead of a source: a recording's meter, or an analysis of your own. Drawn as they
   * are: `scale`, `noiseFloor` and `normalize` don't apply.
   */
  samples?: readonly number[] | null
  /**
   * With `samples`: one bar per sample, the newest at the right edge, older ones moving off to the left. Made for a
   * recording's meter.
   * @default false
   */
  live?: boolean
  /** Where playback is, from `useWaveformPlayback(player)`. */
  playback?: WaveformPlayback | null
  /** Or a progress of your own, from 0 to 1, with the analysis's duration. Ignored when `playback` is set. */
  progress?: number
  /**
   * Seconds to mark: a transcript's words, chapters, loop points. A drag ticks as it crosses one; they are drawn only as
   * `markerStyle` says.
   */
  markers?: readonly number[] | null
  /** @default 'none' */
  markerStyle?: WaveformMarkerStyle
  /** @default 'bars' */
  variant?: WaveformVariant
  /** In points. @default 3 */
  barWidth?: number
  /** @default 2 */
  barGap?: number
  /** @default half of `barWidth` */
  barRadius?: number
  /**
   * The shortest a bar gets, so silence still reads as a line.
   * @default 2
   */
  minBarHeight?: number
  /** With `line`. @default 2 */
  lineWidth?: number
  /** @default 'center' */
  alignment?: WaveformAlignment
  /** @default 'logarithmic' */
  scale?: WaveformScale
  /**
   * Decibels drawn as no height, with `logarithmic`.
   * @default -50
   */
  noiseFloor?: number
  /**
   * Stretch the loudest moment of the source to the full height.
   * @default true
   */
  normalize?: boolean
  /**
   * What hasn't played yet, and a live meter. `PlatformColor` and `DynamicColorIOS` work, and follow dark mode.
   * @default the text colour at 30% opacity: `tertiaryLabel` on iOS, the theme's primary text colour on Android
   */
  color?: ColorValue
  /**
   * What has played.
   * @default the accent: the view's `tintColor` on iOS, the system accent (Material You) on Android 12 and later, blue
   * before
   */
  progressColor?: ColorValue
  /** @default `color` at half its opacity */
  markerColor?: ColorValue
  /** @default `progressColor` at half its opacity */
  playedMarkerColor?: ColorValue
  /**
   * A thin line at the playhead.
   * @default false
   */
  cursor?: boolean
  /** @default `progressColor` */
  cursorColor?: ColorValue
  /**
   * Touch to seek: the playhead jumps under the finger as it lands and follows it. And an adjustable control for
   * VoiceOver and TalkBack (a twentieth of the audio per swipe, between 1 and 15 seconds). A vertical drag still
   * scrolls what is around.
   * @default true when `onSeek` is set
   */
  seekable?: boolean
  /**
   * A tick as a drag starts and ends, and as it crosses a marker or reaches an end. The system's touch feedback setting
   * is followed.
   * @default true
   */
  haptics?: boolean
  /** The source was analysed, or came from the cache. */
  onLoad?: (event: {duration: number; fromCache: boolean}) => void
  onError?: (event: {code: WaveformErrorCode; message: string}) => void
  /** A finger landed at `time` seconds: the playhead is under it from now until it lifts. */
  onScrubStart?: (time: number) => void
  /** The playhead under the finger, as it moves. */
  onScrub?: (time: number) => void
  /**
   * The finger lifted at `time` (`onSeek` follows), or the touch was let go (`null`: it went up or down first, or a
   * scroll view around took it). After every `onScrubStart`.
   */
  onScrubEnd?: (time: number | null) => void
  /**
   * The finger lifted, after a tap or a drag: seek the player to `time`. A touch that goes up or down first is not a
   * scrub (it scrolls what is around): it seeks nowhere, and the playhead goes back.
   */
  onSeek?: (time: number) => void
}

/**
 * - `audio`: the file is missing, has no audio track, or could not be decoded.
 * - `network`: a remote source could not be downloaded.
 * - `unsupported`: a URI that is neither a file nor http(s), or a format this device has no decoder for.
 */
export type WaveformErrorCode = 'audio' | 'network' | 'unsupported' | 'unknown'

/** An analysis: the loudness of every short stretch of the audio. */
export type WaveformData = {
  /** Root mean square of each stretch, from 0 to 1 (full scale). */
  samples: number[]
  /** Seconds each sample covers. */
  sampleDuration: number
  /** The audio's length in seconds. */
  duration: number
  /** Whether the analysis came from the cache. */
  fromCache: boolean
}

export type LoadOptions = {
  /**
   * Samples per second of audio. Long files get fewer, so an analysis keeps to `maxSamples`.
   * @default 100
   */
  samplesPerSecond?: number
  /** @default 20000 */
  maxSamples?: number
  /** Headers for downloading a remote source, added to the source's own. */
  headers?: Record<string, string>
}

export type BarOptions = {
  /** @default 'logarithmic' */
  scale?: WaveformScale
  /** @default -50 */
  noiseFloor?: number
  /** @default true */
  normalize?: boolean
}

/**
 * A live meter that follows its source natively, with no JavaScript in between: expo-speech's `Transcription`, or any
 * shared object that publishes levels the same way (see the README).
 */
/**
 * Which way a live meter's bars move. New bars come in at the opposite edge: `left` brings them in at the right edge,
 * `up` at the bottom. In `up` and `down` the bars lie across the view, and `bottom` alignment grows them from its right
 * edge.
 */
export type LiveWaveformFlow = 'left' | 'right' | 'up' | 'down'

/**
 * A timing curve: a name, or a cubic Bézier's control points `[x1, y1, x2, y2]` as in CSS's `cubic-bezier()`.
 */
export type LiveWaveformEasing = 'linear' | 'easeIn' | 'easeOut' | 'easeInOut' | [number, number, number, number]

/** How a new bar grows to its level as it comes in at the edge. */
export type LiveWaveformGrowIn = {
  /**
   * Milliseconds from the bar's shortest to its level.
   * @default 250
   */
  duration?: number
  /** @default 'easeOut' */
  easing?: LiveWaveformEasing
}

export type LiveWaveformViewProps = Omit<ViewProps, 'children'> & {
  /** Followed while set; another one, or `null`, starts from nothing. */
  meter?: InstanceType<SharedObject> | null
  /** @default 'left' */
  flow?: LiveWaveformFlow
  /**
   * How many levels the meter runs behind the newest while they come, so levels that arrive unevenly still flow at an
   * even speed, and a level that comes late or a busy main thread doesn't stop the bars. `0` shows each level the moment
   * it arrives, and moves as unevenly as they come.
   * @default 2
   */
  lag?: number
  /**
   * Seconds between two levels, for a source with a fixed rate. Measured from the levels as they come when unset.
   */
  pace?: number
  /**
   * New bars grow from their shortest to their level as they come in at the edge: from the middle, or up from the
   * bottom with `bottom` alignment. `true` takes the defaults.
   * @default false
   */
  growIn?: boolean | LiveWaveformGrowIn
  /** @default 'bars' */
  variant?: WaveformVariant
  /** In points. @default 3 */
  barWidth?: number
  /** @default 2 */
  barGap?: number
  /** @default half of `barWidth` */
  barRadius?: number
  /**
   * The shortest a bar gets: silence as a line, or as dots with `minBarHeight` equal to `barWidth`.
   * @default 2
   */
  minBarHeight?: number
  /** With `line`. @default 2 */
  lineWidth?: number
  /** @default 'center' */
  alignment?: WaveformAlignment
  /** @default 'logarithmic' */
  scale?: WaveformScale
  /**
   * Decibels drawn as no height, with `logarithmic`.
   * @default -50
   */
  noiseFloor?: number
  /** @default the text colour at 30% opacity: `tertiaryLabel` on iOS, the theme's primary text colour on Android */
  color?: ColorValue
}
