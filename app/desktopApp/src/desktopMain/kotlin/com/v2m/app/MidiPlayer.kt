package com.v2m.app

import java.io.ByteArrayInputStream
import java.util.Locale
import javax.sound.midi.MidiMessage
import javax.sound.midi.MidiSystem
import javax.sound.midi.Receiver
import javax.sound.midi.Sequencer
import javax.sound.midi.ShortMessage
import javax.sound.midi.Synthesizer

/** In-app SMF playback (no temp file, no external player): the JDK
 *  javax.sound.midi Sequencer routed into the software synthesizer
 *  (SoftSynthesizer/Gervill — instruments come from the JVM soundbank,
 *  standard OpenJDK ships the "Emergency GM" set). One playback at a time:
 *  [play] replaces any current one, [stop] cuts it. End-of-playback is
 *  reported through the [play] callback on a background thread, so the UI
 *  thread is never blocked. */
object MidiPlayer {
    private val lock = Any()
    private var generation = 0              // bumped on stop/error: stale watchers stay silent
    private var done: (() -> Unit)? = null  // end callback of the current playback
    private var seq: Sequencer? = null      // devices of the current playback
    private var synth: Synthesizer? = null
    private var diagnosed = false

    val isPlaying: Boolean get() = synchronized(lock) { done != null }

    /** Позиция текущего воспроизведения в секундах от начала файла
     *  (п.5а приёмки #43): UI опрашивает в своём цикле тиков; 0, когда
     *  ничего не играет (после конца/стопа — [watch]/[stop] сбросили seq). */
    val positionSec: Double
        get() {
            val q = synchronized(lock) { if (done == null) null else seq }
                ?: return 0.0
            return runCatching { q.microsecondPosition / 1e6 }.getOrDefault(0.0)
        }

    /** Start [midi] (a complete SMF file). Returns null on success or a
     *  user-readable error text. [onEnd] runs once on a background thread
     *  when the playback ends — by itself or via [stop]. */
    fun play(midi: ByteArray, onEnd: () -> Unit = {}): String? {
        stop()
        val g = synchronized(lock) {
            generation++
            done = onEnd
            generation
        }
        var q: Sequencer? = null
        var s: Synthesizer? = null
        try {
            s = MidiSystem.getSynthesizer()
            q = MidiSystem.getSequencer(false)
            s.open()
            q.open()
            diagnose(s)
            if (s.defaultSoundbank == null || s.loadedInstruments.isEmpty()) {
                throw IllegalStateException(
                    "в этой JVM нет звукового банка — синтезатор будет без звука")
            }
            // Между Sequencer и Synthesizer — прозрачный приёмник уровня
            // (билд #40): все события пробрасываются синтезатору без
            // изменений, а «громкость нот» публикуется в AudioLevel.midi.
            q.transmitter.receiver = MeterReceiver(s.receiver)
            q.sequence = MidiSystem.getSequence(ByteArrayInputStream(midi))
            synchronized(lock) {
                seq = q
                synth = s
            }
            q.start()
            Thread { watch(g, q, s) }.apply {
                isDaemon = true
                name = "v2m-midi"
            }.start()
            return null
        } catch (e: Exception) {
            synchronized(lock) {
                if (g == generation) {
                    done = null
                    if (seq === q) seq = null
                    if (synth === s) synth = null
                }
            }
            runCatching { q?.close() }
            runCatching { s?.close() }
            return Strings.playFailed.format(e.message)
        }
    }

    /** End the current playback immediately (held notes are cut off). */
    fun stop() {
        var cb: (() -> Unit)? = null
        var q: Sequencer? = null
        var s: Synthesizer? = null
        synchronized(lock) {
            generation++
            cb = done
            done = null
            q = seq
            s = synth
            seq = null
            synth = null
        }
        if (q != null) runCatching { q.close() }
        if (s != null) runCatching { s.close() }
        AudioLevel.midi = 0f // воспроизведение кончилось — уровень гаснет
        cb?.invoke()
    }

    /** Wait for the natural end of the sequence, then release the devices
     *  and report. When the playback was replaced or stopped meanwhile,
     *  [stop] has already reported and closed — do nothing but close. */
    private fun watch(g: Int, q: Sequencer, s: Synthesizer) {
        while (synchronized(lock) { g == generation } &&
            runCatching { q.isRunning }.getOrDefault(false)
        ) {
            try {
                Thread.sleep(30)
            } catch (_: InterruptedException) {
                break
            }
        }
        var cb: (() -> Unit)? = null
        synchronized(lock) {
            if (g == generation) {
                cb = done
                done = null
                if (seq === q) seq = null
                if (synth === s) synth = null
            }
        }
        runCatching { q.close() }
        runCatching { s.close() }
        AudioLevel.midi = 0f
        cb?.invoke()
    }

    /** One-time startup diagnostics: which synth and soundbank are used
     *  (a JVM without any soundbank would play silence — see [play]). */
    private fun diagnose(s: Synthesizer) {
        synchronized(lock) {
            if (diagnosed) return
            diagnosed = true
        }
        println("MIDI-плеер: синтезатор ${s.javaClass.simpleName}, " +
            "банк «${s.defaultSoundbank?.name ?: "нет"}», " +
            "инструментов загружено: ${s.loadedInstruments.size}")
    }
}

/** Прозрачный приёмник между Sequencer и Synthesizer (билд #40): все
 *  события уходят синтезатору без изменений (ошибки синтезатора летят
 *  дальше, как при прямом подключении), а «громкость нот» — сумма velocity
 *  звучащих нот — публикуется в AudioLevel.midi. Полная шкала: 4 ноты fff
 *  (127×4); паузы дают 0 — полоса «в молчании» гаснет. */
private class MeterReceiver(private val next: Receiver) : Receiver {
    private val vel = Array(16) { ShortArray(128) } // [канал][питч] → velocity
    private var sum = 0
    private var lastLog = 0L

    override fun send(msg: MidiMessage?, timeStamp: Long) {
        try {
            if (msg is ShortMessage) {
                val ch = msg.channel
                val p = msg.data1
                val on = msg.command == ShortMessage.NOTE_ON
                val off = msg.command == ShortMessage.NOTE_OFF
                if (on || off) {
                    val v = if (on && msg.data2 > 0) msg.data2 else 0
                    val old = vel[ch][p].toInt()
                    if (old != v) {
                        vel[ch][p] = v.toShort()
                        sum += v - old
                        AudioLevel.midi = minOf(1f, sum / (127f * 4f))
                        val now = System.currentTimeMillis()
                        if (now - lastLog >= 500) { // debug-метка [midi] (билд #41)
                            lastLog = now
                            Log.d("midi", "уровень нот: sum=$sum/508" +
                                (if (sum > 0) " (" +
                                    "%.1f".format(Locale.ROOT, levelDb(sum / 508f)) +
                                    " dBFS)" else " (−∞ dBFS)"))
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // метр не должен мешать звуку
        } finally {
            next.send(msg, timeStamp)
        }
    }

    override fun close() = next.close()
}
