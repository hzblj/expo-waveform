package com.hzblj.expowaveform

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import java.nio.ByteOrder
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Decodes the first audio track of anything `MediaExtractor` reads (mp3, m4a, aac, ogg, opus, flac, wav, amr, and the
 * audio of mp4 or webm) with `MediaCodec`, averages the channels and measures it as it goes: a long file never sits in
 * memory. 16-bit, float, 8-bit and 32-bit PCM all come out of decoders; every one is read.
 */
object WaveformAnalyzer {
  private const val TIMEOUT_US = 10_000L

  fun analyse(context: Context, source: WaveformSource.Local, samplesPerSecond: Double, maxSamples: Int, isActive: () -> Boolean): WaveformAnalysis {
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null

    try {
      try {
        source.open(context, extractor)
      } catch (error: Exception) {
        throw WaveformFailure(WaveformFailure.Code.AUDIO, "The file could not be read: ${error.message ?: error}")
      }

      val track =
        (0 until extractor.trackCount).firstOrNull {
          extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw WaveformFailure(WaveformFailure.Code.AUDIO, "The file has no audio track")
      extractor.selectTrack(track)

      val format = extractor.getTrackFormat(track)
      val mime = format.getString(MediaFormat.KEY_MIME)!!
      val seconds = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1e6 else 0.0
      val rate = if (seconds > 0) min(samplesPerSecond, maxSamples / seconds) else samplesPerSecond

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
      }
      codec =
        try {
          MediaCodec.createDecoderByType(mime).apply {
            configure(format, null, null, 0)
            start()
          }
        } catch (error: Exception) {
          throw WaveformFailure(WaveformFailure.Code.UNSUPPORTED, "No decoder for $mime on this device: ${error.message ?: error}")
        }

      // The decoder's output format can differ from the file's (HE-AAC doubles the rate): measured from what comes out.
      var output = codec.outputFormat
      var accumulator: LoudnessAccumulator? = null
      var outputRate = format.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, 44_100)
      var mono = FloatArray(0)
      val info = MediaCodec.BufferInfo()
      var inputDone = false
      // A decoder that never says it is done is let go after two seconds of nothing once its input has ended.
      var idle = 0

      while (true) {
        if (!isActive()) {
          throw CancellationException("The analysis was cancelled")
        }

        if (!inputDone) {
          val index = codec.dequeueInputBuffer(TIMEOUT_US)
          if (index >= 0) {
            val buffer = codec.getInputBuffer(index)!!
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) {
              codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
              inputDone = true
            } else {
              codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
              extractor.advance()
            }
          }
        }

        val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          output = codec.outputFormat
          continue
        }
        if (index < 0) {
          if (inputDone && ++idle > 200) {
            break
          }
          continue
        }
        idle = 0

        val channels = max(1, output.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, 1))
        outputRate = output.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, outputRate)
        val encoding = output.getIntegerOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        val accumulated =
          accumulator ?: LoudnessAccumulator(ceil(outputRate / max(0.001, rate)).toInt(), maxSamples).also { accumulator = it }

        if (info.size > 0) {
          val buffer = codec.getOutputBuffer(index)!!.order(ByteOrder.nativeOrder())
          buffer.position(info.offset)
          buffer.limit(info.offset + info.size)
          val frames = info.size / (bytesPerSample(encoding) * channels)
          if (mono.size < frames) {
            mono = FloatArray(frames)
          }
          mix(buffer, encoding, channels, frames, mono)
          accumulated.add(mono, frames)
        }
        codec.releaseOutputBuffer(index, false)

        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
          break
        }
      }

      return (accumulator ?: LoudnessAccumulator(1, maxSamples)).finish(outputRate.toDouble())
    } catch (error: WaveformFailure) {
      throw error
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      throw WaveformFailure(WaveformFailure.Code.AUDIO, "The audio could not be decoded: ${error.message ?: error}")
    } finally {
      runCatching { codec?.stop() }
      runCatching { codec?.release() }
      extractor.release()
    }
  }

  private fun bytesPerSample(encoding: Int) =
    when (encoding) {
      AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
      AudioFormat.ENCODING_PCM_8BIT -> 1
      AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
      else -> 2
    }

  /** The average of the channels of each frame, from -1 to 1. */
  private fun mix(buffer: java.nio.ByteBuffer, encoding: Int, channels: Int, frames: Int, into: FloatArray) {
    val scale = 1f / channels
    for (frame in 0 until frames) {
      var sum = 0f
      for (channel in 0 until channels) {
        sum +=
          when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> buffer.float
            AudioFormat.ENCODING_PCM_32BIT -> buffer.int / 2_147_483_648f
            AudioFormat.ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 0xff) - 128) / 128f
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
              val low = buffer.get().toInt() and 0xff
              val middle = buffer.get().toInt() and 0xff
              val high = buffer.get().toInt()
              ((high shl 16) or (middle shl 8) or low) / 8_388_608f
            }
            else -> buffer.short / 32_768f
          }
      }
      into[frame] = sum * scale
    }
  }

  private fun MediaFormat.getIntegerOr(key: String, fallback: Int) = if (containsKey(key)) getInteger(key) else fallback
}
