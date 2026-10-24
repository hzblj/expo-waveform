import AVFoundation
import XCTest

@testable import ExpoWaveformCore

final class AnalyzerTests: XCTestCase {
  func testASineIsMeasuredAsItsRootMeanSquare() async throws {
    let url = try Audio.file([(1, 0.5), (1, 0)])
    defer { try? FileManager.default.removeItem(at: url) }

    let analysis = try await WaveformAnalyzer.analyse(file: url, samplesPerSecond: 100, maxSamples: 20_000)

    XCTAssertEqual(analysis.duration, 2, accuracy: 0.001)
    XCTAssertEqual(Double(analysis.samples.count), 200, accuracy: 1)
    XCTAssertEqual(analysis.sampleDuration, 0.01, accuracy: 0.0001)
    // A sine's RMS is its amplitude over the square root of two.
    XCTAssertEqual(analysis.samples[50], 0.5 / Float(2).squareRoot(), accuracy: 0.005)
    XCTAssertEqual(analysis.samples[150], 0, accuracy: 0.0001)
  }

  func testStereoIsMixedDownAndCompressedAudioDecodes() async throws {
    let url = try Audio.file([(0.5, 0), (0.5, 0.8)], channels: 2, format: kAudioFormatMPEG4AAC, ext: "m4a")
    defer { try? FileManager.default.removeItem(at: url) }

    let analysis = try await WaveformAnalyzer.analyse(file: url, samplesPerSecond: 50, maxSamples: 20_000)

    XCTAssertEqual(analysis.duration, 1, accuracy: 0.06)
    XCTAssertLessThan(analysis.samples[10], 0.01)
    XCTAssertEqual(analysis.samples[37], 0.8 / Float(2).squareRoot(), accuracy: 0.03)
  }

  func testALongFileKeepsToMaxSamples() async throws {
    let url = try Audio.file([(3, 0.3)], sampleRate: 8_000)
    defer { try? FileManager.default.removeItem(at: url) }

    let analysis = try await WaveformAnalyzer.analyse(file: url, samplesPerSecond: 100, maxSamples: 120)

    XCTAssertLessThanOrEqual(analysis.samples.count, 120)
    XCTAssertGreaterThan(analysis.samples.count, 100)
    XCTAssertEqual(analysis.sampleDuration * Double(analysis.samples.count), 3, accuracy: analysis.sampleDuration)
  }

  func testAMissingOrBrokenFileFailsAsAudio() async throws {
    do {
      _ = try await WaveformAnalyzer.analyse(
        file: URL(fileURLWithPath: "/nope/\(UUID().uuidString).mp3"), samplesPerSecond: 100, maxSamples: 100)
      XCTFail("Expected a failure")
    } catch let failure as WaveformFailure {
      XCTAssertEqual(failure.code, .audio)
    }

    let junk = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString).mp3")
    try Data("not audio".utf8).write(to: junk)
    defer { try? FileManager.default.removeItem(at: junk) }
    do {
      _ = try await WaveformAnalyzer.analyse(file: junk, samplesPerSecond: 100, maxSamples: 100)
      XCTFail("Expected a failure")
    } catch let failure as WaveformFailure {
      XCTAssertEqual(failure.code, .audio)
    }
  }

  func testTheAccumulatorMergesAStreamLongerThanExpected() {
    var accumulator = LoudnessAccumulator(binFrames: 10, maxSamples: 4)
    accumulator.add([Float](repeating: 0.5, count: 95))

    let analysis = accumulator.finish(sampleRate: 10)

    XCTAssertEqual(accumulator.binFrames, 20)
    XCTAssertEqual(analysis.samples.count, 3)
    XCTAssertEqual(analysis.sampleDuration, 4)
    XCTAssertEqual(analysis.duration, 9.5)
    XCTAssertEqual(analysis.samples, [0.5, 0.5, 0.5])
  }

  func testAnAnalysisSurvivesTheDisk() {
    let analysis = WaveformAnalysis(samples: [0, 0.25, 1], sampleDuration: 0.01, duration: 0.03)
    let decoded = WaveformAnalysis(encoded: analysis.encoded)

    XCTAssertEqual(decoded?.samples, [0, 0.25, 1])
    XCTAssertEqual(decoded?.sampleDuration, 0.01)
    XCTAssertEqual(decoded?.duration, 0.03)
    XCTAssertNil(WaveformAnalysis(encoded: Data("EWF1".utf8)))
    XCTAssertNil(WaveformAnalysis(encoded: analysis.encoded.dropLast()))
  }
}
