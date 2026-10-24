package com.hzblj.expowaveform

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class AnalysisTest {
  @Test
  fun aSineIsMeasuredAsItsRootMeanSquare() {
    val rate = 8_000
    val accumulator = LoudnessAccumulator(rate / 100, 20_000)
    // Fed in uneven chunks, as a decoder hands them over.
    val sine = FloatArray(rate) { (0.5 * sin(2 * PI * 440 * it / rate)).toFloat() }
    accumulator.add(sine.copyOfRange(0, 3_001))
    accumulator.add(sine.copyOfRange(3_001, rate))
    accumulator.add(FloatArray(rate))

    val analysis = accumulator.finish(rate.toDouble())

    assertEquals(2.0, analysis.duration, 0.0)
    assertEquals(200, analysis.samples.size)
    assertEquals(0.01, analysis.sampleDuration, 0.0)
    assertEquals((0.5 / sqrt(2.0)).toFloat(), analysis.samples[50], 0.005f)
    assertEquals(0f, analysis.samples[150], 0f)
  }

  @Test
  fun aStreamLongerThanExpectedMerges() {
    val accumulator = LoudnessAccumulator(10, 4)
    accumulator.add(FloatArray(95) { 0.5f })

    val analysis = accumulator.finish(10.0)

    assertEquals(20, accumulator.binFrames)
    assertEquals(4.0, analysis.sampleDuration, 0.0)
    assertEquals(9.5, analysis.duration, 0.0)
    assertArrayEquals(floatArrayOf(0.5f, 0.5f, 0.5f), analysis.samples, 0.0001f)
  }

  @Test
  fun anAnalysisSurvivesTheDiskInTheFormatIosWrites() {
    val analysis = WaveformAnalysis(floatArrayOf(0f, 0.25f, 1f), 0.01, 0.03)
    val bytes = analysis.encoded()
    val decoded = WaveformAnalysis.decode(bytes)!!

    assertEquals("EWF1", String(bytes, 0, 4, Charsets.US_ASCII))
    assertArrayEquals(floatArrayOf(0f, 0.25f, 1f), decoded.samples, 0f)
    assertEquals(0.01, decoded.sampleDuration, 0.0)
    assertEquals(0.03, decoded.duration, 0.0)
    assertNull(WaveformAnalysis.decode("EWF1".toByteArray()))
    assertNull(WaveformAnalysis.decode(bytes.copyOf(bytes.size - 1)))
  }
}
