import XCTest

@testable import ExpoWaveformCore

final class BarsTests: XCTestCase {
  func testLevelsAreDecibelsAboveTheFloor() {
    XCTAssertEqual(Bars.level(1, scale: .logarithmic, noiseFloor: -50), 1)
    XCTAssertEqual(Bars.level(0.1, scale: .logarithmic, noiseFloor: -50), 0.6, accuracy: 0.0001)
    XCTAssertEqual(Bars.level(0.001, scale: .logarithmic, noiseFloor: -50), 0)
    XCTAssertEqual(Bars.level(0, scale: .logarithmic, noiseFloor: -50), 0)
    XCTAssertEqual(Bars.level(0.25, scale: .linear, noiseFloor: -50), 0.25)
    XCTAssertEqual(Bars.level(2, scale: .linear, noiseFloor: -50), 1)
  }

  func testABarIsTheLoudestStretchItCovers() {
    let samples: [Float] = [0, 0, 0.5, 0, 0, 0, 0.25, 0]
    XCTAssertEqual(Bars.heights(samples, count: 4, scale: .linear, normalize: false), [0, 0.5, 0, 0.25])
    XCTAssertEqual(Bars.heights(samples, count: 4, scale: .linear, normalize: true), [0, 1, 0, 0.5])
  }

  func testMoreBarsThanSamplesRepeatThem() {
    XCTAssertEqual(Bars.heights([0.2, 0.4], count: 4, scale: nil, normalize: false), [0.2, 0.2, 0.4, 0.4])
    XCTAssertEqual(Bars.heights([], count: 4, scale: nil, normalize: true), [])
    XCTAssertEqual(Bars.heights([0.2], count: 0, scale: nil, normalize: true), [])
  }

  func testTheSameBarsAsJavaScript() {
    // The fixture `test/bars.test.ts` checks against `toBars`.
    let samples: [Float] = [0.01, 0.2, 0.05, 0.9, 0.3, 0.001, 0.6]
    let heights = Bars.heights(samples, count: 3, scale: .logarithmic, noiseFloor: -40, normalize: true)
    XCTAssertEqual(heights.count, 3)
    XCTAssertEqual(heights[0], 0.66575, accuracy: 0.0001)
    XCTAssertEqual(heights[1], 1, accuracy: 0.0001)
    XCTAssertEqual(heights[2], 0.90989, accuracy: 0.0001)
  }

  func testCountFitsBarsWithGapsBetween() {
    XCTAssertEqual(Bars.count(fitting: 100, width: 3, gap: 2), 20)
    XCTAssertEqual(Bars.count(fitting: 98, width: 3, gap: 2), 20)
    XCTAssertEqual(Bars.count(fitting: 97, width: 3, gap: 2), 19)
    XCTAssertEqual(Bars.count(fitting: 0, width: 3, gap: 2), 0)
  }

  func testShiftFindsWhatALiveMeterAppended() {
    XCTAssertEqual(Bars.shift(from: [0.1, 0.2], to: [0.1, 0.2, 0.3]), 1)
    XCTAssertEqual(Bars.shift(from: [0.1, 0.2, 0.3, 0.4], to: [0.3, 0.4, 0.5, 0.6]), 2)
    XCTAssertEqual(Bars.shift(from: [0.1, 0.2, 0.3], to: [0.1, 0.2, 0.3]), 0)
    XCTAssertEqual(Bars.shift(from: [0.1, 0.2, 0.3], to: [0.9, 0.8, 0.7]), 0)
    XCTAssertEqual(Bars.shift(from: [], to: [0.1]), 0)
  }
}
