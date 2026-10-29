package com.hzblj.expowaveform

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A timing curve: a cubic Bézier from (0, 0) to (1, 1) through two control points, as CSS defines its own.
 * `LiveWaveformEasing` in `src/types.ts`; the same as `WaveformEasing` in `ios/Core/LiveMotion.swift`.
 */
class WaveformEasing(x1: Double, val y1: Double, x2: Double, val y2: Double) {
  /** The control points' x is kept within 0..1, so the curve is a function of time. */
  val x1 = x1.coerceIn(0.0, 1.0)
  val x2 = x2.coerceIn(0.0, 1.0)

  /** How far along the curve is at `time`, both from 0 to 1. */
  fun value(time: Double): Double {
    val x = time.coerceIn(0.0, 1.0)
    if (x <= 0.0 || x >= 1.0) return x
    return bezier(y1, y2, parameter(x))
  }

  /** Where on the curve its x is `x`: Newton's method, or halving where the curve is too flat for it. */
  private fun parameter(x: Double): Double {
    var t = x
    for (step in 0 until 8) {
      val error = bezier(x1, x2, t) - x
      if (abs(error) < 1e-7) return t
      val slope = slope(x1, x2, t)
      if (abs(slope) <= 1e-6) break
      t -= error / slope
      if (t < 0.0 || t > 1.0) break
    }

    var low = 0.0
    var high = 1.0
    t = x
    for (step in 0 until 40) {
      val value = bezier(x1, x2, t)
      if (abs(value - x) < 1e-7) break
      if (value < x) low = t else high = t
      t = (low + high) / 2
    }
    return t
  }

  override fun equals(other: Any?) =
    other is WaveformEasing && x1 == other.x1 && y1 == other.y1 && x2 == other.x2 && y2 == other.y2

  override fun hashCode() = listOf(x1, y1, x2, y2).hashCode()

  companion object {
    val LINEAR = WaveformEasing(0.0, 0.0, 1.0, 1.0)
    val EASE_IN = WaveformEasing(0.42, 0.0, 1.0, 1.0)
    val EASE_OUT = WaveformEasing(0.0, 0.0, 0.58, 1.0)
    val EASE_IN_OUT = WaveformEasing(0.42, 0.0, 0.58, 1.0)

    /** `linear`, `easeIn`, `easeOut` or `easeInOut`. */
    fun named(name: String?): WaveformEasing? =
      when (name) {
        "linear" -> LINEAR
        "easeIn" -> EASE_IN
        "easeOut" -> EASE_OUT
        "easeInOut" -> EASE_IN_OUT
        else -> null
      }

    /** One coordinate of the curve, whose ends are 0 and 1. */
    private fun bezier(first: Double, second: Double, t: Double): Double {
      val rest = 1 - t
      return 3 * rest * rest * t * first + 3 * rest * t * t * second + t * t * t
    }

    private fun slope(first: Double, second: Double, t: Double): Double {
      val rest = 1 - t
      return 3 * rest * rest * first + 6 * rest * t * (second - first) + 3 * t * t * (1 - second)
    }
  }
}

/**
 * How a live meter's new bars grow in: from their shortest to their level, from the middle (or up from the bottom),
 * while they come in at the edge. `LiveWaveformGrowIn` in `src/types.ts`.
 */
data class LiveWaveformGrowIn(
  /** Seconds. */
  val duration: Double = 0.25,
  val easing: WaveformEasing = WaveformEasing.EASE_OUT,
) {
  /** How much of its level a bar shows `elapsed` seconds after it started to come in: 0 before, 1 once grown. */
  fun progress(elapsed: Double): Double {
    if (duration <= 0) return if (elapsed >= 0) 1.0 else 0.0
    return easing.value(elapsed / duration)
  }
}

/**
 * Seconds between two levels, from the times the last ones came: over a window of them, so a late one or a burst
 * barely moves it.
 */
class LivePace {
  var value = INITIAL
    private set
  private val times = ArrayDeque<Double>()

  fun record(time: Double) {
    times.lastOrNull()?.let { if (time - it > PAUSE) times.clear() }
    times.addLast(time)
    while (times.size > WINDOW) times.removeFirst()
    if (times.size >= 3) {
      value = ((times.last() - times.first()) / (times.size - 1)).coerceIn(0.02, 0.3)
    }
  }

  companion object {
    const val INITIAL = 0.08
    /** About two seconds of a microphone's buffers. */
    const val WINDOW = 24
    /** Longer than this between two levels is a pause, not a slow source: measuring starts again after it. */
    const val PAUSE = 0.5
  }
}

/**
 * Where a live meter is, in levels, as a straight line in time: from `start` at `time`, `speed` levels a second, up to
 * `end`, where it stops. Each new level starts a new line from wherever the last one had got to, so the meter never
 * jumps, and only its speed changes, by a little. The same as `LiveMotion` in `ios/Core/LiveMotion.swift`.
 */
class LiveMotion {
  var time = 0.0
    private set
  var start = -1.0
    private set
  var speed = 0.0
    private set
  var end = -1.0
    private set

  fun position(now: Double): Double = min(end, start + max(0.0, now - time) * speed)

  /** When the meter is at `position`: in the past for one it has passed, null for one it stops short of. */
  fun timeReaching(position: Double): Double? {
    if (position > end || speed <= 0) return if (position <= start) Double.NEGATIVE_INFINITY else null
    return time + (position - start) / speed
  }

  /**
   * A new newest level at `now`: on from where the meter is, at the pace, a little faster while it is more than `lag`
   * levels behind the newest and a little slower while it is less. A meter that has stood at the newest for longer
   * than a level (the levels had stopped, or this is the first) waits until it is `lag` behind again, so it has that
   * much to ride out the next late level; one that only just got there moves on, slower.
   */
  fun follow(newest: Int, lag: Double, pace: Double, now: Double) {
    val here = position(now)
    val behind = newest - lag - here
    val pace = max(0.005, pace)
    val arrived = if (speed > 0) time + (end - start) / speed else Double.NEGATIVE_INFINITY

    if (here >= end && behind < 0 && now - arrived > pace) {
      time = now - behind * pace
      speed = 1 / pace
    } else {
      time = now
      speed = (1 + (behind * GAIN).coerceIn(-NUDGE, NUDGE)) / pace
    }
    start = here
    end = newest.toDouble()
  }

  /** Straight to `position`, standing there: the first level, reduced motion, or no lag. */
  fun jump(position: Double, now: Double) {
    time = now
    start = position
    end = position
    speed = 0.0
  }

  companion object {
    /** How much the speed follows the distance to `lag`, per level of it, and how far it may go from the pace. */
    const val GAIN = 0.1
    const val NUDGE = 0.25
  }
}
