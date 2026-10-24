import CryptoKit
import Foundation
import UniformTypeIdentifiers

/// What to analyse and how finely: `LoadOptions` in `src/types.ts`.
public struct WaveformRequest: Sendable {
  public var uri: String
  public var headers: [String: String]
  public var samplesPerSecond: Double
  public var maxSamples: Int

  public init(uri: String, headers: [String: String] = [:], samplesPerSecond: Double = 100, maxSamples: Int = 20_000) {
    self.uri = uri
    self.headers = headers
    self.samplesPerSecond = max(0.001, samplesPerSecond)
    self.maxSamples = max(1, maxSamples)
  }
}

public struct WaveformResult: Sendable {
  public let analysis: WaveformAnalysis
  public let fromCache: Bool
}

/// The package's folder under Caches: finished analyses, and downloads while they are analysed.
public enum WaveformStorage {
  public static var root: URL {
    FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("expo-waveform")
  }

  static var analyses: URL { root.appendingPathComponent("analyses") }
  static var downloads: URL { root.appendingPathComponent("downloads") }

  /// The most analyses kept on disk; the least recently used go first.
  static let limit = 512

  static func hash(_ value: String) -> String {
    SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined()
  }

  static func read(key: String) -> WaveformAnalysis? {
    let url = analyses.appendingPathComponent("\(key).ewf")
    guard let data = try? Data(contentsOf: url), let analysis = WaveformAnalysis(encoded: data) else {
      return nil
    }
    try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: url.path)
    return analysis
  }

  static func write(_ analysis: WaveformAnalysis, key: String) {
    try? FileManager.default.createDirectory(at: analyses, withIntermediateDirectories: true)
    try? analysis.encoded.write(to: analyses.appendingPathComponent("\(key).ewf"), options: .atomic)

    let files =
      (try? FileManager.default.contentsOfDirectory(
        at: analyses, includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
    guard files.count > limit else {
      return
    }
    let byAge = files.sorted {
      let first = (try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
      let second = (try? $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
      return first < second
    }
    for file in byAge.prefix(files.count - limit) {
      try? FileManager.default.removeItem(at: file)
    }
  }
}

/// Analyses kept in memory, the least recently used dropped first. Readable from any thread, so a view finds a
/// cached waveform in the frame it is given its source.
final class AnalysisMemory: @unchecked Sendable {
  private let lock = NSLock()
  private var entries: [String: WaveformAnalysis] = [:]
  private var recent: [String] = []
  /// About 8 MB of samples at the most.
  private let limit = 2_000_000

  func get(_ key: String) -> WaveformAnalysis? {
    lock.withLock {
      guard let analysis = entries[key] else {
        return nil
      }
      recent.removeAll { $0 == key }
      recent.append(key)
      return analysis
    }
  }

  func set(_ analysis: WaveformAnalysis, key: String) {
    lock.withLock {
      entries[key] = analysis
      recent.removeAll { $0 == key }
      recent.append(key)
      var total = entries.values.reduce(0) { $0 + $1.samples.count }
      while total > limit, recent.count > 1 {
        total -= entries.removeValue(forKey: recent.removeFirst())?.samples.count ?? 0
      }
    }
  }

  func clear() {
    lock.withLock {
      entries = [:]
      recent = []
    }
  }
}

/// Every analysis goes through here: from memory, from disk, or analysed once however many ask for it at the same
/// time. A request that is cancelled leaves; the analysis stops when nobody waits for it any more.
public actor WaveformStore {
  public static let shared = WaveformStore()

  private final class Pending {
    let task: Task<WaveformAnalysis, Error>
    var waiters = 0

    init(task: Task<WaveformAnalysis, Error>) {
      self.task = task
    }
  }

  private let memory = AnalysisMemory()
  private var pending: [String: Pending] = [:]

  public init() {}

  /// The analysis if it is in memory already: no disk, no waiting.
  public nonisolated func cached(_ request: WaveformRequest) -> WaveformAnalysis? {
    guard let url = try? Self.url(request.uri) else {
      return nil
    }
    return memory.get(Self.key(url: url, request: request))
  }

  public func load(_ request: WaveformRequest) async throws -> WaveformResult {
    let url = try Self.url(request.uri)
    let key = Self.key(url: url, request: request)

    if let analysis = memory.get(key) {
      return WaveformResult(analysis: analysis, fromCache: true)
    }
    if let analysis = WaveformStorage.read(key: key) {
      memory.set(analysis, key: key)
      return WaveformResult(analysis: analysis, fromCache: true)
    }

    let entry =
      pending[key]
      ?? {
        let entry = Pending(task: Task.detached(priority: .userInitiated) { try await Self.analyse(url, request) })
        pending[key] = entry
        return entry
      }()
    entry.waiters += 1

    do {
      let analysis = try await withTaskCancellationHandler {
        try await entry.task.value
      } onCancel: {
        Task { await self.leave(key: key, entry: entry) }
      }
      if pending[key] === entry {
        pending[key] = nil
        WaveformStorage.write(analysis, key: key)
        memory.set(analysis, key: key)
      }
      try Task.checkCancellation()
      return WaveformResult(analysis: analysis, fromCache: false)
    } catch {
      if pending[key] === entry {
        pending[key] = nil
      }
      throw error is CancellationError ? error : WaveformFailure.from(error)
    }
  }

  /// Forgets every analysis, in memory and on disk.
  public func clear() {
    memory.clear()
    try? FileManager.default.removeItem(at: WaveformStorage.root)
  }

  private func leave(key: String, entry: Pending) {
    entry.waiters -= 1
    if entry.waiters <= 0 {
      entry.task.cancel()
      if pending[key] === entry {
        pending[key] = nil
      }
    }
  }

  /// A `file://` or `http(s)://` URL, or an absolute path.
  static func url(_ uri: String) throws -> URL {
    if uri.hasPrefix("/") {
      return URL(fileURLWithPath: uri)
    }
    guard let url = URL(string: uri), let scheme = url.scheme?.lowercased(), ["file", "http", "https"].contains(scheme)
    else {
      throw WaveformFailure(.unsupported, "\(uri) is neither a file nor an http(s) URL")
    }
    return url
  }

  /// The URL and the options, and for a file its size and date, so an edited file is analysed again.
  static func key(url: URL, request: WaveformRequest) -> String {
    var parts = ["1", url.absoluteString, "\(request.samplesPerSecond)", "\(request.maxSamples)"]
    if url.isFileURL, let attributes = try? FileManager.default.attributesOfItem(atPath: url.path) {
      parts.append("\((attributes[.size] as? NSNumber)?.int64Value ?? 0)")
      parts.append("\((attributes[.modificationDate] as? Date)?.timeIntervalSince1970 ?? 0)")
    }
    return WaveformStorage.hash(parts.joined(separator: "\u{1F}"))
  }

  private static func analyse(_ url: URL, _ request: WaveformRequest) async throws -> WaveformAnalysis {
    guard !url.isFileURL else {
      return try await WaveformAnalyzer.analyse(
        file: url, samplesPerSecond: request.samplesPerSecond, maxSamples: request.maxSamples)
    }

    let file = try await download(url, headers: request.headers)
    defer { try? FileManager.default.removeItem(at: file) }
    return try await WaveformAnalyzer.analyse(
      file: file, samplesPerSecond: request.samplesPerSecond, maxSamples: request.maxSamples)
  }

  /// The file, under a name whose extension says what it is: AVFoundation goes by it.
  private static func download(_ url: URL, headers: [String: String]) async throws -> URL {
    var request = URLRequest(url: url)
    for (name, value) in headers {
      request.setValue(value, forHTTPHeaderField: name)
    }

    let (temporary, response) = try await URLSession.shared.download(for: request)
    defer { try? FileManager.default.removeItem(at: temporary) }

    if let response = response as? HTTPURLResponse, !(200..<300).contains(response.statusCode) {
      throw WaveformFailure(.network, "The download failed with HTTP \(response.statusCode)")
    }

    let ext =
      [
        (response.suggestedFilename as NSString?)?.pathExtension,
        url.pathExtension,
        response.mimeType.flatMap { UTType(mimeType: $0)?.preferredFilenameExtension },
      ]
      .compactMap { $0 }
      .first { !$0.isEmpty } ?? "audio"
    let destination = WaveformStorage.downloads.appendingPathComponent("\(UUID().uuidString).\(ext)")

    try FileManager.default.createDirectory(at: WaveformStorage.downloads, withIntermediateDirectories: true)
    try FileManager.default.moveItem(at: temporary, to: destination)
    return destination
  }
}
