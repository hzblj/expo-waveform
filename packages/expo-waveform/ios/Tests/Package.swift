// swift-tools-version:5.9
// The module's Foundation, AVFoundation and CoreGraphics logic (`ios/Core`), tested on the Mac with `swift test`: the
// analyser decodes real audio here, without an app. `Sources/ExpoWaveformCore` is a link to `../Core`, the files the
// pod compiles.
import PackageDescription

let package = Package(
  name: "ExpoWaveformCoreTests",
  platforms: [.macOS(.v13)],
  targets: [
    .target(name: "ExpoWaveformCore", path: "Sources/ExpoWaveformCore"),
    .testTarget(name: "ExpoWaveformCoreTests", dependencies: ["ExpoWaveformCore"], path: "Tests/ExpoWaveformCoreTests"),
  ]
)
