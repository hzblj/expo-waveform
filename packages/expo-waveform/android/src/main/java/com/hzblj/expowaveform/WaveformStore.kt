package com.hzblj.expowaveform

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.MediaExtractor
import android.net.Uri
import android.webkit.MimeTypeMap
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** What to analyse and how finely: `LoadOptions` in `src/types.ts`. */
data class WaveformRequest(
  val uri: String,
  val headers: Map<String, String> = emptyMap(),
  val samplesPerSecond: Double = 100.0,
  val maxSamples: Int = 20_000,
)

class WaveformResult(val analysis: WaveformAnalysis, val fromCache: Boolean)

/** Where audio comes from: something `MediaExtractor` opens, or a URL to download first. */
sealed class WaveformSource {
  abstract val identity: String

  sealed class Local : WaveformSource() {
    abstract fun open(context: Context, extractor: MediaExtractor)
  }

  data class FilePath(val file: File) : Local() {
    override val identity
      get() = "${file.absolutePath}\u001f${file.length()}\u001f${file.lastModified()}"

    override fun open(context: Context, extractor: MediaExtractor) {
      if (!file.exists()) {
        throw WaveformFailure(WaveformFailure.Code.AUDIO, "There is no file at ${file.absolutePath}")
      }
      extractor.setDataSource(file.absolutePath)
    }
  }

  /** `content://` and `android.resource://`, and a release build's bundled audio (a raw resource). */
  data class ContentUri(val uri: Uri, val bundled: Boolean) : Local() {
    override val identity
      get() = uri.toString()

    override fun open(context: Context, extractor: MediaExtractor) {
      extractor.setDataSource(context, uri, null)
    }
  }

  data class Asset(val path: String) : Local() {
    override val identity
      get() = "asset:$path"

    override fun open(context: Context, extractor: MediaExtractor) {
      context.assets.openFd(path).use { descriptor: AssetFileDescriptor -> extractor.setDataSource(descriptor) }
    }
  }

  data class Remote(val url: String) : WaveformSource() {
    override val identity
      get() = url
  }

  companion object {
    /**
     * A source for a URI: a path or `file://`, `content://`, `android.resource://`, `file:///android_asset/`, an
     * `http(s)://` URL, or a bare resource name, which is what a `require()`d asset resolves to in a release build.
     */
    fun of(context: Context, uri: String): WaveformSource {
      if (uri.startsWith("/")) {
        return FilePath(File(uri))
      }
      val parsed = Uri.parse(uri)
      return when (parsed.scheme?.lowercase()) {
        "file" -> {
          val path = parsed.path ?: ""
          if (path.startsWith("/android_asset/")) Asset(path.removePrefix("/android_asset/")) else FilePath(File(path))
        }
        "asset" -> Asset(parsed.path?.removePrefix("/") ?: "")
        "http", "https" -> Remote(uri)
        "content", "android.resource" -> ContentUri(parsed, bundled = parsed.scheme == "android.resource")
        null -> {
          val id = context.resources.getIdentifier(uri, "raw", context.packageName)
          if (id == 0) {
            throw WaveformFailure(WaveformFailure.Code.UNSUPPORTED, "$uri is neither a URL nor a bundled audio resource")
          }
          ContentUri(Uri.parse("android.resource://${context.packageName}/$id"), bundled = true)
        }
        else -> throw WaveformFailure(WaveformFailure.Code.UNSUPPORTED, "$uri is neither a file nor an http(s) URL")
      }
    }
  }
}

/** Analyses kept in memory, the least recently used dropped first. Readable from any thread. */
private class AnalysisMemory {
  private val entries = LinkedHashMap<String, WaveformAnalysis>(16, 0.75f, true)

  /** About 8 MB of samples at the most. */
  private val limit = 2_000_000

  @Synchronized
  fun get(key: String) = entries[key]

  @Synchronized
  fun set(analysis: WaveformAnalysis, key: String) {
    entries[key] = analysis
    var total = entries.values.sumOf { it.samples.size }
    val iterator = entries.entries.iterator()
    while (total > limit && entries.size > 1 && iterator.hasNext()) {
      total -= iterator.next().value.samples.size
      iterator.remove()
    }
  }

  @Synchronized
  fun clear() = entries.clear()
}

/**
 * Every analysis goes through here: from memory, from disk, or analysed once however many ask for it at the same
 * time. A request that is cancelled leaves; the analysis stops when nobody waits for it any more.
 */
object WaveformStore {
  /** The most analyses kept on disk; the least recently used go first. */
  private const val LIMIT = 512

  private class Pending(val analysis: Deferred<WaveformAnalysis>) {
    var waiters = 0
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val memory = AnalysisMemory()
  private val pending = HashMap<String, Pending>()

  private fun root(context: Context) = File(context.cacheDir, "expo-waveform")

  private fun analyses(context: Context) = File(root(context), "analyses")

  private fun downloads(context: Context) = File(root(context), "downloads")

  /** The analysis if it is in memory already: no disk, no waiting. */
  fun cached(context: Context, request: WaveformRequest): WaveformAnalysis? =
    runCatching { memory.get(key(context, WaveformSource.of(context, request.uri), request)) }.getOrNull()

  suspend fun load(context: Context, request: WaveformRequest): WaveformResult {
    val source = WaveformSource.of(context, request.uri)
    val key = withContext(Dispatchers.IO) { key(context, source, request) }

    memory.get(key)?.let {
      return WaveformResult(it, fromCache = true)
    }
    withContext(Dispatchers.IO) { read(context, key) }?.let {
      memory.set(it, key)
      return WaveformResult(it, fromCache = true)
    }

    val entry =
      synchronized(pending) {
        pending.getOrPut(key) { Pending(scope.async { analyse(context, source, request) }) }.also { it.waiters += 1 }
      }

    try {
      val analysis = entry.analysis.await()
      val first = synchronized(pending) { (pending[key] === entry).also { if (it) pending.remove(key) } }
      if (first) {
        memory.set(analysis, key)
        withContext(Dispatchers.IO) { write(context, analysis, key) }
      }
      coroutineContext.ensureActive()
      return WaveformResult(analysis, fromCache = false)
    } catch (error: CancellationException) {
      synchronized(pending) {
        entry.waiters -= 1
        if (entry.waiters <= 0) {
          entry.analysis.cancel()
          if (pending[key] === entry) {
            pending.remove(key)
          }
        }
      }
      throw error
    } catch (error: Throwable) {
      synchronized(pending) {
        if (pending[key] === entry) {
          pending.remove(key)
        }
      }
      throw WaveformFailure.from(error)
    }
  }

  /** Forgets every analysis, in memory and on disk. */
  fun clear(context: Context) {
    memory.clear()
    root(context).deleteRecursively()
  }

  /**
   * The source and the options, and for a file its size and date, so an edited file is analysed again. Bundled audio
   * changes only with the app, so the app's install time stands in for its date.
   */
  private fun key(context: Context, source: WaveformSource, request: WaveformRequest): String {
    var identity = source.identity
    if (source is WaveformSource.Asset || (source is WaveformSource.ContentUri && source.bundled)) {
      val installed = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }
      identity += "\u001f${installed.getOrDefault(0L)}"
    }
    val value = listOf("1", identity, request.samplesPerSecond.toString(), request.maxSamples.toString()).joinToString("\u001f")
    return MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
  }

  private fun read(context: Context, key: String): WaveformAnalysis? {
    val file = File(analyses(context), "$key.ewf")
    if (!file.exists()) {
      return null
    }
    val analysis = runCatching { WaveformAnalysis.decode(file.readBytes()) }.getOrNull() ?: return null
    file.setLastModified(System.currentTimeMillis())
    return analysis
  }

  private fun write(context: Context, analysis: WaveformAnalysis, key: String) {
    val folder = analyses(context)
    folder.mkdirs()
    val temporary = File(folder, "$key.${UUID.randomUUID()}.tmp")
    runCatching {
      temporary.writeBytes(analysis.encoded())
      temporary.renameTo(File(folder, "$key.ewf"))
    }
    temporary.delete()

    val files = folder.listFiles { file -> file.name.endsWith(".ewf") } ?: return
    if (files.size > LIMIT) {
      files.sortedBy { it.lastModified() }.take(files.size - LIMIT).forEach { it.delete() }
    }
  }

  private suspend fun analyse(context: Context, source: WaveformSource, request: WaveformRequest): WaveformAnalysis {
    val active = coroutineContext
    val isActive = { active.isActive }

    if (source !is WaveformSource.Remote) {
      return WaveformAnalyzer.analyse(context, source as WaveformSource.Local, request.samplesPerSecond, request.maxSamples, isActive)
    }

    val file = download(context, source.url, request.headers, isActive)
    try {
      return WaveformAnalyzer.analyse(context, WaveformSource.FilePath(file), request.samplesPerSecond, request.maxSamples, isActive)
    } finally {
      file.delete()
    }
  }

  /** The file, under a name whose extension says what it is. Redirects are followed, http to https included. */
  private fun download(context: Context, url: String, headers: Map<String, String>, isActive: () -> Boolean): File {
    var location = URL(url)
    var connection: HttpURLConnection

    var redirects = 0
    while (true) {
      connection = location.openConnection() as HttpURLConnection
      connection.instanceFollowRedirects = false
      connection.connectTimeout = 15_000
      connection.readTimeout = 30_000
      headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }

      val status = connection.responseCode
      if (status in 300..399 && redirects < 5) {
        val next = connection.getHeaderField("Location")
        connection.disconnect()
        if (next == null) {
          throw WaveformFailure(WaveformFailure.Code.NETWORK, "The download was redirected nowhere")
        }
        location = URL(location, next)
        redirects += 1
        continue
      }
      if (status !in 200..299) {
        connection.disconnect()
        throw WaveformFailure(WaveformFailure.Code.NETWORK, "The download failed with HTTP $status")
      }
      break
    }

    val extension =
      MimeTypeMap.getFileExtensionFromUrl(location.toString()).takeIf { it.isNotEmpty() }
        ?: connection.contentType?.substringBefore(';')?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        ?: "audio"
    val folder = downloads(context).apply { mkdirs() }
    val file = File(folder, "${UUID.randomUUID()}.$extension")

    try {
      connection.inputStream.use { input ->
        file.outputStream().use { output ->
          val buffer = ByteArray(64 * 1024)
          while (true) {
            if (!isActive()) {
              throw CancellationException("The download was cancelled")
            }
            val read = input.read(buffer)
            if (read < 0) {
              break
            }
            output.write(buffer, 0, read)
          }
        }
      }
    } catch (error: Throwable) {
      file.delete()
      throw error
    } finally {
      connection.disconnect()
    }
    return file
  }
}
