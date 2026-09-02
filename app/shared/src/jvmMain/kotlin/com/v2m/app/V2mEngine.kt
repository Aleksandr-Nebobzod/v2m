package com.v2m.app

/**
 * JNI bridge to the v2m native library (basicpitch/src/libv2m).
 * The library must be on java.library.path; see desktopApp build script.
 */
object V2mEngine {

    init {
        System.loadLibrary("v2m")
    }

    private external fun nativeParamsDefault(): DoubleArray
    private external fun nativeTranscribe(
        pcm: FloatArray, sampleRate: Int, params: DoubleArray,
    ): ByteArray?
    private external fun nativeMidiToMusicXml(midi: ByteArray, path: String, clef: Int): Boolean
    private external fun nativeLastReport(): String

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
    ) {
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
            timeSigNum.toDouble(),
            timeSigDen.toDouble(),
            if (verbose) 1.0 else 0.0,
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
                )
            }
        }
    }

    /** PCM mono (any sample rate) -> standard MIDI file bytes, or null on error. */
    fun transcribe(pcm: FloatArray, sampleRate: Int, params: Params, verbose: Boolean = false): ByteArray? =
        nativeTranscribe(pcm, sampleRate, params.toArray(verbose))

    /** Write a MusicXML file next to the MIDI bytes. [clef]: 0 = G
     *  (treble), 1 = F (bass). */
    fun midiToMusicXml(midi: ByteArray, path: String, clef: Int = 0): Boolean =
        nativeMidiToMusicXml(midi, path, clef)

    /** Informative pipeline report of the last transcription (tempo, mode
     *  fit, global shift); ONNX "Schema error" noise is filtered out. */
    fun lastReport(): String = nativeLastReport()
}
