import {type NativeModule, requireNativeModule, type SharedObject} from 'expo'

/** A stand-in microphone: levels shaped like speech, posted natively the way expo-speech's `Transcription` posts its own. */
declare class Meter extends SharedObject {
  /** A level every `interval` seconds, each early or late by up to `jitter` seconds. */
  start(interval: number, jitter: number): void
  stop(): void
}

declare class MockMeterModule extends NativeModule {
  Meter: typeof Meter
  /** Holds the main thread for `duration` seconds every `every` seconds; 0 lets it go. */
  setMainThreadHitches(every: number, duration: number): void
}

export const MockMeter = requireNativeModule<MockMeterModule>('MockMeter')
