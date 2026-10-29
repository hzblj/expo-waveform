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
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A live meter: one bar per level, coming in at the right edge and flowing left. It follows a level source on its own
 * (`meter`: an object with `addAudioLevelListener(DoubleConsumer): AutoCloseable`, such as expo-speech's
 * `Transcription`), or takes levels from `append`. A plain `View`, so native code can use it as it is. `style` is in
 * pixels.
 *
 * Levels arrive unevenly, so the bars do not move when one arrives: each level sets the meter on a straight line
 * toward it at the pace levels usually come, `lag` levels behind (`LiveMotion`), and `Choreographer` draws it there
 * every frame. With `growIn` a bar grows to its level as it comes in at the edge. Nothing is drawn before the first
 * level, so the meter grows in from the edge the bars come in at. The same rules as `ios/LiveWaveformView.swift`.
 */
class LiveWaveformView(context: Context) : View(context) {
  var style = WaveformStyle()
    set(value) {
      if (value != field) {
        field = value
        invalidate()
      }
    }
  /** Which way the bars move; new ones come in at the opposite edge. */
  var flow = LiveWaveformFlow.LEFT
    set(value) {
      if (value != field) {
        field = value
        invalidate()
      }
    }

  /**
   * How many levels the meter runs behind the newest while they come: room for a level that comes late, or a busy
   * main thread, before the bars would have to stop. 0 shows each one at once, and moves as unevenly as they come.
   */
  var lag = 2.0

  /** Seconds between two levels, when the source has a fixed one; measured from the levels as they come when null. */
  var pace: Double? = null

  /** New bars grow from their shortest to their level as they come in at the edge; null draws them whole. */
  var growIn: LiveWaveformGrowIn? = null
    set(value) {
      if (value != field) {
        field = value
        invalidate()
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
  /** When each level's bar started, or will start, to come in at the edge, by the level's number. */
  private val entries = HashMap<Int, Double>()
  private var motion = LiveMotion()
  private var measuredPace = LivePace()
  private var moving = false
  /** The frame `Choreographer` is drawing, so every bar is placed for the same moment. */
  private var frameTime: Double? = null
  private var isGrowing = false
  private var subscription: AutoCloseable? = null
  private val frameCallback = Choreographer.FrameCallback { frameTime -> tick(frameTime / 1e9) }

  init {
    applyColor()
  }

  private val newest: Int
    get() = dropped + levels.size - 1

  /** A new level, as a root mean square from 0 to 1. On the main thread. */
  fun append(rms: Float) {
    val now = now()
    val isFirst = levels.isEmpty()
    val next = levels + Bars.level(rms, scale, noiseFloor)
    val length = LiveFlowGeometry.canvasSize(flow, width.toFloat(), height.toFloat()).first
    val kept = WaveformGeometry.count(length, style) + ceil(lag).toInt() + 4
    if (next.size > kept * 2) {
      val removed = next.size - kept
      levels = next.copyOfRange(removed, next.size)
      dropped += removed
      entries.keys.removeAll { it < dropped }
    } else {
      levels = next
    }
    measuredPace.record(now)

    if (lag <= 0 || !ValueAnimator.areAnimatorsEnabled()) {
      motion.jump(newest.toDouble(), now)
    } else {
      if (isFirst) motion.jump(newest - 1.0, now)
      motion.follow(newest, lag, pace ?: measuredPace.value, now)
    }
    startMoving()
    invalidate()
  }

  /** Back to nothing. */
  fun reset() {
    levels = FloatArray(0)
    dropped = 0
    entries.clear()
    motion = LiveMotion()
    measuredPace = LivePace()
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

  override fun onConfigurationChanged(newConfig: Configuration?) {
    super.onConfigurationChanged(newConfig)
    applyColor()
  }

  /**
   * The bars from the left edge to the newest, the newest at its place at the edge, and the canvas slid back by how far
   * the meter is short of it.
   */
  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    isGrowing = false
    if (levels.isEmpty() || width == 0 || height == 0) return

    val now = frameTime?.takeIf { now() - it < 0.05 } ?: now()
    val here = motion.position(now)
    val (length, thickness) = LiveFlowGeometry.canvasSize(flow, width.toFloat(), height.toFloat())
    val first = max(dropped, floor(here).toInt() - WaveformGeometry.count(length, style) - 1)
    val growIn = growIn.takeIf { ValueAnimator.areAnimatorsEnabled() }
    // A bar starts to show once the meter is less than a bar's width short of it.
    val entering = (style.barWidth / style.step).toDouble()
    val heights = FloatArray(newest - first + 1) { index ->
      val level = first + index
      val shown = growIn?.let { shown(level, it, entering, now) } ?: 1f
      if (shown < 1f) isGrowing = true
      levels[level - dropped] * shown
    }

    WaveformGeometry.path(heights, length, thickness, style, live = true, path = path)
    turn.setValues(LiveFlowGeometry.matrix(flow, width.toFloat(), height.toFloat()))
    path.transform(turn)
    paint.style = if (style.variant == WaveformVariant.LINE) Paint.Style.STROKE else Paint.Style.FILL
    paint.strokeWidth = style.lineWidth

    val (dx, dy) = LiveFlowGeometry.offset(flow, ((newest - here) * style.step).toFloat())
    canvas.save()
    canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
    canvas.translate(dx, dy)
    canvas.drawPath(path, paint)
    canvas.restore()
  }

  /** How much of its level `level`'s bar shows at `now`, from when it starts to come in at the edge. */
  private fun shown(level: Int, growIn: LiveWaveformGrowIn, entering: Double, now: Double): Float {
    val known = entries[level]?.takeIf { it <= now }
    val entry = known ?: motion.timeReaching(level - entering)
    if (known == null) {
      if (entry == null) entries.remove(level) else entries[level] = entry
    }
    return entry?.let { growIn.progress(now - it).toFloat() } ?: 0f
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
    Choreographer.getInstance().postFrameCallback(frameCallback)
  }

  private fun stopMoving() {
    if (!moving) return
    moving = false
    frameTime = null
    Choreographer.getInstance().removeFrameCallback(frameCallback)
  }

  /** Draws every frame until the meter stands at the newest with every bar grown. */
  private fun tick(now: Double) {
    if (!moving) return
    frameTime = now
    invalidate()
    if (motion.position(now) >= motion.end && now >= motion.time && !isGrowing) {
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
