package com.hzblj.expowaveform

import android.content.Context
import expo.modules.kotlin.functions.Coroutine
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.sharedobjects.SharedObject
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** `LoadOptions` in `src/types.ts`. */
class LoadOptionsRecord : Record {
  @Field val samplesPerSecond: Double = 100.0

  @Field val maxSamples: Int = 20_000

  @Field val headers: Map<String, String> = emptyMap()
}

class ExpoWaveformModule : Module() {
  private val context: Context
    get() = appContext.reactContext ?: throw WaveformException(WaveformFailure(WaveformFailure.Code.UNKNOWN, "There is no React context"))

  override fun definition() = ModuleDefinition {
    Name("ExpoWaveform")

    AsyncFunction("loadAsync") Coroutine { uri: String, options: LoadOptionsRecord ->
      val result =
        try {
          WaveformStore.load(
            context.applicationContext,
            WaveformRequest(uri, options.headers, options.samplesPerSecond.coerceAtLeast(0.001), options.maxSamples.coerceAtLeast(1)),
          )
        } catch (error: CancellationException) {
          throw WaveformException(WaveformFailure(WaveformFailure.Code.UNKNOWN, "The analysis was cancelled"))
        } catch (error: Throwable) {
          throw WaveformException(WaveformFailure.from(error) as? WaveformFailure ?: WaveformFailure(WaveformFailure.Code.UNKNOWN, error.toString()))
        }
      mapOf(
        "samples" to result.analysis.samples,
        "sampleDuration" to result.analysis.sampleDuration,
        "duration" to result.analysis.duration,
        "fromCache" to result.fromCache,
      )
    }

    AsyncFunction("clearCacheAsync") Coroutine { ->
      withContext(Dispatchers.IO) { WaveformStore.clear(context) }
    }

    View(WaveformView::class) {
      Events("onLoad", "onError", "onScrubStart", "onScrub", "onScrubEnd", "onSeek")

      Prop("uri") { view: WaveformView, uri: String? -> view.uri = uri }
      Prop("headers") { view: WaveformView, headers: Map<String, String>? -> view.headers = headers ?: emptyMap() }
      Prop("samples") { view: WaveformView, samples: FloatArray? -> view.samples = samples }
      Prop("live") { view: WaveformView, live: Boolean? -> view.live = live ?: false }
      Prop("playback") { view: WaveformView, playback: PlaybackRecord? -> view.playback = playback }
      Prop("progress") { view: WaveformView, progress: Double? -> view.progress = progress }
      Prop("markers") { view: WaveformView, markers: DoubleArray? -> view.markers = markers ?: DoubleArray(0) }
      Prop("variant") { view: WaveformView, variant: String? -> view.variant = WaveformVariant.of(variant) }
      Prop("barWidth") { view: WaveformView, width: Float? -> view.barWidth = width ?: 3f }
      Prop("barGap") { view: WaveformView, gap: Float? -> view.barGap = gap ?: 2f }
      Prop("barRadius") { view: WaveformView, radius: Float? -> view.barRadius = radius }
      Prop("minBarHeight") { view: WaveformView, height: Float? -> view.minBarHeight = height ?: 2f }
      Prop("lineWidth") { view: WaveformView, width: Float? -> view.lineWidth = width ?: 2f }
      Prop("markerStyle") { view: WaveformView, markerStyle: String? -> view.markerStyle = WaveformMarkerStyle.of(markerStyle) }
      Prop("alignment") { view: WaveformView, alignment: String? -> view.alignment = WaveformAlignment.of(alignment) }
      Prop("scale") { view: WaveformView, scale: String? -> view.scale = WaveformScale.of(scale) }
      Prop("noiseFloor") { view: WaveformView, floor: Float? -> view.noiseFloor = minOf(-1f, floor ?: -50f) }
      Prop("normalize") { view: WaveformView, normalize: Boolean? -> view.normalize = normalize ?: true }
      // Any colour: a number, or a `PlatformColor`, which Expo's colour type does not read on Android.
      Prop("color") { view: WaveformView, color: Any? -> view.color = color }
      Prop("progressColor") { view: WaveformView, color: Any? -> view.progressColor = color }
      Prop("markerColor") { view: WaveformView, color: Any? -> view.markerColor = color }
      Prop("playedMarkerColor") { view: WaveformView, color: Any? -> view.playedMarkerColor = color }
      Prop("cursor") { view: WaveformView, cursor: Boolean? -> view.cursor = cursor ?: false }
      Prop("cursorColor") { view: WaveformView, color: Any? -> view.cursorColor = color }
      Prop("seekable") { view: WaveformView, seekable: Boolean? -> view.seekable = seekable ?: false }
      Prop("haptics") { view: WaveformView, haptics: Boolean? -> view.haptics = haptics ?: true }

      OnViewDidUpdateProps { view: WaveformView -> view.didUpdateProps() }
      OnViewDestroys { view: WaveformView -> view.destroy() }
    }

    View(LiveWaveformComponent::class) {
      Prop("meter") { view: LiveWaveformComponent, meter: SharedObject? -> view.waveform.meter = meter }
      Prop("variant") { view: LiveWaveformComponent, variant: String? -> view.variant = WaveformVariant.of(variant) }
      Prop("barWidth") { view: LiveWaveformComponent, width: Float? -> view.barWidth = width ?: 3f }
      Prop("barGap") { view: LiveWaveformComponent, gap: Float? -> view.barGap = gap ?: 2f }
      Prop("barRadius") { view: LiveWaveformComponent, radius: Float? -> view.barRadius = radius }
      Prop("minBarHeight") { view: LiveWaveformComponent, height: Float? -> view.minBarHeight = height ?: 2f }
      Prop("lineWidth") { view: LiveWaveformComponent, width: Float? -> view.lineWidth = width ?: 2f }
      Prop("alignment") { view: LiveWaveformComponent, alignment: String? -> view.alignment = WaveformAlignment.of(alignment) }
      Prop("scale") { view: LiveWaveformComponent, scale: String? -> view.waveform.scale = WaveformScale.of(scale) }
      Prop("noiseFloor") { view: LiveWaveformComponent, floor: Float? -> view.waveform.noiseFloor = minOf(-1f, floor ?: -50f) }
      Prop("color") { view: LiveWaveformComponent, color: Any? -> view.color = color }
      Prop("flow") { view: LiveWaveformComponent, flow: String? -> view.waveform.flow = LiveWaveformFlow.of(flow) }
      Prop("lag") { view: LiveWaveformComponent, lag: Double? -> view.waveform.lag = maxOf(0.0, lag ?: 2.0) }
      Prop("pace") { view: LiveWaveformComponent, pace: Double? -> view.waveform.pace = pace?.takeIf { it > 0 } }
      Prop("growIn") { view: LiveWaveformComponent, growIn: Map<String, Any?>? -> view.waveform.growIn = growInOf(growIn) }

      OnViewDidUpdateProps { view: LiveWaveformComponent -> view.didUpdateProps() }
      OnViewDestroys { view: LiveWaveformComponent -> view.waveform.meter = null }
    }
  }
}

/**
 * `growIn` from JavaScript: null, or `duration` in milliseconds and `easing` as a name or four control points, either
 * left out for its default.
 */
private fun growInOf(value: Map<String, Any?>?): LiveWaveformGrowIn? {
  if (value == null) return null
  val defaults = LiveWaveformGrowIn()
  val easing = value["easing"]
  val points = (easing as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() }?.takeIf { it.size == 4 }
  return LiveWaveformGrowIn(
    duration = (value["duration"] as? Number)?.let { maxOf(0.0, it.toDouble()) / 1000 } ?: defaults.duration,
    easing = WaveformEasing.named(easing as? String)
      ?: points?.let { WaveformEasing(it[0], it[1], it[2], it[3]) }
      ?: defaults.easing,
  )
}
