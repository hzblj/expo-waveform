import ExpoModulesCore

/// `LoadOptions` in `src/types.ts`.
struct LoadOptionsRecord: Record {
  @Field var samplesPerSecond: Double = 100
  @Field var maxSamples: Int = 20_000
  @Field var headers: [String: String] = [:]
}

final class WaveformException: Exception, @unchecked Sendable {
  private let failure: WaveformFailure

  init(_ failure: WaveformFailure) {
    self.failure = failure
    super.init(name: "WaveformException", description: failure.message, code: failure.code.rawValue)
  }

  override var reason: String {
    failure.message
  }

  override var code: String {
    failure.code.rawValue
  }
}

public final class ExpoWaveformModule: Module {
  public func definition() -> ModuleDefinition {
    Name("ExpoWaveform")

    AsyncFunction("loadAsync") { (uri: String, options: LoadOptionsRecord) async throws -> [String: Any] in
      do {
        let result = try await WaveformStore.shared.load(
          WaveformRequest(
            uri: uri, headers: options.headers, samplesPerSecond: options.samplesPerSecond,
            maxSamples: options.maxSamples))
        return [
          "samples": result.analysis.samples.map(Double.init),
          "sampleDuration": result.analysis.sampleDuration,
          "duration": result.analysis.duration,
          "fromCache": result.fromCache,
        ]
      } catch is CancellationError {
        throw WaveformException(WaveformFailure(.unknown, "The analysis was cancelled"))
      } catch {
        throw WaveformException(WaveformFailure.from(error))
      }
    }

    AsyncFunction("clearCacheAsync") {
      await WaveformStore.shared.clear()
    }

    View(WaveformView.self) {
      Events("onLoad", "onError", "onScrubStart", "onScrub", "onScrubEnd", "onSeek")

      Prop("uri") { (view: WaveformView, uri: String?) in
        view.uri = uri
      }
      Prop("headers") { (view: WaveformView, headers: [String: String]?) in
        view.headers = headers ?? [:]
      }
      Prop("samples") { (view: WaveformView, samples: [Double]?) in
        view.samples = samples?.map(Float.init)
      }
      Prop("live") { (view: WaveformView, live: Bool?) in
        view.live = live ?? false
      }
      Prop("playback") { (view: WaveformView, playback: PlaybackRecord?) in
        view.playback = playback
      }
      Prop("progress") { (view: WaveformView, progress: Double?) in
        view.progress = progress
      }
      Prop("markers") { (view: WaveformView, markers: [Double]?) in
        view.markers = markers ?? []
      }
      Prop("variant") { (view: WaveformView, variant: String?) in
        view.style.variant = variant.flatMap(WaveformVariant.init(rawValue:)) ?? .bars
      }
      Prop("barWidth") { (view: WaveformView, width: Double?) in
        view.style.barWidth = CGFloat(width ?? 3)
      }
      Prop("barGap") { (view: WaveformView, gap: Double?) in
        view.style.barGap = CGFloat(gap ?? 2)
      }
      Prop("barRadius") { (view: WaveformView, radius: Double?) in
        view.style.barRadius = radius.map { CGFloat($0) }
      }
      Prop("minBarHeight") { (view: WaveformView, height: Double?) in
        view.style.minBarHeight = CGFloat(height ?? 2)
      }
      Prop("lineWidth") { (view: WaveformView, width: Double?) in
        view.style.lineWidth = CGFloat(width ?? 2)
      }
      Prop("markerStyle") { (view: WaveformView, markerStyle: String?) in
        view.style.markerStyle = markerStyle.flatMap(WaveformMarkerStyle.init(rawValue:)) ?? .none
      }
      Prop("alignment") { (view: WaveformView, alignment: String?) in
        view.style.alignment = alignment.flatMap(WaveformAlignment.init(rawValue:)) ?? .center
      }
      Prop("scale") { (view: WaveformView, scale: String?) in
        view.scale = scale.flatMap(WaveformScale.init(rawValue:)) ?? .logarithmic
      }
      Prop("noiseFloor") { (view: WaveformView, floor: Double?) in
        view.noiseFloor = Float(min(-1, floor ?? -50))
      }
      Prop("normalize") { (view: WaveformView, normalize: Bool?) in
        view.normalize = normalize ?? true
      }
      Prop("color") { (view: WaveformView, color: UIColor?) in
        view.color = color
      }
      Prop("progressColor") { (view: WaveformView, color: UIColor?) in
        view.progressColor = color
      }
      Prop("markerColor") { (view: WaveformView, color: UIColor?) in
        view.markerColor = color
      }
      Prop("playedMarkerColor") { (view: WaveformView, color: UIColor?) in
        view.playedMarkerColor = color
      }
      Prop("cursor") { (view: WaveformView, cursor: Bool?) in
        view.cursor = cursor ?? false
      }
      Prop("cursorColor") { (view: WaveformView, color: UIColor?) in
        view.cursorColor = color
      }
      Prop("seekable") { (view: WaveformView, seekable: Bool?) in
        view.seekable = seekable ?? false
      }
      Prop("haptics") { (view: WaveformView, haptics: Bool?) in
        view.haptics = haptics ?? true
      }

      OnViewDidUpdateProps { (view: WaveformView) in
        view.didUpdateProps()
      }
    }
  }
}
