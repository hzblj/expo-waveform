import assert from 'node:assert/strict'
import {test} from 'node:test'

import {barCount, levelOf, toBars} from '../src/bars'

const close = (actual: readonly number[], expected: readonly number[]) => {
  assert.equal(actual.length, expected.length)
  actual.forEach((value, index) => {
    assert.ok(Math.abs(value - (expected[index] ?? Number.NaN)) < 0.0001, `${value} is not ${expected[index]}`)
  })
}

test('a level is decibels above the noise floor', () => {
  assert.equal(levelOf(1), 1)
  assert.ok(Math.abs(levelOf(0.1) - 0.6) < 0.0001)
  assert.equal(levelOf(0.001), 0)
  assert.equal(levelOf(0), 0)
  assert.equal(levelOf(0.25, {scale: 'linear'}), 0.25)
  assert.equal(levelOf(2, {scale: 'linear'}), 1)
})

test('a bar is the loudest stretch it covers', () => {
  const samples = [0, 0, 0.5, 0, 0, 0, 0.25, 0]
  close(toBars(samples, 4, {normalize: false, scale: 'linear'}), [0, 0.5, 0, 0.25])
  close(toBars(samples, 4, {scale: 'linear'}), [0, 1, 0, 0.5])
})

test('more bars than samples repeat them, and nothing gives nothing', () => {
  close(toBars([0.2, 0.4], 4, {normalize: false, scale: 'linear'}), [0.2, 0.2, 0.4, 0.4])
  assert.deepEqual(toBars([], 4), [])
  assert.deepEqual(toBars([0.2], 0), [])
})

// The fixture `BarsTests.swift` and `BarsTest.kt` check against too.
test('the same bars as the native views', () => {
  close(toBars([0.01, 0.2, 0.05, 0.9, 0.3, 0.001, 0.6], 3, {noiseFloor: -40}), [0.66575, 1, 0.90989])
})

test('bars with gaps between them fit the width', () => {
  assert.equal(barCount(100, 3, 2), 20)
  assert.equal(barCount(98, 3, 2), 20)
  assert.equal(barCount(97, 3, 2), 19)
  assert.equal(barCount(0, 3, 2), 0)
})
