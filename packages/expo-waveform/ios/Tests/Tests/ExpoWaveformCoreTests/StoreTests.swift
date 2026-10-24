import XCTest

@testable import ExpoWaveformCore

final class StoreTests: XCTestCase {
  func testASecondLoadComesFromTheCache() async throws {
    let url = try Audio.file([(0.5, 0.4)])
    defer { try? FileManager.default.removeItem(at: url) }
    let request = WaveformRequest(uri: url.absoluteString)

    let first = try await WaveformStore().load(request)
    let second = try await WaveformStore().load(request)

    XCTAssertFalse(first.fromCache)
    XCTAssertTrue(second.fromCache)
    XCTAssertEqual(first.analysis.samples, second.analysis.samples)
  }

  func testConcurrentLoadsShareOneAnalysis() async throws {
    let url = try Audio.file([(2, 0.4)])
    defer { try? FileManager.default.removeItem(at: url) }
    let store = WaveformStore()
    let request = WaveformRequest(uri: url.path, samplesPerSecond: 37)

    async let first = store.load(request)
    async let second = store.load(request)
    let results = try await [first, second]

    XCTAssertTrue(results[0].analysis === results[1].analysis)
  }

  func testAnEditedFileIsAnalysedAgain() async throws {
    let url = try Audio.file([(0.5, 0.4)])
    defer { try? FileManager.default.removeItem(at: url) }
    let store = WaveformStore()
    let request = WaveformRequest(uri: url.absoluteString, samplesPerSecond: 41)

    _ = try await store.load(request)
    try FileManager.default.removeItem(at: url)
    try FileManager.default.copyItem(at: try Audio.file([(1, 0.1)]), to: url)
    let edited = try await store.load(request)

    XCTAssertFalse(edited.fromCache)
    XCTAssertEqual(edited.analysis.duration, 1, accuracy: 0.001)
  }

  func testACancelledLoadStopsTheAnalysis() async throws {
    let url = try Audio.file([(30, 0.4)], sampleRate: 48_000)
    defer { try? FileManager.default.removeItem(at: url) }
    let store = WaveformStore()

    let task = Task { try await store.load(WaveformRequest(uri: url.absoluteString, samplesPerSecond: 43)) }
    task.cancel()

    do {
      _ = try await task.value
    } catch {
      XCTAssertTrue(error is CancellationError, "\(error)")
    }
  }

  func testUnsupportedSchemesFail() async {
    do {
      _ = try await WaveformStore().load(WaveformRequest(uri: "ph://ABC"))
      XCTFail("Expected a failure")
    } catch let failure as WaveformFailure {
      XCTAssertEqual(failure.code, .unsupported)
    } catch {
      XCTFail("\(error)")
    }
  }

  func testClearForgetsEverything() async throws {
    let url = try Audio.file([(0.5, 0.4)])
    defer { try? FileManager.default.removeItem(at: url) }
    let store = WaveformStore()
    let request = WaveformRequest(uri: url.absoluteString, samplesPerSecond: 47)

    _ = try await store.load(request)
    await store.clear()
    let again = try await store.load(request)

    XCTAssertFalse(again.fromCache)
  }
}
