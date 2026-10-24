import XCTest

@testable import ExpoWaveformCore

final class PlayheadTests: XCTestCase {
  func testThePlayheadMovesOnBetweenReports() {
    var playhead = Playhead()
    playhead.report(currentTime: 10, playing: true, rate: 1.5, at: 100)

    XCTAssertEqual(playhead.time(at: 102), 13)

    playhead.report(currentTime: 13.1, playing: false, rate: 1.5, at: 102)
    XCTAssertEqual(playhead.time(at: 110), 13.1)
  }

  func testASeekHoldsUntilThePlayerCatchesUp() {
    var playhead = Playhead()
    playhead.report(currentTime: 10, playing: true, rate: 1, at: 100)
    playhead.seek(to: 50, at: 100)

    // Still the old time: passed over.
    playhead.report(currentTime: 10.2, playing: true, rate: 1, at: 100.2)
    XCTAssertEqual(playhead.time(at: 100.2), 50.2, accuracy: 0.0001)

    // There: taken.
    playhead.report(currentTime: 50.4, playing: true, rate: 1, at: 100.4)
    XCTAssertEqual(playhead.time(at: 100.4), 50.4, accuracy: 0.0001)

    // An ordinary report afterwards moves it anywhere.
    playhead.report(currentTime: 20, playing: true, rate: 1, at: 100.5)
    XCTAssertEqual(playhead.time(at: 100.5), 20, accuracy: 0.0001)
  }

  func testASeekGivesUpAfterAMoment() {
    var playhead = Playhead()
    playhead.report(currentTime: 10, playing: false, rate: 1, at: 100)
    playhead.seek(to: 50, at: 100)

    playhead.report(currentTime: 10, playing: false, rate: 1, at: 101.5)

    XCTAssertEqual(playhead.time(at: 101.5), 10)
  }
}
