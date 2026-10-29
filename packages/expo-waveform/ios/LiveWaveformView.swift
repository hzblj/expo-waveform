import ExpoModulesCore
import QuartzCore
import UIKit

/// A live meter: one bar per level, coming in at the right edge and flowing left. It follows a level source on its own
/// (`meter`: an object that posts `expo.audioLevel`, such as expo-speech's `Transcription`), or takes levels from
/// `append`. Plain UIKit, so native code can use it as it is.
///
/// Levels arrive unevenly (a microphone's buffers, the main queue), so the bars do not move when one arrives: each level
/// sets the meter on a straight line toward it at the pace levels usually come, `lag` levels behind (`LiveMotion`), and
/// Core Animation slides the bars along that line in the render server, so they keep moving while the main thread is
/// busy. With `growIn` a bar grows to its level as it comes in at the edge, keyframed the same way. Nothing is drawn
/// before the first level, so the meter grows in from the edge the bars come in at.
public final class LiveWaveformView: UIView {
  /// What a level source posts, with itself as the object: `userInfo["level"]` is a root mean square from 0 to 1.
  public static let levelNotification = Notification.Name("expo.audioLevel")

  public var style = WaveformStyle() {
    didSet {
      if style != oldValue {
        render()
      }
    }
  }
  /// Which way the bars move; new ones come in at the opposite edge.
  public var flow: LiveWaveformFlow = .left {
    didSet {
      if flow != oldValue {
        render()
      }
    }
  }
  /// How many levels the meter runs behind the newest while they come: room for a level that comes late, or a busy
  /// main thread, before the bars would have to stop. 0 shows each one at once, and moves as unevenly as they come.
  public var lag: Double = 2
  /// Seconds between two levels, when the source has a fixed one; measured from the levels as they come when `nil`.
  public var pace: Double?
  /// New bars grow from their shortest to their level as they come in at the edge; `nil` draws them whole.
  public var growIn: LiveWaveformGrowIn? {
    didSet {
      if growIn != oldValue {
        render()
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

  /// Followed while set; another one, or `nil`, lets go of the previous one and starts from nothing.
  public var meter: AnyObject? {
    didSet {
      if meter !== oldValue {
        follow(meter)
      }
    }
  }

  /// Keyframes for growing bars, two seconds' worth at most.
  private static let frameRate = 60.0
  private static let maxFrames = 120

  /// Slides the bars; the newest level's bar is drawn at its place at the edge.
  private let content = CALayer()
  /// Bars done growing.
  private let settled = CAShapeLayer()
  /// Bars still growing, and the whole envelope while any is.
  private let growing = CAShapeLayer()
  /// Heights from 0 to 1; `dropped` older ones were let go, so an index here is `dropped` less than a level's number.
  private var levels: [Float] = []
  private var dropped = 0
  /// When each level's bar started, or will start, to come in at the edge, by the level's number.
  private var entries: [Int: CFTimeInterval] = [:]
  private var motion = LiveMotion()
  private var measuredPace = LivePace()
  private var renderedSize = CGSize.zero
  private var observer: NSObjectProtocol?
  private var appliedStyle: UIUserInterfaceStyle?

  public override init(frame: CGRect) {
    super.init(frame: frame)
    isUserInteractionEnabled = false
    clipsToBounds = true
    content.addSublayer(settled)
    content.addSublayer(growing)
    layer.addSublayer(content)
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
    let now = CACurrentMediaTime()
    let isFirst = levels.isEmpty
    levels.append(Bars.level(rms, scale: scale, noiseFloor: noiseFloor))
    let length = WaveformGeometry.canvasSize(flow, size: bounds.size).width
    let kept = WaveformGeometry.count(width: length, style: style) + Int(lag.rounded(.up)) + 4
    if levels.count > kept * 2 {
      let removed = levels.count - kept
      levels.removeFirst(removed)
      dropped += removed
      entries = entries.filter { $0.key >= dropped }
    }
    measuredPace.record(now)

    if lag <= 0 || UIAccessibility.isReduceMotionEnabled {
      motion.jump(to: Double(newest), at: now)
    } else {
      if isFirst {
        motion.jump(to: Double(newest) - 1, at: now)
      }
      motion.follow(newest: newest, lag: lag, pace: pace ?? measuredPace.value, at: now)
    }
    render(at: now)
  }

  /// Back to nothing.
  public func reset() {
    levels = []
    dropped = 0
    entries = [:]
    motion = LiveMotion()
    measuredPace = LivePace()
    render()
  }

  public override func layoutSubviews() {
    super.layoutSubviews()
    if bounds.size != renderedSize {
      render()
    }
    if appliedStyle != traitCollection.userInterfaceStyle {
      applyColor()
    }
  }

  public override func didMoveToWindow() {
    super.didMoveToWindow()
    if window != nil {
      render()
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

  /// Hands the motion from `now` on to Core Animation: the slide to the newest, and the bars growing on the way.
  private func render(at now: CFTimeInterval = CACurrentMediaTime()) {
    let size = bounds.size
    let canvas = WaveformGeometry.canvasSize(flow, size: size)
    renderedSize = size

    CATransaction.begin()
    CATransaction.setDisableActions(true)
    defer {
      CATransaction.commit()
    }

    content.bounds = CGRect(origin: .zero, size: size)
    content.position = CGPoint(x: size.width / 2, y: size.height / 2)
    for shape in [settled, growing] {
      shape.frame = content.bounds
      shape.contentsScale = max(1, traitCollection.displayScale)
      shape.lineWidth = style.variant == .line ? style.lineWidth : 0
    }

    guard !levels.isEmpty, canvas.width > 0, canvas.height > 0 else {
      content.removeAllAnimations()
      growing.removeAllAnimations()
      content.transform = CATransform3DIdentity
      settled.path = nil
      growing.path = nil
      return
    }

    slide(at: now)
    draw(canvas: canvas, at: now)
    applyColor()
  }

  /// The layer sits as far back toward the edge the bars come in at as the meter is short of the newest, and moves
  /// along the motion's line until it stops there.
  private func slide(at now: CFTimeInterval) {
    let offset = { (position: Double) -> CATransform3D in
      let point = WaveformGeometry.flowOffset(self.flow, shift: CGFloat(Double(self.newest) - position) * self.style.step)
      return CATransform3DMakeTranslation(point.x, point.y, 0)
    }
    content.removeAnimation(forKey: "slide")
    content.transform = offset(motion.end)

    let duration = motion.speed > 0 ? (motion.end - motion.start) / motion.speed : 0
    guard duration > 0, motion.time + duration > now else {
      return
    }
    let animation = CABasicAnimation(keyPath: "transform")
    animation.fromValue = NSValue(caTransform3D: offset(motion.start))
    animation.toValue = NSValue(caTransform3D: offset(motion.end))
    animation.beginTime = content.convertTime(motion.time, from: nil)
    animation.duration = duration
    animation.fillMode = .backwards
    Self.preferFullFrameRate(animation)
    content.add(animation, forKey: "slide")
  }

  /// Bars done growing go into one path; the ones still growing, keyframed from now until the last has grown.
  private func draw(canvas: CGSize, at now: CFTimeInterval) {
    let here = motion.position(at: now)
    let count = WaveformGeometry.count(width: canvas.width, style: style)
    let first = max(dropped, Int(here.rounded(.down)) - count - 1)
    let growIn = UIAccessibility.isReduceMotionEnabled ? nil : self.growIn

    growing.removeAnimation(forKey: "grow")
    guard let growIn else {
      settled.path = path(first...newest, canvas: canvas) { _ in 1 }
      growing.path = nil
      return
    }

    // A bar starts to show once the meter is less than a bar's width short of it.
    let entering = Double(style.barWidth / style.step)
    for level in first...newest where entries[level].map({ $0 > now }) ?? true {
      entries[level] = motion.time(reaching: Double(level) - entering)
    }
    let shown = { (level: Int, time: CFTimeInterval) -> Float in
      self.entries[level].map { Float(growIn.progress(after: time - $0)) } ?? 0
    }

    let stillGrowing = (first...newest).first { shown($0, now) < 1 }
    guard let stillGrowing else {
      settled.path = path(first...newest, canvas: canvas) { _ in 1 }
      growing.path = nil
      return
    }
    let isEnvelope = style.variant != .bars
    let grown = isEnvelope ? nil : (first < stillGrowing ? first...(stillGrowing - 1) : nil)
    let moving = isEnvelope ? first...newest : stillGrowing...newest
    settled.path = grown.map { path($0, canvas: canvas) { _ in 1 } }

    let done = moving.compactMap { entries[$0] }.max().map { $0 + growIn.duration } ?? now
    let frames = min(Self.maxFrames, max(0, Int(((done - now) * Self.frameRate).rounded(.up))))
    let paths = (0...frames).map { frame in
      let time = now + Double(frame) / Self.frameRate
      return path(moving, canvas: canvas) { shown($0, time) }
    }
    growing.path = paths.last
    guard frames > 0 else {
      return
    }

    let animation = CAKeyframeAnimation(keyPath: "path")
    animation.values = paths
    animation.keyTimes = (0...frames).map { NSNumber(value: Double($0) / Double(frames)) }
    animation.beginTime = growing.convertTime(now, from: nil)
    animation.duration = Double(frames) / Self.frameRate
    animation.calculationMode = .linear
    animation.fillMode = .backwards
    Self.preferFullFrameRate(animation)
    growing.add(animation, forKey: "grow")
  }

  /// The bars of `range`, each at `shown` of its level, placed for the newest at the edge and turned to the flow.
  private func path(_ range: ClosedRange<Int>, canvas: CGSize, shown: (Int) -> Float) -> CGPath {
    let heights = range.map { levels[$0 - dropped] * shown($0) }
    let back = CGFloat(newest - range.upperBound) * style.step
    var turn = CGAffineTransform(translationX: -back, y: 0)
      .concatenating(WaveformGeometry.flowTransform(flow, size: bounds.size))
    return WaveformGeometry.path(heights, size: canvas, style: style, live: true).copy(using: &turn)
      ?? CGMutablePath()
  }

  /// A slow slide looks smoothest on every frame a ProMotion display has.
  private static func preferFullFrameRate(_ animation: CAAnimation) {
    if #available(iOS 15.0, *) {
      animation.preferredFrameRateRange = CAFrameRateRange(minimum: 60, maximum: 120, preferred: 120)
    }
  }

  private func applyColor() {
    appliedStyle = traitCollection.userInterfaceStyle
    let resolved = (color ?? .tertiaryLabel).resolvedColor(with: traitCollection).cgColor
    CATransaction.begin()
    CATransaction.setDisableActions(true)
    for shape in [settled, growing] {
      shape.fillColor = style.variant == .line ? nil : resolved
      shape.strokeColor = style.variant == .line ? resolved : nil
    }
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
