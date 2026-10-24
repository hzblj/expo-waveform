package com.hzblj.expowaveform

import android.content.Context
import expo.modules.kotlin.functions.Coroutine
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
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
  }
}
