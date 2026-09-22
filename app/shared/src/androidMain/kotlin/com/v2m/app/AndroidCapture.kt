package com.v2m.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.runBlocking
import kotlin.math.max

/** Захват микрофона на Android (этап 5 плана docs/260921_android_plan.md) —
 *  `AudioRecord`, замена ALSA-JNI десктопа. Контракт [AudioCapture] требует
 *  моно PCM на частоте модели ([V2mEngine.SAMPLE_RATE]); если устройство её
 *  не поддерживает, захват идёт на поддержанной частоте, а ресемплинг — здесь
 *  (как и на desktop, где его делает нативный слой).
 *
 *  `stop()` безопасен из любого потока: чтение ведётся неблокирующим режимом,
 *  поэтому остановка видна в пределах [POLL_MS] мс. */
internal class AndroidCapture(
    private val host: AndroidHost,
    private val targetRate: Int = V2mEngine.SAMPLE_RATE,
) : AudioCapture {

    @Volatile private var stopRequested = false
    @Volatile private var rec: AudioRecord? = null

    /** open() вызывается интерфейсом в фоновом потоке, а запрос разрешения —
     *  только с главного: [AndroidHost.ensureRecordPermission] сам переходит
     *  на главный поток, здесь ожидание его ответа (поток ввода-вывода, не
     *  интерфейса). */
    @SuppressLint("MissingPermission") // разрешение запрошено строкой выше
    override fun open(): String? {
        if (!runBlocking { host.ensureRecordPermission() }) {
            return "нет разрешения на запись с микрофона"
        }
        for (rate in listOf(targetRate, 44_100, 48_000)) {
            val minBuf = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) continue
            val buffer = max(minBuf, rate / 2 * 2) // ≈0.5 с — с запасом на опрос
            val r = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC, rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buffer,
                )
            } catch (e: Exception) {
                Log.d("rec", "AudioRecord($rate Гц) не создан: ${e.message}")
                continue
            }
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                r.release()
                continue
            }
            rec = r
            Log.d("rec", "open ok: $rate Гц, 1 к., 16 бит (AudioRecord)${if (rate != targetRate) " → $targetRate Гц ресемплинг" else ""}")
            return null
        }
        return "микрофон недоступен (нет поддержанной частоты захвата)"
    }

    override fun stop() {
        stopRequested = true
        // Разблокировка не нужна: read() неблокирующий — цикл сам увидит флаг
    }

    override fun record(maxMs: Long): AudioCapture.Result? {
        val r = rec ?: return null
        val rate = r.sampleRate
        val buf = ShortArray(1024)
        val blocks = ArrayList<ShortArray>()
        var total = 0
        val deadline = System.currentTimeMillis() + maxMs
        var reason = "?"
        var failed = false
        Log.d("rec", "record(): старт — $rate Гц, 1 к., лимит $maxMs мс")
        r.startRecording()
        try {
            while (!stopRequested && System.currentTimeMillis() < deadline) {
                val n = r.read(buf, 0, buf.size, AudioRecord.READ_NON_BLOCKING)
                if (n < 0) {
                    failed = true
                    reason = "ошибка захвата: код $n"
                    Log.d("rec", reason)
                    break
                }
                if (n == 0) {
                    Thread.sleep(POLL_MS)
                    continue
                }
                val block = buf.copyOf(n)
                blocks.add(block)
                total += n
                val rms = rmsShorts(block)
                AudioLevel.mic = rms
            }
            if (!failed) {
                reason = when {
                    stopRequested -> "останов пользователем"
                    else -> "лимит $maxMs мс"
                }
            }
        } catch (e: Exception) {
            failed = true
            reason = "ошибка захвата: ${e.message}"
            Log.d("rec", reason)
        } finally {
            runCatching { r.stop() }
            rec = null
            runCatching { r.release() }
        }
        if (blocks.isEmpty()) {
            Log.d("rec", "record(): конец — $reason, данных нет")
            return null
        }
        val pcm = shortsToFloats(blocks, total)
        val out = if (rate == targetRate) pcm else resample(pcm, rate, targetRate)
        Log.d("rec", "record(): конец — $reason, сэмплов $total → ${out.size} (${targetRate} Гц), ≈ ${out.size / targetRate} с")
        return AudioCapture.Result(out, targetRate)
    }

    private companion object {
        /** Шаг опроса неблокирующего чтения: столько же — верхняя граница
         *  задержки остановки. */
        const val POLL_MS = 10L

        fun shortsToFloats(blocks: List<ShortArray>, total: Int): FloatArray {
            val out = FloatArray(total)
            var i = 0
            for (b in blocks) for (s in b) out[i++] = s / 32768f
            return out
        }

        /** Приведение к частоте модели линейной интерполяцией: захват идёт на
         *  частоте устройства (44.1/48 кГц), модель ждёт [targetRate]. */
        fun resample(pcm: FloatArray, fromRate: Int, targetRate: Int): FloatArray {
            val n = pcm.size.toLong() * targetRate / fromRate
            val out = FloatArray(n.toInt())
            val step = fromRate.toDouble() / targetRate
            for (i in out.indices) {
                val pos = i * step
                val i0 = pos.toInt()
                val i1 = (i0 + 1).coerceAtMost(pcm.size - 1)
                val f = (pos - i0).toFloat()
                out[i] = pcm[i0] * (1 - f) + pcm[i1] * f
            }
            return out
        }
    }
}
