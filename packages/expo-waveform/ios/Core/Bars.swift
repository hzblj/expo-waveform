import Foundation

/// How loudness becomes height: `WaveformScale` in `src/types.ts`.
public enum WaveformScale: String, Sendable {
  case logarithmic
  case linear
}

/// The rule every bar is drawn by, the same as `toBars` in `src/bars.ts` and `Bars` in Kotlin.
public enum Bars {
  /// A level from 0 to 1 for a root mean square: decibels above the noise floor, or the amplitude itself.
  public static func level(_ rms: Float, scale: WaveformScale, noiseFloor: Float) -> Float {
    guard rms > 0 else {
      return 0
    }
    if scale == .linear {
      return min(1, rms)
    }
    let decibels = 20 * log10(rms)
    return min(1, max(0, (decibels - noiseFloor) / -noiseFloor))
  }

  /// `count` heights from 0 to 1: each bar is the loudest stretch it covers, so a short burst is not averaged away.
  /// `scale: nil` takes the samples as levels already.
  public static func heights(
    _ samples: [Float], count: Int, scale: WaveformScale?, noiseFloor: Float = -50, normalize: Bool
  ) -> [Float] {
    let length = samples.count
    guard count > 0, length > 0 else {
      return []
    }

    var bars = [Float](repeating: 0, count: count)
    var loudest: Float = 0

    samples.withUnsafeBufferPointer { samples in
      for index in 0..<count {
        let start = index * length / count
        let end = min(length, max(start + 1, (index + 1) * length / count))
        var peak: Float = 0
        for sample in start..<end {
          peak = max(peak, samples[sample])
        }
        let level = scale.map { level(peak, scale: $0, noiseFloor: noiseFloor) } ?? min(1, max(0, peak))
        bars[index] = level
        loudest = max(loudest, level)
      }
    }

    if normalize, loudest > 0 {
      for index in 0..<count {
        bars[index] /= loudest
      }
    }
    return bars
  }

  /// How many bars of `width` with `gap` between them fit in `available`.
  public static func count(fitting available: Double, width: Double, gap: Double) -> Int {
    max(0, Int(((available + gap) / max(0.5, width + gap)).rounded(.down)))
  }

  /// How many samples were appended between two snapshots of a live meter: `previous` shifted left by the answer is a
  /// prefix of `next`. A meter that grows, or keeps a window of the last few, both work. `0` when nothing matches.
  public static func shift(from previous: [Float], to next: [Float], limit: Int = 8) -> Int {
    guard !previous.isEmpty, !next.isEmpty, previous != next else {
      return 0
    }
    if next.count > previous.count, next.starts(with: previous) {
      return next.count - previous.count
    }
    for shift in stride(from: 1, through: min(limit, previous.count - 1), by: 1) {
      let kept = previous[shift...]
      if kept.count <= next.count, next.starts(with: kept) {
        return next.count - kept.count
      }
    }
    return 0
  }
}
