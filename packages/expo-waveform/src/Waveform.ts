import {ExpoWaveform} from './ExpoWaveformModule'
import {resolveSource} from './source'
import type {LoadOptions, WaveformData, WaveformSource} from './types'

const missing = () =>
  Object.assign(
    new Error('[expo-waveform] The native module is missing; rebuild the app with `npx expo run:ios|android`.'),
    {
      code: 'unsupported',
    }
  )

/** Analyses outside a view: the same analysis, from the same cache, as `WaveformView` draws. */
export const Waveform = {
  /** Forgets every analysis, in memory and on disk. */
  clearCacheAsync: async (): Promise<void> => {
    await ExpoWaveform?.clearCacheAsync()
  },

  /**
   * The loudness of every short stretch of the audio: decoded on the device, off the main thread, and cached by the
   * source and the options. A remote file is downloaded for the analysis and not kept. Rejects with an error whose
   * `code` is a `WaveformErrorCode`.
   */
  loadAsync: async (
    source: WaveformSource,
    {headers, maxSamples = 20000, samplesPerSecond = 100}: LoadOptions = {}
  ): Promise<WaveformData> => {
    if (!ExpoWaveform) {
      throw missing()
    }
    const resolved = resolveSource(source)

    return ExpoWaveform.loadAsync(resolved.uri, {
      headers: {...resolved.headers, ...headers},
      maxSamples,
      samplesPerSecond,
    })
  },
}
