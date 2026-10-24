import Foundation

/// The loudness of every short stretch of a file: `WaveformData` in `src/types.ts`, without `fromCache`.
public final class WaveformAnalysis: Sendable {
  /// Root mean square of each stretch, from 0 to 1 (full scale).
  public let samples: [Float]
  /// Seconds each sample covers; the last one may cover less.
  public let sampleDuration: Double
  /// The file's length in seconds, from the frames decoded.
  public let duration: Double

  public init(samples: [Float], sampleDuration: Double, duration: Double) {
    self.samples = samples
    self.sampleDuration = sampleDuration
    self.duration = duration
  }

  /// `EWF1`, the sample duration and the duration (Float64), the count (UInt32), then the samples (Float32), all
  /// little-endian. Android writes the same.
  public var encoded: Data {
    var data = Data(capacity: 24 + samples.count * 4)
    data.append(contentsOf: Array("EWF1".utf8))
    withUnsafeBytes(of: sampleDuration.bitPattern.littleEndian) { data.append(contentsOf: $0) }
    withUnsafeBytes(of: duration.bitPattern.littleEndian) { data.append(contentsOf: $0) }
    withUnsafeBytes(of: UInt32(samples.count).littleEndian) { data.append(contentsOf: $0) }
    for sample in samples {
      withUnsafeBytes(of: sample.bitPattern.littleEndian) { data.append(contentsOf: $0) }
    }
    return data
  }

  public convenience init?(encoded data: Data) {
    guard data.count >= 24, data.prefix(4).elementsEqual("EWF1".utf8) else {
      return nil
    }
    let bytes = [UInt8](data)
    func integer<T: FixedWidthInteger>(_ type: T.Type, at offset: Int) -> T {
      var value: T = 0
      for index in 0..<MemoryLayout<T>.size {
        value |= T(bytes[offset + index]) << (8 * index)
      }
      return value
    }

    let count = Int(integer(UInt32.self, at: 20))
    guard data.count == 24 + count * 4 else {
      return nil
    }
    let samples = (0..<count).map { Float(bitPattern: integer(UInt32.self, at: 24 + $0 * 4)) }
    self.init(
      samples: samples,
      sampleDuration: Double(bitPattern: integer(UInt64.self, at: 4)),
      duration: Double(bitPattern: integer(UInt64.self, at: 12)))
  }
}
