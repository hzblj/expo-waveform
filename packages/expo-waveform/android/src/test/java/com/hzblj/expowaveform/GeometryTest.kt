package com.hzblj.expowaveform

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class GeometryTest {
  @Test
  fun barsSpanTheWidthFlushAtBothEdges() {
    assertArrayEquals(floatArrayOf(0f, 50f, 100f), WaveformGeometry.positions(3, 103f, WaveformStyle(), live = false), 0f)
  }

  @Test
  fun liveBarsAreAnchoredAtTheRight() {
    assertArrayEquals(floatArrayOf(87f, 92f, 97f), WaveformGeometry.positions(3, 100f, WaveformStyle(), live = true), 0f)
  }

  @Test
  fun silenceStillDrawsTheShortestBar() {
    assertEquals(2f, WaveformGeometry.barHeight(0f, 40f, WaveformStyle()), 0f)
    assertEquals(40f, WaveformGeometry.barHeight(1f, 40f, WaveformStyle()), 0f)
    assertEquals(21f, WaveformGeometry.barHeight(0.5f, 40f, WaveformStyle()), 0f)
    assertEquals(40f, WaveformGeometry.barHeight(0f, 40f, WaveformStyle(minBarHeight = 100f)), 0f)
  }

  @Test
  fun markersLandOnWholePixelsInsideTheView() {
    assertArrayEquals(floatArrayOf(0f, 50f, 99f), WaveformGeometry.markers(doubleArrayOf(0.0, 5.0, 10.0, 11.0), 10.0, 100f, 1f), 0f)
    assertEquals(0, WaveformGeometry.markers(doubleArrayOf(1.0), 0.0, 100f, 1f).size)
  }
}
