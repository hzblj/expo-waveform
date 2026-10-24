import Foundation

/// Where playback is between two reports of the player: the last report, moved on by the time since at the rate it
/// plays. After a seek, reports still on the old time are passed over for a moment, so the playhead does not jump
/// back before the player catches up.
public struct Playhead: Sendable {
  public private(set) var playing = false
  public private(set) var rate: Double = 1
  private var anchorTime: Double = 0
  private var anchorAt: Double = 0
  private var seek: (time: Double, until: Double)?

  /// How long a seek waits for the player to report it.
  public static let seekPatience: Double = 1
  /// How close a report must be to a seek to confirm it, in seconds.
  public static let seekTolerance: Double = 0.5

  public init() {}

  public func time(at now: Double) -> Double {
    playing ? anchorTime + max(0, now - anchorAt) * rate : anchorTime
  }

  public mutating func report(currentTime: Double, playing: Bool, rate: Double, at now: Double) {
    if let seek {
      if now < seek.until, abs(currentTime - seek.time) > Self.seekTolerance {
        let time = time(at: now)
        self.playing = playing
        self.rate = rate
        anchorTime = time
        anchorAt = now
        return
      }
      self.seek = nil
    }
    self.playing = playing
    self.rate = rate
    anchorTime = currentTime
    anchorAt = now
  }

  /// The view sent `onSeek(time)`: the playhead stays there until the player says so too.
  public mutating func seek(to time: Double, at now: Double) {
    anchorTime = time
    anchorAt = now
    seek = (time, now + Self.seekPatience)
  }
}
