package com.hzblj.expowaveform

import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/** How loudness becomes height: `WaveformScale` in `src/types.ts`. */
enum class WaveformScale(val value: String) {
  LOGARITHMIC("logarithmic"),
  LINEAR("linear");

  companion object {
    fun of(value: String?) = entries.firstOrNull { it.value == value } ?: LOGARITHMIC
  }
}

/** The rule every bar is drawn by, the same as `toBars` in `src/bars.ts` and `Bars` in Swift. */
object Bars {
  /** A level from 0 to 1 for a root mean square: decibels above the noise floor, or the amplitude itself. */
  fun level(rms: Float, scale: WaveformScale, noiseFloor: Float): Float {
    if (!(rms > 0f)) {
      return 0f
    }
    if (scale == WaveformScale.LINEAR) {
      return min(1f, rms)
    }
    val decibels = 20f * log10(rms)
    return min(1f, max(0f, (decibels - noiseFloor) / -noiseFloor))
  }

  /**
   * `count` heights from 0 to 1: each bar is the loudest stretch it covers, so a short burst is not averaged away.
   * `scale = null` takes the samples as levels already.
   */
  fun heights(samples: FloatArray, count: Int, scale: WaveformScale?, noiseFloor: Float = -50f, normalize: Boolean): FloatArray {
    val length = samples.size
    if (count <= 0 || length == 0) {
      return FloatArray(0)
    }

    val bars = FloatArray(count)
    var loudest = 0f

    for (index in 0 until count) {
      val start = (index.toLong() * length / count).toInt()
      val end = min(length, max(start + 1, ((index + 1).toLong() * length / count).toInt()))
      var peak = 0f
      for (sample in start until end) {
        peak = max(peak, samples[sample])
      }
      val level = if (scale != null) level(peak, scale, noiseFloor) else min(1f, max(0f, peak))
      bars[index] = level
      loudest = max(loudest, level)
    }

    if (normalize && loudest > 0f) {
      for (index in 0 until count) {
        bars[index] /= loudest
      }
    }
    return bars
  }

  /** How many bars of `width` with `gap` between them fit in `available`. */
  fun count(available: Double, width: Double, gap: Double): Int =
    max(0, floor((available + gap) / max(0.5, width + gap)).toInt())

  /**
   * How many samples were appended between two snapshots of a live meter: `previous` shifted left by the answer is a
   * prefix of `next`. A meter that grows, or keeps a window of the last few, both work. `0` when nothing matches.
   */
  fun shift(previous: FloatArray, next: FloatArray, limit: Int = 8): Int {
    if (previous.isEmpty() || next.isEmpty() || previous.contentEquals(next)) {
      return 0
    }
    if (next.size > previous.size && startsWith(next, previous, 0)) {
      return next.size - previous.size
    }
    for (shift in 1..min(limit, previous.size - 1)) {
      val kept = previous.size - shift
      if (kept <= next.size && startsWith(next, previous, shift)) {
        return next.size - kept
      }
    }
    return 0
  }

  /** Whether `array` starts with `prefix` from `from` on. */
  private fun startsWith(array: FloatArray, prefix: FloatArray, from: Int): Boolean {
    for (index in from until prefix.size) {
      if (array[index - from] != prefix[index]) {
        return false
      }
    }
    return true
  }
}
