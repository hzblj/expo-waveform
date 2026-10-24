package com.hzblj.expowaveform

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Region
import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.PathInterpolator
import android.widget.SeekBar
import com.facebook.react.bridge.ColorPropConverter
import com.facebook.react.uimanager.events.NativeGestureUtil
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record
import expo.modules.kotlin.viewevent.EventDispatcher
import expo.modules.kotlin.views.ExpoView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Where playback is, from `useWaveformPlayback`: `WaveformPlayback` in `src/types.ts`. */
class PlaybackRecord : Record {
  @Field val currentTime: Double = 0.0

  @Field val duration: Double? = null

  @Field val playing: Boolean = false

  @Field val rate: Double? = null

  fun sameAs(other: PlaybackRecord?) =
    other != null && currentTime == other.currentTime && duration == other.duration && playing == other.playing && rate == other.rate
}

/**
 * The waveform: bars (or an envelope) in two colours split at the playhead, markers behind them, a cursor on top.
 * The path is built when the data, the size or the style change; playback only redraws it with another clip, and only
 * when the playhead has moved a pixel.
 */
@SuppressLint("ViewConstructor")
class WaveformView(context: Context, appContext: AppContext) : ExpoView(context, appContext) {
  private val onLoad by EventDispatcher()
  private val onError by EventDispatcher()
  private val onScrubStart by EventDispatcher()
  private val onScrub by EventDispatcher()
  // `time` is null when the touch was let go: a map that holds a null.
  private val onScrubEnd by EventDispatcher<Map<String, Any?>>()
  private val onSeek by EventDispatcher()

  // Props, applied together in `didUpdateProps`. Lengths in dp.

  var uri: String? = null
  var headers: Map<String, String> = emptyMap()
  var samples: FloatArray? = null
  var live = false
  var playback: PlaybackRecord? = null
  var progress: Double? = null
  var markers = DoubleArray(0)
  var variant = WaveformVariant.BARS
  var barWidth = 3f
  var barGap = 2f
  var barRadius: Float? = null
  var minBarHeight = 2f
  var lineWidth = 2f
  var alignment = WaveformAlignment.CENTER
  var markerStyle = WaveformMarkerStyle.NONE
  var scale = WaveformScale.LOGARITHMIC
  var noiseFloor = -50f
  var normalize = true
  var color: Any? = null
  var progressColor: Any? = null
  var markerColor: Any? = null
  var playedMarkerColor: Any? = null
  var cursor = false
  var cursorColor: Any? = null
  var seekable = false
  var haptics = true

  // State

  private var style = WaveformStyle()
  private var loadedRequest: WaveformRequest? = null
  private var loadJob: Job? = null
  private var loads = 0
  private var analysis: WaveformAnalysis? = null
  private var shownSamples: FloatArray? = null
  private var samplesVersion = 0
  private var lastSampleUpdate = 0.0
  private val playhead = Playhead()
  private var reported: PlaybackRecord? = null
  private var built: Built? = null
  private var sortedMarkers = DoubleArray(0)
  private var markerLefts = FloatArray(0)

  /** The view's height, less the band dots take below the bars. */
  private var barsHeight = 0f
  private var heights = FloatArray(0)
  private var scrubTime: Double? = null
  private var lastTick = 0.0
  private var drawnX = -1f

  /** Bars rising from silence when an analysis arrives. */
  private var growFrom: FloatArray? = null
  private var growStart = 0.0

  /** A live meter's scroll: from `scrollFrom` pixels right of its place to its place. */
  private var scrollFrom = 0f
  private var scrollStart = 0.0
  private var scrollDuration = 0.0

  private val path = Path()
  private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val playedMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG)

  // Touch

  private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
  private var downX = 0f
  private var downY = 0f
  private var dragging = false

  private data class Built(
    val width: Int,
    val height: Int,
    val style: WaveformStyle,
    val live: Boolean,
    val scale: WaveformScale,
    val noiseFloor: Float,
    val normalize: Boolean,
    val analysis: WaveformAnalysis?,
    val samples: Int,
    val markers: List<Double>,
    val duration: Double,
  )

  private val frameCallback = Choreographer.FrameCallback { tick() }
  private var framePosted = false

  init {
    setWillNotDraw(false)
    for (paint in listOf(trackPaint, progressPaint)) {
      paint.strokeCap = Paint.Cap.ROUND
      paint.strokeJoin = Paint.Join.ROUND
    }
    resolveColors()
  }

  private val density
    get() = resources.displayMetrics.density

  private val interactive
    get() = seekable && !live

  /** The audio's length: what the player says, else the analysis. */
  private val duration: Double
    get() = playback?.duration?.takeIf { it > 0 } ?: analysis?.duration ?: 0.0

  private fun now() = SystemClock.uptimeMillis() / 1000.0

  private fun motionEnabled() = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()

  fun didUpdateProps() {
    style =
      WaveformStyle(
        variant = variant,
        barWidth = barWidth * density,
        barGap = barGap * density,
        barRadius = barRadius?.let { it * density },
        minBarHeight = minBarHeight * density,
        lineWidth = lineWidth * density,
        alignment = alignment,
        markerStyle = markerStyle,
      )
    sortedMarkers = markers.sortedArray()
    isFocusable = interactive
    importantForAccessibility = if (interactive) IMPORTANT_FOR_ACCESSIBILITY_YES else IMPORTANT_FOR_ACCESSIBILITY_AUTO

    updateSource()
    updateSamples()
    updatePlayback()
    resolveColors()
    rebuild()
    invalidate()
  }

  fun destroy() {
    loadJob?.cancel()
    stopFrames()
  }

  /** Loads the source when it changed: from memory at once, else in the background while silence is drawn. */
  private fun updateSource() {
    val uri = uri
    if (samples != null || uri == null) {
      loadJob?.cancel()
      loadJob = null
      loadedRequest = null
      analysis = null
      return
    }
    val request = WaveformRequest(uri, headers)
    if (loadedRequest?.uri == request.uri && loadedRequest?.headers == request.headers) {
      return
    }

    loadJob?.cancel()
    loadJob = null
    loadedRequest = request
    loads += 1
    analysis = WaveformStore.cached(context, request)

    analysis?.let {
      onLoad(mapOf("duration" to it.duration, "fromCache" to true))
      return
    }

    val load = loads
    loadJob =
      appContext.mainQueue.launch {
        try {
          val result = WaveformStore.load(context.applicationContext, request)
          if (loads != load) {
            return@launch
          }
          analysis = result.analysis
          updatePlayback()
          rebuild(animated = true)
          invalidate()
          onLoad(mapOf("duration" to result.analysis.duration, "fromCache" to result.fromCache))
        } catch (error: kotlin.coroutines.cancellation.CancellationException) {
          // A newer source took over, or the view went away.
        } catch (error: Throwable) {
          if (loads == load) {
            val failure = WaveformFailure.from(error) as? WaveformFailure ?: WaveformFailure(WaveformFailure.Code.UNKNOWN, error.toString())
            onError(mapOf("code" to failure.code.value, "message" to failure.message))
          }
        }
      }
  }

  /** A live meter that moved on scrolls by what it appended, over the time since the last update. */
  private fun updateSamples() {
    val samples = samples
    if (samples?.contentEquals(shownSamples) == true || (samples == null && shownSamples == null)) {
      return
    }
    val previous = shownSamples ?: FloatArray(0)
    shownSamples = samples
    samplesVersion += 1

    val now = now()
    val interval = min(0.5, max(0.03, now - lastSampleUpdate))
    lastSampleUpdate = now

    if (!live || samples == null || !motionEnabled()) {
      scrollFrom = 0f
      return
    }
    val shift = Bars.shift(previous, samples)
    if (shift <= 0) {
      return
    }
    scrollFrom = scrollOffset(now) + shift * style.step
    scrollStart = now
    scrollDuration = interval
  }

  private fun scrollOffset(now: Double): Float {
    if (scrollFrom <= 0f || scrollDuration <= 0) {
      return 0f
    }
    val progress = min(1.0, (now - scrollStart) / scrollDuration)
    return (scrollFrom * (1 - progress)).toFloat()
  }

  override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
    super.onSizeChanged(width, height, oldWidth, oldHeight)
    rebuild()
  }

  override fun onConfigurationChanged(configuration: Configuration?) {
    super.onConfigurationChanged(configuration)
    // Theme attributes and system colours resolve anew for the new night mode.
    resolveColors()
    invalidate()
  }

  /** Heights for the bars that fit: the analysis, the samples given, or silence while there is neither. */
  private fun heights(count: Int): FloatArray {
    val samples = samples
    val analysis = analysis
    if (live) {
      // Older bars past the left edge too, so a scroll never uncovers a gap.
      val levels = (samples ?: FloatArray(0)).let { it.copyOfRange(max(0, it.size - count - 8), it.size) }
      val padding = max(0, count - levels.size)
      return FloatArray(padding + levels.size) { if (it < padding) 0f else min(1f, max(0f, levels[it - padding])) }
    }
    if (samples != null) {
      return Bars.heights(samples, count, scale = null, normalize = false)
    }
    if (analysis != null && analysis.samples.isNotEmpty()) {
      return Bars.heights(analysis.samples, count, scale, noiseFloor, normalize)
    }
    return FloatArray(count)
  }

  private fun rebuild(animated: Boolean = false) {
    val key =
      Built(
        width, height, style, live, scale, noiseFloor, normalize, analysis, samplesVersion,
        if (live || style.markerStyle == WaveformMarkerStyle.NONE) emptyList() else sortedMarkers.toList(), duration,
      )
    if (key == built) {
      return
    }
    val resized = built?.width != width || built?.height != height
    built = key

    val count = WaveformGeometry.count(width.toFloat(), style)
    val previous = heights
    heights = heights(count)
    growFrom = if (animated && !resized && previous.size == heights.size && motionEnabled()) previous else null
    growStart = now()
    val markerWidth = if (style.markerStyle == WaveformMarkerStyle.DOT) WaveformGeometry.DOT_SIZE * density else density
    markerLefts = WaveformGeometry.markers(key.markers.toDoubleArray(), duration, width.toFloat(), markerWidth)
    barsHeight = height - if (style.markerStyle == WaveformMarkerStyle.DOT && markerLefts.isNotEmpty()) WaveformGeometry.DOT_BAND * density else 0f
    buildPath(heights)

    val stroked = style.variant == WaveformVariant.LINE
    for (paint in listOf(trackPaint, progressPaint)) {
      paint.style = if (stroked) Paint.Style.STROKE else Paint.Style.FILL
      paint.strokeWidth = if (stroked) style.lineWidth else 0f
    }
  }

  private fun buildPath(heights: FloatArray) {
    WaveformGeometry.path(heights, width.toFloat(), max(0f, barsHeight), style, live, path)
  }

  // Colours

  private fun resolveColors() {
    val track = resolve(color) ?: withAlpha(themeColor(android.R.attr.textColorPrimary) ?: if (night) Color.WHITE else Color.BLACK, 0.3f)
    val played = resolve(progressColor) ?: accent()
    trackPaint.color = track
    progressPaint.color = played
    markerPaint.color = resolve(markerColor) ?: withAlpha(track, Color.alpha(track) / 255f * 0.5f)
    playedMarkerPaint.color = resolve(playedMarkerColor) ?: withAlpha(played, Color.alpha(played) / 255f * 0.5f)
    cursorPaint.color = resolve(cursorColor) ?: played
  }

  private val night
    get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

  /** The system accent (Material You) from Android 12, blue before. */
  private fun accent(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      context.getColor(if (night) android.R.color.system_accent1_200 else android.R.color.system_accent1_600)
    } else {
      Color.rgb(0, 122, 255)
    }

  private fun themeColor(attribute: Int): Int? {
    val values = context.obtainStyledAttributes(intArrayOf(attribute))
    return try {
      values.getColorStateList(0)?.defaultColor
    } finally {
      values.recycle()
    }
  }

  private fun withAlpha(color: Int, alpha: Float) = Color.argb((alpha * 255).roundToInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

  /** A processed colour: a number, or a `PlatformColor`'s resource paths (`?attr/…`, `@android:color/…`). */
  private fun resolve(value: Any?): Int? =
    when (value) {
      null -> null
      is Number -> value.toLong().toInt()
      is String -> runCatching { Color.parseColor(value) }.getOrNull()
      is Map<*, *> -> (value["resource_paths"] as? List<*>)?.firstNotNullOfOrNull { ColorPropConverter.resolveResourcePath(context, it as? String) }
      else -> null
    }

  // Playback

  /** A new report from the player moves the playhead; the same one again does not (it would move it back). */
  private fun updatePlayback() {
    val playback = playback
    if (playback != null && !playback.sameAs(reported)) {
      playhead.report(playback.currentTime, playback.playing, playback.rate ?: 1.0, now())
    }
    reported = playback
    updateFrames()
  }

  private val running
    get() = playback != null && playhead.playing && duration > 0 && scrubTime == null && isAttachedToWindow

  private fun updateFrames() {
    if (running) postFrame() else stopFrames()
  }

  private fun postFrame() {
    if (!framePosted) {
      framePosted = true
      Choreographer.getInstance().postFrameCallback(frameCallback)
    }
  }

  private fun stopFrames() {
    if (framePosted) {
      framePosted = false
      Choreographer.getInstance().removeFrameCallback(frameCallback)
    }
  }

  /** Once a frame while playing: a redraw only when the playhead has moved a pixel. */
  private fun tick() {
    framePosted = false
    if (!running) {
      return
    }
    if (abs(progressX() - drawnX) >= 1f) {
      invalidate()
    }
    postFrame()
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    updateFrames()
  }

  override fun onDetachedFromWindow() {
    stopFrames()
    super.onDetachedFromWindow()
  }

  /** The playhead from 0 to 1. */
  private val fraction: Double
    get() {
      val duration = duration
      scrubTime?.let { return if (duration > 0) it / duration else 0.0 }
      if (playback != null) {
        return if (duration > 0) playhead.time(now()) / duration else 0.0
      }
      return progress ?: 0.0
    }

  private val currentTime
    get() = min(duration, max(0.0, fraction * duration))

  private fun progressX(): Float = if (live) 0f else (min(1.0, max(0.0, fraction)) * width).roundToInt().toFloat()

  // Drawing

  override fun dispatchDraw(canvas: Canvas) {
    val save = canvas.save()
    clipToPaddingBox(canvas)
    drawWaveform(canvas)
    canvas.restoreToCount(save)
    super.dispatchDraw(canvas)
  }

  private fun drawWaveform(canvas: Canvas) {
    val now = now()
    var animating = false

    growFrom?.let { from ->
      val progress = min(1.0, (now - growStart) / GROW_DURATION).toFloat()
      val eased = GROW_EASING.getInterpolation(progress)
      buildPath(FloatArray(heights.size) { from[it] + (heights[it] - from[it]) * eased })
      if (progress >= 1f) growFrom = null else animating = true
    }

    val width = width.toFloat()
    val height = height.toFloat()
    val x = progressX()
    drawnX = x

    val offset = scrollOffset(now)
    if (offset > 0f) {
      animating = true
    }

    canvas.save()
    canvas.clipRect(x, 0f, width, height)
    canvas.translate(offset, 0f)
    drawMarkers(canvas, markerPaint)
    canvas.drawPath(path, trackPaint)
    canvas.restore()

    if (x > 0f) {
      canvas.save()
      canvas.clipRect(0f, 0f, x, height)
      drawMarkers(canvas, playedMarkerPaint)
      canvas.drawPath(path, progressPaint)
      canvas.restore()
    }

    if (cursor && !live) {
      val cursorWidth = 2 * density
      val left = min(width - cursorWidth, max(0f, x - cursorWidth / 2))
      canvas.drawRoundRect(left, 0f, left + cursorWidth, height, cursorWidth / 2, cursorWidth / 2, cursorPaint)
    }

    if (animating) {
      postInvalidateOnAnimation()
    }
  }

  /**
   * Dots in the band below the bars, or lines behind them: around the bars, never through them, since a translucent bar
   * would show the line behind it. A line variant hides nothing.
   */
  private fun drawMarkers(canvas: Canvas, paint: Paint) {
    if (markerLefts.isEmpty()) {
      return
    }
    if (style.markerStyle == WaveformMarkerStyle.DOT) {
      val radius = WaveformGeometry.DOT_SIZE * density / 2
      for (left in markerLefts) {
        canvas.drawCircle(left + radius, height - radius, radius, paint)
      }
      return
    }
    val lineWidth = density
    val save = canvas.save()
    if (style.variant != WaveformVariant.LINE) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        canvas.clipOutPath(path)
      } else {
        @Suppress("DEPRECATION")
        canvas.clipPath(path, Region.Op.DIFFERENCE)
      }
    }
    for (left in markerLefts) {
      canvas.drawRoundRect(left, 0f, left + lineWidth, height.toFloat(), lineWidth / 2, lineWidth / 2, paint)
    }
    canvas.restoreToCount(save)
  }

  // Seeking

  private fun time(x: Float): Double = if (width > 0) min(1.0, max(0.0, (x / width).toDouble())) * duration else 0.0

  /**
   * The playhead goes under the finger the moment it lands, and follows it. A sideways drag is claimed from the scroll
   * view around; a vertical one is left to it, which cancels the touch and puts the playhead back.
   */
  @SuppressLint("ClickableViewAccessibility")
  override fun onTouchEvent(event: MotionEvent): Boolean {
    if (!interactive || duration <= 0) {
      return super.onTouchEvent(event)
    }

    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        downX = event.x
        downY = event.y
        dragging = false
        val time = time(event.x)
        scrubTime = time
        stopFrames()
        onScrubStart(mapOf("time" to time))
        invalidate()
        return true
      }
      MotionEvent.ACTION_MOVE -> {
        val previous = scrubTime ?: return true
        val dx = abs(event.x - downX)
        val dy = abs(event.y - downY)
        // Up or down first: not a scrub, even where nothing around scrolls. The playhead goes back.
        if (!dragging && dy > touchSlop && dy > dx) {
          letGo(event)
          return true
        }
        if (!dragging && dx > touchSlop && dx > dy) {
          dragging = true
          parent?.requestDisallowInterceptTouchEvent(true)
          NativeGestureUtil.notifyNativeGestureStarted(this, event)
          haptic(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.DRAG_START else HapticFeedbackConstants.CLOCK_TICK)
        }
        val time = time(event.x)
        if (time != previous) {
          tickIfCrossing(previous, time)
          scrubTime = time
          onScrub(mapOf("time" to time))
          invalidate()
        }
        return true
      }
      MotionEvent.ACTION_UP -> {
        if (scrubTime == null) {
          return true
        }
        if (dragging) {
          NativeGestureUtil.notifyNativeGestureEnded(this, event)
          haptic(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.GESTURE_END else HapticFeedbackConstants.CLOCK_TICK)
        } else {
          performClick()
        }
        dragging = false
        scrubTime = null
        val time = time(event.x)
        onScrubEnd(mapOf("time" to time))
        seek(time)
        return true
      }
      MotionEvent.ACTION_CANCEL -> {
        if (scrubTime != null) {
          letGo(event)
        }
        return true
      }
    }
    return super.onTouchEvent(event)
  }

  /** The touch is not a scrub after all: the playhead goes back to where playback is, and nothing is sought. */
  private fun letGo(event: MotionEvent) {
    if (dragging) {
      NativeGestureUtil.notifyNativeGestureEnded(this, event)
    }
    dragging = false
    scrubTime = null
    onScrubEnd(mapOf("time" to null))
    updateFrames()
    invalidate()
  }

  override fun performClick(): Boolean {
    super.performClick()
    return true
  }

  private fun seek(time: Double) {
    playhead.seek(time, now())
    if (playback == null && duration > 0) {
      progress = time / duration
    }
    onSeek(mapOf("time" to time))
    updateFrames()
    invalidate()
  }

  /** A tick for a marker passed, or an end reached; never more than one every 35 ms. */
  private fun tickIfCrossing(previous: Double, time: Double) {
    val low = min(previous, time)
    val high = max(previous, time)
    val crossed = sortedMarkers.any { it > low && it <= high } || (time <= 0 && previous > 0) || (time >= duration && previous < duration)
    val now = now()
    if (crossed && now - lastTick > 0.035) {
      lastTick = now
      haptic(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
    }
  }

  /** The system's own feedback, which follows the touch feedback setting. */
  private fun haptic(constant: Int) {
    if (haptics) {
      performHapticFeedback(constant)
    }
  }

  // Accessibility: a seek bar, while seekable

  override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
    super.onInitializeAccessibilityNodeInfo(info)
    val duration = duration
    if (!interactive || duration <= 0) {
      return
    }
    val current = currentTime
    info.className = SeekBar::class.java.name
    info.rangeInfo =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        AccessibilityNodeInfo.RangeInfo(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0f, duration.toFloat(), current.toFloat())
      } else {
        @Suppress("DEPRECATION")
        AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0f, duration.toFloat(), current.toFloat())
      }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      info.stateDescription = spoken(current)
    }
    if (current > 0) {
      info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }
    if (current < duration) {
      info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
    }
    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
  }

  override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
    if (interactive && duration > 0) {
      when (action) {
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> return step(1.0)
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> return step(-1.0)
        android.R.id.accessibilityActionSetProgress -> {
          val value = arguments?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE) ?: return false
          seek(min(duration, max(0.0, value.toDouble())))
          return true
        }
      }
    }
    return super.performAccessibilityAction(action, arguments)
  }

  /** A twentieth of the audio, between a second and fifteen. */
  private fun step(direction: Double): Boolean {
    val duration = duration
    val step = min(15.0, max(1.0, duration / 20))
    seek(min(duration, max(0.0, currentTime + direction * step)))
    return true
  }

  private fun spoken(seconds: Double): String {
    val total = seconds.toInt()
    val measures =
      listOfNotNull(
        (total / 3600).takeIf { it > 0 }?.let { Measure(it, MeasureUnit.HOUR) },
        (total / 60 % 60).takeIf { it > 0 || total >= 3600 }?.let { Measure(it, MeasureUnit.MINUTE) },
        Measure(total % 60, MeasureUnit.SECOND),
      )
    return MeasureFormat.getInstance(Locale.getDefault(), MeasureFormat.FormatWidth.WIDE).formatMeasures(*measures.toTypedArray())
  }

  private companion object {
    const val GROW_DURATION = 0.45

    /** Material's emphasized deceleration: the bars rise into place without a bounce. */
    val GROW_EASING = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
  }
}
