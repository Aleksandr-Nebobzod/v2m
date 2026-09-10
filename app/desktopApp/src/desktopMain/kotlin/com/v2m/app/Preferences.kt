package com.v2m.app

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/** Нижние границы «Ритмики» (замечание А.М. 2026-09-06): мин. длина ноты
 *  от 99 мс, тремоло от 50 мс — ограничение разрешения демонстрации
 *  (гистограмма не показывает микро-детали). Движок считает кадрами
 *  (1 кадр = 256 сэмплов @ 22050 Гц ≈ [FRAME_MS] мс), поэтому границы —
 *  первые кадры не короче замечания: 99 мс → 9 кадров (≈ 104 мс),
 *  50 мс → 5 кадров (≈ 58 мс). Клампы paramsFromProps ниже и границы
 *  ParamMs (App.kt) ссылаются на эти константы — единое место определения. */
internal const val MIN_NOTE_LEN_FRAMES = 9
internal const val MIN_ENERGY_TOL_FRAMES = 5
internal const val FRAME_MS = 11.61f

/** Полный фортепианный диапазон (88 клавиш A0..C8, билд #46) — границы
 *  фильтра нот по умолчанию (движки в ☰-меню «Гистограмма»). Единое место
 *  определения: дефолты prefs и valueRange слайдера (App.kt). */
internal const val PITCH_LO_DEFAULT = 21 // A0
internal const val PITCH_HI_DEFAULT = 108 // C8

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
        val listenExternal: Boolean, // «Слушать»: false = in-app player, true = OS MIDI app
        val darkTheme: Boolean, // ☰-меню «Вид»: тёмная тема
        val showAbc: Boolean, // ☰-меню «Вид»: показывать ABC-вкладку (билд #35)
        val exportFmt: String, // кнопка «Экспорт» низа: последний формат ("mid"/"mid+ctx"/"musicxml"/"abc")
        val author: String, // ☰-меню: автор (метаданные файла признаков, билд #38; пусто = не указан)
        val presetName: String?, // последний применённый пресет (билд #38, замечание «д»: имя видно при старте)
        val tScale: Float, // гистограмма: сколько секунд музыки видно в окне канвы, 1..10 (3.0)
        val pitchLo: Int, // фильтр нот (билд #46): нижняя граница питча, 0..127 (A0)
        val pitchHi: Int, // фильтр нот (билд #46): верхняя граница питча, 0..127 (C8)
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
        return Loaded(
            params = paramsFromProps(p),
            keySel = i("keySel", 0, 0, 24), // legacy "detectKey" property is ignored
            smoothingWindow = (i("smoothingWindow", 5, 3, 15) or 1).coerceIn(3, 15),
            instrument = p.getProperty("instrument")?.toIntOrNull()?.coerceIn(1, 128),
            clef = i("clef", 0, 0, 1),
            anacrusis = i("anacrusis", 0, 0, 8),
            listenExternal = b("listenExternal", false),
            darkTheme = b("darkTheme", false),
            showAbc = b("showAbc", true),
            exportFmt = p.getProperty("exportFmt")?.takeIf { it in EXPORT_FORMATS } ?: "mid",
            author = p.getProperty("author") ?: "",
            presetName = p.getProperty("presetName")?.takeIf { it.isNotEmpty() },
            tScale = f("tScale", 3f, 1f, 10f),
            // Фильтр нот (билд #46): хранится как есть, но с инвариантом
            // lo <= hi — при ручной правке файла границы не путаются местами
            pitchLo = i("pitchLo", PITCH_LO_DEFAULT, 0, 127).coerceAtMost(i("pitchHi", PITCH_HI_DEFAULT, 0, 127)),
            pitchHi = i("pitchHi", PITCH_HI_DEFAULT, 0, 127).coerceAtLeast(i("pitchLo", PITCH_LO_DEFAULT, 0, 127)),
            lastWav = p.getProperty("lastWav"),
            lastMidi = p.getProperty("lastMidi"),
            lastXml = p.getProperty("lastXml"),
        )
    }

    /** Params из properties-строк: отсутствующие ключи — нативные дефолты
     *  движка, значения клампятся. Единое место определения полей Params
     *  (считывание): используется load() и применением пресетов. */
    internal fun paramsFromProps(p: Properties): V2mEngine.Params {
        fun f(k: String, d: Float, lo: Float = 0f, hi: Float = 1f) =
            (p.getProperty(k)?.toFloatOrNull() ?: d).coerceIn(lo, hi)
        fun i(k: String, d: Int, lo: Int = 0, hi: Int = Int.MAX_VALUE) =
            (p.getProperty(k)?.toIntOrNull() ?: d).coerceIn(lo, hi)
        fun b(k: String, d: Boolean) = p.getProperty(k)?.toBooleanStrictOrNull() ?: d
        val def = V2mEngine.Params.defaults()
        return V2mEngine.Params(
            onsetThreshold = f("onsetThreshold", def.onsetThreshold),
            frameThreshold = f("frameThreshold", def.frameThreshold),
            minNoteLen = i("minNoteLen", def.minNoteLen, MIN_NOTE_LEN_FRAMES, 100),
            energyTol = i("energyTol", def.energyTol, MIN_ENERGY_TOL_FRAMES, 100),
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
        )
    }

    /** Все ключи Params (плюс keySel/smoothingWindow) в properties-строках.
     *  Единое место определения полей Params (запись): используется save()
     *  и пресетами (дифф от дефолтов). */
    internal fun paramsToProps(params: V2mEngine.Params, keySel: Int, smoothingWindow: Int): Properties {
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
        return p
    }

    /** Форматы экспорта — типы в системном диалоге (билд #35; «mid+ctx» —
     *  .mid и рядом файл кадровых признаков, билд #38, замечание «в»).
     *  Единое место определения. */
    val EXPORT_FORMATS = listOf("mid", "mid+ctx", "musicxml", "abc")

    /** Расширение файла для формата [fmt]: «mid+ctx» пишет .mid — признаки
     *  отдельным файлом <имя>.frames.json рядом. */
    fun exportExt(fmt: String): String = fmt.removeSuffix("+ctx")

    fun save(
        params: V2mEngine.Params,
        keySel: Int,
        smoothingWindow: Int,
        instrument: Int,
        clef: Int,
        anacrusis: Int,
        listenExternal: Boolean,
        darkTheme: Boolean,
        showAbc: Boolean,
        exportFmt: String,
        author: String,
        presetName: String?,
        tScale: Float,
        pitchLo: Int,
        pitchHi: Int,
        lastWav: String?,
        lastMidi: String?,
        lastXml: String?,
    ) {
        val p = paramsToProps(params, keySel, smoothingWindow)
        p.setProperty("instrument", instrument.toString())
        p.setProperty("clef", clef.toString())
        p.setProperty("anacrusis", anacrusis.toString())
        p.setProperty("listenExternal", listenExternal.toString())
        p.setProperty("darkTheme", darkTheme.toString())
        p.setProperty("showAbc", showAbc.toString())
        p.setProperty("exportFmt", exportFmt)
        p.setProperty("author", author)
        presetName?.let { p.setProperty("presetName", it) }
        p.setProperty("tScale", tScale.toString())
        p.setProperty("pitchLo", pitchLo.toString())
        p.setProperty("pitchHi", pitchHi.toString())
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
