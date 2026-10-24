import CoreGraphics
import XCTest

@testable import ExpoWaveformCore

final class GeometryTests: XCTestCase {
  func testBarsSpanTheWidthFlushAtBothEdges() {
    let style = WaveformStyle()
    let positions = WaveformGeometry.positions(count: 3, size: CGSize(width: 103, height: 20), style: style, live: false)

    XCTAssertEqual(positions, [0, 50, 100])
  }

  func testLiveBarsAreAnchoredAtTheRight() {
    let style = WaveformStyle()
    let positions = WaveformGeometry.positions(count: 3, size: CGSize(width: 100, height: 20), style: style, live: true)

    XCTAssertEqual(positions, [87, 92, 97])
  }

  func testSilenceStillDrawsTheShortestBar() {
    var style = WaveformStyle()
    XCTAssertEqual(WaveformGeometry.barHeight(0, in: 40, style: style), 2)
    XCTAssertEqual(WaveformGeometry.barHeight(1, in: 40, style: style), 40)
    XCTAssertEqual(WaveformGeometry.barHeight(0.5, in: 40, style: style), 21)
    style.minBarHeight = 100
    XCTAssertEqual(WaveformGeometry.barHeight(0, in: 40, style: style), 40)
  }

  func testBarsAreCentredOrSitOnTheBottom() {
    var style = WaveformStyle()
    let size = CGSize(width: 3, height: 40)

    XCTAssertEqual(WaveformGeometry.path([0.5], size: size, style: style, live: false).boundingBox.minY, 9.5)
    style.alignment = .bottom
    XCTAssertEqual(WaveformGeometry.path([0.5], size: size, style: style, live: false).boundingBox.maxY, 40)
    XCTAssertEqual(WaveformGeometry.path([0.5], size: size, style: style, live: false).boundingBox.minY, 19)
  }

  func testAnEnvelopeStaysInsideTheView() {
    var style = WaveformStyle()
    let size = CGSize(width: 200, height: 40)
    let heights: [Float] = [0, 1, 0, 1, 0.2, 0.9, 0]

    for variant in [WaveformVariant.area, .line] {
      style.variant = variant
      let box = WaveformGeometry.path(heights, size: size, style: style, live: false).boundingBoxOfPath
      XCTAssertGreaterThanOrEqual(box.minX, 0)
      XCTAssertLessThanOrEqual(box.maxX, 200)
      XCTAssertGreaterThanOrEqual(box.minY, 0)
      XCTAssertLessThanOrEqual(box.maxY, 40)
    }
  }

  func testMarkersLandOnWholePixelsInsideTheView() {
    let path = WaveformGeometry.markers([0, 5, 10, 11], duration: 10, size: CGSize(width: 100, height: 20), scale: 3)
    let box = path.boundingBox

    XCTAssertEqual(box.minX, 0)
    XCTAssertEqual(box.maxX, 100)
    XCTAssertTrue(WaveformGeometry.markers([1], duration: 0, size: CGSize(width: 100, height: 20), scale: 3).isEmpty)
  }

  func testDotsTakeABandBelowTheBars() {
    var style = WaveformStyle()
    let size = CGSize(width: 100, height: 40)

    XCTAssertEqual(WaveformGeometry.barsSize(size, style: style, hasMarkers: true).height, 40)
    style.markerStyle = .dot
    XCTAssertEqual(WaveformGeometry.barsSize(size, style: style, hasMarkers: true).height, 34)
    XCTAssertEqual(WaveformGeometry.barsSize(size, style: style, hasMarkers: false).height, 40)

    let dots = WaveformGeometry.dots([0, 5, 10], duration: 10, size: size, scale: 3).boundingBox
    XCTAssertEqual(dots.minY, 37)
    XCTAssertEqual(dots.maxY, 40)
    XCTAssertEqual(dots.minX, 0)
    XCTAssertEqual(dots.maxX, 100)
  }
}
