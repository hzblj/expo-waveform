package com.hzblj.expowaveform

import expo.modules.kotlin.exception.CodedException
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** An error with one of the codes of `WaveformErrorCode` in `src/types.ts`. */
class WaveformFailure(val code: Code, override val message: String) : Exception(message) {
  enum class Code(val value: String) {
    AUDIO("audio"),
    NETWORK("network"),
    UNSUPPORTED("unsupported"),
    UNKNOWN("unknown"),
  }

  companion object {
    /** Any error, as a failure with the closest code. Cancellation stays what it is. */
    fun from(error: Throwable): Throwable =
      when (error) {
        is WaveformFailure, is CancellationException -> error
        is IOException -> WaveformFailure(Code.NETWORK, error.message ?: "The download failed")
        else -> WaveformFailure(Code.UNKNOWN, error.message ?: error.toString())
      }
  }
}

/** A failure as JavaScript sees it: `error.code` is the failure's code. */
class WaveformException(failure: WaveformFailure) : CodedException(failure.code.value, failure.message, failure)
