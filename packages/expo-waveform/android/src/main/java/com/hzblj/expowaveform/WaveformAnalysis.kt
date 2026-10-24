package com.hzblj.expowaveform

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.sqrt

/** The loudness of every short stretch of a file: `WaveformData` in `src/types.ts`, without `fromCache`. */
class WaveformAnalysis(
  /** Root mean square of each stretch, from 0 to 1 (full scale). */
  val samples: FloatArray,
  /** Seconds each sample covers; the last one may cover less. */
  val sampleDuration: Double,
  /** The file's length in seconds, from the frames decoded. */
  val duration: Double,
) {
  /**
   * `EWF1`, the sample duration and the duration (Float64), the count (UInt32), then the samples (Float32), all
   * little-endian: what iOS writes too.
   */
  fun encoded(): ByteArray {
    val buffer = ByteBuffer.allocate(24 + samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put("EWF1".toByteArray(Charsets.US_ASCII))
    buffer.putDouble(sampleDuration)
    buffer.putDouble(duration)
    buffer.putInt(samples.size)
    for (sample in samples) {
      buffer.putFloat(sample)
    }
    return buffer.array()
  }

  companion object {
    fun decode(bytes: ByteArray): WaveformAnalysis? {
      if (bytes.size < 24 || String(bytes, 0, 4, Charsets.US_ASCII) != "EWF1") {
        return null
      }
      val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
      buffer.position(4)
      val sampleDuration = buffer.double
      val duration = buffer.double
      val count = buffer.int
      if (count < 0 || bytes.size != 24 + count * 4) {
        return null
      }
      return WaveformAnalysis(FloatArray(count) { buffer.float }, sampleDuration, duration)
    }
  }
}

/**
 * Root mean squares of consecutive stretches of `binFrames` frames. A stream longer than expected never holds more
 * than twice `maxSamples`: neighbours merge and the stretches double. The analyser's arithmetic, apart from decoding.
 */
class LoudnessAccumulator(binFrames: Int, maxSamples: Int) {
  var binFrames = maxOf(1, binFrames)
    private set
  var totalFrames = 0L
    private set
  private val maxSamples = maxOf(1, maxSamples)
  private var sums = DoubleArray(64)
  private var count = 0
  private var current = 0.0
  private var currentFrames = 0

  /** The first `length` mono frames of `frames`, in order. */
  fun add(frames: FloatArray, length: Int = frames.size) {
    var offset = 0

    while (offset < length) {
      val take = min(binFrames - currentFrames, length - offset)
      var sum = 0.0
      for (index in offset until offset + take) {
        val value = frames[index]
        sum += value * value
      }
      current += sum
      currentFrames += take
      offset += take

      if (currentFrames == binFrames) {
        if (count == sums.size) {
          sums = sums.copyOf(sums.size * 2)
        }
        sums[count++] = current
        current = 0.0
        currentFrames = 0

        if (count >= 2 * maxSamples) {
          for (index in 0 until count / 2) {
            sums[index] = sums[index * 2] + sums[index * 2 + 1]
          }
          count /= 2
          binFrames *= 2
        }
      }
    }
    totalFrames += length
  }

  /** The analysis, with at most `maxSamples` samples: neighbours merge once more if there are still too many. */
  fun finish(sampleRate: Double): WaveformAnalysis {
    val bins = count + if (currentFrames > 0) 1 else 0
    val factor = maxOf(1, (bins + maxSamples - 1) / maxSamples)
    val samples = FloatArray((bins + factor - 1) / factor)

    for (sample in samples.indices) {
      var sum = 0.0
      var frames = 0L
      for (bin in sample * factor until min(bins, (sample + 1) * factor)) {
        if (bin < count) {
          sum += sums[bin]
          frames += binFrames
        } else {
          sum += current
          frames += currentFrames
        }
      }
      samples[sample] = min(1.0, sqrt(sum / maxOf(1L, frames))).toFloat()
    }

    return WaveformAnalysis(samples, binFrames * factor / sampleRate, totalFrames / sampleRate)
  }
}
