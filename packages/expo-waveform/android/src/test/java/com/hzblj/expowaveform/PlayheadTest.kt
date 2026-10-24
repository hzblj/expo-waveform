package com.hzblj.expowaveform

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayheadTest {
  @Test
  fun thePlayheadMovesOnBetweenReports() {
    val playhead = Playhead()
    playhead.report(10.0, true, 1.5, 100.0)
    assertEquals(13.0, playhead.time(102.0), 0.0)

    playhead.report(13.1, false, 1.5, 102.0)
    assertEquals(13.1, playhead.time(110.0), 0.0)
  }

  @Test
  fun aSeekHoldsUntilThePlayerCatchesUp() {
    val playhead = Playhead()
    playhead.report(10.0, true, 1.0, 100.0)
    playhead.seek(50.0, 100.0)

    playhead.report(10.2, true, 1.0, 100.2)
    assertEquals(50.2, playhead.time(100.2), 0.0001)

    playhead.report(50.4, true, 1.0, 100.4)
    assertEquals(50.4, playhead.time(100.4), 0.0001)

    playhead.report(20.0, true, 1.0, 100.5)
    assertEquals(20.0, playhead.time(100.5), 0.0001)
  }

  @Test
  fun aSeekGivesUpAfterAMoment() {
    val playhead = Playhead()
    playhead.report(10.0, false, 1.0, 100.0)
    playhead.seek(50.0, 100.0)

    playhead.report(10.0, false, 1.0, 101.5)
    assertEquals(10.0, playhead.time(101.5), 0.0)
  }
}
