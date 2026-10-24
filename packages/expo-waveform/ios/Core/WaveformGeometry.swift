import CoreGraphics

/// `WaveformVariant` in `src/types.ts`.
public enum WaveformVariant: String, Sendable {
  case bars
  case area
  case line
}

/// How markers are drawn: `WaveformMarkerStyle` in `src/types.ts`.
public enum WaveformMarkerStyle: String, Sendable {
  case none
  case dot
  case line
}

public enum WaveformAlignment: String, Sendable {
  case center
  case bottom
}

/// How the view draws: the props that shape it, not the ones that colour it.
public struct WaveformStyle: Equatable, Sendable {
  public var variant: WaveformVariant = .bars
  public var barWidth: CGFloat = 3
  public var barGap: CGFloat = 2
  /// `nil` is half of `barWidth`.
  public var barRadius: CGFloat?
  public var minBarHeight: CGFloat = 2
  public var lineWidth: CGFloat = 2
  public var alignment: WaveformAlignment = .center
  public var markerStyle: WaveformMarkerStyle = .none

  public init() {}

  public var step: CGFloat {
    max(0.5, barWidth + barGap)
  }
}

/// Paths for heights from 0 to 1. A waveform spans the width flush at both edges; a live one is anchored at the right,
/// one bar per step, and anything older runs off the left edge.
public enum WaveformGeometry {
  /// How many bars, or points of an envelope, fit in `width`.
  public static func count(width: CGFloat, style: WaveformStyle) -> Int {
    Bars.count(fitting: Double(width), width: Double(max(0, style.barWidth)), gap: Double(max(0, style.barGap)))
  }

  /// The left edge of each bar.
  public static func positions(count: Int, size: CGSize, style: WaveformStyle, live: Bool) -> [CGFloat] {
    guard count > 0 else {
      return []
    }
    let width = max(0, style.barWidth)
    if live {
      return (0..<count).map { size.width - width - CGFloat(count - 1 - $0) * style.step }
    }
    guard count > 1 else {
      return [(size.width - width) / 2]
    }
    let spacing = (size.width - width) / CGFloat(count - 1)
    return (0..<count).map { CGFloat($0) * spacing }
  }

  public static func barHeight(_ level: Float, in height: CGFloat, style: WaveformStyle) -> CGFloat {
    let minimum = min(height, max(0, style.minBarHeight))
    return minimum + CGFloat(min(1, max(0, level))) * (height - minimum)
  }

  public static func path(_ heights: [Float], size: CGSize, style: WaveformStyle, live: Bool) -> CGPath {
    switch style.variant {
    case .bars:
      return bars(heights, size: size, style: style, live: live)
    case .area, .line:
      return envelope(heights, size: size, style: style, live: live)
    }
  }

  private static func bars(_ heights: [Float], size: CGSize, style: WaveformStyle, live: Bool) -> CGPath {
    let path = CGMutablePath()
    let width = max(0, style.barWidth)
    guard width > 0, size.height > 0 else {
      return path
    }

    // Every bar, even of no height: two paths of one count animate element by element.
    for (level, x) in zip(heights, positions(count: heights.count, size: size, style: style, live: live)) {
      let height = barHeight(level, in: size.height, style: style)
      let y = style.alignment == .bottom ? size.height - height : (size.height - height) / 2
      let radius = min(style.barRadius ?? width / 2, width / 2, height / 2)
      path.addRoundedRect(
        in: CGRect(x: x, y: y, width: width, height: height), cornerWidth: max(0, radius), cornerHeight: max(0, radius))
    }
    return path
  }

  /// `area` fills the envelope, `line` strokes its edges; both through the middle of every bar's place.
  private static func envelope(_ heights: [Float], size: CGSize, style: WaveformStyle, live: Bool) -> CGPath {
    let path = CGMutablePath()
    guard !heights.isEmpty, size.height > 0 else {
      return path
    }

    let inset = style.variant == .line ? max(0, style.lineWidth) / 2 : 0
    let height = max(0, size.height - inset * 2)
    let xs: [CGFloat] =
      live || heights.count == 1
      ? positions(count: heights.count, size: size, style: style, live: live).map { $0 + max(0, style.barWidth) / 2 }
      : (0..<heights.count).map { CGFloat($0) * size.width / CGFloat(heights.count - 1) }
    let levels = heights.map { barHeight($0, in: height, style: style) }

    let top: [CGPoint]
    let bottom: [CGPoint]
    switch style.alignment {
    case .center:
      top = zip(xs, levels).map { CGPoint(x: $0, y: inset + (height - $1) / 2) }
      bottom = zip(xs, levels).map { CGPoint(x: $0, y: inset + (height + $1) / 2) }
    case .bottom:
      top = zip(xs, levels).map { CGPoint(x: $0, y: inset + height - $1) }
      bottom = xs.map { CGPoint(x: $0, y: inset + height) }
    }

    if style.variant == .area {
      path.move(to: top[0])
      addCurve(through: top, to: path)
      path.addLine(to: bottom[bottom.count - 1])
      addCurve(through: bottom.reversed(), to: path)
      path.closeSubpath()
    } else {
      path.move(to: top[0])
      addCurve(through: top, to: path)
      if style.alignment == .center {
        path.move(to: bottom[0])
        addCurve(through: bottom, to: path)
      }
    }
    return path
  }

  /// A smooth curve from the current point through `points`: quadratic segments between the midpoints, which never
  /// overshoot, so a height never goes past its neighbours.
  private static func addCurve(through points: [CGPoint], to path: CGMutablePath) {
    guard points.count > 1 else {
      return
    }
    path.addLine(to: midpoint(points[0], points[1]))
    for index in 1..<(points.count - 1) {
      path.addQuadCurve(to: midpoint(points[index], points[index + 1]), control: points[index])
    }
    path.addLine(to: points[points.count - 1])
  }

  private static func midpoint(_ first: CGPoint, _ second: CGPoint) -> CGPoint {
    CGPoint(x: (first.x + second.x) / 2, y: (first.y + second.y) / 2)
  }

  /// A dot's diameter, and the band of the height dots take below the bars (the dot and a gap above it).
  public static let dotSize: CGFloat = 3
  public static let dotBand: CGFloat = 6

  /// Where the bars go: the whole view, or above the band dots take.
  public static func barsSize(_ size: CGSize, style: WaveformStyle, hasMarkers: Bool) -> CGSize {
    let band = style.markerStyle == .dot && hasMarkers ? dotBand : 0
    return CGSize(width: size.width, height: max(0, size.height - band))
  }

  /// A dot at each time, centred on whole pixels along the bottom edge.
  public static func dots(_ times: [Double], duration: Double, size: CGSize, scale: CGFloat) -> CGPath {
    let path = CGMutablePath()
    guard duration > 0, size.width > 0 else {
      return path
    }
    for time in times where time >= 0 && time <= duration {
      let x = (CGFloat(time / duration) * size.width * scale).rounded() / scale
      let left = min(size.width - dotSize, max(0, x - dotSize / 2))
      path.addEllipse(in: CGRect(x: left, y: size.height - dotSize, width: dotSize, height: dotSize))
    }
    return path
  }

  /// A line a point wide across the height at each time, on whole pixels.
  public static func markers(_ times: [Double], duration: Double, size: CGSize, scale: CGFloat) -> CGPath {
    let path = CGMutablePath()
    guard duration > 0, size.width > 0 else {
      return path
    }
    let width: CGFloat = 1
    for time in times where time >= 0 && time <= duration {
      let x = CGFloat(time / duration) * size.width - width / 2
      let left = min(size.width - width, max(0, (x * scale).rounded() / scale))
      path.addRoundedRect(
        in: CGRect(x: left, y: 0, width: width, height: size.height), cornerWidth: width / 2, cornerHeight: width / 2)
    }
    return path
  }
}
