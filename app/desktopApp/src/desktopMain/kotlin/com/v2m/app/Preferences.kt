package com.v2m.app

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/** Persistent user preferences: transcription parameters, instrument,
 *  keySel (the "Тональность" slider), smoothingWindow and the last used
 *  file paths. Stored as a properties file in ~/.v2m/prefs.properties —
 *  the desktop equivalent of Android SharedPreferences, without extra
 *  dependencies. */
object Preferences {
    private val file = File(System.getProperty("user.home"), ".v2m" + File.separator + "prefs.properties")

    data class Loaded(
        val params: V2mEngine.Params,
        val keySel: Int, // 0 = auto, 1..24 (see Key.kt keyFromSelection)
        val smoothingWindow: Int, // median filter width, odd 3..15 (UI-only for now)
        val instrument: Int?, // 1..128 (GM); null = never stored — use params.program + 1
        val clef: Int, // 0 = G (treble), 1 = F (bass); MusicXML export
        val anacrusis: Int, // partial first measure of N eighth notes, 0..8 (Р5)
        val lastWav: String?,
        val lastMidi: String?,
        val lastXml: String?,
    )

    fun load(): Loaded {
        val p = Properties()
        if (file.isFile) runCatching { file.inputStream().use(p::load) }
        // Values are clamped — the file may be edited by hand.
        fun f(k: String, d: Float, lo: Float = 0f, hi: Float = 1f) =
            (p.getProperty(k)?.toFloatOrNull() ?: d).coerceIn(lo, hi)
        fun i(k: String, d: Int, lo: Int = 0, hi: Int = Int.MAX_VALUE) =
            (p.getProperty(k)?.toIntOrNull() ?: d).coerceIn(lo, hi)
        fun b(k: String, d: Boolean) = p.getProperty(k)?.toBooleanStrictOrNull() ?: d
        val def = V2mEngine.Params.defaults()
        return Loaded(
            params = V2mEngine.Params(
                onsetThreshold = f("onsetThreshold", def.onsetThreshold),
                frameThreshold = f("frameThreshold", def.frameThreshold),
                minNoteLen = i("minNoteLen", def.minNoteLen, 1, 100),
                energyTol = i("energyTol", def.energyTol, 0, 100),
                program = i("program", def.program, 0, 127),
                velocityCompress = f("velocityCompress", def.velocityCompress),
                useMelodiaTrick = b("useMelodiaTrick", def.useMelodiaTrick),
                includePitchBends = b("includePitchBends", def.includePitchBends),
                tempoBpm = f("tempoBpm", def.tempoBpm, 0f, 250f).let { if (it in 1f..24f) 25f else it },
                quantize = i("quantize", def.quantize, 0, 4),
                toleranceMs = f("toleranceMs", def.toleranceMs, 0f, 1000f),
                harmonizeMerge = i("harmonizeMerge", def.harmonizeMerge, 0, 10),
                minBendBins = i("minBendBins", def.minBendBins, 0, 100),
                globalShift = f("globalShift", def.globalShift),
                modeSnap = f("modeSnap", def.modeSnap),
                timeSigNum = i("timeSigNum", def.timeSigNum, 0, 32),
                timeSigDen = i("timeSigDen", def.timeSigDen, 0, 64),
            ),
            keySel = i("keySel", 0, 0, 24), // legacy "detectKey" property is ignored
            smoothingWindow = (i("smoothingWindow", 5, 3, 15) or 1).coerceIn(3, 15),
            instrument = p.getProperty("instrument")?.toIntOrNull()?.coerceIn(1, 128),
            clef = i("clef", 0, 0, 1),
            anacrusis = i("anacrusis", 0, 0, 8),
            lastWav = p.getProperty("lastWav"),
            lastMidi = p.getProperty("lastMidi"),
            lastXml = p.getProperty("lastXml"),
        )
    }

    fun save(
        params: V2mEngine.Params,
        keySel: Int,
        smoothingWindow: Int,
        instrument: Int,
        clef: Int,
        anacrusis: Int,
        lastWav: String?,
        lastMidi: String?,
        lastXml: String?,
    ) {
        val p = Properties()
        p.setProperty("onsetThreshold", params.onsetThreshold.toString())
        p.setProperty("frameThreshold", params.frameThreshold.toString())
        p.setProperty("minNoteLen", params.minNoteLen.toString())
        p.setProperty("energyTol", params.energyTol.toString())
        p.setProperty("program", params.program.toString())
        p.setProperty("velocityCompress", params.velocityCompress.toString())
        p.setProperty("useMelodiaTrick", params.useMelodiaTrick.toString())
        p.setProperty("includePitchBends", params.includePitchBends.toString())
        p.setProperty("tempoBpm", params.tempoBpm.toString())
        p.setProperty("quantize", params.quantize.toString())
        p.setProperty("toleranceMs", params.toleranceMs.toString())
        p.setProperty("harmonizeMerge", params.harmonizeMerge.toString())
        p.setProperty("minBendBins", params.minBendBins.toString())
        p.setProperty("globalShift", params.globalShift.toString())
        p.setProperty("modeSnap", params.modeSnap.toString())
        p.setProperty("timeSigNum", params.timeSigNum.toString())
        p.setProperty("timeSigDen", params.timeSigDen.toString())
        p.setProperty("keySel", keySel.toString())
        p.setProperty("smoothingWindow", smoothingWindow.toString())
        p.setProperty("instrument", instrument.toString())
        p.setProperty("clef", clef.toString())
        p.setProperty("anacrusis", anacrusis.toString())
        lastWav?.let { p.setProperty("lastWav", it) }
        lastMidi?.let { p.setProperty("lastMidi", it) }
        lastXml?.let { p.setProperty("lastXml", it) }
        try {
            file.parentFile?.mkdirs()
            // Atomic: write to a temp file, then rename over the target so a
            // crash mid-write never leaves a truncated prefs file.
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().use { p.store(it, "v2m preferences") }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            e.printStackTrace() // prefs are best-effort; the app keeps working
        }
    }
}
