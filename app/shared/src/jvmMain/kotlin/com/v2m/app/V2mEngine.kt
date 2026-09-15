package com.v2m.app

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * JNI bridge to the v2m native library (basicpitch/src/libv2m).
 * The library must be on java.library.path; see desktopApp build script.
 */
object V2mEngine {

    init {
        System.loadLibrary("v2m")
    }

    /** Частота, на которой работает ядро (ресемпл внутри v2m_transcribe и
     *  v2m_process_audio, см. basic_pitch::constants::SAMPLE_RATE) — единое
     *  место определения для Kotlin-стороны: по ней GUI считает
     *  спектрограмму обработанного материала (билд #50). */
    const val SAMPLE_RATE = 22050

    private external fun nativeParamsDefault(): DoubleArray

    /** Сколько полей отдаёт .so (nativeParamsDefault) — проба свежести
     *  библиотеки в самотесте (Main.kt): .so старше билда #54 отдаёт 23
     *  поля, без pitch_median_window, и подстановка дефолта прошла бы молча.
     *  public: desktopApp — отдельный модуль, internal из shared не виден. */
    fun paramsDefaultFieldCount(): Int = nativeParamsDefault().size
    private external fun nativeTranscribeFrames(
        pcm: FloatArray, sampleRate: Int, params: DoubleArray,
    ): ByteArray?
    private external fun nativeSetFramesMeta(track: String?, author: String?)
    private external fun nativeMidiToMusicXml(midi: ByteArray, path: String, clef: Int, fifths: Int, anacrusis: Int): Boolean
    private external fun nativeLastReport(): String
    private external fun nativeLastFramesJson(): String
    private external fun nativeSpectrogram(pcm: FloatArray, sampleRate: Int): ByteArray?
    private external fun nativeProcessAudio(
        pcm: FloatArray, sampleRate: Int, params: DoubleArray,
    ): FloatArray?

    /** All transcription knobs; order matches the native V2mParams. */
    data class Params(
        val onsetThreshold: Float,
        val frameThreshold: Float,
        val minNoteLen: Int,
        val energyTol: Int,
        val program: Int,
        val velocityCompress: Float,
        val useMelodiaTrick: Boolean,
        val includePitchBends: Boolean,
        val tempoBpm: Float,
        val quantize: Int,
        val toleranceMs: Float,
        val harmonizeMerge: Int,
        val minBendBins: Int,
        val globalShift: Float,
        val modeSnap: Float,
        val timeSigNum: Int = 0,
        val timeSigDen: Int = 0,
        // «Обработка» (билд #50): применяется к сигналу до модели, крайние
        // значения = выключено (V2mParams, раздел «Обработка» в GUI).
        val gateDb: Float = -80f,
        val lowCutHz: Float = 20f,
        val highCutHz: Float = 10000f,
        val expComp: Float = 0f,
        // «Мелодика» (билд #52, п.5 приёмки #51): медианное сглаживание
        // кадров модели, нечётное окно 3..15 кадров; 1 = выключено (дефолт
        // с билда #54). App.kt подставляет сюда значение слайдера
        // «Сглаживание» (в prefs и пресетах оно живёт отдельно — как keySel).
        val smoothingWindow: Int = 1,
        // «Мелодика» (билд #54, Р19): стабильность питча — удаление коротких
        // нот-выбросов по медиане высот соседей; окно 1 (выкл, дефолт) или
        // нечётные 3..7 нот (harmonize.cpp:stabilize_note_pitches).
        val pitchMedianWindow: Int = 1,
    ) {
        /** Порядок = V2mParams поле за полем: verbose на 15, подпись такта на
         *  16/17, эффекты на 18..21, сглаживание на 22, стабильность питча
         *  на 23 (см. params_from_doubles в v2m_jni.cpp). */
        fun toArray(verbose: Boolean = false): DoubleArray = doubleArrayOf(
            onsetThreshold.toDouble(),
            frameThreshold.toDouble(),
            minNoteLen.toDouble(),
            energyTol.toDouble(),
            program.toDouble(),
            velocityCompress.toDouble(),
            if (useMelodiaTrick) 1.0 else 0.0,
            if (includePitchBends) 1.0 else 0.0,
            tempoBpm.toDouble(),
            quantize.toDouble(),
            toleranceMs.toDouble(),
            harmonizeMerge.toDouble(),
            minBendBins.toDouble(),
            globalShift.toDouble(),
            modeSnap.toDouble(),
            if (verbose) 1.0 else 0.0,
            timeSigNum.toDouble(),
            timeSigDen.toDouble(),
            gateDb.toDouble(),
            lowCutHz.toDouble(),
            highCutHz.toDouble(),
            expComp.toDouble(),
            smoothingWindow.toDouble(),
            pitchMedianWindow.toDouble(),
        )

        companion object {
            fun defaults(): Params {
                val d = nativeParamsDefault()
                return Params(
                    d[0].toFloat(), d[1].toFloat(), d[2].toInt(), d[3].toInt(),
                    d[4].toInt(), d[5].toFloat(), d[6] != 0.0, d[7] != 0.0,
                    d[8].toFloat(), d[9].toInt(), d[10].toFloat(),
                    d[11].toInt(), d[12].toInt(), d[13].toFloat(), d[14].toFloat(),
                    d[16].toInt(), d[17].toInt(),
                    d[18].toFloat(), d[19].toFloat(), d[20].toFloat(), d[21].toFloat(),
                    d[22].toInt(),
                    // .so старше билда #54 шлёт 23 значения — фильтра нет
                    if (d.size >= 24) d[23].toInt() else 1,
                )
            }
        }
    }

    /** PCM mono (any sample rate) -> standard MIDI file bytes, or null on
     *  error. Every transcription also condenses the raw per-frame model
     *  output into a features summary (see [lastFramesJson]) — it exists
     *  only in the run, so it is always computed and the export decides
     *  whether to write it to disk (билд #38). [track]/[author] go to the
     *  summary "meta" (track = the audio file name without extension).
     *  A mismatched (older) libv2m.so surfaces loudly, not silently. */
    fun transcribe(
        pcm: FloatArray, sampleRate: Int, params: Params,
        track: String? = null, author: String? = null, verbose: Boolean = false,
    ): ByteArray? {
        val a = params.toArray(verbose)
        try {
            nativeSetFramesMeta(track, author)
        } catch (e: UnsatisfiedLinkError) {
            throw IllegalStateException(
                "libv2m.so устарела: пересоберите basicpitch/src/libv2m (nativeSetFramesMeta)", e)
        }
        return nativeTranscribeFrames(pcm, sampleRate, a)
    }

    /** Frame-features summary JSON (schema v2m-frame-features-2) of the
     *  last [transcribe]; "" when none was produced. */
    fun lastFramesJson(): String = nativeLastFramesJson()

    /** Spectrogram overview of the audio (билд #47, вкладка «Звук»): multi-
     *  resolution STFT, log-spaced bands. Display only — it neither affects
     *  nor reflects the transcription. [frames] are model frames (11.6 ms,
     *  the vertical axis in the GUI), [bands] run from 10 Hz (index 0) to
     *  10 kHz; 0 = silence, 255 = top of the 60 dB scale. Null on failure.
     *  A mismatched (older) libv2m.so surfaces loudly, not silently. */
    fun spectrogram(pcm: FloatArray, sampleRate: Int): Spectrogram? {
        val raw = try {
            nativeSpectrogram(pcm, sampleRate)
        } catch (e: UnsatisfiedLinkError) {
            throw IllegalStateException(
                "libv2m.so устарела: пересоберите basicpitch/src/libv2m (nativeSpectrogram)", e)
        } ?: return null
        require(raw.size >= 12 && raw[0] == 'V'.code.toByte() && raw[1] == 'S'.code.toByte() &&
            raw[2] == 'P'.code.toByte() && raw[3] == '1'.code.toByte()) {
            "spectrogram: неожиданный формат ответа (${raw.size} байт)"
        }
        val head = ByteBuffer.wrap(raw, 4, 8).order(ByteOrder.BIG_ENDIAN)
        val frames = head.int
        val bands = head.int
        require(frames >= 0 && bands > 0 && raw.size == 12 + frames * bands) {
            "spectrogram: размер матрицы не совпал ($frames x $bands, ${raw.size} байт)"
        }
        return Spectrogram(frames, bands, raw.copyOfRange(12, raw.size))
    }

    /** «Обработка» без транскрипции (билд #50): тот же материал (22050 Гц),
     *  какой получает модель — по нему GUI считает обработанную спектрограмму
     *  вкладки «Спектр». При выключенных эффектах возвращает ресемплированный
     *  вход без изменений. Null on failure; устаревшая libv2m.so — явная
     *  ошибка (UnsatisfiedLinkError), а не молчаливое сырьё. */
    fun processAudio(pcm: FloatArray, sampleRate: Int, params: Params): FloatArray? = try {
        nativeProcessAudio(pcm, sampleRate, params.toArray())
    } catch (e: UnsatisfiedLinkError) {
        throw IllegalStateException(
            "libv2m.so устарела: пересоберите basicpitch/src/libv2m (nativeProcessAudio)", e)
    }

    /** Write a MusicXML file next to the MIDI bytes. [clef]: 0 = G
     *  (treble), 1 = F (bass). [fifths]: the <key><fifths> value (−7..7);
     *  0 = C major/A minor (no key signature). [anacrusis]: partial first
     *  measure of N eighth notes (0 = none). */
    fun midiToMusicXml(midi: ByteArray, path: String, clef: Int = 0, fifths: Int = 0, anacrusis: Int = 0): Boolean =
        nativeMidiToMusicXml(midi, path, clef, fifths, anacrusis)

    /** Informative pipeline report of the last transcription (tempo, mode
     *  fit, global shift); ONNX "Schema error" noise is filtered out. */
    fun lastReport(): String = nativeLastReport()
}

/** Spectrogram matrix of one audio take (see [V2mEngine.spectrogram]):
 *  [frames] × [bands] bytes, index = frame*bands + band. */
class Spectrogram(val frames: Int, val bands: Int, val data: ByteArray) {
    operator fun get(frame: Int, band: Int): Int = data[frame * bands + band].toInt() and 0xFF
}
