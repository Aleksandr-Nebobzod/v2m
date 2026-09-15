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

/** Активная вкладка «Звучания» по умолчанию (билд #48): пользователь
 *  возвращается к той вкладке, что выбрал (хранится в prefs); при первом
 *  запуске — «Спектр» (первая, спектрограмма доступна сразу после загрузки
 *  материала). Индексы: 0 = Спектр, 1 = Кванты, 2 = Тоны, 3 = ABC. */
internal const val NOTES_TAB_DEFAULT = 0

/** Префикс ключей развёрнутости секций панели в prefs (билд #52, п.6
 *  приёмки #51): `section.<id>` = true/false; id секции задаёт App.kt
 *  (Collapsible). Секция без записи открывается по своему дефолту. */
internal const val SECTION_PREFIX = "section."

/** Громкость WAV-воспроизведения (☰ → «Слушать», билд #53, п.1 приёмки #52):
 *  регистр слайдера 0..[WAV_VOLUME_MAX] = 0..25 % усиления записи — полезный
 *  диапазон по замечанию А.М. №52 («0-200 % не очень полезно»). Усиление
 *  WavPlayer.volume = регистр / [WAV_VOLUME_DIVISOR] (100 → 0,25).
 *  До билда #53 в prefs хранились проценты усиления 0..200; файлы без маркера
 *  [WAV_VOLUME_SCALE_KEY] мигрируются однократно: старое значение × 4
 *  (0,15 → 60) с клампом в верхнюю границу. Единое место определения —
 *  App.kt (слайдер) берёт границы отсюда. */
internal const val WAV_VOLUME_MAX = 100
internal const val WAV_VOLUME_DIVISOR = 400f
internal const val WAV_VOLUME_SCALE_KEY = "wavVolumeScale"
internal const val WAV_VOLUME_SCALE = 2

/** Громкость WAV из значения в prefs в регистр слайдера: до билда #53
 *  хранились проценты усиления 0..200 (scale < [WAV_VOLUME_SCALE]) —
 *  перевод × 4 (0,15 → 60) с клампом; новые значения берутся как есть.
 *  Используется load() и самотестом. */
internal fun wavVolumeFromStored(stored: Int, scale: Int): Int =
    if (scale >= WAV_VOLUME_SCALE) stored.coerceIn(0, WAV_VOLUME_MAX)
    else (stored * 4).coerceIn(0, WAV_VOLUME_MAX)

/** «Сглаживание» из prefs: только нечётные окна; 1 = выкл (дефолт с билда
 *  #54, п.2 ответа А.М.; сохранённое значение проходит как есть). */
internal fun smoothingFromStored(stored: Int): Int = (stored or 1).coerceIn(1, 15)

/** «Стабильность питча» из prefs: только нечётные 1..7; 1 = выкл (билд #54). */
internal fun pitchMedianFromStored(stored: Int): Int = (stored or 1).coerceIn(1, 7)

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
        val smoothingWindow: Int, // median filter width, odd 1..15, frames; 1 = off (билд #52, дефолт с #54)
        val pitchMedianWindow: Int, // pitch stability: odd 1..7 notes; 1 = off (билд #54)
        val instrument: Int?, // 1..128 (GM); null = never stored — use params.program + 1
        val clef: Int, // 0 = G (treble), 1 = F (bass); MusicXML export
        val anacrusis: Int, // partial first measure of N eighth notes, 0..8 (Р5)
        val listenExternal: Boolean, // «Слушать»: false = in-app player, true = OS MIDI app
        val midiVolume: Int, // ☰-меню «Слушать»: выходная громкость MIDI, %, 0..127 (билд #51)
        val wavVolume: Int, // ☰-меню «Слушать»: громкость WAV-воспроизведения, регистр 0..100 = 0..25 % (билд #53)
        val sections: Map<String, Boolean>, // развёрнутость секций панели (билд #52), см. SECTION_PREFIX
        val darkTheme: Boolean, // ☰-меню «Вид»: тёмная тема
        val showAbc: Boolean, // ☰-меню «Вид»: показывать ABC-вкладку (билд #35)
        val notesTab: Int, // активная вкладка «Звучания» (билд #48): 0..3, см. NOTES_TAB_DEFAULT
        val gamma: String, // ☰-меню «Гистограмма»: гамма спектрограммы (билд #49), см. Gamma.id
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
        // Громкость WAV (билд #53): файлы старой шкалы (проценты усиления
        // 0..200) переводятся в регистр 0..100 однократно — × 4 с клампом.
        val wavScale = i(WAV_VOLUME_SCALE_KEY, 1, 1, WAV_VOLUME_SCALE)
        val wavStored = i("wavVolume", WAV_VOLUME_MAX, 0,
            if (wavScale >= WAV_VOLUME_SCALE) WAV_VOLUME_MAX else 200)
        return Loaded(
            params = paramsFromProps(p),
            keySel = i("keySel", 0, 0, 24), // legacy "detectKey" property is ignored
            // Дефолт «выкл» (1) с билда #54, п.2 ответа А.М.: сохранённое
            // значение не мигрируется
            smoothingWindow = smoothingFromStored(i("smoothingWindow", 1, 1, 15)),
            pitchMedianWindow = pitchMedianFromStored(i("pitchMedianWindow", 1, 1, 7)),
            instrument = p.getProperty("instrument")?.toIntOrNull()?.coerceIn(1, 128),
            clef = i("clef", 0, 0, 1),
            anacrusis = i("anacrusis", 0, 0, 8),
            listenExternal = b("listenExternal", false),
            // 100 % — заводская громкость синтезатора (см. MidiPlayer.setVolume)
            midiVolume = i("midiVolume", 100, 0, 127),
            // Регистр 0..100 = 0..25 % усиления (100 → WavPlayer.volume = 0.25)
            wavVolume = wavVolumeFromStored(wavStored, wavScale),
            sections = p.stringPropertyNames()
                .filter { it.startsWith(SECTION_PREFIX) }
                .associate { it.removePrefix(SECTION_PREFIX) to b(it, true) },
            darkTheme = b("darkTheme", false),
            showAbc = b("showAbc", true),
            notesTab = i("notesTab", NOTES_TAB_DEFAULT, 0, 3),
            gamma = Gamma.byId(p.getProperty("gamma")).id,
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
            // «Обработка» (билд #50): крайние значения = эффект выключен
            gateDb = f("gateDb", def.gateDb, -80f, 0f),
            lowCutHz = f("lowCutHz", def.lowCutHz, 20f, 10000f),
            highCutHz = f("highCutHz", def.highCutHz, 20f, 10000f),
            expComp = f("expComp", def.expComp, -100f, 100f),
        )
    }

    /** Все ключи Params (плюс keySel/smoothingWindow/pitchMedianWindow) в
     *  properties-строках. Единое место определения полей Params (запись):
     *  используется save() и пресетами (дифф от дефолтов). */
    internal fun paramsToProps(params: V2mEngine.Params, keySel: Int, smoothingWindow: Int,
                               pitchMedianWindow: Int): Properties {
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
        p.setProperty("gateDb", params.gateDb.toString())
        p.setProperty("lowCutHz", params.lowCutHz.toString())
        p.setProperty("highCutHz", params.highCutHz.toString())
        p.setProperty("expComp", params.expComp.toString())
        p.setProperty("keySel", keySel.toString())
        p.setProperty("smoothingWindow", smoothingWindow.toString())
        p.setProperty("pitchMedianWindow", pitchMedianWindow.toString())
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
        pitchMedianWindow: Int,
        instrument: Int,
        clef: Int,
        anacrusis: Int,
        listenExternal: Boolean,
        midiVolume: Int,
        wavVolume: Int,
        sections: Map<String, Boolean>,
        darkTheme: Boolean,
        showAbc: Boolean,
        notesTab: Int,
        gamma: String,
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
        val p = paramsToProps(params, keySel, smoothingWindow, pitchMedianWindow)
        p.setProperty("instrument", instrument.toString())
        p.setProperty("clef", clef.toString())
        p.setProperty("anacrusis", anacrusis.toString())
        p.setProperty("listenExternal", listenExternal.toString())
        p.setProperty("midiVolume", midiVolume.toString())
        p.setProperty("wavVolume", wavVolume.toString())
        p.setProperty(WAV_VOLUME_SCALE_KEY, WAV_VOLUME_SCALE.toString())
        // Развёрнутость секций панели (билд #52, п.6 приёмки #51): полный
        // набор из состояния GUI — иначе неизвестные save() секции терялись бы
        for ((id, open) in sections) {
            p.setProperty(SECTION_PREFIX + id, open.toString())
        }
        p.setProperty("darkTheme", darkTheme.toString())
        p.setProperty("showAbc", showAbc.toString())
        p.setProperty("notesTab", notesTab.toString())
        p.setProperty("gamma", gamma)
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
