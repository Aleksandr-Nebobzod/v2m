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
    private external fun nativeTranscribeFrames(
        pcm: FloatArray, sampleRate: Int, params: DoubleArray,
    ): ByteArray?
    private external fun nativeSetFramesMeta(track: String?, author: String?)
    private external fun nativeMidiToMusicXml(midi: ByteArray, path: String, clef: Int, fifths: Int, anacrusis: Int): Boolean
    private external fun nativeLastReport(): String
    private external fun nativeLastFramesJson(): String

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
