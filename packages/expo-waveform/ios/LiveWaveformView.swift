import ExpoModulesCore
import QuartzCore
import UIKit

/// A live meter: one bar per level, coming in at the right edge and flowing left. It follows a level source on its own
/// (`meter`: an object that posts `expo.audioLevel`, such as expo-speech's `Transcription`), or takes levels from
/// `append`. Plain UIKit, so native code can use it as it is.
///
/// Levels arrive unevenly (a microphone's buffers, the main queue), so the bars do not move when one arrives: a display
/// link moves them at the pace levels usually come, `lag` levels behind the newest, and nudges the speed to keep that
/// distance. Nothing is drawn before the first level, so the meter grows in from the edge the bars come in at.
public final class LiveWaveformView: UIView {
  /// What a level source posts, with itself as the object: `userInfo["level"]` is a root mean square from 0 to 1.
  public static let levelNotification = Notification.Name("expo.audioLevel")

  public var style = WaveformStyle() {
    didSet {
      if style != oldValue {
        builtBase = nil
        render()
      }
    }
  }
  /// Which way the bars move; new ones come in at the opposite edge.
  public var flow: LiveWaveformFlow = .left {
    didSet {
      if flow != oldValue {
        builtBase = nil
        render()
      }
    }
  }
  /// How many levels the meter runs behind the newest while they come, to even out how they arrive. 0 shows each one
  /// at once, and moves as unevenly as they come.
  public var lag: Double = 1
  /// Seconds between two levels, when the source has a fixed one; measured from the levels as they come when `nil`.
  public var pace: Double?
  public var scale: WaveformScale = .logarithmic
  public var noiseFloor: Float = -50
  /// `tertiaryLabel` when unset.
  public var color: UIColor? {
    didSet {
      applyColor()
    }
  }

  /// Followed while set; another one, or `nil`, lets go of the previous one and starts from nothing.
  public var meter: AnyObject? {
    didSet {
      if meter !== oldValue {
        follow(meter)
      }
    }
  }

  private let barsLayer = CAShapeLayer()
  /// Heights from 0 to 1; `dropped` older ones were let go, so an index here is `dropped` less than a level's number.
  private var levels: [Float] = []
  private var dropped = 0
  /// Where the meter is, in levels: the level with this number sits at the right edge. The first one comes in from -1.
  private var position: Double = -1
  /// Seconds between two levels, on average.
  private var measuredPace = 0.08
  private var lastAppend: CFTimeInterval?
  private var lastFrame: CFTimeInterval?
  private var displayLink: CADisplayLink?
  private var builtBase: Int?
  private var builtSize = CGSize.zero
  private var observer: NSObjectProtocol?
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
    displayLink?.invalidate()
    if let observer {
      NotificationCenter.default.removeObserver(observer)
    }
  }

  /// A new level, as a root mean square from 0 to 1. On the main thread.
  public func append(_ rms: Float) {
    levels.append(Bars.level(rms, scale: scale, noiseFloor: noiseFloor))
    let length = WaveformGeometry.canvasSize(flow, size: bounds.size).width
    let kept = WaveformGeometry.count(width: length, style: style) + Int(lag.rounded(.up)) + 4
    if levels.count > kept * 2 {
      let removed = levels.count - kept
      levels.removeFirst(removed)
      dropped += removed
    }

    let now = CACurrentMediaTime()
    if let lastAppend, now - lastAppend < 0.5 {
      measuredPace = measuredPace * 0.9 + min(0.3, max(0.02, now - lastAppend)) * 0.1
    }
    lastAppend = now
    startMoving()
  }

  /// Back to nothing.
  public func reset() {
    levels = []
    dropped = 0
    position = -1
    lastAppend = nil
    builtBase = nil
    stopMoving()
    render()
  }

  public override func layoutSubviews() {
    super.layoutSubviews()
    if bounds.size != builtSize {
      builtBase = nil
      render()
    }
    if appliedStyle != traitCollection.userInterfaceStyle {
      applyColor()
    }
  }

  public override func didMoveToWindow() {
    super.didMoveToWindow()
    if window == nil {
      stopMoving()
    } else if !levels.isEmpty {
      startMoving()
    }
  }

  public override func tintColorDidChange() {
    super.tintColorDidChange()
    applyColor()
  }

  private var newest: Int {
    dropped + levels.count - 1
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

  private func startMoving() {
    guard displayLink == nil, window != nil else {
      return
    }
    lastFrame = nil
    let link = CADisplayLink(target: LiveDisplayLinkTarget(self), selector: #selector(LiveDisplayLinkTarget.tick))
    link.add(to: .main, forMode: .common)
    displayLink = link
  }

  private func stopMoving() {
    displayLink?.invalidate()
    displayLink = nil
  }

  /// Moves toward one level behind the newest, or onto the newest once levels stop coming, so the last one shows.
  fileprivate func tick(_ now: CFTimeInterval) {
    let elapsed = lastFrame.map { min(0.1, now - $0) } ?? 0
    lastFrame = now
    let last = Double(newest)
    let pace = max(0.005, self.pace ?? measuredPace)
    let isFlowing = lastAppend.map { now - $0 < pace * max(3, lag + 2) } ?? false
    let target = isFlowing ? last - max(0, lag) : last

    if UIAccessibility.isReduceMotionEnabled {
      position = last
    } else {
      let behind = target - position
      let speed = (1 + min(0.8, max(-0.8, behind * 0.6))) / pace
      position = min(last, position + elapsed * speed)
    }
    render()

    if !isFlowing, position >= last {
      stopMoving()
    }
  }

  /// The path holds the bars up to the one coming in, right-anchored; the layer slides it by the fraction of a level.
  private func render() {
    let size = bounds.size
    let canvas = WaveformGeometry.canvasSize(flow, size: size)
    let base = Int(position.rounded(.down))
    let last = min(newest, base + 1)
    guard !levels.isEmpty, size.width > 0, last - dropped >= 0 else {
      barsLayer.path = nil
      builtBase = nil
      builtSize = size
      return
    }

    if base != builtBase || size != builtSize {
      builtBase = base
      builtSize = size
      let count = WaveformGeometry.count(width: canvas.width, style: style) + 2
      let end = last - dropped + 1
      let heights = Array(levels[max(0, end - count)..<end])
      var turn = WaveformGeometry.flowTransform(flow, size: size)

      CATransaction.begin()
      CATransaction.setDisableActions(true)
      barsLayer.frame = CGRect(origin: .zero, size: size)
      barsLayer.contentsScale = max(1, traitCollection.displayScale)
      barsLayer.path = WaveformGeometry.path(heights, size: canvas, style: style, live: true).copy(using: &turn)
      barsLayer.lineWidth = style.variant == .line ? style.lineWidth : 0
      CATransaction.commit()
      applyColor()
    }

    let shift = last > base ? (1 - (position - Double(base))) * style.step : 0
    let offset = WaveformGeometry.flowOffset(flow, shift: shift)
    CATransaction.begin()
    CATransaction.setDisableActions(true)
    barsLayer.setAffineTransform(CGAffineTransform(translationX: offset.x, y: offset.y))
    CATransaction.commit()
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

/// The display link holds its target strongly; this lets go of the view.
private final class LiveDisplayLinkTarget {
  private weak var view: LiveWaveformView?

  init(_ view: LiveWaveformView) {
    self.view = view
  }

  @objc func tick(_ link: CADisplayLink) {
    guard let view else {
      link.invalidate()
      return
    }
    view.tick(link.targetTimestamp)
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
