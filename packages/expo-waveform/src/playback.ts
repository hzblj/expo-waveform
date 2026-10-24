import {useEffect, useState} from 'react'

import type {WaveformPlayback} from './types'

/**
 * Anything that plays audio and says where it is: expo-audio's `AudioPlayer` and expo-video's `VideoPlayer` both are.
 * Events it does not have are never heard, so either works.
 */
export type PlaybackSource = {
  currentTime: number
  duration?: number
  playing: boolean
  playbackRate?: number
  addListener(eventName: never, listener: (event: never) => void): {remove(): void}
}

type Listenable = {addListener(eventName: string, listener: (event: Record<string, unknown>) => void): {remove(): void}}

const number = (value: unknown) => (typeof value === 'number' && Number.isFinite(value) ? value : undefined)
const boolean = (value: unknown) => (typeof value === 'boolean' ? value : undefined)

const snapshotOf = (player: PlaybackSource): WaveformPlayback => ({
  currentTime: player.currentTime,
  duration: number(player.duration) ?? 0,
  playing: player.playing,
  rate: player.playbackRate ?? 1,
})

/** How far the reported time may stray from the reckoned one before the view is told again, in seconds. */
const DRIFT = 0.15

/**
 * What a player's report changes: the playback to hand the view, or `null` when the view's own reckoning from `last`
 * (reported at `at`, in milliseconds) is still right.
 */
export const nextPlayback = (
  last: WaveformPlayback,
  at: number,
  patch: Partial<WaveformPlayback>,
  now: number
): WaveformPlayback | null => {
  const next = {...last, ...patch}
  const reckoned = last.playing ? last.currentTime + ((now - at) / 1000) * (last.rate ?? 1) : last.currentTime
  const changed =
    next.playing !== last.playing ||
    next.rate !== last.rate ||
    next.duration !== last.duration ||
    Math.abs(next.currentTime - reckoned) > DRIFT

  return changed ? next : null
}

/**
 * Where a player is, for `WaveformView`'s `playback`. The view moves its playhead by itself between updates, so this
 * changes only when something does: play or pause, the rate, a seek, or the time drifting from the reckoning. A player
 * reporting every 100 ms does not re-render the component ten times a second.
 */
export const useWaveformPlayback = (player: PlaybackSource | null): WaveformPlayback | null => {
  const [playback, setPlayback] = useState<WaveformPlayback | null>(() => (player ? snapshotOf(player) : null))

  useEffect(() => {
    if (!player) {
      setPlayback(null)
      return
    }

    const source = player as unknown as Listenable
    let last = snapshotOf(player)
    let at = performance.now()

    const report = (patch: Partial<WaveformPlayback>) => {
      const now = performance.now()
      const next = nextPlayback(last, at, patch, now)

      if (next) {
        last = next
        at = now
        setPlayback(next)
      }
    }

    setPlayback(last)

    const subscriptions = [
      source.addListener('playbackStatusUpdate', status =>
        report({
          currentTime: number(status.currentTime) ?? last.currentTime,
          duration: number(status.duration) ?? last.duration,
          playing: boolean(status.playing) ?? last.playing,
          rate: number(status.playbackRate) ?? last.rate,
        })
      ),
      source.addListener('timeUpdate', event => report({currentTime: number(event.currentTime) ?? last.currentTime})),
      source.addListener('playingChange', event =>
        report({currentTime: player.currentTime, playing: boolean(event.isPlaying) ?? last.playing})
      ),
      source.addListener('playbackRateChange', event => report({rate: number(event.playbackRate) ?? last.rate})),
      source.addListener('sourceLoad', event => report({duration: number(event.duration) ?? last.duration})),
    ]

    return () => {
      for (const subscription of subscriptions) {
        subscription.remove()
      }
    }
  }, [player])

  return playback
}
