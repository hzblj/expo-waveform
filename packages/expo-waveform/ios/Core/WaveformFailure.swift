import Foundation

/// An error with one of the codes of `WaveformErrorCode` in `src/types.ts`.
public struct WaveformFailure: Error, LocalizedError, Sendable {
  public enum Code: String, Sendable {
    case audio
    case network
    case unsupported
    case unknown
  }

  public let code: Code
  public let message: String

  public init(_ code: Code, _ message: String) {
    self.code = code
    self.message = message
  }

  public var errorDescription: String? {
    message
  }

  /// Any error, as a failure with the closest code.
  public static func from(_ error: Error) -> WaveformFailure {
    if let failure = error as? WaveformFailure {
      return failure
    }

    let nsError = error as NSError

    if nsError.domain == NSURLErrorDomain {
      return WaveformFailure(.network, nsError.localizedDescription)
    }
    if nsError.domain == "AVFoundationErrorDomain" || nsError.domain == NSOSStatusErrorDomain {
      return WaveformFailure(.audio, nsError.localizedDescription)
    }
    return WaveformFailure(.unknown, nsError.localizedDescription)
  }
}
