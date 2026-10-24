import type {BarOptions} from './types'

/** A level from 0 to 1 for a root mean square: decibels above the noise floor, or the amplitude itself. */
export const levelOf = (rms: number, {noiseFloor = -50, scale = 'logarithmic'}: BarOptions = {}) => {
  if (!(rms > 0)) {
    return 0
  }
  if (scale === 'linear') {
    return Math.min(1, rms)
  }
  const decibels = 20 * Math.log10(rms)
  return Math.min(1, Math.max(0, (decibels - noiseFloor) / -noiseFloor))
}

/**
 * `count` bar heights from 0 to 1 for an analysis: each bar is the loudest stretch it covers, so a short burst is not
 * averaged away. The same rule as the native views draw with (`Bars` in Swift and Kotlin).
 */
export const toBars = (samples: readonly number[], count: number, options: BarOptions = {}): number[] => {
  const length = samples.length
  if (count <= 0 || length === 0) {
    return []
  }

  const bars = new Array<number>(count)
  let loudest = 0

  for (let index = 0; index < count; index += 1) {
    const start = Math.floor((index * length) / count)
    const end = Math.max(start + 1, Math.floor(((index + 1) * length) / count))
    let peak = 0
    for (let sample = start; sample < end && sample < length; sample += 1) {
      peak = Math.max(peak, samples[sample] ?? 0)
    }
    const level = levelOf(peak, options)
    bars[index] = level
    loudest = Math.max(loudest, level)
  }

  if ((options.normalize ?? true) && loudest > 0) {
    for (let index = 0; index < count; index += 1) {
      bars[index] = (bars[index] ?? 0) / loudest
    }
  }
  return bars
}

/** How many bars of `barWidth` with `barGap` between them fit in `width`. */
export const barCount = (width: number, barWidth: number, barGap: number) =>
  Math.max(0, Math.floor((width + barGap) / Math.max(0.5, barWidth + barGap)))
