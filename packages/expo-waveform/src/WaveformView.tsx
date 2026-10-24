import {requireNativeView} from 'expo'
import type {ComponentType} from 'react'
import {
  type ColorValue,
  type NativeSyntheticEvent,
  type ProcessedColorValue,
  processColor,
  View,
  type ViewProps,
} from 'react-native'

import {ExpoWaveform} from './ExpoWaveformModule'
import {resolveSource} from './source'
import type {WaveformErrorCode, WaveformPlayback, WaveformViewProps} from './types'

type TimeEvent = NativeSyntheticEvent<{time: number}>

type NativeWaveformViewProps = ViewProps & {
  uri: string | null
  headers: Record<string, string>
  samples: readonly number[] | null
  live: boolean
  playback: WaveformPlayback | null
  progress: number | null
  markers: readonly number[] | null
  markerStyle?: string
  variant?: string
  barWidth?: number
  barGap?: number
  barRadius?: number
  minBarHeight?: number
  lineWidth?: number
  alignment?: string
  scale?: string
  noiseFloor?: number
  normalize?: boolean
  color: ProcessedColorValue | null | undefined
  progressColor: ProcessedColorValue | null | undefined
  markerColor: ProcessedColorValue | null | undefined
  playedMarkerColor: ProcessedColorValue | null | undefined
  cursor?: boolean
  cursorColor: ProcessedColorValue | null | undefined
  seekable: boolean
  haptics?: boolean
  onLoad?: (event: NativeSyntheticEvent<{duration: number; fromCache: boolean}>) => void
  onError?: (event: NativeSyntheticEvent<{code: WaveformErrorCode; message: string}>) => void
  onScrubStart?: (event: TimeEvent) => void
  onScrub?: (event: TimeEvent) => void
  onScrubEnd?: (event: NativeSyntheticEvent<{time: number | null}>) => void
  onSeek?: (event: TimeEvent) => void
}

// Expo Go and other builds without the native module get an empty box of the same size instead of an
// "Unimplemented component" error.
const NativeWaveformView: ComponentType<NativeWaveformViewProps> | null =
  ExpoWaveform === null ? null : requireNativeView('ExpoWaveform', 'WaveformView')

let hasWarned = false

/** Colours go native processed: a number, or what a `PlatformColor` or `DynamicColorIOS` is made of. */
const processed = (color: ColorValue | undefined) => (color === undefined ? undefined : processColor(color))

/**
 * An audio waveform, drawn natively: a source analysed on the device (and cached), or levels of your own. Follows
 * `playback` at the display's frame rate, seeks by drag or tap, and meters a recording with `live`.
 */
export const WaveformView = ({
  source,
  samples,
  live = false,
  playback,
  progress,
  markers,
  color,
  progressColor,
  markerColor,
  playedMarkerColor,
  cursorColor,
  seekable,
  onLoad,
  onError,
  onScrubStart,
  onScrub,
  onScrubEnd,
  onSeek,
  ...props
}: WaveformViewProps) => {
  if (NativeWaveformView === null) {
    if (__DEV__ && !hasWarned) {
      hasWarned = true
      // biome-ignore lint/suspicious/noConsole: a one-time development hint about a misconfigured build
      console.warn('[expo-waveform] The native module is missing; rebuild the app with `npx expo run:ios|android`.')
    }

    return <View {...props} />
  }

  const resolved = samples || !source ? null : resolveSource(source)

  return (
    <NativeWaveformView
      {...props}
      color={processed(color)}
      cursorColor={processed(cursorColor)}
      headers={resolved?.headers ?? {}}
      live={live}
      markerColor={processed(markerColor)}
      markers={markers ?? null}
      onError={onError && (event => onError(event.nativeEvent))}
      onLoad={onLoad && (event => onLoad(event.nativeEvent))}
      onScrub={onScrub && (event => onScrub(event.nativeEvent.time))}
      onScrubEnd={onScrubEnd && (event => onScrubEnd(event.nativeEvent.time ?? null))}
      onScrubStart={onScrubStart && (event => onScrubStart(event.nativeEvent.time))}
      onSeek={onSeek && (event => onSeek(event.nativeEvent.time))}
      playback={playback ?? null}
      playedMarkerColor={processed(playedMarkerColor)}
      progress={progress ?? null}
      progressColor={processed(progressColor)}
      samples={samples ?? null}
      seekable={seekable ?? onSeek !== undefined}
      uri={resolved?.uri ?? null}
    />
  )
}
