package com.v2m.app

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.math.sqrt

/** Playback of the last recording (float PCM in memory) — the ▶ button
 *  next to the timer (п.1 приёмки #43; выбор А.М. — javax.sound.sampled
 *  SourceDataLine, like the rest of the app's javax sound — MidiPlayer;
 *  only recording is native ALSA). One playback at a time: [play] replaces
 *  any current one, [stop] cuts it. End-of-playback is reported through
 *  the [play] callback on the writer thread, so the UI is never blocked. */
object WavPlayer {
    private val lock = Any()
    private var generation = 0              // bumped on stop/error: stale writers stay silent
    private var done: (() -> Unit)? = null  // end callback of the current playback
    private var line: SourceDataLine? = null

    val isPlaying: Boolean get() = synchronized(lock) { done != null }

    /** Current playback position in seconds from the start (п.5а приёмки
     *  #43): line.getMicrosecondPosition — actually rendered frames (no
     *  buffer lead); 0 when nothing plays. The UI polls it in its own loop. */
    val positionSec: Double
        get() {
            val l = synchronized(lock) { if (done == null) null else line } ?: return 0.0
            return runCatching { l.microsecondPosition / 1e6 }.getOrDefault(0.0)
        }

    /** Start [pcm] (float samples in -1..1). Returns null on success or a
     *  user-readable error text. [onEnd] runs once on a background thread
     *  when the playback ends — by itself or via [stop]. */
    fun play(pcm: FloatArray, sr: Int, onEnd: () -> Unit = {}): String? {
        stop()
        val g = synchronized(lock) {
            generation++
            done = onEnd
            generation
        }
        var l: SourceDataLine? = null
        try {
            val format = AudioFormat(sr.toFloat(), 16, 1, true, false) // S16_LE, как запись
            l = AudioSystem.getSourceDataLine(format)
            l.open(format, 4096)
            synchronized(lock) { line = l }
            l.start()
            AudioLevel.mic = 0f
            Thread { writer(g, l, pcm) }.apply {
                isDaemon = true
                name = "v2m-wav"
            }.start()
            return null
        } catch (e: Exception) {
            synchronized(lock) {
                if (g == generation) {
                    done = null
                    if (line === l) line = null
                }
            }
            runCatching { l?.close() }
            return Strings.playFailed.format(e.message)
        }
    }

    /** End the current playback immediately (held audio is cut off). */
    fun stop() {
        var cb: (() -> Unit)? = null
        var l: SourceDataLine? = null
        synchronized(lock) {
            generation++
            cb = done
            done = null
            l = line
            line = null
        }
        if (l != null) {
            // stop()+flush() из чужого потока: снимают зависший на полном
            // буфере write (flush сбрасывает очередь) — поток выйдет по
            // смене поколения и сам закроет линию.
            runCatching { l.stop() }
            runCatching { l.flush() }
        }
        AudioLevel.mic = 0f
        cb?.invoke()
    }

    /** Writer thread: float → S16_LE in 2048-frame blocks, feeds the line;
     *  each block's RMS is published into AudioLevel.mic (полоса уровня —
     *  источник «запись», как при захвате). At the natural end it closes
     *  the line and reports via the [play] callback; when replaced/stopped
     *  meanwhile, [stop] has already reported — close and stay silent. */
    private fun writer(g: Int, l: SourceDataLine, pcm: FloatArray) {
        val block = 2048 // фреймов на порцию (как блок захвата NativeCapture)
        val bytes = ByteArray(block * 2)
        var off = 0
        try {
            while (synchronized(lock) { g == generation }) {
                val take = minOf(block, pcm.size - off)
                if (take <= 0) break
                var sumSq = 0.0
                var si: Int
                for (i in 0 until take) {
                    val x = pcm[off + i]
                    si = (x * 32767f).toInt().coerceIn(-32768, 32767)
                    bytes[i * 2] = (si and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((si shr 8) and 0xFF).toByte()
                    sumSq += x.toDouble() * x
                }
                off += take
                l.write(bytes, 0, take * 2)
                AudioLevel.mic = sqrt(sumSq / take).toFloat()
            }
        } catch (_: Exception) {
            // закрытая/остановленная линия — выход; статус снят стопом или ниже
        } finally {
            runCatching { l.close() }
        }
        var cb: (() -> Unit)? = null
        synchronized(lock) {
            if (g == generation) {
                cb = done
                done = null
                if (line === l) line = null
            }
        }
        AudioLevel.mic = 0f
        cb?.invoke()
    }
}
