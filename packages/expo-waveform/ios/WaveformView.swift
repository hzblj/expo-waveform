import ExpoModulesCore
import QuartzCore
import UIKit

/// Where playback is, from `useWaveformPlayback`: `WaveformPlayback` in `src/types.ts`.
struct PlaybackRecord: Record {
  @Field var currentTime: Double = 0
  @Field var duration: Double?
  @Field var playing: Bool = false
  @Field var rate: Double?
}

/// The waveform: bars (or an envelope) in two colours split at the playhead, markers (dots below them or lines behind
/// them) if asked for, a cursor on top.
/// Paths are built when the data, the size or the style change; playback only moves two masks and the cursor, once a
/// display frame while playing.
public final class WaveformView: ExpoView, UIGestureRecognizerDelegate {
  let onLoad = EventDispatcher()
  let onError = EventDispatcher()
  let onScrubStart = EventDispatcher()
  let onScrub = EventDispatcher()
  let onScrubEnd = EventDispatcher()
  let onSeek = EventDispatcher()

  // MARK: Props, applied together in `didUpdateProps`

  var uri: String?
  var headers: [String: String] = [:]
  var samples: [Float]?
  var live = false
  var playback: PlaybackRecord?
  var progress: Double?
  var markers: [Double] = []
  var style = WaveformStyle()
  var scale = WaveformScale.logarithmic
  var noiseFloor: Float = -50
  var normalize = true
  var color: UIColor?
  var progressColor: UIColor?
  var markerColor: UIColor?
  var playedMarkerColor: UIColor?
  var cursor = false
  var cursorColor: UIColor?
  var seekable = false
  var haptics = true

  // MARK: State

  private var loadedRequest: WaveformRequest?
  private var loadTask: Task<Void, Never>?
  private var loads = 0
  private var analysis: WaveformAnalysis?
  private var shownSamples: [Float]?
  private var samplesVersion = 0
  private var lastSampleUpdate: CFTimeInterval = 0
  private var playhead = Playhead()
  private var reported: PlaybackReport?
  private var built: Built?
  private var appliedAppearance: (UIUserInterfaceStyle, UIAccessibilityContrast, CGFloat)?
  private var displayLink: CADisplayLink?
  private var scrubTime: Double?
  private var lastTick: CFTimeInterval = 0
  private var sortedMarkers: [Double] = []

  // MARK: Layers

  /// What has not played: right of the playhead.
  private let unplayed = CALayer()
  private let unplayedMask = CALayer()
  private let markersShape = CAShapeLayer()
  private let markersMask = CAShapeLayer()
  private let barsShape = CAShapeLayer()
  /// What has: left of it.
  private let played = CALayer()
  private let playedMask = CALayer()
  private let playedMarkersShape = CAShapeLayer()
  private let playedMarkersMask = CAShapeLayer()
  private let playedBarsShape = CAShapeLayer()
  private let cursorLayer = CALayer()

  /// Everything the paths are built from: when none of it changed, nothing is rebuilt.
  private struct Built: Equatable {
    var size: CGSize
    var displayScale: CGFloat
    var style: WaveformStyle
    var live: Bool
    var scale: WaveformScale
    var noiseFloor: Float
    var normalize: Bool
    var analysis: ObjectIdentifier?
    var samples: Int
    var markers: [Double]
    var duration: Double
  }

  private struct PlaybackReport: Equatable {
    var currentTime: Double
    var duration: Double?
    var playing: Bool
    var rate: Double?
  }

  private var shapes: [CAShapeLayer] {
    [markersShape, barsShape, playedMarkersShape, playedBarsShape]
  }

  /// Claims a sideways drag from a scroll view around; the touch itself is followed in `touches…`.
  private lazy var pan = UIPanGestureRecognizer(target: self, action: #selector(handlePan(_:)))
  private var dragging = false
  private var touchStart = CGPoint.zero
  private let selection = UISelectionFeedbackGenerator()

  public required init(appContext: AppContext? = nil) {
    super.init(appContext: appContext)
    clipsToBounds = true

    for layer in [unplayedMask, playedMask] {
      layer.backgroundColor = UIColor.black.cgColor
    }
    unplayed.mask = unplayedMask
    played.mask = playedMask
    // Markers show around the bars, never through them: a translucent bar would show the line behind it.
    for (shape, mask) in [(markersShape, markersMask), (playedMarkersShape, playedMarkersMask)] {
      mask.fillRule = .evenOdd
      shape.mask = mask
    }
    unplayed.addSublayer(markersShape)
    unplayed.addSublayer(barsShape)
    played.addSublayer(playedMarkersShape)
    played.addSublayer(playedBarsShape)
    for shape in shapes {
      shape.lineCap = .round
      shape.lineJoin = .round
    }
    layer.addSublayer(unplayed)
    layer.addSublayer(played)
    layer.addSublayer(cursorLayer)
    cursorLayer.isHidden = true

    pan.delegate = self
    // The touch keeps coming to the view once the pan claims it.
    pan.cancelsTouchesInView = false
    addGestureRecognizer(pan)
  }

  deinit {
    loadTask?.cancel()
    displayLink?.invalidate()
  }

  // MARK: Updates

  func didUpdateProps() {
    sortedMarkers = markers.sorted()
    pan.isEnabled = interactive

    updateSource()
    updateSamples()
    updatePlayback()
    rebuild()
    applyColors()
  }

  private var interactive: Bool {
    seekable && !live
  }

  /// The audio's length: what the player says, else the analysis.
  private var duration: Double {
    if let duration = playback?.duration, duration > 0 {
      return duration
    }
    return analysis?.duration ?? 0
  }

  private var displayScale: CGFloat {
    max(1, traitCollection.displayScale)
  }

  /// Loads the source when it changed: from memory at once, else in the background while silence is drawn.
  private func updateSource() {
    guard samples == nil, let uri else {
      loadTask?.cancel()
      loadTask = nil
      loadedRequest = nil
      analysis = nil
      return
    }
    let request = WaveformRequest(uri: uri, headers: headers)
    if let loadedRequest, loadedRequest.uri == request.uri, loadedRequest.headers == request.headers {
      return
    }

    loadTask?.cancel()
    loadTask = nil
    loadedRequest = request
    loads += 1
    analysis = WaveformStore.shared.cached(request)

    if let analysis {
      onLoad(["duration": analysis.duration, "fromCache": true])
      return
    }

    let load = loads
    loadTask = Task { [weak self] in
      do {
        let result = try await WaveformStore.shared.load(request)
        guard let self, self.loads == load else {
          return
        }
        self.analysis = result.analysis
        self.updatePlayback()
        self.rebuild(animated: true)
        self.onLoad(["duration": result.analysis.duration, "fromCache": result.fromCache])
      } catch {
        guard let self, self.loads == load, !(error is CancellationError) else {
          return
        }
        let failure = WaveformFailure.from(error)
        self.onError(["code": failure.code.rawValue, "message": failure.message])
      }
    }
  }

  /// A live meter that moved on scrolls by what it appended, over the time since the last update.
  private func updateSamples() {
    guard samples != shownSamples else {
      return
    }
    let previous = shownSamples ?? []
    shownSamples = samples
    samplesVersion += 1

    let now = CACurrentMediaTime()
    let interval = min(0.5, max(0.03, now - lastSampleUpdate))
    lastSampleUpdate = now

    guard live, let samples, !UIAccessibility.isReduceMotionEnabled else {
      unplayed.removeAnimation(forKey: "scroll")
      return
    }
    let shift = Bars.shift(from: previous, to: samples)
    guard shift > 0 else {
      return
    }
    let remaining = (unplayed.presentation()?.value(forKeyPath: "transform.translation.x") as? CGFloat) ?? 0
    let animation = CABasicAnimation(keyPath: "transform.translation.x")
    animation.fromValue = remaining + CGFloat(shift) * style.step
    animation.toValue = 0
    animation.duration = interval
    animation.timingFunction = CAMediaTimingFunction(name: .linear)
    unplayed.add(animation, forKey: "scroll")
  }

  public override func layoutSubviews() {
    super.layoutSubviews()
    rebuild()
    updateProgress()

    // UIKit lays out again when the appearance changes: dynamic colours resolve anew here.
    let appearance = (traitCollection.userInterfaceStyle, traitCollection.accessibilityContrast, displayScale)
    if appliedAppearance.map({ $0 != appearance }) ?? true {
      applyColors()
    }
  }

  public override func tintColorDidChange() {
    super.tintColorDidChange()
    applyColors()
  }

  /// Heights for the bars that fit: the analysis, the samples given, or silence while there is neither.
  private func heights(count: Int) -> [Float] {
    if live {
      // Older bars past the left edge too, so a scroll never uncovers a gap.
      let extra = 8
      let levels = (samples ?? []).suffix(count + extra).map { min(1, max(0, $0)) }
      return [Float](repeating: 0, count: max(0, count - levels.count)) + levels
    }
    if let samples {
      return Bars.heights(samples, count: count, scale: nil, normalize: false)
    }
    if let analysis, !analysis.samples.isEmpty {
      return Bars.heights(analysis.samples, count: count, scale: scale, noiseFloor: noiseFloor, normalize: normalize)
    }
    return [Float](repeating: 0, count: count)
  }

  private func rebuild(animated: Bool = false) {
    let size = bounds.size
    let key = Built(
      size: size, displayScale: displayScale, style: style, live: live, scale: scale, noiseFloor: noiseFloor,
      normalize: normalize, analysis: analysis.map(ObjectIdentifier.init), samples: samplesVersion,
      markers: live || style.markerStyle == .none ? [] : sortedMarkers, duration: duration)
    guard key != built else {
      return
    }
    let resized = built?.size != size
    built = key

    CATransaction.begin()
    CATransaction.setDisableActions(true)
    for layer in [unplayed, played, markersMask, playedMarkersMask] + shapes {
      layer.frame = CGRect(origin: .zero, size: size)
    }
    for shape in shapes {
      shape.contentsScale = displayScale
    }
    if !live {
      unplayed.removeAnimation(forKey: "scroll")
    }

    let count = WaveformGeometry.count(width: size.width, style: style)
    let barsSize = WaveformGeometry.barsSize(size, style: style, hasMarkers: !key.markers.isEmpty)
    let path = WaveformGeometry.path(heights(count: count), size: barsSize, style: style, live: live)
    let markers =
      style.markerStyle == .dot
      ? WaveformGeometry.dots(key.markers, duration: duration, size: size, scale: displayScale)
      : WaveformGeometry.markers(key.markers, duration: duration, size: size, scale: displayScale)

    // Everything but the bars (or the area): what the markers may show in. A line hides nothing.
    let outside = CGMutablePath()
    outside.addRect(CGRect(origin: .zero, size: size).insetBy(dx: -1, dy: -1))
    if style.variant != .line {
      outside.addPath(path)
    }

    if animated, !resized, !UIAccessibility.isReduceMotionEnabled, let from = barsShape.path,
      let fromOutside = markersMask.path
    {
      for (shape, from, to) in [
        (barsShape, from, path), (playedBarsShape, from, path), (markersMask, fromOutside, outside),
        (playedMarkersMask, fromOutside, outside),
      ] as [(CAShapeLayer, CGPath, CGPath)] {
        let animation = Self.spring(keyPath: "path")
        animation.fromValue = from
        animation.toValue = to
        shape.add(animation, forKey: "path")
      }
    }
    barsShape.path = path
    playedBarsShape.path = path
    markersShape.path = markers
    playedMarkersShape.path = markers
    markersMask.path = outside
    playedMarkersMask.path = outside

    let stroked = style.variant == .line
    for shape in [barsShape, playedBarsShape] {
      shape.lineWidth = stroked ? max(0, style.lineWidth) : 0
    }
    CATransaction.commit()

    applyColors()
    updateProgress()
  }

  /// Critically damped: the bars rise into place without a bounce.
  private static func spring(keyPath: String) -> CASpringAnimation {
    let animation: CASpringAnimation
    if #available(iOS 17.0, *) {
      animation = CASpringAnimation(perceptualDuration: 0.45, bounce: 0)
    } else {
      animation = CASpringAnimation(keyPath: keyPath)
      animation.mass = 1
      animation.stiffness = 195
      animation.damping = 28
      animation.duration = animation.settlingDuration
    }
    animation.keyPath = keyPath
    return animation
  }

  private func applyColors() {
    appliedAppearance = (traitCollection.userInterfaceStyle, traitCollection.accessibilityContrast, displayScale)

    let track = resolved(color ?? .tertiaryLabel)
    let progress = resolved(progressColor ?? tintColor)
    let marker = markerColor.map(resolved) ?? track.withAlphaComponent(track.cgColor.alpha * 0.5)
    let playedMarker = playedMarkerColor.map(resolved) ?? progress.withAlphaComponent(progress.cgColor.alpha * 0.5)
    let stroked = style.variant == .line

    CATransaction.begin()
    CATransaction.setDisableActions(true)
    barsShape.fillColor = stroked ? nil : track.cgColor
    barsShape.strokeColor = stroked ? track.cgColor : nil
    playedBarsShape.fillColor = stroked ? nil : progress.cgColor
    playedBarsShape.strokeColor = stroked ? progress.cgColor : nil
    markersShape.fillColor = marker.cgColor
    playedMarkersShape.fillColor = playedMarker.cgColor
    cursorLayer.backgroundColor = resolved(cursorColor ?? progressColor ?? tintColor).cgColor
    CATransaction.commit()
  }

  private func resolved(_ color: UIColor) -> UIColor {
    color.resolvedColor(with: traitCollection)
  }

  // MARK: Playback

  /// A new report from the player moves the playhead; the same one again does not (it would move it back).
  private func updatePlayback() {
    let report = playback.map {
      PlaybackReport(currentTime: $0.currentTime, duration: $0.duration, playing: $0.playing, rate: $0.rate)
    }
    if let report, report != reported {
      playhead.report(
        currentTime: report.currentTime, playing: report.playing, rate: report.rate ?? 1, at: CACurrentMediaTime())
    }
    reported = report

    let running = playback != nil && playhead.playing && duration > 0 && scrubTime == nil && window != nil
    if running {
      startDisplayLink()
    } else {
      stopDisplayLink()
    }
    updateProgress()
  }

  /// The playhead from 0 to 1.
  private var fraction: Double {
    if let scrubTime, duration > 0 {
      return scrubTime / duration
    }
    if playback != nil {
      return duration > 0 ? playhead.time(at: CACurrentMediaTime()) / duration : 0
    }
    return progress ?? 0
  }

  private var currentTime: Double {
    min(duration, max(0, fraction * duration))
  }

  private func updateProgress() {
    let size = bounds.size
    let x = live ? 0 : round(CGFloat(min(1, max(0, fraction))) * size.width * displayScale) / displayScale

    CATransaction.begin()
    CATransaction.setDisableActions(true)
    playedMask.frame = CGRect(x: 0, y: 0, width: x, height: size.height)
    unplayedMask.frame = CGRect(x: x, y: 0, width: max(0, size.width - x), height: size.height)
    if live {
      // A live meter has no playhead: everything is still to come.
      unplayedMask.frame = CGRect(x: -size.width, y: 0, width: size.width * 3, height: size.height)
    }
    let width: CGFloat = 2
    cursorLayer.isHidden = !cursor || live
    cursorLayer.frame = CGRect(x: min(size.width - width, max(0, x - width / 2)), y: 0, width: width, height: size.height)
    cursorLayer.cornerRadius = width / 2
    CATransaction.commit()
  }

  private func startDisplayLink() {
    guard displayLink == nil else {
      updateFrameRate()
      return
    }
    let link = CADisplayLink(target: DisplayLinkTarget(self), selector: #selector(DisplayLinkTarget.tick))
    displayLink = link
    updateFrameRate()
    link.add(to: .main, forMode: .common)
  }

  private func stopDisplayLink() {
    displayLink?.invalidate()
    displayLink = nil
  }

  /// No faster than the playhead moves a pixel: a podcast's playhead crawls, a voice message's runs.
  private func updateFrameRate() {
    guard let displayLink, duration > 0 else {
      return
    }
    let pixelsPerSecond = Double(bounds.width * displayScale) / duration * abs(playhead.rate)
    let rate = Float(min(60, max(4, pixelsPerSecond.rounded(.up))))
    displayLink.preferredFrameRateRange = CAFrameRateRange(minimum: min(rate, 4), maximum: 60, preferred: rate)
  }

  fileprivate func tick() {
    updateProgress()
  }

  public override func didMoveToWindow() {
    super.didMoveToWindow()
    updatePlayback()
  }

  // MARK: Seeking

  /// UIKit asks the view as well as the delegate: one answer for both.
  public override func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
    guard gestureRecognizer === pan else {
      return super.gestureRecognizerShouldBegin(gestureRecognizer)
    }
    // Sideways only: a vertical drag belongs to the scroll view around.
    let velocity = pan.velocity(in: self)
    return abs(velocity.x) > abs(velocity.y)
  }

  public func gestureRecognizer(
    _ gestureRecognizer: UIGestureRecognizer, shouldBeRequiredToFailBy other: UIGestureRecognizer
  ) -> Bool {
    // A scroll view waits to see whether the drag is ours.
    gestureRecognizer === pan && other.view is UIScrollView
  }

  private func time(at x: CGFloat) -> Double {
    guard bounds.width > 0 else {
      return 0
    }
    return Double(min(1, max(0, x / bounds.width))) * duration
  }

  /// A drag is a scrub from here on: the scroll view around has let it go.
  @objc private func handlePan(_ pan: UIPanGestureRecognizer) {
    if pan.state == .began, scrubTime != nil, !dragging {
      dragging = true
      if haptics {
        selection.selectionChanged()
        selection.prepare()
      }
    }
  }

  /// The playhead goes under the finger the moment it lands, and follows it.
  public override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
    guard interactive, duration > 0, let touch = touches.first else {
      return super.touchesBegan(touches, with: event)
    }
    touchStart = touch.location(in: self)
    let time = time(at: touchStart.x)
    scrubTime = time
    dragging = false
    stopDisplayLink()
    if haptics {
      selection.prepare()
    }
    onScrubStart(["time": time])
    updateProgress()
  }

  public override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
    guard let previous = scrubTime, let touch = touches.first else {
      return super.touchesMoved(touches, with: event)
    }
    // Up or down first: not a scrub, even where nothing around scrolls. The playhead goes back.
    let travel = CGPoint(
      x: touch.location(in: self).x - touchStart.x, y: touch.location(in: self).y - touchStart.y)
    if !dragging, abs(travel.y) > 10, abs(travel.y) > abs(travel.x) {
      letGo()
      return
    }
    let time = time(at: touch.location(in: self).x)
    guard time != previous else {
      return
    }
    tickIfCrossing(from: previous, to: time)
    scrubTime = time
    onScrub(["time": time])
    updateProgress()
  }

  /// A tap or the end of a drag: seek there.
  public override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
    guard scrubTime != nil, let touch = touches.first else {
      return super.touchesEnded(touches, with: event)
    }
    if haptics, dragging {
      selection.selectionChanged()
    }
    let time = time(at: touch.location(in: self).x)
    onScrubEnd(["time": time])
    finishScrub(at: time)
  }

  /// A scroll view took the touch (a vertical drag): back where playback is, nothing chosen.
  public override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
    guard scrubTime != nil else {
      return super.touchesCancelled(touches, with: event)
    }
    letGo()
  }

  /// The touch is not a scrub after all: the playhead goes back to where playback is, and nothing is sought.
  private func letGo() {
    scrubTime = nil
    dragging = false
    onScrubEnd(["time": NSNull()])
    updatePlayback()
    updateProgress()
  }

  private func finishScrub(at time: Double) {
    scrubTime = nil
    dragging = false
    playhead.seek(to: time, at: CACurrentMediaTime())
    if playback == nil, duration > 0 {
      progress = time / duration
    }
    onSeek(["time": time])
    updatePlayback()
  }

  /// A tick for a marker passed, or an end reached; never more than one every 35 ms.
  private func tickIfCrossing(from previous: Double, to time: Double) {
    guard haptics else {
      return
    }
    let low = min(previous, time)
    let high = max(previous, time)
    let crossed =
      sortedMarkers.contains { $0 > low && $0 <= high } || (time <= 0 && previous > 0)
      || (time >= duration && previous < duration)
    let now = CACurrentMediaTime()
    if crossed, now - lastTick > 0.035 {
      lastTick = now
      selection.selectionChanged()
      selection.prepare()
    }
  }

  // MARK: Accessibility: an adjustable control, while seekable

  public override var isAccessibilityElement: Bool {
    get { interactive || super.isAccessibilityElement }
    set { super.isAccessibilityElement = newValue }
  }

  public override var accessibilityTraits: UIAccessibilityTraits {
    get { interactive ? super.accessibilityTraits.union(.adjustable) : super.accessibilityTraits }
    set { super.accessibilityTraits = newValue }
  }

  public override var accessibilityValue: String? {
    get { interactive && duration > 0 ? Self.spoken(currentTime) : super.accessibilityValue }
    set { super.accessibilityValue = newValue }
  }

  public override func accessibilityIncrement() {
    interactive ? step(by: 1) : super.accessibilityIncrement()
  }

  public override func accessibilityDecrement() {
    interactive ? step(by: -1) : super.accessibilityDecrement()
  }

  /// A twentieth of the audio, between a second and fifteen.
  private func step(by direction: Double) {
    guard duration > 0 else {
      return
    }
    let step = min(15, max(1, duration / 20))
    let time = min(duration, max(0, currentTime + direction * step))
    playhead.seek(to: time, at: CACurrentMediaTime())
    if playback == nil {
      progress = time / duration
    }
    onSeek(["time": time])
    updatePlayback()
  }

  private static let spokenFormatter: DateComponentsFormatter = {
    let formatter = DateComponentsFormatter()
    formatter.unitsStyle = .full
    formatter.allowedUnits = [.hour, .minute, .second]
    return formatter
  }()

  static func spoken(_ seconds: Double) -> String? {
    spokenFormatter.string(from: seconds.rounded(.down))
  }
}

/// The display link holds its target strongly: this holds the view weakly, so a view that goes away stops it.
private final class DisplayLinkTarget {
  private weak var view: WaveformView?

  init(_ view: WaveformView) {
    self.view = view
  }

  @objc func tick(_ link: CADisplayLink) {
    guard let view else {
      link.invalidate()
      return
    }
    view.tick()
  }
}
