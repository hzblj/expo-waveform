import {type NativeModule, requireOptionalNativeModule} from 'expo'

import type {LoadOptions, WaveformData} from './types'

declare class ExpoWaveformNativeModule extends NativeModule<Record<never, never>> {
  loadAsync(uri: string, options: LoadOptions & {headers: Record<string, string>}): Promise<WaveformData>
  clearCacheAsync(): Promise<void>
}

/** `null` in a build without the native module (Expo Go): the view then draws an empty box of its size. */
export const ExpoWaveform = requireOptionalNativeModule<ExpoWaveformNativeModule>('ExpoWaveform')
