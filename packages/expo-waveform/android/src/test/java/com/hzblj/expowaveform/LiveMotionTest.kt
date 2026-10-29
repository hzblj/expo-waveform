package com.hzblj.expowaveform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same cases as `LiveMotionTests.swift`. */
class LiveMotionTest {
  @Test
  fun easingStartsAtNothingAndEndsWhole() {
    val curves = listOf(
      WaveformEasing.LINEAR,
      WaveformEasing.EASE_IN,
      WaveformEasing.EASE_OUT,
      WaveformEasing.EASE_IN_OUT,
      WaveformEasing(0.2, 1.4, 0.4, 1.0)
    )
    for (easing in curves) {
      assertEquals(0.0, easing.value(0.0), 0.0)
      assertEquals(1.0, easing.value(1.0), 0.0)
      assertEquals(0.0, easing.value(-1.0), 0.0)
      assertEquals(1.0, easing.value(2.0), 0.0)
    }
  }

  @Test
  fun easingFollowsItsCurve() {
    assertEquals(0.3, WaveformEasing.LINEAR.value(0.3), 1e-6)
    assertEquals(0.5, WaveformEasing.EASE_IN_OUT.value(0.5), 1e-6)
    assertTrue(WaveformEasing.EASE_OUT.value(0.25) > 0.25)
    assertTrue(WaveformEasing.EASE_IN.value(0.25) < 0.25)
    assertEquals(0.30837, WaveformEasing.EASE_OUT.value(0.2), 1e-4)

    var previous = 0.0
    for (step in 1..100) {
      val value = WaveformEasing.EASE_OUT.value(step / 100.0)
      assertTrue(value >= previous)
      previous = value
    }
  }

  @Test
  fun easingByName() {
    assertEquals(WaveformEasing.EASE_OUT, WaveformEasing.named("easeOut"))
    assertNull(WaveformEasing.named("bounce"))
  }

  @Test
  fun aBarGrowsOverItsDuration() {
    val growIn = LiveWaveformGrowIn(duration = 0.2, easing = WaveformEasing.LINEAR)

    assertEquals(0.0, growIn.progress(-0.1), 0.0)
    assertEquals(0.5, growIn.progress(0.1), 1e-6)
    assertEquals(1.0, growIn.progress(0.3), 0.0)
    assertEquals(1.0, LiveWaveformGrowIn(duration = 0.0).progress(0.0), 0.0)
  }

  @Test
  fun thePaceHardlyMovesForABurst() {
    val pace = LivePace()
    assertEquals(LivePace.INITIAL, pace.value, 0.0)

    for (index in 0 until 20) pace.record(index * 0.1)
    assertEquals(0.1, pace.value, 1e-9)

    pace.record(2.05)
    pace.record(2.06)
    assertEquals(0.1, pace.value, 0.01)

    pace.record(5.0)
    assertEquals(0.1, pace.value, 0.01)
  }

  @Test
  fun aStandingMeterWaitsUntilItIsLagBehind() {
    val motion = LiveMotion()
    motion.jump(-1.0, 0.0)
    motion.follow(0, 2.0, 0.1, 0.0)

    assertEquals(-1.0, motion.position(0.05), 0.0)
    assertEquals(-0.5, motion.position(0.15), 1e-9)
    assertEquals(0.15, motion.timeReaching(-0.5) ?: 0.0, 1e-9)
    assertNull(motion.timeReaching(0.5))
  }

  @Test
  fun aMeterThatOnlyJustStoppedMovesOnAndOneThatStoodWaits() {
    val motion = LiveMotion()
    motion.jump(-1.0, 0.0)
    for (newest in 0 until 20) motion.follow(newest, 2.0, 0.1, newest * 0.1)

    assertEquals(19.0, motion.position(2.15), 1e-6)
    motion.follow(20, 2.0, 0.1, 2.15)
    assertTrue(motion.position(2.16) > 19.0)

    motion.follow(21, 2.0, 0.1, 4.0)
    assertEquals(20.0, motion.position(4.09), 1e-6)
    assertTrue(motion.position(4.11) > 20.0)
  }

  @Test
  fun levelsOnTimeMoveAtAnEvenSpeedLagBehind() {
    val pace = 0.08
    val motion = LiveMotion()
    motion.jump(-1.0, 0.0)

    for (newest in 0 until 60) motion.follow(newest, 2.0, pace, newest * pace)

    assertEquals(1 / pace, motion.speed, 0.01 / pace)
    assertEquals(57.0, motion.start, 0.01)
  }

  @Test
  fun lateLevelsNeitherStopNorJerkTheMeter() {
    val pace = 0.085
    val motion = LiveMotion()
    val random = java.util.Random(7)
    var previous = Double.NEGATIVE_INFINITY
    var time = 0.0
    motion.jump(-1.0, 0.0)

    for (newest in 0 until 400) {
      val late = (random.nextDouble() - 0.5) * 0.03 + if (newest % 23 == 0) 0.06 else 0.0
      time = maxOf(time, newest * pace + late)

      val here = motion.position(time)
      assertTrue(here >= previous)
      if (newest > 3) assertTrue("stopped before level $newest came", here < motion.end)
      previous = here

      motion.follow(newest, 2.0, pace, time)
      if (newest > 3) assertEquals(1.0, motion.speed * pace, 0.25)
    }
  }
}
