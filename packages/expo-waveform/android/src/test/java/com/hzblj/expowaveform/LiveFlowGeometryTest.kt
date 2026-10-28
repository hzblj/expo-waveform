package com.hzblj.expowaveform

import org.junit.Assert.assertEquals
import org.junit.Test

/** The same cases as the flow tests in `GeometryTests.swift`. */
class LiveFlowGeometryTest {
  private fun place(flow: LiveWaveformFlow, width: Float, height: Float, x: Float, y: Float): Pair<Float, Float> {
    val m = LiveFlowGeometry.matrix(flow, width, height)
    return (m[0] * x + m[1] * y + m[2]) to (m[3] * x + m[4] * y + m[5])
  }

  @Test
  fun everyFlowBringsTheNewestBarInAtItsOwnEdge() {
    assertEquals(99f to 10f, place(LiveWaveformFlow.LEFT, 100f, 20f, 99f, 10f))
    assertEquals(1f to 10f, place(LiveWaveformFlow.RIGHT, 100f, 20f, 99f, 10f))
    assertEquals(100f to 20f, LiveFlowGeometry.canvasSize(LiveWaveformFlow.UP, 20f, 100f))
    assertEquals(10f to 99f, place(LiveWaveformFlow.UP, 20f, 100f, 99f, 10f))
    assertEquals(10f to 1f, place(LiveWaveformFlow.DOWN, 20f, 100f, 99f, 10f))
  }

  @Test
  fun aBarShortOfItsPlaceSitsTowardTheEdgeItComesIn() {
    assertEquals(4f to 0f, LiveFlowGeometry.offset(LiveWaveformFlow.LEFT, 4f))
    assertEquals(-4f to 0f, LiveFlowGeometry.offset(LiveWaveformFlow.RIGHT, 4f))
    assertEquals(0f to 4f, LiveFlowGeometry.offset(LiveWaveformFlow.UP, 4f))
    assertEquals(0f to -4f, LiveFlowGeometry.offset(LiveWaveformFlow.DOWN, 4f))
  }
}
