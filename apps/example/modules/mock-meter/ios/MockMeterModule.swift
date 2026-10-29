import ExpoModulesCore
import QuartzCore

public final class MockMeterModule: Module {
  private var hitches: Timer?

  public func definition() -> ModuleDefinition {
    Name("MockMeter")

    Class("Meter", MockMeter.self) {
      Constructor {
        MockMeter()
      }
      Function("start") { (meter: MockMeter, interval: Double, jitter: Double) in
        meter.start(interval: interval, jitter: jitter)
      }
      Function("stop") { (meter: MockMeter) in
        meter.stop()
      }
    }

    Function("setMainThreadHitches") { (every: Double, duration: Double) in
      DispatchQueue.main.async {
        self.hitches?.invalidate()
        self.hitches = nil
        guard every > 0, duration > 0 else {
          return
        }
        self.hitches = Timer.scheduledTimer(withTimeInterval: every, repeats: true) { _ in
          usleep(useconds_t(duration * 1_000_000))
        }
      }
    }
  }
}

/// A stand-in microphone where there is none (a simulator, an emulator): a level every `interval` seconds, early or late
/// by up to `jitter`, shaped like speech, posted from its own queue the way expo-speech's `Transcription` posts a
/// microphone's.
final class MockMeter: SharedObject, @unchecked Sendable {
  private let queue = DispatchQueue(label: "example.mock-meter")
  private var generation = 0
  private var syllable = 0.7

  func start(interval: Double, jitter: Double) {
    queue.async {
      self.generation += 1
      self.tick(0, from: CACurrentMediaTime(), interval: max(0.01, interval), jitter: max(0, jitter), self.generation)
    }
  }

  func stop() {
    queue.async {
      self.generation += 1
    }
  }

  private func tick(_ index: Int, from start: Double, interval: Double, jitter: Double, _ generation: Int) {
    guard generation == self.generation else {
      return
    }
    NotificationCenter.default.post(
      name: Notification.Name("expo.audioLevel"), object: self,
      userInfo: ["level": level(at: Double(index) * interval)])

    let next = start + Double(index + 1) * interval + Double.random(in: -jitter...jitter)
    queue.asyncAfter(deadline: .now() + max(0, next - CACurrentMediaTime())) {
      self.tick(index + 1, from: start, interval: interval, jitter: jitter, generation)
    }
  }

  /// Phrases of syllables, about four and a half a second for 2.2 s, then half a second of room noise.
  private func level(at time: Double) -> Double {
    let isPhrase = time.truncatingRemainder(dividingBy: 2.7) < 2.2
    let wave = max(0, sin(2 * .pi * 4.5 * time))
    if wave < 0.1 {
      syllable = Double.random(in: 0.4...1)
    }
    let decibels = isPhrase ? -46 + 40 * syllable * pow(wave, 0.6) : -46
    return pow(10, (decibels + Double.random(in: -3...3)) / 20)
  }
}
