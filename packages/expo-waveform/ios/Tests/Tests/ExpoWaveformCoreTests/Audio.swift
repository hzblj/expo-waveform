import AVFoundation
import Foundation

/// Audio files written for a test: a tone at an amplitude for each stretch, then silence where the amplitude is 0.
enum Audio {
  static func file(
    _ parts: [(seconds: Double, amplitude: Float)], sampleRate: Double = 44_100, channels: AVAudioChannelCount = 1,
    format: AudioFormatID = kAudioFormatLinearPCM, ext: String = "caf"
  ) throws -> URL {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString).\(ext)")
    var settings: [String: Any] = [
      AVFormatIDKey: format,
      AVSampleRateKey: sampleRate,
      AVNumberOfChannelsKey: channels,
    ]
    if format == kAudioFormatLinearPCM {
      settings[AVLinearPCMBitDepthKey] = 32
      settings[AVLinearPCMIsFloatKey] = true
    }
    let file = try AVAudioFile(forWriting: url, settings: settings, commonFormat: .pcmFormatFloat32, interleaved: false)
    let processing = file.processingFormat

    var phase: Double = 0
    for part in parts {
      let frames = AVAudioFrameCount(part.seconds * sampleRate)
      guard frames > 0, let buffer = AVAudioPCMBuffer(pcmFormat: processing, frameCapacity: frames) else {
        continue
      }
      buffer.frameLength = frames
      for frame in 0..<Int(frames) {
        let value = part.amplitude * Float(sin(phase))
        phase += 2 * .pi * 440 / sampleRate
        for channel in 0..<Int(processing.channelCount) {
          buffer.floatChannelData![channel][frame] = value
        }
      }
      try file.write(from: buffer)
    }
    return url
  }
}
