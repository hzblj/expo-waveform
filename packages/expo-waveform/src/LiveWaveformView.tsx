import {requireNativeView} from 'expo'
import type {ComponentType} from 'react'
import {type ColorValue, type ProcessedColorValue, processColor, View, type ViewProps} from 'react-native'

import {ExpoWaveform} from './ExpoWaveformModule'
import type {LiveWaveformViewProps} from './types'

type NativeLiveWaveformViewProps = Omit<ViewProps, 'children'> & {
  meter: number | null
  flow?: string
  lag?: number
  pace?: number
  variant?: string
  barWidth?: number
  barGap?: number
  barRadius?: number
  minBarHeight?: number
  lineWidth?: number
  alignment?: string
  scale?: string
  noiseFloor?: number
  color: ProcessedColorValue | null | undefined
}

// Builds without the native module get an empty box of the same size, as `WaveformView` does.
const NativeLiveWaveformView: ComponentType<NativeLiveWaveformViewProps> | null =
  ExpoWaveform === null ? null : requireNativeView('ExpoWaveform', 'LiveWaveformComponent')

const processed = (color: ColorValue | undefined) => (color === undefined ? undefined : processColor(color))

/** A view prop takes a shared object by its id: Fabric cannot carry the object itself, which would arrive as nothing. */
const sharedObjectId = (object: object | null | undefined): number | null => {
  const id: unknown = object ? Reflect.get(object, '__expo_shared_object_id__') : null

  return typeof id === 'number' ? id : null
}

/**
 * A live meter that follows `meter` natively: one bar per level, the newest at the right edge, the older ones
 * scrolling off the left. No re-render and no JavaScript per level.
 */
export const LiveWaveformView = ({meter, color, ...props}: LiveWaveformViewProps) => {
  if (NativeLiveWaveformView === null) {
    return <View {...props} />
  }

  return <NativeLiveWaveformView {...props} color={processed(color)} meter={sharedObjectId(meter)} />
}
