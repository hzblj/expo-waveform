import XCTest

@testable import ExpoWaveformCore

final class LiveMotionTests: XCTestCase {
  func testEasingStartsAtNothingAndEndsWhole() {
    for easing in [WaveformEasing.linear, .easeIn, .easeOut, .easeInOut, WaveformEasing(0.2, 1.4, 0.4, 1)] {
      XCTAssertEqual(easing.value(at: 0), 0)
      XCTAssertEqual(easing.value(at: 1), 1)
      XCTAssertEqual(easing.value(at: -1), 0)
      XCTAssertEqual(easing.value(at: 2), 1)
    }
  }

  func testEasingFollowsItsCurve() {
    XCTAssertEqual(WaveformEasing.linear.value(at: 0.3), 0.3, accuracy: 1e-6)
    XCTAssertEqual(WaveformEasing.easeInOut.value(at: 0.5), 0.5, accuracy: 1e-6)
    XCTAssertGreaterThan(WaveformEasing.easeOut.value(at: 0.25), 0.25)
    XCTAssertLessThan(WaveformEasing.easeIn.value(at: 0.25), 0.25)
    // CSS's `ease-out` at a fifth of its time.
    XCTAssertEqual(WaveformEasing.easeOut.value(at: 0.2), 0.30837, accuracy: 1e-4)

    var previous = 0.0
    for step in 1...100 {
      let value = WaveformEasing.easeOut.value(at: Double(step) / 100)
      XCTAssertGreaterThanOrEqual(value, previous)
      previous = value
    }
  }

  func testEasingByName() {
    XCTAssertEqual(WaveformEasing.named("easeOut"), .easeOut)
    XCTAssertNil(WaveformEasing.named("bounce"))
  }

  func testABarGrowsOverItsDuration() {
    let growIn = LiveWaveformGrowIn(duration: 0.2, easing: .linear)

    XCTAssertEqual(growIn.progress(after: -0.1), 0)
    XCTAssertEqual(growIn.progress(after: 0.1), 0.5, accuracy: 1e-6)
    XCTAssertEqual(growIn.progress(after: 0.3), 1)
    XCTAssertEqual(LiveWaveformGrowIn(duration: 0).progress(after: 0), 1)
  }

  func testThePaceHardlyMovesForABurst() {
    var pace = LivePace()
    XCTAssertEqual(pace.value, LivePace.initial)

    for index in 0..<20 {
      pace.record(Double(index) * 0.1)
    }
    XCTAssertEqual(pace.value, 0.1, accuracy: 1e-9)

    // One late, and the next right behind it.
    pace.record(2.05)
    pace.record(2.06)
    XCTAssertEqual(pace.value, 0.1, accuracy: 0.01)

    // After a pause it measures again, and keeps the last value until it can.
    pace.record(5)
    XCTAssertEqual(pace.value, 0.1, accuracy: 0.01)
  }

  func testAStandingMeterWaitsUntilItIsLagBehind() {
    var motion = LiveMotion()
    motion.jump(to: -1, at: 0)
    motion.follow(newest: 0, lag: 2, pace: 0.1, at: 0)

    XCTAssertEqual(motion.position(at: 0.05), -1)
    XCTAssertEqual(motion.position(at: 0.15), -0.5, accuracy: 1e-9)
    XCTAssertEqual(motion.time(reaching: -0.5) ?? 0, 0.15, accuracy: 1e-9)
    XCTAssertNil(motion.time(reaching: 0.5))
  }

  func testAMeterThatOnlyJustStoppedMovesOnAndOneThatStoodWaits() {
    var motion = LiveMotion()
    motion.jump(to: -1, at: 0)
    for newest in 0..<20 {
      motion.follow(newest: newest, lag: 2, pace: 0.1, at: Double(newest) * 0.1)
    }

    // Level 20 is 150 ms late: the meter got to 19 at 2.1 s and has stood there for 50 ms.
    XCTAssertEqual(motion.position(at: 2.15), 19, accuracy: 1e-6)
    motion.follow(newest: 20, lag: 2, pace: 0.1, at: 2.15)
    XCTAssertGreaterThan(motion.position(at: 2.16), 19)

    // The next one after a second of nothing: it waits until it is two levels behind.
    motion.follow(newest: 21, lag: 2, pace: 0.1, at: 4)
    XCTAssertEqual(motion.position(at: 4.09), 20, accuracy: 1e-6)
    XCTAssertGreaterThan(motion.position(at: 4.11), 20)
  }

  func testLevelsOnTimeMoveAtAnEvenSpeedLagBehind() {
    let pace = 0.08
    var motion = LiveMotion()
    motion.jump(to: -1, at: 0)

    for newest in 0..<60 {
      motion.follow(newest: newest, lag: 2, pace: pace, at: Double(newest) * pace)
    }

    XCTAssertEqual(motion.speed, 1 / pace, accuracy: 0.01 / pace)
    XCTAssertEqual(motion.start, 59 - 2, accuracy: 0.01)
  }

  func testLateLevelsNeitherStopNorJerkTheMeter() {
    let pace = 0.085
    var motion = LiveMotion()
    var random = SplitMix(seed: 7)
    var previous = -Double.infinity
    var time = 0.0
    motion.jump(to: -1, at: 0)

    for newest in 0..<400 {
      // Up to 15 ms either way, and every so often a busy main thread holds one back 60 ms more.
      let late = (random.next() - 0.5) * 0.03 + (newest % 23 == 0 ? 0.06 : 0)
      time = max(time, Double(newest) * pace + late)

      let here = motion.position(at: time)
      XCTAssertGreaterThanOrEqual(here, previous)
      if newest > 3 {
        XCTAssertLessThan(here, motion.end, "stopped before level \(newest) came")
      }
      previous = here

      motion.follow(newest: newest, lag: 2, pace: pace, at: time)
      if newest > 3 {
        XCTAssertEqual(motion.speed * pace, 1, accuracy: 0.25)
      }
    }
  }
}

/// Repeatable numbers from 0 to 1.
private struct SplitMix {
  var state: UInt64

  init(seed: UInt64) {
    state = seed
  }

  mutating func next() -> Double {
    state &+= 0x9E37_79B9_7F4A_7C15
    var value = state
    value = (value ^ (value >> 30)) &* 0xBF58_476D_1CE4_E5B9
    value = (value ^ (value >> 27)) &* 0x94D0_49BB_1331_11EB
    value ^= value >> 31
    return Double(value >> 11) / Double(1 << 53)
  }
}
