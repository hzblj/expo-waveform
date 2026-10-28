import ExpoModulesCore
import UIKit

/// A live meter: one bar per level, the newest at the right edge, the older ones scrolling off the left over the time
/// between two levels. It follows a level source on its own (`meter`: an object that posts `expo.audioLevel`, such as
/// expo-speech's `Transcription`), or takes levels from `append`. Plain UIKit, so native code can use it as it is.
public final class LiveWaveformView: UIView {
  /// What a level source posts, with itself as the object: `userInfo["level"]` is a root mean square from 0 to 1.
  public static let levelNotification = Notification.Name("expo.audioLevel")

  public var style = WaveformStyle() {
    didSet {
      if style != oldValue {
        redraw()
      }
    }
  }
  public var scale: WaveformScale = .logarithmic
  public var noiseFloor: Float = -50
  /// `tertiaryLabel` when unset.
  public var color: UIColor? {
    didSet {
      applyColor()
    }
  }

  /// Followed while set; another one, or `nil`, lets go of the previous one and starts from silence.
  public var meter: AnyObject? {
    didSet {
      if meter !== oldValue {
        follow(meter)
      }
    }
  }

  private let barsLayer = CAShapeLayer()
  private var levels: [Float] = []
  private var observer: NSObjectProtocol?
  private var lastAppend = CACurrentMediaTime()
  private var drawnSize = CGSize.zero
  private var appliedStyle: UIUserInterfaceStyle?

  public override init(frame: CGRect) {
    super.init(frame: frame)
    isUserInteractionEnabled = false
    clipsToBounds = true
    layer.addSublayer(barsLayer)
  }

  public required init?(coder: NSCoder) {
    fatalError("init(coder:) is not supported")
  }

  deinit {
    if let observer {
      NotificationCenter.default.removeObserver(observer)
    }
  }

  /// A new level, as a root mean square from 0 to 1. On the main thread.
  public func append(_ rms: Float) {
    levels.append(Bars.level(rms, scale: scale, noiseFloor: noiseFloor))
    let kept = WaveformGeometry.count(width: bounds.width, style: style) + 8
    if levels.count > kept * 2 {
      levels.removeFirst(levels.count - kept)
    }

    let now = CACurrentMediaTime()
    let interval = min(0.5, max(0.03, now - lastAppend))
    lastAppend = now
    redraw()
    scroll(over: interval)
  }

  /// Back to silence.
  public func reset() {
    levels = []
    barsLayer.removeAnimation(forKey: "scroll")
    redraw()
  }

  public override func layoutSubviews() {
    super.layoutSubviews()
    if bounds.size != drawnSize {
      redraw()
    }
    if appliedStyle != traitCollection.userInterfaceStyle {
      applyColor()
    }
  }

  public override func tintColorDidChange() {
    super.tintColorDidChange()
    applyColor()
  }

  private func follow(_ meter: AnyObject?) {
    if let observer {
      NotificationCenter.default.removeObserver(observer)
    }
    observer = nil
    reset()
    guard let meter else {
      return
    }
    observer = NotificationCenter.default.addObserver(forName: Self.levelNotification, object: meter, queue: .main) {
      [weak self] notification in
      guard let level = notification.userInfo?["level"] as? Double else {
        return
      }
      self?.append(Float(level))
    }
  }

  /// The bars that fit, and the few older ones past the left edge, so a scroll never uncovers a gap.
  private func redraw() {
    let size = bounds.size
    drawnSize = size
    let count = WaveformGeometry.count(width: size.width, style: style)
    let shown = levels.suffix(count + 8)
    let heights = [Float](repeating: 0, count: max(0, count - shown.count)) + shown

    CATransaction.begin()
    CATransaction.setDisableActions(true)
    barsLayer.frame = CGRect(origin: .zero, size: size)
    barsLayer.contentsScale = max(1, traitCollection.displayScale)
    barsLayer.path = WaveformGeometry.path(heights, size: size, style: style, live: true)
    barsLayer.lineWidth = style.variant == .line ? style.lineWidth : 0
    CATransaction.commit()
    applyColor()
  }

  private func scroll(over interval: Double) {
    guard !UIAccessibility.isReduceMotionEnabled else {
      return
    }
    let remaining = (barsLayer.presentation()?.value(forKeyPath: "transform.translation.x") as? CGFloat) ?? 0
    let animation = CABasicAnimation(keyPath: "transform.translation.x")
    animation.fromValue = remaining + style.step
    animation.toValue = 0
    animation.duration = interval
    animation.timingFunction = CAMediaTimingFunction(name: .linear)
    barsLayer.add(animation, forKey: "scroll")
  }

  private func applyColor() {
    appliedStyle = traitCollection.userInterfaceStyle
    let resolved = (color ?? .tertiaryLabel).resolvedColor(with: traitCollection).cgColor
    CATransaction.begin()
    CATransaction.setDisableActions(true)
    barsLayer.fillColor = style.variant == .line ? nil : resolved
    barsLayer.strokeColor = style.variant == .line ? resolved : nil
    CATransaction.commit()
  }
}

/// `LiveWaveformView` as a React Native view.
final class LiveWaveformComponent: ExpoView {
  let waveform = LiveWaveformView()

  required init(appContext: AppContext? = nil) {
    super.init(appContext: appContext)
    clipsToBounds = true
    addSubview(waveform)
  }

  override func layoutSubviews() {
    super.layoutSubviews()
    waveform.frame = bounds
  }
}
