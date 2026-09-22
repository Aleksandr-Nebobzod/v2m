package com.v2m.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.min

/** Воспроизведение звука на Android (этап 5 плана docs/260921_android_plan.md):
 *  MIDI — штатный синтезатор системы через `MediaPlayer`, WAV — `AudioTrack`.
 *  Изменения громкости слышны сразу (у MIDI — на следующем запуске,
 *  у WAV — со следующего блока записи). */

/** MIDI через `MediaPlayer`. Источником ему нужен путь или дескриптор, а не
 *  память, поэтому SMF пишется в файл каталога данных (перезаписывается при
 *  каждом запуске — как и у десктопного «слушать внешним плеером»).
 *  !ai — поддержку SMF у `MediaPlayer` (Android 6.0+) проверить на устройстве:
 *  если система формат не примет, вызов вернёт текст ошибки, а интерфейс
 *  покажет её в журнале. */
internal class AndroidMidiPlayback(private val context: Context) : MidiPlayback {

    private val file: File get() = File(context.filesDir, "v2m-listen.mid")
    private var player: MediaPlayer? = null
    private var volumePct = 100

    /** `MediaPlayer.setVolume` принимает 0..1: заводская громкость здесь и есть
     *  максимум, запаса «до 127 %» (CC7 десктопного синтезатора) на Android нет. */
    override fun setVolume(pct: Int) {
        volumePct = pct
        val g = gain
        player?.let { p -> runCatching { p.setVolume(g, g) } }
    }

    private val gain: Float get() = volumePct.coerceIn(0, 100) / 100f

    override val positionSec: Double
        get() = player?.let { p -> runCatching { p.currentPosition / 1000.0 }.getOrDefault(0.0) } ?: 0.0

    override fun play(midi: ByteArray, onEnd: () -> Unit): String? {
        stop()
        var p: MediaPlayer? = null
        return try {
            file.writeBytes(midi)
            p = MediaPlayer()
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            p.setDataSource(file.absolutePath)
            p.setOnCompletionListener { onEnd() }
            p.setOnErrorListener { _, what, extra ->
                Log.d("play", "MediaPlayer: ошибка what=$what extra=$extra")
                stop()
                true
            }
            // Файл локальный и короткий: синхронная подготовка не заметна,
            // а ошибку разбора SMF видно сразу (её и возвращаем текстом).
            p.prepare()
            p.setVolume(gain, gain)
            player = p // до start(): обработчики ошибок уже видят текущий плеер
            p.start()
            null
        } catch (e: Exception) {
            Log.d("play", "MIDI не запустился: ${e.message}")
            // Плеер освобождает либо stop() (если успел стать текущим), либо
            // эта ветка — иначе setDataSource/prepare утекали бы
            if (player === p) stop() else runCatching { p?.release() }
            e.message ?: e.toString()
        }
    }

    override fun stop() {
        val p = player ?: return
        player = null
        runCatching { p.stop() }
        runCatching { p.release() }
    }
}

/** WAV через `AudioTrack` (поток 16 бит, запись блоками). Громкость — тот же
 *  линейный множитель к сэмплам, что и на desktop (`WavPlayer`), поэтому
 *  звучание совпадает: множитель применяется к каждому блоку, смена ручки
 *  слышна со следующего. */
internal class AndroidWavPlayback : WavPlayback {

    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null
    private var track: AudioTrack? = null

    @Volatile
    override var volume: Float = 1f

    override val positionSec: Double
        get() = track?.let { t ->
            runCatching { t.playbackHeadPosition / t.sampleRate.toDouble() }.getOrDefault(0.0)
        } ?: 0.0

    override fun play(pcm: FloatArray, sr: Int, onEnd: () -> Unit): String? {
        stop()
        val minBuf = AudioTrack.getMinBufferSize(
            sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return "частота $sr Гц не поддержана устройством"
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            // ≈200 мс в буфере: меньше щёлкает при старте, чем минимум системы
            .setBufferSizeInBytes(maxOf(minBuf, sr / 5 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            t.release()
            return "AudioTrack не инициализирован ($sr Гц)"
        }
        track = t
        t.play()
        job = scope.launch {
            try {
                val block = ShortArray(2048)
                var i = 0
                while (isActive && i < pcm.size) {
                    val n = min(block.size, pcm.size - i)
                    val v = volume // читается на каждый блок: ручка слышна в процессе
                    for (k in 0 until n) {
                        val s = (pcm[i + k] * v).coerceIn(-1f, 1f) * 32767f
                        block[k] = s.toInt().toShort()
                    }
                    t.write(block, 0, n)
                    i += n
                }
                // Доигрываем буфер: write() возвращается раньше звука
                while (isActive && t.playbackHeadPosition < pcm.size) delay(20)
                if (isActive) onEnd()
            } finally {
                if (track === t) {
                    track = null
                    runCatching { t.stop() }
                    t.release()
                }
            }
        }
        return null
    }

    override fun stop() {
        job?.cancel()
        job = null
        val t = track ?: return
        track = null
        runCatching { t.pause() }
        runCatching { t.flush() }
        runCatching { t.release() }
    }
}
