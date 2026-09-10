package com.v2m.app

import java.util.Locale
import kotlin.math.sqrt

/** Захват микрофона через ALSA (JNI → libv2m.so), замена javax.sound
 *  (билд #43). Нативный слой гарантирует ровно targetRate Гц / 16 бит / моно —
 *  ресемплинга на стороне Kotlin нет. Стоп — атомарный флаг в нативе: read()
 *  возвращается ≤150 мс, кросс-поточных ALSA-вызовов нет. Реализация живёт в
 *  desktopApp (а не в shared): нужны Log/AudioLevel, и Android получит свой
 *  бэкенд захвата (ALSA-JNI переиспользованию не подлежит). */
class NativeCapture(private val targetRate: Int = 22050) : AudioCapture {

    @Volatile private var stopRequested = false
    @Volatile private var handle: Long = 0 // нативный v2m_capture*; 0 = закрыто

    override fun open(): String? {
        val h = nativeCaptureOpen("default", targetRate, 1)
        if (h == 0L) {
            val why = nativeCaptureLastError(0)
            Log.d("rec", "open не удался: $why")
            return why
        }
        handle = h
        Log.d("rec", "open ok: $targetRate Гц, 1 к., 16 бит (ALSA default)")
        return null
    }

    override fun stop() {
        stopRequested = true
        val h = handle
        if (h != 0L) nativeCaptureStop(h) // только флаг: read вернётся ≤150 мс
    }

    override fun record(maxMs: Long): AudioCapture.Result? {
        val h = handle
        if (h == 0L) return null
        val buf = ShortArray(2048) // блок 4096 байт — как read() в javax-версии
        val blocks = ArrayList<ShortArray>()
        var total = 0L
        val deadline = System.currentTimeMillis() + maxMs
        var reason = "?"
        var failed = false
        var lastLog = 0L
        Log.d("rec", "record(): старт — ALSA $targetRate Гц, 1 к., лимит $maxMs мс")
        try {
            while (!stopRequested && System.currentTimeMillis() < deadline) {
                // 0 = стоп-флаг натива/таймаут poll — свои условия выхода
                // проверяет цикл (Kotlin-флаги авторитетны); <0 — фатально.
                val n = nativeCaptureRead(h, buf, buf.size)
                if (n < 0) {
                    failed = true
                    reason = "ошибка захвата: ${nativeCaptureLastError(h)}"
                    Log.d("rec", reason)
                    break
                }
                if (n == 0) continue
                val block = buf.copyOf(n)
                blocks.add(block)
                total += n
                val rms = rmsShorts(block)
                AudioLevel.mic = rms
                val now = System.currentTimeMillis()
                if (now - lastLog >= 1000) { // сырой RMS раз в секунду — для калибровки полосы
                    lastLog = now
                    Log.d("rec", "уровень: rms=${"%.4f".format(Locale.ROOT, rms)} " +
                        "(${"%.1f".format(Locale.ROOT, levelDb(rms))} dBFS)")
                }
            }
            if (!failed) {
                if (stopRequested) reason = "останов пользователем"
                else if (System.currentTimeMillis() >= deadline) reason = "лимит $maxMs мс"
            }
        } finally {
            // Сначала сброс handle, потом close: stop() из другого потока в
            // этот момент уже не дёрнет натив (use-after-free исключён).
            handle = 0
            nativeCaptureClose(h)
        }
        Log.d("rec", "record(): конец — $reason, сэмплов $total, ≈ " +
            "${"%.1f".format(Locale.ROOT, total.toFloat() / targetRate)} с")
        if (total == 0L) {
            Log.d("rec", "record(): данных нет — возврат null")
            return null
        }
        val pcm = FloatArray(total.toInt())
        var off = 0
        for (b in blocks) {
            for (s in b) pcm[off++] = s / 32768f
        }
        return AudioCapture.Result(pcm, targetRate)
    }

    /** RMS блока S16_LE (0..1) для полосы уровня — формула rmsBlock 16 бит. */
    private fun rmsShorts(s: ShortArray): Float {
        var sum = 0.0
        for (v in s) {
            val x = v / 32768.0
            sum += x * x
        }
        return sqrt(sum / s.size).toFloat()
    }

    /** Проба JNI-слоя захвата (самотест Main.selfTest): ждём "alsa-ok…". */
    fun selftest(): String = nativeCaptureSelfTest()

    private external fun nativeCaptureOpen(device: String?, rate: Int, channels: Int): Long
    private external fun nativeCaptureRead(handle: Long, buf: ShortArray, maxFrames: Int): Int
    private external fun nativeCaptureStop(handle: Long)
    private external fun nativeCaptureClose(handle: Long)
    private external fun nativeCaptureLastError(handle: Long): String
    private external fun nativeCaptureSelfTest(): String

    init {
        System.loadLibrary("v2m")
    }
}
