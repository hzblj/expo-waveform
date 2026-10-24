import assert from 'node:assert/strict'
import {test} from 'node:test'

import {nextPlayback} from '../src/playback'

const playing = {currentTime: 10, duration: 60, playing: true, rate: 1}

test('a report the view reckoned already changes nothing', () => {
  assert.equal(nextPlayback(playing, 0, {currentTime: 11.05}, 1000), null)
  assert.equal(nextPlayback({...playing, rate: 2}, 0, {currentTime: 12.1}, 1000), null)
})

test('a seek, a pause, a new rate or a drift does', () => {
  assert.deepEqual(nextPlayback(playing, 0, {currentTime: 40}, 1000), {...playing, currentTime: 40})
  assert.deepEqual(nextPlayback(playing, 0, {currentTime: 11, playing: false}, 1000), {
    ...playing,
    currentTime: 11,
    playing: false,
  })
  assert.deepEqual(nextPlayback(playing, 0, {currentTime: 11, rate: 1.5}, 1000), {
    ...playing,
    currentTime: 11,
    rate: 1.5,
  })
  assert.deepEqual(nextPlayback(playing, 0, {currentTime: 11.3}, 1000), {...playing, currentTime: 11.3})
})

test('paused, the time stands still', () => {
  const paused = {...playing, playing: false}
  assert.equal(nextPlayback(paused, 0, {currentTime: 10}, 5000), null)
  assert.notEqual(nextPlayback(paused, 0, {currentTime: 15}, 5000), null)
})
