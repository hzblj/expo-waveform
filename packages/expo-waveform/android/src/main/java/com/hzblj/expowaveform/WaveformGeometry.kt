package com.hzblj.expowaveform

import android.graphics.Path
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** `WaveformVariant` in `src/types.ts`. */
enum class WaveformVariant(val value: String) {
  BARS("bars"),
  AREA("area"),
  LINE("line");

  companion object {
    fun of(value: String?) = entries.firstOrNull { it.value == value } ?: BARS
  }
}

/** How markers are drawn: `WaveformMarkerStyle` in `src/types.ts`. */
enum class WaveformMarkerStyle(val value: String) {
  NONE("none"),
  DOT("dot"),
  LINE("line");

  companion object {
    fun of(value: String?) = entries.firstOrNull { it.value == value } ?: NONE
  }
}

enum class WaveformAlignment(val value: String) {
  CENTER("center"),
  BOTTOM("bottom");

  companion object {
    fun of(value: String?) = entries.firstOrNull { it.value == value } ?: CENTER
  }
}

/** How the view draws: the props that shape it, not the ones that colour it. Lengths in pixels. */
data class WaveformStyle(
  val variant: WaveformVariant = WaveformVariant.BARS,
  val barWidth: Float = 3f,
  val barGap: Float = 2f,
  /** `null` is half of `barWidth`. */
  val barRadius: Float? = null,
  val minBarHeight: Float = 2f,
  val lineWidth: Float = 2f,
  val alignment: WaveformAlignment = WaveformAlignment.CENTER,
  val markerStyle: WaveformMarkerStyle = WaveformMarkerStyle.NONE,
) {
  val step: Float
    get() = max(0.5f, barWidth + barGap)
}

/**
 * Paths for heights from 0 to 1. A waveform spans the width flush at both edges; a live one is anchored at the right,
 * one bar per step, and anything older runs off the left edge.
 */
object WaveformGeometry {
  /** How many bars, or points of an envelope, fit in `width`. */
  fun count(width: Float, style: WaveformStyle): Int =
    Bars.count(width.toDouble(), max(0f, style.barWidth).toDouble(), max(0f, style.barGap).toDouble())

  /** The left edge of each bar. */
  fun positions(count: Int, width: Float, style: WaveformStyle, live: Boolean): FloatArray {
    if (count <= 0) {
      return FloatArray(0)
    }
    val barWidth = max(0f, style.barWidth)
    if (live) {
      return FloatArray(count) { width - barWidth - (count - 1 - it) * style.step }
    }
    if (count == 1) {
      return floatArrayOf((width - barWidth) / 2)
    }
    val spacing = (width - barWidth) / (count - 1)
    return FloatArray(count) { it * spacing }
  }

  fun barHeight(level: Float, height: Float, style: WaveformStyle): Float {
    val minimum = min(height, max(0f, style.minBarHeight))
    return minimum + min(1f, max(0f, level)) * (height - minimum)
  }

  fun path(heights: FloatArray, width: Float, height: Float, style: WaveformStyle, live: Boolean, path: Path = Path()): Path {
    path.reset()
    when (style.variant) {
      WaveformVariant.BARS -> bars(heights, width, height, style, live, path)
      WaveformVariant.AREA, WaveformVariant.LINE -> envelope(heights, width, height, style, live, path)
    }
    return path
  }

  private fun bars(heights: FloatArray, width: Float, height: Float, style: WaveformStyle, live: Boolean, path: Path) {
    val barWidth = max(0f, style.barWidth)
    if (barWidth <= 0f || height <= 0f) {
      return
    }
    val rect = RectF()
    val positions = positions(heights.size, width, style, live)

    // Every bar, even past the left edge: a live meter's scroll brings the older ones in.
    for (index in heights.indices) {
      val x = positions[index]
      val barHeight = barHeight(heights[index], height, style)
      val y = if (style.alignment == WaveformAlignment.BOTTOM) height - barHeight else (height - barHeight) / 2
      val radius = max(0f, minOf(style.barRadius ?: (barWidth / 2), barWidth / 2, barHeight / 2))
      rect.set(x, y, x + barWidth, y + barHeight)
      path.addRoundRect(rect, radius, radius, Path.Direction.CW)
    }
  }

  /** `area` fills the envelope, `line` strokes its edges; both through the middle of every bar's place. */
  private fun envelope(heights: FloatArray, width: Float, height: Float, style: WaveformStyle, live: Boolean, path: Path) {
    if (heights.isEmpty() || height <= 0f) {
      return
    }
    val inset = if (style.variant == WaveformVariant.LINE) max(0f, style.lineWidth) / 2 else 0f
    val inner = max(0f, height - inset * 2)
    val xs =
      if (live || heights.size == 1) {
        val positions = positions(heights.size, width, style, live)
        FloatArray(heights.size) { positions[it] + max(0f, style.barWidth) / 2 }
      } else {
        FloatArray(heights.size) { it * width / (heights.size - 1) }
      }
    val levels = FloatArray(heights.size) { barHeight(heights[it], inner, style) }
    val center = style.alignment == WaveformAlignment.CENTER
    val top = FloatArray(heights.size) { if (center) inset + (inner - levels[it]) / 2 else inset + inner - levels[it] }
    val bottom = FloatArray(heights.size) { if (center) inset + (inner + levels[it]) / 2 else inset + inner }

    path.moveTo(xs[0], top[0])
    curve(path, xs, top, reversed = false)
    if (style.variant == WaveformVariant.AREA) {
      path.lineTo(xs.last(), bottom.last())
      curve(path, xs, bottom, reversed = true)
      path.close()
    } else if (center) {
      path.moveTo(xs[0], bottom[0])
      curve(path, xs, bottom, reversed = false)
    }
  }

  /**
   * A smooth curve from the current point through the points: quadratic segments between the midpoints, which never
   * overshoot, so a height never goes past its neighbours.
   */
  private fun curve(path: Path, xs: FloatArray, ys: FloatArray, reversed: Boolean) {
    val count = xs.size
    if (count < 2) {
      return
    }
    fun x(index: Int) = xs[if (reversed) count - 1 - index else index]
    fun y(index: Int) = ys[if (reversed) count - 1 - index else index]

    path.lineTo((x(0) + x(1)) / 2, (y(0) + y(1)) / 2)
    for (index in 1 until count - 1) {
      path.quadTo(x(index), y(index), (x(index) + x(index + 1)) / 2, (y(index) + y(index + 1)) / 2)
    }
    path.lineTo(x(count - 1), y(count - 1))
  }

  /** A dot's diameter, and the band of the height dots take below the bars (the dot and a gap above it), in dp. */
  const val DOT_SIZE = 3f
  const val DOT_BAND = 6f

  /** The left edge of a marker `lineWidth` wide (a line, or a dot's box), on a whole pixel, at each time inside the audio. */
  fun markers(times: DoubleArray, duration: Double, width: Float, lineWidth: Float): FloatArray {
    if (duration <= 0 || width <= 0f) {
      return FloatArray(0)
    }
    return times
      .filter { it in 0.0..duration }
      .map { min(width - lineWidth, max(0f, (it / duration * width - lineWidth / 2).toFloat().roundToInt().toFloat())) }
      .toFloatArray()
  }
}

/** Which way a live meter's bars move: `LiveWaveformFlow` in `src/types.ts`. New bars come in at the opposite edge. */
enum class LiveWaveformFlow(val value: String) {
  LEFT("left"),
  RIGHT("right"),
  UP("up"),
  DOWN("down");

  val isVertical: Boolean
    get() = this == UP || this == DOWN

  companion object {
    fun of(value: String?) = entries.firstOrNull { it.value == value } ?: LEFT
  }
}

/**
 * A live meter is laid out flowing left in a canvas as long as the flow; these turn it to its flow in the view. The same
 * as `flowTransform`, `canvasSize` and `flowOffset` in `ios/Core/WaveformGeometry.swift`.
 */
object LiveFlowGeometry {
  /** The canvas's width and height: the view's, turned for a vertical flow. */
  fun canvasSize(flow: LiveWaveformFlow, width: Float, height: Float): Pair<Float, Float> =
    if (flow.isVertical) height to width else width to height

  /** `android.graphics.Matrix` values, in `setValues` order, from the canvas to the view. */
  fun matrix(flow: LiveWaveformFlow, width: Float, height: Float): FloatArray =
    when (flow) {
      LiveWaveformFlow.LEFT -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
      LiveWaveformFlow.RIGHT -> floatArrayOf(-1f, 0f, width, 0f, 1f, 0f, 0f, 0f, 1f)
      LiveWaveformFlow.UP -> floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
      LiveWaveformFlow.DOWN -> floatArrayOf(0f, 1f, 0f, -1f, 0f, height, 0f, 0f, 1f)
    }

  /** Where a bar `shift` pixels short of its place is drawn: back toward the edge it comes in at. */
  fun offset(flow: LiveWaveformFlow, shift: Float): Pair<Float, Float> =
    when (flow) {
      LiveWaveformFlow.LEFT -> shift to 0f
      LiveWaveformFlow.RIGHT -> -shift to 0f
      LiveWaveformFlow.UP -> 0f to shift
      LiveWaveformFlow.DOWN -> 0f to -shift
    }
}
