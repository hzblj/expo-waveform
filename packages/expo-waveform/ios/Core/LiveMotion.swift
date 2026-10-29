import Foundation

/// A timing curve: a cubic Bézier from (0, 0) to (1, 1) through two control points, as CSS and Core Animation define
/// theirs. `LiveWaveformEasing` in `src/types.ts`.
public struct WaveformEasing: Equatable, Sendable {
  public let x1: Double
  public let y1: Double
  public let x2: Double
  public let y2: Double

  /// The control points' x is kept within 0...1, so the curve is a function of time.
  public init(_ x1: Double, _ y1: Double, _ x2: Double, _ y2: Double) {
    self.x1 = min(1, max(0, x1))
    self.y1 = y1
    self.x2 = min(1, max(0, x2))
    self.y2 = y2
  }

  public static let linear = WaveformEasing(0, 0, 1, 1)
  public static let easeIn = WaveformEasing(0.42, 0, 1, 1)
  public static let easeOut = WaveformEasing(0, 0, 0.58, 1)
  public static let easeInOut = WaveformEasing(0.42, 0, 0.58, 1)

  /// `linear`, `easeIn`, `easeOut` or `easeInOut`.
  public static func named(_ name: String) -> WaveformEasing? {
    switch name {
    case "linear": return .linear
    case "easeIn": return .easeIn
    case "easeOut": return .easeOut
    case "easeInOut": return .easeInOut
    default: return nil
    }
  }

  /// How far along the curve is at `time`, both from 0 to 1.
  public func value(at time: Double) -> Double {
    let x = min(1, max(0, time))
    guard x > 0, x < 1 else {
      return x
    }
    return Self.bezier(y1, y2, at: parameter(for: x))
  }

  /// Where on the curve its x is `x`: Newton's method, or halving where the curve is too flat for it.
  private func parameter(for x: Double) -> Double {
    var t = x
    for _ in 0..<8 {
      let error = Self.bezier(x1, x2, at: t) - x
      if abs(error) < 1e-7 {
        return t
      }
      let slope = Self.slope(x1, x2, at: t)
      guard abs(slope) > 1e-6 else {
        break
      }
      t -= error / slope
      guard t >= 0, t <= 1 else {
        break
      }
    }

    var low = 0.0
    var high = 1.0
    t = x
    for _ in 0..<40 {
      let value = Self.bezier(x1, x2, at: t)
      if abs(value - x) < 1e-7 {
        break
      }
      if value < x {
        low = t
      } else {
        high = t
      }
      t = (low + high) / 2
    }
    return t
  }

  /// One coordinate of the curve, whose ends are 0 and 1.
  private static func bezier(_ first: Double, _ second: Double, at t: Double) -> Double {
    let rest = 1 - t
    return 3 * rest * rest * t * first + 3 * rest * t * t * second + t * t * t
  }

  private static func slope(_ first: Double, _ second: Double, at t: Double) -> Double {
    let rest = 1 - t
    return 3 * rest * rest * first + 6 * rest * t * (second - first) + 3 * t * t * (1 - second)
  }
}

/// How a live meter's new bars grow in: from their shortest to their level, from the middle (or up from the bottom),
/// while they come in at the edge. `LiveWaveformGrowIn` in `src/types.ts`.
public struct LiveWaveformGrowIn: Equatable, Sendable {
  /// Seconds.
  public var duration: Double
  public var easing: WaveformEasing

  public init(duration: Double = 0.25, easing: WaveformEasing = .easeOut) {
    self.duration = duration
    self.easing = easing
  }

  /// How much of its level a bar shows `elapsed` seconds after it started to come in: 0 before, 1 once grown.
  public func progress(after elapsed: Double) -> Double {
    guard duration > 0 else {
      return elapsed >= 0 ? 1 : 0
    }
    return easing.value(at: elapsed / duration)
  }
}

/// Seconds between two levels, from the times the last ones came: over a window of them, so a late one or a burst
/// barely moves it.
public struct LivePace: Sendable {
  public static let initial = 0.08
  /// About two seconds of a microphone's buffers.
  static let window = 24
  /// Longer than this between two levels is a pause, not a slow source: measuring starts again after it.
  static let pause = 0.5

  public private(set) var value = LivePace.initial
  private var times: [Double] = []

  public init() {}

  public mutating func record(_ time: Double) {
    if let last = times.last, time - last > Self.pause {
      times.removeAll()
    }
    times.append(time)
    if times.count > Self.window {
      times.removeFirst(times.count - Self.window)
    }
    if times.count >= 3, let first = times.first, let last = times.last {
      value = min(0.3, max(0.02, (last - first) / Double(times.count - 1)))
    }
  }
}

/// Where a live meter is, in levels, as a straight line in time: from `start` at `time`, `speed` levels a second, up to
/// `end`, where it stops. Each new level starts a new line from wherever the last one had got to, so the meter never
/// jumps, and only its speed changes, by a little.
public struct LiveMotion: Equatable, Sendable {
  /// How much the speed follows the distance to `lag`, per level of it, and how far it may go from the pace.
  static let gain = 0.1
  static let nudge = 0.25

  public private(set) var time: Double = 0
  public private(set) var start: Double = -1
  public private(set) var speed: Double = 0
  public private(set) var end: Double = -1

  public init() {}

  public func position(at now: Double) -> Double {
    min(end, start + max(0, now - time) * speed)
  }

  /// When the meter is at `position`: in the past for one it has passed, `nil` for one it stops short of.
  public func time(reaching position: Double) -> Double? {
    guard position <= end, speed > 0 else {
      return position <= start ? -.infinity : nil
    }
    return time + (position - start) / speed
  }

  /// A new newest level at `now`: on from where the meter is, at the pace, a little faster while it is more than `lag`
  /// levels behind the newest and a little slower while it is less. A meter that has stood at the newest for longer than
  /// a level (the levels had stopped, or this is the first) waits until it is `lag` behind again, so it has that much to
  /// ride out the next late level; one that only just got there moves on, slower.
  public mutating func follow(newest: Int, lag: Double, pace: Double, at now: Double) {
    let here = position(at: now)
    let behind = Double(newest) - lag - here
    let pace = max(0.005, pace)
    let arrived = speed > 0 ? time + (end - start) / speed : -.infinity

    if here >= end, behind < 0, now - arrived > pace {
      time = now - behind * pace
      speed = 1 / pace
    } else {
      time = now
      speed = (1 + min(Self.nudge, max(-Self.nudge, behind * Self.gain))) / pace
    }
    start = here
    end = Double(newest)
  }

  /// Straight to `position`, standing there: the first level, reduced motion, or no lag.
  public mutating func jump(to position: Double, at now: Double) {
    time = now
    start = position
    end = position
    speed = 0
  }
}
