import AVFoundation
import Accelerate
import Foundation

/// Root mean squares of consecutive stretches of `binFrames` frames. A stream longer than expected never holds more
/// than twice `maxSamples`: neighbours merge and the stretches double. The analyser's arithmetic, apart from decoding.
public struct LoudnessAccumulator {
  public private(set) var binFrames: Int
  public private(set) var totalFrames = 0
  let maxSamples: Int
  private var sums: [Double] = []
  private var current: Double = 0
  private var currentFrames = 0

  public init(binFrames: Int, maxSamples: Int) {
    self.binFrames = max(1, binFrames)
    self.maxSamples = max(1, maxSamples)
  }

  /// Mono frames, in order.
  public mutating func add(_ frames: UnsafeBufferPointer<Float>) {
    guard let base = frames.baseAddress else {
      return
    }
    var offset = 0

    while offset < frames.count {
      let take = min(binFrames - currentFrames, frames.count - offset)
      var sum: Float = 0
      vDSP_svesq(base + offset, 1, &sum, vDSP_Length(take))
      current += Double(sum)
      currentFrames += take
      offset += take

      if currentFrames == binFrames {
        sums.append(current)
        current = 0
        currentFrames = 0

        if sums.count >= 2 * maxSamples {
          sums = stride(from: 0, to: sums.count, by: 2).map { sums[$0] + sums[$0 + 1] }
          binFrames *= 2
        }
      }
    }
    totalFrames += frames.count
  }

  public mutating func add(_ frames: [Float]) {
    frames.withUnsafeBufferPointer { add($0) }
  }

  /// The analysis, with at most `maxSamples` samples: neighbours merge once more if there are still too many.
  public func finish(sampleRate: Double) -> WaveformAnalysis {
    var sums = sums
    var frames = [Int](repeating: binFrames, count: sums.count)
    if currentFrames > 0 {
      sums.append(current)
      frames.append(currentFrames)
    }

    let factor = max(1, (sums.count + maxSamples - 1) / maxSamples)
    let samples = stride(from: 0, to: sums.count, by: factor).map { start -> Float in
      let end = min(sums.count, start + factor)
      let count = frames[start..<end].reduce(0, +)
      return Float(min(1, (sums[start..<end].reduce(0, +) / Double(max(1, count))).squareRoot()))
    }

    return WaveformAnalysis(
      samples: samples,
      sampleDuration: Double(binFrames * factor) / sampleRate,
      duration: Double(totalFrames) / sampleRate)
  }
}

/// Decodes the audio track of any file AVFoundation reads (mp3, m4a, aac, wav, caf, aiff, flac, and the audio of mp4
/// or mov) with `AVAssetReader` at the file's own rate and channels, averages the channels (the converter's own mixdown
/// adds them at -3 dB, which reads louder than the file is) and measures it as it goes: a long file never sits in
/// memory.
public enum WaveformAnalyzer {
  public static func analyse(file url: URL, samplesPerSecond: Double, maxSamples: Int) async throws -> WaveformAnalysis {
    guard FileManager.default.fileExists(atPath: url.path) else {
      throw WaveformFailure(.audio, "There is no file at \(url.path)")
    }

    let asset = AVURLAsset(url: url)
    let track: AVAssetTrack
    let duration: CMTime
    let descriptions: [CMFormatDescription]

    do {
      guard let first = try await asset.loadTracks(withMediaType: .audio).first else {
        throw WaveformFailure(.audio, "The file has no audio track")
      }
      track = first
      duration = try await asset.load(.duration)
      descriptions = try await track.load(.formatDescriptions)
    } catch let failure as WaveformFailure {
      throw failure
    } catch {
      throw WaveformFailure(.audio, "The file could not be read: \(error.localizedDescription)")
    }

    let sampleRate =
      descriptions.lazy
      .compactMap { CMAudioFormatDescriptionGetStreamBasicDescription($0)?.pointee.mSampleRate }
      .first { $0 > 0 } ?? 44_100
    let seconds = duration.isNumeric ? duration.seconds : 0
    let rate = seconds > 0 ? min(samplesPerSecond, Double(maxSamples) / seconds) : samplesPerSecond
    var accumulator = LoudnessAccumulator(
      binFrames: Int((sampleRate / max(0.001, rate)).rounded(.up)), maxSamples: maxSamples)

    let reader: AVAssetReader
    do {
      reader = try AVAssetReader(asset: asset)
    } catch {
      throw WaveformFailure(.audio, "The file could not be opened: \(error.localizedDescription)")
    }

    let output = AVAssetReaderTrackOutput(
      track: track,
      outputSettings: [
        AVFormatIDKey: kAudioFormatLinearPCM,
        AVLinearPCMBitDepthKey: 32,
        AVLinearPCMIsFloatKey: true,
        AVLinearPCMIsBigEndianKey: false,
        AVLinearPCMIsNonInterleaved: false,
      ])
    output.alwaysCopiesSampleData = false
    reader.add(output)

    guard reader.startReading() else {
      throw WaveformFailure(.unsupported, "The audio could not be decoded: \(reader.error?.localizedDescription ?? "unknown")")
    }
    defer {
      if reader.status == .reading {
        reader.cancelReading()
      }
    }

    var interleaved: [Float] = []
    var mono: [Float] = []
    var outputRate = sampleRate

    while let sample = output.copyNextSampleBuffer() {
      try Task.checkCancellation()

      let count = CMSampleBufferGetNumSamples(sample)
      guard count > 0, let block = CMSampleBufferGetDataBuffer(sample),
        let format = CMSampleBufferGetFormatDescription(sample)
          .flatMap({ CMAudioFormatDescriptionGetStreamBasicDescription($0)?.pointee })
      else {
        continue
      }
      let channels = Int(max(1, format.mChannelsPerFrame))
      outputRate = format.mSampleRate > 0 ? format.mSampleRate : outputRate

      if interleaved.count < count * channels {
        interleaved = [Float](repeating: 0, count: count * channels)
        mono = [Float](repeating: 0, count: count)
      }
      let status = interleaved.withUnsafeMutableBytes { bytes in
        CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: count * channels * 4, destination: bytes.baseAddress!)
      }
      guard status == kCMBlockBufferNoErr else {
        throw WaveformFailure(.audio, "The audio could not be decoded (\(status))")
      }

      if channels == 1 {
        interleaved.withUnsafeBufferPointer { accumulator.add(UnsafeBufferPointer(rebasing: $0[0..<count])) }
        continue
      }
      interleaved.withUnsafeBufferPointer { source in
        mono.withUnsafeMutableBufferPointer { target in
          var scale = 1 / Float(channels)
          vDSP_vsmul(source.baseAddress!, channels, &scale, target.baseAddress!, 1, vDSP_Length(count))
          for channel in 1..<channels {
            vDSP_vsma(
              source.baseAddress! + channel, channels, &scale, target.baseAddress!, 1, target.baseAddress!, 1,
              vDSP_Length(count))
          }
        }
      }
      mono.withUnsafeBufferPointer { accumulator.add(UnsafeBufferPointer(rebasing: $0[0..<count])) }
    }

    if reader.status == .failed {
      throw WaveformFailure(.audio, "The file could not be decoded: \(reader.error?.localizedDescription ?? "unknown")")
    }
    try Task.checkCancellation()
    return accumulator.finish(sampleRate: outputRate)
  }
}
