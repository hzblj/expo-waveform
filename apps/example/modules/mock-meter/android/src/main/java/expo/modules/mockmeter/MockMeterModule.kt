package expo.modules.mockmeter

import android.os.Handler
import android.os.Looper
import androidx.annotation.Keep
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.sharedobjects.SharedObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.DoubleConsumer
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

class MockMeterModule : Module() {
  private val main = Handler(Looper.getMainLooper())
  private var hitches: Runnable? = null

  override fun definition() = ModuleDefinition {
    Name("MockMeter")

    Class("Meter", MockMeter::class) {
      Constructor { -> MockMeter(appContext) }
      Function("start") { meter: MockMeter, interval: Double, jitter: Double -> meter.start(interval, jitter) }
      Function("stop") { meter: MockMeter -> meter.stop() }
    }

    Function("setMainThreadHitches") { every: Double, duration: Double ->
      main.post {
        hitches?.let(main::removeCallbacks)
        hitches = null
        if (every <= 0 || duration <= 0) return@post
        val hitch = object : Runnable {
          override fun run() {
            Thread.sleep((duration * 1000).toLong())
            main.postDelayed(this, (every * 1000).toLong())
          }
        }
        hitches = hitch
        main.postDelayed(hitch, (every * 1000).toLong())
      }
    }
  }
}

/**
 * A stand-in microphone where there is none (a simulator, an emulator): a level every `interval` seconds, early or late
 * by up to `jitter`, shaped like speech, handed to its listeners on its own thread the way expo-speech's
 * `Transcription` hands over a microphone's.
 */
class MockMeter(appContext: AppContext) : SharedObject(appContext) {
  private val listeners = CopyOnWriteArrayList<DoubleConsumer>()
  @Volatile private var generation = 0
  private var syllable = 0.7

  @Keep
  fun addAudioLevelListener(listener: DoubleConsumer): AutoCloseable {
    listeners.add(listener)
    return AutoCloseable { listeners.remove(listener) }
  }

  fun start(interval: Double, jitter: Double) {
    thread.execute {
      generation += 1
      tick(0, now(), max(0.01, interval), max(0.0, jitter), generation)
    }
  }

  fun stop() {
    thread.execute { generation += 1 }
  }

  private fun tick(index: Int, start: Double, interval: Double, jitter: Double, generation: Int) {
    if (generation != this.generation) return
    val level = level(index * interval)
    for (listener in listeners) listener.accept(level)

    val next = start + (index + 1) * interval + if (jitter > 0) Random.nextDouble(-jitter, jitter) else 0.0
    val delay = max(0L, ((next - now()) * 1e6).toLong())
    thread.schedule({ tick(index + 1, start, interval, jitter, generation) }, delay, TimeUnit.MICROSECONDS)
  }

  /** Phrases of syllables, about four and a half a second for 2.2 s, then half a second of room noise. */
  private fun level(time: Double): Double {
    val isPhrase = time % 2.7 < 2.2
    val wave = max(0.0, sin(2 * PI * 4.5 * time))
    if (wave < 0.1) syllable = Random.nextDouble(0.4, 1.0)
    val decibels = if (isPhrase) -46 + 40 * syllable * wave.pow(0.6) else -46.0
    return 10.0.pow((decibels + Random.nextDouble(-3.0, 3.0)) / 20)
  }

  private fun now() = System.nanoTime() / 1e9

  companion object {
    private val thread = Executors.newSingleThreadScheduledExecutor()
  }
}
