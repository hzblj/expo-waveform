package com.hzblj.expowaveform

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import com.facebook.react.bridge.ColorPropConverter
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.views.ExpoView
import java.util.function.DoubleConsumer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A live meter: one bar per level, the newest at the right edge, the older ones scrolling off the left over the time
 * between two levels. It follows a level source on its own (`meter`: an object with `addAudioLevelListener(DoubleConsumer):
 * AutoCloseable`, such as expo-speech's `Transcription`), or takes levels from `append`. A plain `View`, so native code
 * can use it as it is. `style` is in pixels.
 */
class LiveWaveformView(context: Context) : View(context) {
  var style = WaveformStyle()
    set(value) {
      if (value != field) {
        field = value
        rebuild()
      }
    }
  var scale = WaveformScale.LOGARITHMIC
  var noiseFloor = -50f

  /** A colour int; the theme's primary text colour at 30 % when null. */
  var color: Int? = null
    set(value) {
      field = value
      applyColor()
    }

  /** Followed while set; another one, or null, lets go of the previous one and starts from silence. */
  var meter: Any? = null
    set(value) {
      if (value === field) return
      field = value
      unsubscribe()
      reset()
      if (isAttachedToWindow) subscribe()
    }

  private val main = Handler(Looper.getMainLooper())
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val path = Path()
  private var levels = FloatArray(0)
  private var subscription: AutoCloseable? = null
  private var lastAppend = now()
  /** The usual time between two levels: each scroll takes a little longer, so the bars move on without a stop. */
  private var pace: Double? = null
  private var scrollFrom = 0f
  private var scrollStart = 0.0
  private var scrollDuration = 0.0

  init {
    applyColor()
  }

  /** A new level, as a root mean square from 0 to 1. On the main thread. */
  fun append(rms: Float) {
    val kept = WaveformGeometry.count(width.toFloat(), style) + 8
    val next = levels + Bars.level(rms, scale, noiseFloor)
    levels = if (next.size > kept * 2) next.copyOfRange(next.size - kept, next.size) else next

    val now = now()
    val gap = now - lastAppend
    lastAppend = now
    if (gap < 0.5) {
      pace = pace?.let { it * 0.8 + max(0.03, gap) * 0.2 } ?: max(0.03, gap)
    }
    if (motionEnabled()) {
      scrollFrom = scrollOffset(now) + style.step
      scrollStart = now
      scrollDuration = (pace ?: 0.1) * 1.25
    }
    rebuild()
  }

  /** Back to silence. */
  fun reset() {
    levels = FloatArray(0)
    pace = null
    scrollFrom = 0f
    rebuild()
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    subscribe()
  }

  override fun onDetachedFromWindow() {
    unsubscribe()
    super.onDetachedFromWindow()
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    rebuild()
  }

  override fun onConfigurationChanged(newConfig: Configuration?) {
    super.onConfigurationChanged(newConfig)
    applyColor()
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val offset = scrollOffset(now())
    canvas.save()
    canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
    canvas.translate(offset, 0f)
    canvas.drawPath(path, paint)
    canvas.restore()
    if (offset > 0f) postInvalidateOnAnimation()
  }

  /** The source's levels come on its thread; each one is drawn on the main thread. */
  private fun subscribe() {
    val source = meter ?: return
    if (subscription != null) return
    val add = source.javaClass.methods.firstOrNull {
      it.name == "addAudioLevelListener" && it.parameterTypes.contentEquals(arrayOf(DoubleConsumer::class.java))
    } ?: return
    val listener = DoubleConsumer { level -> main.post { if (meter === source) append(level.toFloat()) } }
    subscription = runCatching { add.invoke(source, listener) as? AutoCloseable }.getOrNull()
  }

  private fun unsubscribe() {
    runCatching { subscription?.close() }
    subscription = null
  }

  /** The bars that fit, and the few older ones past the left edge, so a scroll never uncovers a gap. */
  private fun rebuild() {
    val count = WaveformGeometry.count(width.toFloat(), style)
    val shown = levels.copyOfRange(max(0, levels.size - count - 8), levels.size)
    val heights = FloatArray(max(0, count - shown.size)) + shown
    path.reset()
    WaveformGeometry.path(heights, width.toFloat(), height.toFloat(), style, live = true, path = path)
    paint.style = if (style.variant == WaveformVariant.LINE) Paint.Style.STROKE else Paint.Style.FILL
    paint.strokeWidth = style.lineWidth
    invalidate()
  }

  private fun scrollOffset(now: Double): Float {
    if (scrollFrom <= 0f || scrollDuration <= 0) return 0f
    val progress = min(1.0, (now - scrollStart) / scrollDuration)
    return (scrollFrom * (1 - progress)).toFloat()
  }

  private fun applyColor() {
    paint.color = color ?: themeTextColor()
    invalidate()
  }

  private fun themeTextColor(): Int {
    val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val values = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
    val text = try {
      values.getColorStateList(0)?.defaultColor
    } finally {
      values.recycle()
    } ?: if (night) Color.WHITE else Color.BLACK
    return Color.argb((0.3f * 255).roundToInt(), Color.red(text), Color.green(text), Color.blue(text))
  }

  private fun motionEnabled() = ValueAnimator.areAnimatorsEnabled()

  private fun now() = SystemClock.uptimeMillis() / 1000.0
}

/** `LiveWaveformView` as a React Native view. Sizes arrive in dp. */
class LiveWaveformComponent(context: Context, appContext: AppContext) : ExpoView(context, appContext) {
  val waveform = LiveWaveformView(context)
  var variant = WaveformVariant.BARS
  var barWidth = 3f
  var barGap = 2f
  var barRadius: Float? = null
  var minBarHeight = 2f
  var lineWidth = 2f
  var alignment = WaveformAlignment.CENTER

  /** Any colour: a number, or a `PlatformColor`'s resource paths. */
  var color: Any? = null
    set(value) {
      field = value
      waveform.color = resolveColor(value)
    }

  init {
    addView(waveform)
  }

  fun didUpdateProps() {
    val density = resources.displayMetrics.density
    waveform.style = WaveformStyle(
      variant = variant,
      barWidth = barWidth * density,
      barGap = barGap * density,
      barRadius = barRadius?.let { it * density },
      minBarHeight = minBarHeight * density,
      lineWidth = lineWidth * density,
      alignment = alignment
    )
  }

  override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
    waveform.measure(
      MeasureSpec.makeMeasureSpec(right - left, MeasureSpec.EXACTLY),
      MeasureSpec.makeMeasureSpec(bottom - top, MeasureSpec.EXACTLY)
    )
    waveform.layout(0, 0, right - left, bottom - top)
  }

  override fun onConfigurationChanged(newConfig: Configuration?) {
    super.onConfigurationChanged(newConfig)
    waveform.color = resolveColor(color)
  }

  private fun resolveColor(value: Any?): Int? =
    when (value) {
      null -> null
      is Number -> value.toLong().toInt()
      is String -> runCatching { Color.parseColor(value) }.getOrNull()
      is Map<*, *> -> (value["resource_paths"] as? List<*>)?.firstNotNullOfOrNull { ColorPropConverter.resolveResourcePath(context, it as? String) }
      else -> null
    }
}
