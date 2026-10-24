package com.hzblj.expowaveform

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BarsTest {
  @Test
  fun levelsAreDecibelsAboveTheFloor() {
    assertEquals(1f, Bars.level(1f, WaveformScale.LOGARITHMIC, -50f), 0f)
    assertEquals(0.6f, Bars.level(0.1f, WaveformScale.LOGARITHMIC, -50f), 0.0001f)
    assertEquals(0f, Bars.level(0.001f, WaveformScale.LOGARITHMIC, -50f), 0f)
    assertEquals(0f, Bars.level(0f, WaveformScale.LOGARITHMIC, -50f), 0f)
    assertEquals(0.25f, Bars.level(0.25f, WaveformScale.LINEAR, -50f), 0f)
    assertEquals(1f, Bars.level(2f, WaveformScale.LINEAR, -50f), 0f)
  }

  @Test
  fun aBarIsTheLoudestStretchItCovers() {
    val samples = floatArrayOf(0f, 0f, 0.5f, 0f, 0f, 0f, 0.25f, 0f)
    assertArrayEquals(floatArrayOf(0f, 0.5f, 0f, 0.25f), Bars.heights(samples, 4, WaveformScale.LINEAR, normalize = false), 0f)
    assertArrayEquals(floatArrayOf(0f, 1f, 0f, 0.5f), Bars.heights(samples, 4, WaveformScale.LINEAR, normalize = true), 0f)
  }

  @Test
  fun moreBarsThanSamplesRepeatThem() {
    assertArrayEquals(floatArrayOf(0.2f, 0.2f, 0.4f, 0.4f), Bars.heights(floatArrayOf(0.2f, 0.4f), 4, null, normalize = false), 0f)
    assertEquals(0, Bars.heights(FloatArray(0), 4, null, normalize = true).size)
    assertEquals(0, Bars.heights(floatArrayOf(0.2f), 0, null, normalize = true).size)
  }

  // The fixture `test/bars.test.ts` and `BarsTests.swift` check against too.
  @Test
  fun theSameBarsAsJavaScript() {
    val samples = floatArrayOf(0.01f, 0.2f, 0.05f, 0.9f, 0.3f, 0.001f, 0.6f)
    assertArrayEquals(floatArrayOf(0.66575f, 1f, 0.90989f), Bars.heights(samples, 3, WaveformScale.LOGARITHMIC, -40f, normalize = true), 0.0001f)
  }

  @Test
  fun countFitsBarsWithGapsBetween() {
    assertEquals(20, Bars.count(100.0, 3.0, 2.0))
    assertEquals(20, Bars.count(98.0, 3.0, 2.0))
    assertEquals(19, Bars.count(97.0, 3.0, 2.0))
    assertEquals(0, Bars.count(0.0, 3.0, 2.0))
  }

  @Test
  fun shiftFindsWhatALiveMeterAppended() {
    assertEquals(1, Bars.shift(floatArrayOf(0.1f, 0.2f), floatArrayOf(0.1f, 0.2f, 0.3f)))
    assertEquals(2, Bars.shift(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f), floatArrayOf(0.3f, 0.4f, 0.5f, 0.6f)))
    assertEquals(0, Bars.shift(floatArrayOf(0.1f, 0.2f, 0.3f), floatArrayOf(0.1f, 0.2f, 0.3f)))
    assertEquals(0, Bars.shift(floatArrayOf(0.1f, 0.2f, 0.3f), floatArrayOf(0.9f, 0.8f, 0.7f)))
    assertEquals(0, Bars.shift(FloatArray(0), floatArrayOf(0.1f)))
  }
}
