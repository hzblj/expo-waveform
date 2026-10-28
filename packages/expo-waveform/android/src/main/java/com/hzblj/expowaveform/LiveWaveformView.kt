package com.hzblj.expowaveform

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.View
import com.facebook.react.bridge.ColorPropConverter
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.views.ExpoView
import java.util.function.DoubleConsumer
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A live meter: one bar per level, coming in at the right edge and flowing left. It follows a level source on its own
 * (`meter`: an object with `addAudioLevelListener(DoubleConsumer): AutoCloseable`, such as expo-speech's
 * `Transcription`), or takes levels from `append`. A plain `View`, so native code can use it as it is. `style` is in
 * pixels.
 *
 * Levels arrive unevenly, so the bars do not move when one arrives: `Choreographer` moves them every frame at the pace
 * levels usually come, `lag` levels behind the newest, and nudges the speed to keep that distance. Nothing is drawn
 * before the first level, so the meter grows in from the edge the bars come in at. The same rules as
 * `ios/LiveWaveformView.swift`.
 */
class LiveWaveformView(context: Context) : View(context) {
  var style = WaveformStyle()
    set(value) {
      if (value != field) {
        field = value
        builtBase = null
        invalidate()
      }
    }
  /** Which way the bars move; new ones come in at the opposite edge. */
  var flow = LiveWaveformFlow.LEFT
    set(value) {
      if (value != field) {
        field = value
        builtBase = null
        invalidate()
      }
    }

  /**
   * How many levels the meter runs behind the newest while they come, to even out how they arrive. 0 shows each one at
   * once, and moves as unevenly as they come.
   */
  var lag = 1.0

  /** Seconds between two levels, when the source has a fixed one; measured from the levels as they come when null. */
  var pace: Double? = null
  var scale = WaveformScale.LOGARITHMIC
  var noiseFloor = -50f

  /** A colour int; the theme's primary text colour at 30 % when null. */
  var color: Int? = null
    set(value) {
      field = value
      applyColor()
    }

  /** Followed while set; another one, or null, lets go of the previous one and starts from nothing. */
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
  private val turn = Matrix()
  /** Heights from 0 to 1; `dropped` older ones were let go, so an index here is `dropped` less than a level's number. */
  private var levels = FloatArray(0)
  private var dropped = 0
  /** Where the meter is, in levels: the level with this number sits at the right edge. The first one comes in from -1. */
  private var position = -1.0
  /** Seconds between two levels, on average. */
  private var measuredPace = 0.08
  private var lastAppend: Double? = null
  private var lastFrame: Double? = null
  private var moving = false
  private var builtBase: Int? = null
  private var builtWidth = 0
  private var builtHeight = 0
  private var subscription: AutoCloseable? = null
  private val frameCallback = Choreographer.FrameCallback { frameTime -> tick(frameTime / 1e9) }

  init {
    applyColor()
  }

  private val newest: Int
    get() = dropped + levels.size - 1

  /** A new level, as a root mean square from 0 to 1. On the main thread. */
  fun append(rms: Float) {
    val next = levels + Bars.level(rms, scale, noiseFloor)
    val length = LiveFlowGeometry.canvasSize(flow, width.toFloat(), height.toFloat()).first
    val kept = WaveformGeometry.count(length, style) + kotlin.math.ceil(lag).toInt() + 4
    if (next.size > kept * 2) {
      val removed = next.size - kept
      levels = next.copyOfRange(removed, next.size)
      dropped += removed
    } else {
      levels = next
    }

    val now = now()
    lastAppend?.let { previous ->
      if (now - previous < 0.5) {
        measuredPace = measuredPace * 0.9 + min(0.3, max(0.02, now - previous)) * 0.1
      }
    }
    lastAppend = now
    startMoving()
  }

  /** Back to nothing. */
  fun reset() {
    levels = FloatArray(0)
    dropped = 0
    position = -1.0
    lastAppend = null
    builtBase = null
    stopMoving()
    invalidate()
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    subscribe()
    if (levels.isNotEmpty()) startMoving()
  }

  override fun onDetachedFromWindow() {
    unsubscribe()
    stopMoving()
    super.onDetachedFromWindow()
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    builtBase = null
  }

  override fun onConfigurationChanged(newConfig: Configuration?) {
    super.onConfigurationChanged(newConfig)
    applyColor()
  }

  /** The path holds the bars up to the one coming in, right-anchored; the canvas slides it by the fraction of a level. */
  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val base = floor(position).toInt()
    val last = min(newest, base + 1)
    if (levels.isEmpty() || width == 0 || last - dropped < 0) return

    if (base != builtBase || width != builtWidth || height != builtHeight) {
      builtBase = base
      builtWidth = width
      builtHeight = height
      val (length, thickness) = LiveFlowGeometry.canvasSize(flow, width.toFloat(), height.toFloat())
      val count = WaveformGeometry.count(length, style) + 2
      val end = last - dropped + 1
      val heights = levels.copyOfRange(max(0, end - count), end)
      path.reset()
      WaveformGeometry.path(heights, length, thickness, style, live = true, path = path)
      turn.setValues(LiveFlowGeometry.matrix(flow, width.toFloat(), height.toFloat()))
      path.transform(turn)
      paint.style = if (style.variant == WaveformVariant.LINE) Paint.Style.STROKE else Paint.Style.FILL
      paint.strokeWidth = style.lineWidth
    }

    val shift = if (last > base) ((1 - (position - base)) * style.step).toFloat() else 0f
    val (dx, dy) = LiveFlowGeometry.offset(flow, shift)
    canvas.save()
    canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
    canvas.translate(dx, dy)
    canvas.drawPath(path, paint)
    canvas.restore()
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

  private fun startMoving() {
    if (moving || !isAttachedToWindow) return
    moving = true
    lastFrame = null
    Choreographer.getInstance().postFrameCallback(frameCallback)
  }

  private fun stopMoving() {
    if (!moving) return
    moving = false
    Choreographer.getInstance().removeFrameCallback(frameCallback)
  }

  /** Moves toward one level behind the newest, or onto the newest once levels stop coming, so the last one shows. */
  private fun tick(now: Double) {
    if (!moving) return
    val elapsed = lastFrame?.let { min(0.1, now - it) } ?: 0.0
    lastFrame = now
    val last = newest.toDouble()
    val pace = max(0.005, this.pace ?: measuredPace)
    val flowing = lastAppend?.let { now() - it < pace * max(3.0, lag + 2) } ?: false
    val target = if (flowing) last - max(0.0, lag) else last

    position = if (!ValueAnimator.areAnimatorsEnabled()) {
      last
    } else {
      val behind = target - position
      val speed = (1 + min(0.8, max(-0.8, behind * 0.6))) / pace
      min(last, position + elapsed * speed)
    }
    invalidate()

    if (!flowing && position >= last) {
      moving = false
      return
    }
    Choreographer.getInstance().postFrameCallback(frameCallback)
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

  private fun now() = System.nanoTime() / 1e9
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
