package com.hzblj.expowaveform

import kotlin.math.abs
import kotlin.math.max

/**
 * Where playback is between two reports of the player: the last report, moved on by the time since at the rate it
 * plays. After a seek, reports still on the old time are passed over for a moment, so the playhead does not jump back
 * before the player catches up. Times in seconds, `now` from one monotonic clock.
 */
class Playhead {
  var playing = false
    private set
  var rate = 1.0
    private set
  private var anchorTime = 0.0
  private var anchorAt = 0.0
  private var seekTime: Double? = null
  private var seekUntil = 0.0

  fun time(now: Double): Double = if (playing) anchorTime + max(0.0, now - anchorAt) * rate else anchorTime

  fun report(currentTime: Double, playing: Boolean, rate: Double, now: Double) {
    val seekTime = seekTime
    if (seekTime != null) {
      if (now < seekUntil && abs(currentTime - seekTime) > SEEK_TOLERANCE) {
        val time = time(now)
        this.playing = playing
        this.rate = rate
        anchorTime = time
        anchorAt = now
        return
      }
      this.seekTime = null
    }
    this.playing = playing
    this.rate = rate
    anchorTime = currentTime
    anchorAt = now
  }

  /** The view sent `onSeek(time)`: the playhead stays there until the player says so too. */
  fun seek(time: Double, now: Double) {
    anchorTime = time
    anchorAt = now
    seekTime = time
    seekUntil = now + SEEK_PATIENCE
  }

  companion object {
    /** How long a seek waits for the player to report it. */
    const val SEEK_PATIENCE = 1.0

    /** How close a report must be to a seek to confirm it, in seconds. */
    const val SEEK_TOLERANCE = 0.5
  }
}
