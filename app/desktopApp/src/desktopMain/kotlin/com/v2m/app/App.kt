package com.v2m.app

import java.util.Locale
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2m.app.resources.Res
import com.v2m.app.resources.metronome
import com.v2m.app.resources.music_note_2
import com.v2m.app.resources.play
import com.v2m.app.resources.stop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.v2m.app.resources.menu
import java.awt.Desktop
import java.awt.Desktop.Action
import java.awt.FileDialog
import java.awt.Frame
import java.net.URI
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

/** One transcription result (added to the version list on each run).
 *  [midiIn]/[notesIn]/[reportIn] — «Вход» (замечание 8): повторный прогон
 *  с нейтральной мелодикой, результат обработки ритмических параметров;
 *  null, когда мелодика уже нейтральна (Вход = Выход). [muted] (билд #35,
 *  п.4) — заглушенные [X]-кнопкой ноты как noteKeyOf(startTick, pitch):
 *  не звучат, из экспортов исключаются, на гистограмме — полоска; правка
 *  создаёт новую Version (откат — выбор предыдущей). */
data class Version(
    val midi: ByteArray,
    val notes: List<MidiNote>,
    val report: String,
    val wavName: String,
    val params: V2mEngine.Params,
    val midiIn: ByteArray? = null,
    val notesIn: List<MidiNote>? = null,
    val reportIn: String? = null,
    val muted: Set<Long> = emptySet(),
    val framesJson: String? = null, // сводные кадровые признаки прогона (билд #38: всегда)
) {
    /** Ноты «Входа» для показа: прогон «Вход» либо (он не делался) финал. */
    val notesForInput: List<MidiNote> get() = notesIn ?: notes
    val reportForInput: String get() = reportIn ?: report
}

/** Мелодическая группа движка в нейтральном состоянии? Тогда повторный
 *  прогон «Вход» не нужен — он дал бы те же ноты, что финальный. */
private fun melodyNeutral(p: V2mEngine.Params): Boolean =
    !p.useMelodiaTrick && !p.includePitchBends && p.harmonizeMerge == 0 &&
        p.minBendBins == 0 && p.globalShift == 0f && p.modeSnap == 0f

/** Те же параметры, но мелодика нейтральна: сдвиг/лад/слияние/колоратура
 *  выключены (не вмешиваться), мелодический проход и бенды отключены. */
private fun neutralMelody(p: V2mEngine.Params): V2mEngine.Params = p.copy(
    useMelodiaTrick = false, includePitchBends = false, harmonizeMerge = 0,
    minBendBins = 0, globalShift = 0f, modeSnap = 0f,
)

/** Long-press help: English name (bold), purpose (plain), examples (italic). */
data class ParamHelp(val english: String, val purpose: String, val examples: String)

/** Label with long-press popup (English bold, purpose plain, examples italic). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ParamLabel(text: String, helpKey: String) {
    val h = Strings.HELP[helpKey]
    if (h == null) {
        Text(text, style = MaterialTheme.typography.body2)
        return
    }
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            text,
            style = MaterialTheme.typography.body2,
            modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { open = true }),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.padding(12.dp).widthIn(max = 360.dp)) {
                Text(h.english, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.body2)
                Text(h.purpose, style = MaterialTheme.typography.body2)
                Text("например: ${h.examples}", fontStyle = FontStyle.Italic, style = MaterialTheme.typography.body2)
            }
        }
    }
}

@Composable
private fun Collapsible(title: String, defaultOpen: Boolean = false, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(defaultOpen) }
    Column {
        TextButton(onClick = { open = !open }, contentPadding = PaddingValues(0.dp)) {
            Text((if (open) "▾ " else "▸ ") + title, style = MaterialTheme.typography.subtitle1)
        }
        AnimatedVisibility(open) { content() }
    }
}

@Composable
@OptIn(ExperimentalMaterialApi::class) // RangeSlider (билд #46)
fun App() {
    val prefs = remember { Preferences.load() }
    var darkTheme by remember { mutableStateOf(prefs.darkTheme) } // ☰-меню «Вид»: тёмная тема
    MaterialTheme(colors = if (darkTheme) darkColors() else lightColors()) {
        var params by remember { mutableStateOf(prefs.params) }
        var wavFile by remember { mutableStateOf<File?>(null) }
        // Длительность загруженного внешнего файла (из заголовка WAV) —
        // таймер в покое, когда записи нет (замечание «б» приёмки #44)
        var wavDurSec by remember { mutableStateOf<Int?>(null) }
        // Р14 — запись с микрофона: свежая запись — текущий вход транскрипции
        // (в приоритете над выбранным файлом); [Сохранить] фиксирует копию на диск.
        var rec by remember { mutableStateOf<AudioCapture.Result?>(null) }
        var recName by remember { mutableStateOf<String?>(null) } // автоимя «rec_…wav»
        var recSaved by remember { mutableStateOf(false) }
        var recPhase by remember { mutableStateOf(RecPhase.Idle) }
        var recCountdown by remember { mutableStateOf(3) } // цифра отсчёта (таймер «−0:0N»)
        var recElapsed by remember { mutableStateOf(0) } // секунды записи (таймер «m:ss», билд #40)
        var meterLevel by remember { mutableStateOf(0f) } // полоса уровня: сглаженный уровень источника
        var capture by remember { mutableStateOf<AudioCapture?>(null) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var keySel by remember { mutableStateOf(prefs.keySel) } // 0 = авто, 1..24 (см. Key.kt)
        var smoothingWindow by remember { mutableStateOf(prefs.smoothingWindow) } // нечётное 3..15; UI-стаб
        var instrument by remember { mutableStateOf(prefs.instrument ?: (prefs.params.program + 1).coerceIn(1, 128)) } // 1..128 (GM)
        var clef by remember { mutableStateOf(prefs.clef) } // 0 = G (скрипичный), 1 = F (басовый); экспорт MusicXML
        var anacrusis by remember { mutableStateOf(prefs.anacrusis) } // затакт: неполный первый такт из N восьмых, 0..8, 0 = выкл (Р5)
        var listenExternal by remember { mutableStateOf(prefs.listenExternal) } // «Слушать»: false = встроенный плеер, true = внешняя программа
        // Final tempo/size: export-only values (applied to the MIDI on
        // listen/save, never passed to the engine). An explicit edit
        // (override) wins, otherwise the last auto-detected value is shown
        // ("авто" before the first transcription).
        var finalTempoOverride by remember { mutableStateOf<Double?>(null) }
        var finalTempoText by remember { mutableStateOf(Strings.auto) }
        var finalTempoDetected by remember { mutableStateOf(Strings.auto) }
        var finalSizeOverride by remember { mutableStateOf<Pair<Int, Int>?>(null) }
        var finalSizeDetected by remember { mutableStateOf(Strings.auto) }
        var lastWavDir by remember { mutableStateOf(prefs.lastWav) }
        var lastMidiDir by remember { mutableStateOf(prefs.lastMidi) }
        var lastXmlDir by remember { mutableStateOf(prefs.lastXml) }
        var versions by remember { mutableStateOf(listOf<Version>()) }
        var selected by remember { mutableStateOf(0) }
        var playing by remember { mutableStateOf(false) } // встроенный MIDI-плеер звучит (кнопка «Слушать»)
        // Воспроизведение записи (п.1 приёмки #43): ▶ справа от таймера —
        // WavPlayer; playPosSec — текущая позиция любого воспроизведения
        // (п.5: индикатор от начала + полоска канваса), -1 = ничего не играет
        var recPlaying by remember { mutableStateOf(false) }
        var playPosSec by remember { mutableStateOf(-1f) }
        // «Звучание»: 0 = «Кванты» (ритмика, Вход), 1 = «Тоны» (мелодика,
        // Выход), 2 = ABC (замечание 8; билд #35, п.3 — переименованы)
        var notesTab by remember { mutableStateOf(if (prefs.showAbc) 2 else 1) }
        var showAbc by remember { mutableStateOf(prefs.showAbc) } // ☰-меню «Вид»: «ABC-notation» (билд #35, п.2)
        var exportFmt by remember { mutableStateOf(prefs.exportFmt) } // «Экспорт» низа: последний формат (п.6)
        // ☰-меню «Файл признаков» (билд #38, замечание «б»): автор записи —
        // попадает в «meta.author» файла признаков прогона (пусто = не указан)
        var author by remember { mutableStateOf(prefs.author) }
        var selectedKey by remember { mutableStateOf<Long?>(null) } // выделенная нота (п.4): noteKeyOf — рамка на «Тонах»
        var tScale by remember { mutableStateOf(prefs.tScale) } // гистограмма: секунд видно в окне канвы, 1..10 (3.0)
        // Фильтр нот (билд #46, замечание «б» приёмки #45): границы питча,
        // ноты вне которых скрыты на «Тонах» и в ABC и не экспортируются
        // («Кванты» — Вход — не фильтруются)
        var pitchLo by remember { mutableStateOf(prefs.pitchLo) }
        var pitchHi by remember { mutableStateOf(prefs.pitchHi) }
        // «Пресет:» наименование — последний применённый/сохранённый пресет
        // виден при старте (билд #38, замечание «д»: пресет был, но не
        // отображался — поле не загружалось); для сохранения — подставляется
        // при применении
        var presetNameText by remember { mutableStateOf(prefs.presetName ?: "") }
        // Пользовательские пресеты — data-storage паттерн: desktop = файл в
        // ~/.v2m рядом с prefs.properties (см. PresetStore), единый источник
        // имен фабричных — код (FACTORY_PRESETS)
        val presetStore = remember {
            PresetStore(File(System.getProperty("user.home"), ".v2m" + File.separator + "presets.properties"))
        }
        val scope = rememberCoroutineScope()

        val current: Version? = versions.getOrNull(selected)

        fun savePrefs() {
            Preferences.save(params, keySel, smoothingWindow, instrument, clef, anacrusis,
                listenExternal, darkTheme, showAbc, exportFmt, author,
                presetNameText.trim().takeIf { it.isNotEmpty() }, // пусто = пресет не хранится
                tScale, pitchLo, pitchHi, lastWavDir, lastMidiDir, lastXmlDir)
        }

        /** Применить пресет (замечание 2): дефолты движка + диффы пресета;
         *  instrument (program) остаётся как выбран в «Экспорте» — единый
         *  источник инструмента (params.program может быть устаревшим). */
        fun applyPreset(p: Preset) {
            params = presetParams(p, instrument - 1)
            keySel = presetKeySel(p)
            smoothingWindow = presetSmoothing(p)
            presetNameText = p.name
            savePrefs() // имя применённого пресета — сразу в prefs (замечание «д»)
        }

        /** Все доступные пресеты для списка «Открыть»: пользовательские
         *  (перекрывают фабричные с тем же именем — «перезапись имени») +
         *  остальные фабричные. */
        fun presetChoices(): List<Preset> {
            val user = presetStore.list()
            val userNames = user.map { it.name }.toSet()
            return user + FACTORY_PRESETS.filterNot { it.name in userNames }
        }

        /** Сохранить текущие настройки как пресет с именем из поля
         *  наименования (дифф от дефолтов движка). */
        fun savePreset() {
            val name = presetNameText.trim()
            if (name.isEmpty()) {
                error = Strings.presetNoName
                return
            }
            presetStore.save(Preset(name, diffFromDefaults(params, keySel, smoothingWindow)))
            presetNameText = name
            savePrefs() // имя сохранённого пресета — сразу в prefs (замечание «д»)
        }

        /** Key of the selected version for display/export: the explicit
         *  «Тональность» choice when set, otherwise the auto-detected one. */
        fun effectiveKey(report: String): KeyInfo? =
            if (keySel == 0) parseKeyFromReport(report) else keyFromSelection(keySel)

        /** MIDI bytes as exported: normalized note events (no repeated
         *  attacks / stray releases — MuseScore misreads the tempo of such
         *  files), instrument patch + final tempo/size/key overrides.
         *  [muted] (билд #35) — заглушенные [X] ноты: не звучат и не
         *  экспортируются («ноты нулевой длины не экспортируются», п.6);
         *  фильтр [pitchLo..pitchHi] (билд #46) применяется здесь же —
         *  ед. точка: прослушивание и все форматы экспорта консистентны. */
        fun exportMidi(midi: ByteArray, key: KeyInfo?, muted: Set<Long> = emptySet()): ByteArray =
            finalizeMidi(normalizeMidi(patchProgram(midi, instrument), muted, pitchLo..pitchHi),
                finalTempoOverride, finalSizeOverride, key)

        DisposableEffect(Unit) {
            onDispose { savePrefs() }
        }

        // Persist on every change so an abrupt exit (crash, kill) loses nothing.
        LaunchedEffect(params, keySel, smoothingWindow, instrument, clef, anacrusis, listenExternal, darkTheme, showAbc, exportFmt, author, tScale, pitchLo, pitchHi) { savePrefs() }

        fun chooseWav() {
            val dlg = FileDialog(null as Frame?, Strings.loadTitle, FileDialog.LOAD)
            lastWavDir?.let { dlg.directory = it }
            dlg.isVisible = true
            val f = dlg.files.firstOrNull() ?: return
            wavFile = f
            wavDurSec = wavDurationSec(f) // для таймера в покое (замечание «б» приёмки #44)
            rec = null; recName = null; recSaved = false // явный выбор файла отменяет непосохранённую запись
            WavPlayer.stop(); recPlaying = false // и её воспроизведение (п.1 приёмки #43)
            error = null
            lastWavDir = dlg.directory
            savePrefs()
        }

        // Автоимя записи — присваивается в onRecClick при нажатии «Запись»
        // и только если имя отсутствует (замечание 3 приёмки #43; после
        // успешной записи не меняется). Маска: без «rec_», века и секунд,
        // суффикс «_v2m» — «260909_1315_v2m.wav». Урок #42: буквы вне кавычек
        // SimpleDateFormat читает как символы паттерна («illegal pattern
        // character 'r'» — падение в #41): текстовые части — строго в
        // кавычках литерала. Объявлена до onRecClick (локальные fun
        // без forward-ссылок — ошибка компиляции, если вызвать раньше).
        fun autoRecName(): String =
            java.text.SimpleDateFormat("yyMMdd'_'HHmm'_v2m.wav'", Locale.ROOT).format(java.util.Date())

        // Р14: клик по кнопке записи — фазы Idle → Countdown → Recording.
        // Билд #41: debug-метки; страховка — если фаза Recording, а захват
        // ещё не начался (capture == null), клик сбрасывает фазу в Idle
        // (иначе она застревала в Recording навсегда).
        fun onRecClick() {
            when (recPhase) {
                RecPhase.Idle -> {
                    error = null; recPhase = RecPhase.Countdown; recCountdown = 3
                    // Билд #44 (замечание 3а приёмки #43): имя — при нажатии
                    // «Запись» и только если имя отсутствует (есть данные —
                    // имя не менять; после успеха записи тоже не трогать).
                    // Билд #45 (замечание «г»): поле стало изменяемым — стёртое
                    // в нём имя (пустая строка) тоже заменяется автоименем.
                    if (recName.isNullOrBlank()) recName = autoRecName()
                    // Прослушка записи умолкает: её звук попал бы в микрофон
                    WavPlayer.stop(); recPlaying = false
                    Log.d("rec", "клик: Idle → Countdown (отсчёт 3)")
                }
                RecPhase.Countdown -> {
                    recPhase = RecPhase.Idle
                    Log.d("rec", "клик: Countdown → Idle (отмена отсчёта)")
                }
                RecPhase.Recording -> {
                    val c = capture
                    if (c == null) {
                        recPhase = RecPhase.Idle
                        Log.d("rec", "клик: стоп — захват ещё не начат, фаза → Idle")
                    } else {
                        c.stop()
                        Log.d("rec", "клик: стоп — нативный стоп-флаг (read вернётся ≤150 мс)")
                    }
                }
            }
        }

        fun saveRecording() {
            val r = rec ?: return
            val chooser = JFileChooser(lastWavDir ?: System.getProperty("user.home"))
            chooser.dialogTitle = Strings.saveRecTitle
            chooser.isAcceptAllFileFilterUsed = false
            chooser.fileFilter = FileNameExtensionFilter(".wav", "wav")
            // Билд #45: имя могло быть стёрто в поле (recName = "") — пустое
            // имя не подставлять в диалог (File("") бессмысленен)
            chooser.selectedFile = File(recName?.takeIf { it.isNotBlank() } ?: "rec.wav")
            if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return
            var f = chooser.selectedFile
            if (!f.name.lowercase(Locale.ROOT).endsWith(".wav")) f = File(f.parentFile, f.name + ".wav")
            lastWavDir = f.parentFile?.path
            try {
                writeWavMono(f, r.pcm, r.sr)
                recSaved = true
                savePrefs()
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
        }

        // Р14: отсчёт 3..2..1 — отдельным эффектом от захвата (билд #41:
        // в #40 смена recPhase на Recording ВНУТРИ эффекта записи
        // перезапускала LaunchedEffect(recPhase) — Compose отменял корутину
        // на ближайшей приостановке (withContext): захват не стартовал
        // (полоса: уровень 0) либо фаза навсегда застревала в Recording
        // (кнопка «стоп» не работала). Здесь фаза меняется последним
        // действием; смена фазы нажатием отменяет отсчёт.
        LaunchedEffect(recPhase) {
            if (recPhase != RecPhase.Countdown) return@LaunchedEffect
            Log.d("rec", "отсчёт: 3..2..1")
            for (n in 3 downTo 1) {
                recCountdown = n
                delay(1000)
                if (recPhase != RecPhase.Countdown) {
                    Log.d("rec", "отсчёт: отменён")
                    return@LaunchedEffect
                }
            }
            Log.d("rec", "отсчёт: кончился — фаза → Recording (эффект захвата стартует)")
            recPhase = RecPhase.Recording
        }

        // Р14: захват (до нажатия «стоп» или 60 с). Отдельный эффект: живёт
        // всю запись, recPhase меняет только по завершении (finally) — ключ
        // эффекта не меняется изнутри, отмены нет. При отмене эффекта извне
        // (сброс фазы кликом) finally закрывает линию.
        LaunchedEffect(recPhase) {
            if (recPhase != RecPhase.Recording) return@LaunchedEffect
            val mic = NativeCapture()
            try {
                val problem = withContext(Dispatchers.IO) { mic.open() }
                if (problem != null) {
                    Log.d("rec", "open не удался: $problem")
                    error = Strings.recUnavailable + if (problem.isBlank()) "" else " ($problem)"
                    return@LaunchedEffect
                }
                Log.d("rec", "open ok — старт record()")
                capture = mic
                val result = withContext(Dispatchers.IO) { mic.record(60_000L) }
                if (result != null) {
                    Log.d("rec", "запись готова: ${result.pcm.size} сэмплов ≈ " +
                        "${result.pcm.size / result.sr} с")
                    rec = result
                    // Имя уже назначено при старте записи (замечание 3а
                    // приёмки #43) — после успеха не меняется.
                    recSaved = false
                } else {
                    Log.d("rec", "запись: данных нет (result == null)")
                }
            } finally {
                if (capture === mic) capture = null
                mic.stop() // любой исход (стоп/ошибка/отмена) — линия закрыта
                if (recPhase == RecPhase.Recording) {
                    recPhase = RecPhase.Idle
                    Log.d("rec", "захват завершён — фаза → Idle")
                }
            }
        }

        // Таймер записи (билд #40): секунды записи вверх от 0:00 (текст —
        // из recPhase/recCountdown/recElapsed); автостоп на 60-й секунде
        // (лимит в record() остаётся страховкой). Запись стартует при
        // показе «0:00» — сразу после отсчёта −0:03..−0:01.
        LaunchedEffect(recPhase) {
            if (recPhase != RecPhase.Recording) return@LaunchedEffect
            recElapsed = 0
            AudioLevel.mic = 0f // свежая запись — уровень с нуля
            while (recPhase == RecPhase.Recording) {
                delay(1000)
                recElapsed++
                if (recElapsed >= 60) capture?.stop()
            }
        }

        // Позиция воспроизведения (п.5 приёмки #43): пока играет запись
        // (WavPlayer) или версия (MidiPlayer) — публикует секунды от начала
        // в playPosSec 10 раз/с; её читают семисегментный таймер (п.5а) и
        // полоска канваса гистограммы (п.5б, NoteChart.playPosSec).
        LaunchedEffect(recPlaying, playing) {
            if (!recPlaying && !playing) {
                playPosSec = -1f
                return@LaunchedEffect
            }
            while (recPlaying || playing) {
                playPosSec = (if (recPlaying) WavPlayer.positionSec else MidiPlayer.positionSec).toFloat()
                delay(100)
            }
            playPosSec = -1f
        }

        // Полоса уровня (билд #40): сглаженный уровень источника — запись
        // (RMS микрофона), воспроизведение записи (п.1 приёмки #43: RMS из
        // WavPlayer — тоже AudioLevel.mic) или громкость нот MIDI; подъём
        // быстрый, спад медленный (VU). По окончании — плавно гаснет.
        // Debug-метка [meter] (билд #41): уровень раз в секунду — что именно
        // видит полоса (для калибровки порогов и проверки источника).
        LaunchedEffect(recPhase, playing, recPlaying) {
            var disp = 0f
            var lastLog = 0L
            while (recPhase == RecPhase.Recording || playing || recPlaying || disp > 0.004f) {
                val raw = if (recPhase == RecPhase.Recording || recPlaying) AudioLevel.mic else AudioLevel.midi
                val k = if (raw > disp) 0.45f else 0.08f
                disp += (raw - disp) * k
                meterLevel = disp
                val now = System.currentTimeMillis()
                if (disp > 0.004f && now - lastLog >= 1000) {
                    lastLog = now
                    val src = when {
                        recPhase == RecPhase.Recording -> "микрофон"
                        recPlaying -> "запись (воспроизведение)"
                        else -> "MIDI-ноты"
                    }
                    Log.d("meter", "полоса: ${"%.4f".format(Locale.ROOT, disp)} " +
                        "(${"%.1f".format(Locale.ROOT, levelDb(disp))} dBFS), источник: $src")
                }
                delay(40)
            }
            meterLevel = 0f
        }

        fun transcribe() {
            // Вход (Р14): свежая запись с микрофона — в приоритете над файлом;
            // ядро принимает PCM из памяти, файл не участвует.
            val (pcm, sr) = rec?.let { it.pcm to it.sr }
                ?: wavFile?.let { readWavMono(it) }
                ?: return
            val inputName = recName ?: wavFile?.name ?: return
            busy = true
            error = null
            params = params.copy(program = instrument - 1) // инструмент «Экспорта» — единый источник (в отчёт/JSON)
            scope.launch(Dispatchers.Default) {
                try {
                    // «Вход» (замечание 8): повторный прогон с нейтральной
                    // мелодикой — результаты обработки ритмических параметров.
                    // При нейтральной мелодике прогон не нужен: Вход = Выход.
                    val midiIn: ByteArray?
                    val notesIn: List<MidiNote>?
                    val reportIn: String?
                    if (melodyNeutral(params)) {
                        midiIn = null; notesIn = null; reportIn = null
                    } else {
                        val m = V2mEngine.transcribe(pcm, sr, neutralMelody(params))
                        midiIn = m
                        notesIn = m?.let { runCatching { parseMidiSong(it).notes }.getOrNull() }
                        reportIn = V2mEngine.lastReport()
                    }
                    // Кадровая сводка (билд #38, замечание «в»): извлекается из
                    // ядра всегда, при любых параметрах (считается из сырых
                    // тензоров, до пост-обработки); вопрос включения её в
                    // экспорт решает пользователь типом «.mid + признаки».
                    // Трек (имя файла без расширения) и автор (☰-меню) —
                    // метаданные «meta» сводки.
                    val bytes = V2mEngine.transcribe(pcm, sr, params,
                        track = inputName.removeSuffix(".wav"),
                        author = author.ifBlank { null })
                    if (bytes == null) {
                        error = Strings.transcribeFailed
                    } else {
                        val song = runCatching { parseMidiSong(bytes) }.getOrNull()
                        if (song != null) {
                            finalTempoDetected = String.format(Locale.ROOT, "%.1f", song.tempoBpm)
                            if (finalTempoOverride == null) finalTempoText = finalTempoDetected
                            finalSizeDetected = "${song.tsNum}/${song.tsDen}"
                        }
                        val v = Version(
                            midi = bytes,
                            notes = song?.notes ?: emptyList(),
                            report = V2mEngine.lastReport(),
                            wavName = inputName,
                            params = params,
                            midiIn = midiIn,
                            notesIn = notesIn,
                            reportIn = reportIn,
                            framesJson = V2mEngine.lastFramesJson().ifBlank { null },
                        )
                        versions = versions + v
                        selected = versions.size - 1
                        selectedKey = null // новая транскрипция снимает выделение (п.4)
                    }
                } catch (e: Exception) {
                    error = e.message ?: e.toString()
                } finally {
                    busy = false
                    savePrefs()
                }
            }
        }

        fun listen() {
            val v = versions.getOrNull(selected) ?: return
            error = null
            // Взаимоисключение с ▶ записи (п.1 приёмки #43): версия и запись
            // одновременно не звучат
            WavPlayer.stop(); recPlaying = false
            val midi = exportMidi(v.midi, effectiveKey(v.report), v.muted)
            if (listenExternal) { // во внешней программе: открыть файл; остановить встроенный плеер, если звучит
                MidiPlayer.stop()
                playing = false
                playExternally(midi)?.let { error = it }
                return
            }
            if (playing) { // повторное нажатие во время звучания — остановка
                MidiPlayer.stop()
                return
            }
            val err = MidiPlayer.play(midi) { playing = false }
            if (err == null) playing = true else error = err
        }

        /** ▶ источника (п.1 приёмки #43; замечание «б» приёмки #44 — и для
         *  внешнего wav): воспроизведение свежей записи (WavPlayer), а если
         *  её нет — загруженного файла (чтение с диска синхронно, как в
         *  transcribe: файлы источников малы). Повторное нажатие — стоп;
         *  [listen] умолкает, чтобы версия и запись не звучали вместе. */
        fun recListen() {
            error = null
            if (recPlaying) {
                WavPlayer.stop()
                return
            }
            val ps = rec?.let { it.pcm to it.sr }
                ?: wavFile?.let { f ->
                    try {
                        readWavMono(f)
                    } catch (e: Exception) {
                        error = e.message ?: e.toString()
                        return
                    }
                }
                ?: return
            MidiPlayer.stop() // версия не звучит поверх записи
            playing = false
            val err = WavPlayer.play(ps.first, ps.second) { recPlaying = false }
            if (err == null) recPlaying = true else error = err
        }

        /** Play one of the bundled sound samples, transformed per the
         *  acceptance (п.18): [bpm] retempos the metronome sample to the
         *  tempo slider, [key] transposes the tonica sample into the chosen
         *  key (its tempo is fixed at 180 in the file). The buttons are
         *  disabled in the «Авто» state, so at most one transform applies.
         *  Read failures are shown as [error]. */
        fun playSample(resPath: String, bpm: Double? = null, key: KeyInfo? = null) {
            error = null
            scope.launch {
                val midi = try {
                    readSampleBytes(resPath)
                } catch (e: Exception) {
                    error = e.message ?: e.toString()
                    return@launch
                }
                val prepared = when {
                    bpm != null -> rewriteSample(midi, tempoBpm = bpm)
                    key != null -> transposeSample(midi, key)
                    else -> midi
                }
                MidiPlayer.play(prepared)?.let { error = it }
            }
        }

        // Count-in clicks in the selected tempo (disabled at «Авто» = 0)
        fun playMetronome() = playSample("files/counIn.mid", bpm = params.tempoBpm.toDouble())

        // Tonica in the chosen key (disabled at «Авто» = 0), tempo 180
        fun playTriad() = playSample("files/tonica.mid", key = keyFromSelection(keySel))

        // Click on a note in «Звучание» (замечание 8): the tone itself —
        // pitch, loudness, stock 1/8 s duration, the instrument selected in
        // «Экспорт» (замечание А.М. 2026-09-06: клик-звук играл не тем
        // инструментом — params.program отставал от выбора instrument).
        // A new tone replaces any current playback (MidiPlayer.play).
        // [cents] (билд #35) — микротон ноты (% полутона): клик по столбику
        // «Тонов» звучит с её бендом, звук правки [<]/[>] — чистый тон (0).
        fun playNoteTone(pitch: Int, velocity: Int, cents: Float = 0f) {
            error = null
            val err = MidiPlayer.play(noteToneMidi(pitch, velocity, instrument - 1, cents))
            // Журнал (билд #33): что произошло по клику — тон, инструмент
            // экспорта (0..127 GM) и ошибка плеера, если есть.
            if (Log.DEBUG) {
                Log.d("tone", "играю p=$pitch v=$velocity микротон=$cents инструмент=${instrument - 1}" + (err?.let { ", ошибка: $it" } ?: ""))
            }
            err?.let { error = it }
        }

        /** Кнопка «Экспорт» низа (п.6; билд #36, п.3; билд #38): системный
         *  диалог сохранения (JFileChooser) со списком допустимых форматов —
         *  .mid / .mid + признаки / .musicxml / .abc (тип выбирает
         *  пользователь в диалоге — фильтры, замечание «г»: на самой кнопке
         *  формата нет; выбранный фильтр запоминается в prefs как exportFmt).
         *  «mid+ctx» (замечание «в»): .mid и рядом файл кадровых признаков
         *  <имя>.frames.json (сводка в прогоне всегда, файл — по выбору).
         *  Заглушенные ноты в экспорт не попадают (normalizeMidi с
         *  [v.muted]; .abc — тем же фильтром). */
        fun exportVersion(v: Version) {
            val key = effectiveKey(v.report)
            val chooser = JFileChooser((if (exportFmt == "musicxml") lastXmlDir else lastMidiDir)
                ?: System.getProperty("user.home"))
            chooser.dialogTitle = Strings.exportDialogTitle
            chooser.isAcceptAllFileFilterUsed = false
            val filters = Preferences.EXPORT_FORMATS.map { fmt ->
                FileNameExtensionFilter(Strings.EXPORT_FMT_NAMES[fmt] ?: fmt,
                    Preferences.exportExt(fmt)) to fmt
            }
            filters.forEach { (f, _) -> chooser.addChoosableFileFilter(f) }
            chooser.fileFilter = filters.firstOrNull { it.second == exportFmt }?.first ?: filters.first().first
            chooser.selectedFile = File(wavFile?.nameWithoutExtension ?: "result")
            if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return
            val fmt = filters.firstOrNull { chooser.fileFilter === it.first }?.second ?: "mid"
            val file = chooser.selectedFile ?: return
            // Без расширения в имени — дописать расширение выбранного типа
            // («mid+ctx» пишет .mid; признаки — отдельным файлом рядом)
            val path = if (file.name.contains('.')) file.absolutePath
            else file.absolutePath + "." + Preferences.exportExt(fmt)
            file.parentFile?.let { lastMidiDir = it.absolutePath; lastXmlDir = it.absolutePath }
            exportFmt = fmt
            savePrefs()
            val ok = when (fmt) {
                "mid", "mid+ctx" -> {
                    val json = buildParamsJson(v.wavName, v.params, v.report, key)
                    val mid = anacrusisMidi(exportMidi(v.midi, key, v.muted), anacrusis)
                    File(path).writeBytes(midiWithMetaTrack(mid, json))
                    // Кадровая сводка (билд #38) — рядом с сохраняемым .mid
                    // только по типу «.mid + признаки»
                    if (fmt == "mid+ctx") {
                        v.framesJson?.let { fj ->
                            val framesPath = path.removeSuffix(".mid") + ".frames.json"
                            if (!runCatching { File(framesPath).writeText(fj) }.isSuccess) {
                                error = Strings.saveFailed.format(framesPath)
                            }
                        }
                    }
                    true
                }
                "musicxml" -> V2mEngine.midiToMusicXml(
                    anacrusisMidi(exportMidi(v.midi, key, v.muted), anacrusis),
                    path, clef, key?.fifths ?: 0, anacrusis)
                "abc" -> {
                    File(path).writeText(exportAbc(
                        anacrusisMidi(exportMidi(v.midi, key, v.muted), anacrusis),
                        v.wavName, key)); true
                }
                else -> false
            }
            if (!ok) error = Strings.saveFailed.format(path)
        }

        /** Панель правки «Тоны» (п.4): шаг [deltaPitch] от кнопок [<]/[>].
         *  Сдвиг создаёт новую версию; заглушенность ноты переносится на
         *  новую высоту; выделение — на новую ноту; звук — только если
         *  нота не заглушена (заглушенная «не может звучать»). */
        fun editNote(v: Version, deltaPitch: Int) {
            val k = selectedKey ?: return
            val n = v.notes.firstOrNull { noteKeyOf(it) == k } ?: return
            // Микротон-семантика (А.М. 2026-09-06): первый шаг с микротон-
            // ноты идёт к чистой высоте ([<] на C+32% — C, [>] — C#);
            // у чистых нот оба шага хроматические.
            val d = when {
                deltaPitch < 0 && n.cents > 0.5f -> 0
                deltaPitch > 0 && n.cents < -0.5f -> 0
                else -> deltaPitch
            }
            val np = (n.pitch + d).coerceIn(0, 127)
            val midi = retuneNoteMidi(v.midi, n.startTick, n.endTick, n.pitch, n.channel, d)
            if (midi === v.midi && np == n.pitch) return // ни высота, ни микротон не изменились
            val song = runCatching { parseMidiSong(midi) }.getOrNull() ?: return
            val wasMuted = k in v.muted
            val newMuted = if (wasMuted) v.muted - k + noteKeyOf(n.startTick, np) else v.muted
            val newV = v.copy(midi = midi, notes = song.notes, muted = newMuted)
            versions = versions.map { if (it === v) newV else it }
            selectedKey = noteKeyOf(n.startTick, np)
            // Озвучка правки — чистый тон новой высоты (если не заглушена)
            if (!wasMuted) playNoteTone(np, n.velocity)
        }

        /** Кнопка [X] (п.4) — «с памятью»: заглушить выделенную ноту
         *  (длительность 0: не звучит, полоска-рамка на гистограмме,
         *  строка нулевой длины в ABC); повторное нажатие возвращает
         *  длительность и озвучивает ноту. */
        fun toggleMute(v: Version) {
            val k = selectedKey ?: return
            if (k !in v.notes.map { noteKeyOf(it) }) return
            val muted = v.muted
            if (k in muted) {
                val n = v.notes.firstOrNull { noteKeyOf(it) == k }!!
                versions = versions.map { if (it === v) v.copy(muted = muted - k) else it }
                playNoteTone(n.pitch, n.velocity, n.cents) // возврат длительности — тон для подтверждения
            } else {
                versions = versions.map { if (it === v) v.copy(muted = muted + k) else it }
            }
        }

        // Surface paints the theme background and sets the content color
        // (dark theme: text/icons switch to the light-on-dark palette).
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colors.background) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                // Scrollable content area (buttons stick to the bottom)
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                // Полоса уровня записи-воспроизведения (билд #40): 0.9 ширины
                // над строкой входного файла (строка опущена вниз); заливка
                // слева по уровню, цвет зоны (серый/салатовый/малиновый);
                // в молчании невидима. Источники: запись — RMS микрофона,
                // воспроизведение — громкость нот MIDI (см. Level.kt).
                LevelBar(meterLevel, Modifier.fillMaxWidth(0.9f).align(Alignment.CenterHorizontally))

                // Ряд записи (п.2 приёмки #43 — теперь первый): [● запись]
                // [таймер] [▶ записи]; «Выбрать/имя/Сохранить» — вторым рядом
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RecButton(recPhase, recCountdown, ::onRecClick, enabled = !busy)
                    // Таймер (билд #40): отсчёт −0:03..−0:01 до старта, при
                    // записи 0:00..1:00 вверх; при воспроизведении (п.5а
                    // приёмки #43) — позиция от начала (wav или midi);
                    // в покое — длительность источника: записи, а без неё —
                    // загруженного файла (замечание «б» приёмки #44)
                    SevenSegDisplay(
                        text = when {
                            recPhase == RecPhase.Countdown -> "-0:0$recCountdown"
                            recPhase == RecPhase.Recording -> recClock(recElapsed)
                            recPlaying || playing -> recClock(playPosSec.coerceAtLeast(0f).toInt())
                            else -> rec?.let { recClock(it.pcm.size / it.sr) }
                                ?: wavDurSec?.let { recClock(it) } ?: "0:00"
                        },
                        lit = if (recPhase == RecPhase.Idle && !recPlaying && !playing) 0.3f else 1f)
                    // ▶ источника (п.1 приёмки #43): справа от таймера; играет
                    // запись, а без неё — загруженный файл (замечание «б»
                    // приёмки #44); повторное нажатие — стоп (иконка ▢)
                    val recPlayIcon = if (recPlaying) Res.drawable.stop else Res.drawable.play
                    OutlinedButton(onClick = ::recListen,
                        enabled = (rec != null || wavFile != null) && !busy && recPhase == RecPhase.Idle,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                        modifier = Modifier.semantics {
                            contentDescription =
                                if (recPlaying) Strings.recStopListenCd else Strings.recListenCd
                        }) {
                        Icon(painterResource(recPlayIcon), contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
                // Ряд файла (п.2 приёмки #43 — второй): [Выбрать WAV] имя [Сохранить].
                // Имя — InlineField в стиле полей пресета/затакта (замечание «1»
                // приёмки #45; раньше был TextField — рамка вокруг поля): показ
                // имени выбранного файла — пока имя не задано записью (recName);
                // правка пишет recName (для [Сохранить] и транскрипта)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = ::chooseWav, enabled = !busy && recPhase == RecPhase.Idle) {
                        Text(Strings.wavButton)
                    }
                    // TextField → InlineField (билд #46): placeholder показывает
                    // «нет файла», но пустую строку не навязывает — как у затакта
                    InlineField(value = recName ?: wavFile?.name ?: "",
                        onValueChange = { recName = it },
                        modifier = Modifier.weight(1f),
                        placeholder = Strings.noFile)
                    Button(onClick = ::saveRecording,
                        enabled = rec != null && !recSaved && !busy && recPhase == RecPhase.Idle) {
                        Text(Strings.saveLabel)
                    }
                }

                // Пресеты (замечание 2): метка + [Открыть] (выпадающий список
                // фабричных и пользовательских) + поле имени (вытянуто) +
                // [Сохранить] прижат к правому краю строки (замечание А.М.
                // 2026-09-06: «поле имя и линк [Сохранить] переставить
                // местами, [Сохранить] — в конец экрана»).
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(Strings.presetLabel, style = MaterialTheme.typography.body2)
                    var presetMenuOpen by remember { mutableStateOf(false) }
                    var presetChoicesNow by remember { mutableStateOf(listOf<Preset>()) }
                    TextButton(onClick = { presetChoicesNow = presetChoices(); presetMenuOpen = true }) {
                        Text(Strings.presetOpen + " ▾", style = MaterialTheme.typography.body2)
                    }
                    DropdownMenu(expanded = presetMenuOpen, onDismissRequest = { presetMenuOpen = false }) {
                        if (presetChoicesNow.isEmpty()) {
                            DropdownMenuItem(onClick = {}) { Text(Strings.noResults, style = MaterialTheme.typography.body2) }
                        }
                        for (p in presetChoicesNow) {
                            DropdownMenuItem(onClick = { applyPreset(p); presetMenuOpen = false }) {
                                Text(p.name, style = MaterialTheme.typography.body2)
                            }
                        }
                    }
                    InlineField(value = presetNameText, onValueChange = { presetNameText = it },
                        modifier = Modifier.weight(1f).widthIn(min = 80.dp))
                    TextButton(onClick = ::savePreset) { Text(Strings.saveLabel, style = MaterialTheme.typography.body2) }
                }

                // 1. Ритмика — длительность, атака, темп (свёртываемый).
                // Замечание 3: «Квантизация» стоит перед темпом и допуском;
                // при off (0) темп и допуск недоступны — сетка выключена.
                Collapsible(Strings.secRhythm, defaultOpen = true) {
                    val quantized = params.quantize != 0
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ParamSlider(Strings.velocityLabel, params.velocityCompress, "velocityCompress") { params = params.copy(velocityCompress = it) }
                        ParamSlider(Strings.onsetLabel, params.onsetThreshold, "onsetThreshold") { params = params.copy(onsetThreshold = it) }
                        ParamSlider(Strings.frameLabel, params.frameThreshold, "frameThreshold") { params = params.copy(frameThreshold = it) }
                        ParamMs(Strings.minLenLabel, params.minNoteLen, "minNoteLen", { params = params.copy(minNoteLen = it) })
                        ParamMs(Strings.energyLabel, params.energyTol, "energyTol", { params = params.copy(energyTol = it) },
                            minMs = MIN_ENERGY_TOL_FRAMES * FRAME_MS, maxMs = 350f, maxFrames = 30)
                        ParamSelect(Strings.quantizeLabel, Strings.QUANTIZE_OPTIONS, params.quantize, "quantize") { params = params.copy(quantize = it) }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ParamRange(Strings.tempoLabel, params.tempoBpm, 24f..250f, "tempoBpm", Modifier.weight(1f),
                                enabled = quantized,
                                toValue = { r -> val v = r.roundToInt(); if (v <= 24) 0f else v.toFloat() },
                                toPosition = { v -> if (v <= 0f) 24f else v }) { params = params.copy(tempoBpm = it) }
                            // Метроном играет счёт counIn.mid только при выбранном темпе (2а: при «Авто» недоступна)
                            SoundButton(Res.drawable.metronome, ::playMetronome,
                                Modifier.size(36.dp), enabled = params.tempoBpm > 0)
                        }
                        ParamRange(Strings.toleranceLabel, params.toleranceMs, 0f..200f, "toleranceMs",
                            enabled = quantized) { params = params.copy(toleranceMs = it) }
                    }
                }

                Divider()

                // 2. Мелодика — высотная группа (свёртываемый)
                Collapsible(Strings.secMelody, defaultOpen = true) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ParamInt(Strings.smoothingLabel, smoothingWindow, 3..15, "medianFilter", steps = 5) { smoothingWindow = it }
                        ParamInt(Strings.mergeLabel, params.harmonizeMerge, 0..3, "harmonizeMerge", steps = 2) { params = params.copy(harmonizeMerge = it) }
                        // «Колоратура» — инверсия minBendBins: 0..5 в UI, в движок идёт 5 − значение
                        ParamInt(Strings.minBendLabel, (5 - params.minBendBins).coerceIn(0, 5), 0..5, "minBendBins", steps = 4) { params = params.copy(minBendBins = (5 - it).coerceIn(0, 5)) }
                        KeySelector(keySel, { keySel = it }, ::playTriad)
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            ParamSlider(Strings.shiftLabel, params.globalShift, "globalShift", Modifier.weight(1f)) { params = params.copy(globalShift = it) }
                            ParamSlider(Strings.snapLabel, params.modeSnap, "modeSnap", Modifier.weight(1f)) { params = params.copy(modeSnap = it) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            ParamCheck(Strings.melodiaLabel, params.useMelodiaTrick, "useMelodiaTrick") { params = params.copy(useMelodiaTrick = it) }
                            ParamCheck(Strings.bendsLabel, params.includePitchBends, "includePitchBends") { params = params.copy(includePitchBends = it) }
                        }
                    }
                }

                error?.let { Text(it, color = MaterialTheme.colors.error, style = MaterialTheme.typography.body2) }

                // 3. Версии — скроллер с радиокнопками (первая серая до первого
                //    результата). История прогонов: новые версии сверху — индекс
                //    версии по порядку прогона idx = size-1-i, выбранный элемент
                //    и его номер В.NN остаются привязаны к версии, а не к позиции.
                Collapsible(Strings.secVersions, defaultOpen = true) {
                    LazyColumn(Modifier.heightIn(max = 96.dp)) {
                        items(maxOf(versions.size, 1)) { i ->
                            val idx = versions.size - 1 - i
                            val v = versions.getOrNull(idx)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = v != null && idx == selected,
                                    onClick = { selected = idx; selectedKey = null }, // смена версии снимает выделение
                                    enabled = v != null,
                                )
                                Text(
                                    if (v != null) {
                                        val song = runCatching { parseMidiSong(v.midi) }.getOrNull()
                                        // The key name sits right after the note count so a
                                        // "NN% гарм." figure is read together with the mode
                                        // it was measured against (the "C# lydian" episode).
                                        val key = effectiveKey(v.report)
                                        val keyPart = key?.let { ", ${it.name}" } ?: ""
                                        val harm = harmonyPct(v.notes, key)
                                            ?.let { ", $it% гарм." } ?: ""
                                        String.format(Locale.ROOT, Strings.versionRow,
                                            idx + 1, song?.tsNum ?: 0, song?.tsDen ?: 0,
                                            song?.tempoBpm ?: 0.0, v.notes.size,
                                            keyPart, harm, v.wavName)
                                    } else {
                                        Strings.versionNoResult.format(i + 1)
                                    },
                                    style = MaterialTheme.typography.body2)
                            }
                        }
                    }
                }

                // 4. Отчёт — свёртываемый, только полезное (без Schema error),
                //    текст выделяется для копирования
                Collapsible(Strings.secReport) {
                    val v = current
                    if (v != null) {
                        val song = runCatching { parseMidiSong(v.midi) }.getOrNull()
                        val head = buildString {
                            appendLine(Strings.paramsLine.format(paramsCli(v.params)))
                            appendLine(Strings.fileLine.format(v.wavName))
                            if (song != null) {
                                appendLine(String.format(Locale.ROOT, Strings.tempoLine, song.tempoBpm, song.tsNum, song.tsDen, v.notes.size))
                            }
                            // «Тональность» — только при явном выборе (замечание 4):
                            // при «Авто» ключ автоподбора уже виден строкой
                            // «подбор лада» ниже, дублировать его не надо.
                            if (keySel != 0) {
                                val key = keyFromSelection(keySel)
                                if (key != null) {
                                    appendLine(Strings.keyLine.format(key.name, key.alterationsText))
                                }
                            }
                            // Native "mode fit:" and "tempo:" lines are shown in
                            // Russian below (Strings.modeFitLine) or dropped as
                            // duplicates — filter them out of the raw report
                            parseModeFit(v.report)?.let {
                                appendLine(String.format(Locale.ROOT, Strings.modeFitLine,
                                    it.keyName, it.pct, it.cents, it.strength))
                            }
                            // Строка-сводка кадровых признаков прогона (билд #38):
                            // паспорт записи — тесситура, уверенность, полифония,
                            // атаки, стабильность питча (из v.framesJson)
                            framesSummaryLine(v.framesJson)?.let { appendLine(it) }
                        }
                        val tail = v.report.lines()
                            .filterNot {
                                it.startsWith("mode fit:") || it.startsWith("tempo:") ||
                                    it.startsWith("global shift:")
                            }
                            .joinToString("\n")
                        SelectionContainer {
                            Text(head + (tail.ifEmpty { Strings.empty }), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.body2)
                        }
                    } else {
                        Text(Strings.noResults)
                    }
                }

                // 5. Звучание (переименовано, замечание 5): вкладки
                //    «Вход/Выход/ABC» (замечание 8): Вход — результаты
                //    обработки параметров ритма, Выход — параметров мелодии
                //    (финальный прогон); обе — гистограмма (карта
                //    высота×время). ABC — ноты в abc-нотации (текстовая
                //    таблица, как было всегда). Клик по ноте — её звучание.
                Collapsible(Strings.secNotes, defaultOpen = true) {
                    val v = current
                    if (v != null) {
                        // ABC-таблица строится по отфильтрованному midi (билд #46,
                        // фильтр питч-диапазона): ноты вне границ отсутствуют и
                        // здесь, как и в экспорте; заглушенные «×» остаются
                        // (muted не передаётся — их строки показывает вкладка)
                        val song = runCatching {
                            parseMidiSong(normalizeMidi(v.midi, pitchRange = pitchLo..pitchHi))
                        }.getOrNull()
                        if (song != null) {
                            // Вкладки: высоту Compose рассчитывает по контенту
                            // (жёсткая 34dp — причина «мешания», замечание А.М.
                            // 2026-09-06, 4); отступ от области данных —
                            // вертикальный spacedBy контейнера, не Spacer
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                // Вкладка ABC скрывается пунктом меню «Вид» (п.2);
                                // чекбокс при скрытии переводит позицию на «Тоны»
                                val tabs = if (showAbc) listOf(0 to Strings.tabInput, 1 to Strings.tabOutput, 2 to Strings.tabAbc)
                                else listOf(0 to Strings.tabInput, 1 to Strings.tabOutput)
                                TabRow(selectedTabIndex = notesTab) {
                                    for ((idx, name) in tabs) {
                                        // Смена вкладки снимает выделение ноты (панель правки — «Тоны»)
                                        Tab(selected = notesTab == idx, onClick = { notesTab = idx; selectedKey = null },
                                            text = { Text(name, style = MaterialTheme.typography.body2) })
                                    }
                                }
                                when (notesTab) {
                                    // «Кванты» — гистограмма входа (ритмика): без панели
                                    // правки и без заглушений (п.4 — они только у «Тонов»)
                                    0 -> {
                                        val notes = v.notesForInput
                                        if (notes.isEmpty()) {
                                            Text(Strings.noResults)
                                        } else {
                                            // Разметка «Вход» — по автоподбору отчёта своего прогона
                                            NoteChart(notes, parseKeyFromReport(v.reportForInput), tScale,
                                                playPosSec = playPosSec, // п.5б: полоска позиции
                                                onNoteClick = { n -> playNoteTone(n.pitch, n.velocity, n.cents) })
                                        }
                                    }
                                    // «Тоны» — гистограмма выхода (мелодика) с панелью
                                    // правки под ней (п.4): клик выделяет ноту рамкой.
                                    // Фильтр питч-диапазона (билд #46): ноты вне границ
                                    // скрыты — не видны, не звучат и не правятся
                                    // (панель: «нет выделения»); «Кванты» не фильтруются
                                    1 -> {
                                        val notes = v.notes.filter { it.pitch in pitchLo..pitchHi }
                                        if (notes.isEmpty()) {
                                            Text(Strings.noResults)
                                        } else {
                                            // Разметка «Выход» — по ключу вывода (выбранная
                                            // тональность либо автоподбор)
                                            val key = effectiveKey(v.report)
                                            NoteChart(notes, key, tScale,
                                                mutedKeys = v.muted,
                                                selectedKey = selectedKey,
                                                playPosSec = playPosSec, // п.5б: полоска позиции
                                                onNoteClick = { n ->
                                                    // Выделение — по клику; заглушенная нота
                                                    // не звучит (возврат звука — повторным [X])
                                                    val k = noteKeyOf(n)
                                                    selectedKey = k
                                                    if (k !in v.muted) playNoteTone(n.pitch, n.velocity, n.cents)
                                                })
                                            EditNotePanel(notes, selectedKey, v.muted,
                                                onDelta = { editNote(v, it) },
                                                onMute = { toggleMute(v) })
                                        }
                                    }
                                    // ABC — текстовая таблица; нажатие на строке
                                    // ноты — её звук (5: попадание точно по
                                    // ячейке; 5.1: событие — с начала клика)
                                    else -> {
                                        val key = effectiveKey(v.report)
                                        val rows = buildRows(song)
                                        SelectionContainer {
                                            LazyColumn(Modifier.heightIn(max = 300.dp)) {
                                                item {
                                                    Text(Strings.notesHeader,
                                                         fontFamily = FontFamily.Monospace,
                                                         style = MaterialTheme.typography.body2,
                                                         color = MaterialTheme.colors.onSurface)
                                                }
                                                items(rows) { r ->
                                                    // The absolute position in quarters is kept; the bar
                                                    // grid is re-computed under the effective signature
                                                    // (the export override or the detected one). The row
                                                    // measure/beat come from a tsNum-quarters bar grid;
                                                    // the *4/tsDen term converts them to real quarters,
                                                    // and beatPos uses num*4/den per bar, so a den of 8
                                                    // halves the bar (a 6/8 bar is 3 quarters long).
                                                    // Заглушенная нота (п.4) занимает строку нулевой
                                                    // длительности: «×» вместо множителя, 0.00 с, без
                                                    // громкости и без звука по клику.
                                                    val effSize = finalSizeOverride ?: (song.tsNum to song.tsDen)
                                                    val rowQ = (r.measure - 1.0) * song.tsNum + (r.beat - 1.0)
                                                    val qFromStart = rowQ * 4.0 / song.tsDen
                                                    val (measure, tilde, frac) = beatPos(qFromStart, effSize.first, effSize.second)
                                                    val rowMuted = !r.isRest && noteKeyOf(r.startTick, r.pitch) in v.muted
                                                    val cell = if (rowMuted) noteLabel(r.pitch, key) + "×"
                                                    else noteCell(r.pitch, r.isRest, r.durationQuarters, key)
                                                    val vel = if (r.isRest || rowMuted) "" else r.velocity.toString()
                                                    val durSec = if (rowMuted) 0.0 else r.endSec - r.startSec
                                                    var rowPressed by remember(r) { mutableStateOf(false) }
                                                    Text(
                                                        // The "~" marker occupies a fixed 1-char field so
                                                        // off-grid beats do not shift the measure number.
                                                        String.format(Locale.ROOT, "  %1s%02d:%s | %s | %7.2f | %3s",
                                                            tilde, measure, frac, cell, durSec, vel),
                                                        fontFamily = FontFamily.Monospace,
                                                        style = MaterialTheme.typography.body2,
                                                        modifier = if (r.isRest || rowMuted) Modifier else Modifier
                                                            // SelectionContainer на Desktop перехватывает нажатия в
                                                            // Main-пассе — звук и подсветка слушают Initial (п.1:
                                                            // «выбор ноты в режиме таблицы ABC перестал звучать»);
                                                            // подсветка — фоном строки на время нажатия (ripple
                                                            // clickable в Main-пассе не срабатывает — убран)
                                                            .pointerInput(r) {
                                                                awaitEachGesture {
                                                                    val down = awaitFirstDown(pass = PointerEventPass.Initial)
                                                                    rowPressed = true
                                                                    // Звук — с нажатия, не с отпускания (замечание 5.1)
                                                                    if (Log.DEBUG) {
                                                                        Log.d("abc", "клик по строке p=${r.pitch} v=${r.velocity}")
                                                                    }
                                                                    playNoteTone(r.pitch, r.velocity)
                                                                    // Держать подсветку до отпускания (все пассы)
                                                                    while (true) {
                                                                        val ev = awaitPointerEvent(pass = PointerEventPass.Initial)
                                                                        if (ev.changes.none { it.pressed }) break
                                                                    }
                                                                    rowPressed = false
                                                                }
                                                            }
                                                            .background(
                                                                if (rowPressed) MaterialTheme.colors.primary.copy(alpha = 0.15f)
                                                                else Color.Transparent)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            Text(Strings.parseFailed)
                        }
                    } else {
                        Text(Strings.noResults)
                    }
                }

                // 6. Экспорт — финальный темп/размер, clef, инструмент и кнопки
                //    сохранения (свёртываемый). Значения применяются к байтам
                //    MIDI на лету; на транскрипцию не влияют. Правка темпа
                //    создаёт override (0 или "0/0" возвращает автодетект).
                Collapsible(Strings.secExport, defaultOpen = true) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(Strings.finalTempoLabel, style = MaterialTheme.typography.body2)
                            InlineField(
                                value = finalTempoText,
                                onValueChange = { s ->
                                    // Пустой ввод разрешён (стирание, замечание 10);
                                    // принимается только целое 0..300.
                                    if (s.isEmpty()) {
                                        finalTempoText = ""
                                    } else {
                                        val v = s.toIntOrNull()
                                        if (v != null && v in 0..300) {
                                            finalTempoText = v.toString()
                                            finalTempoOverride = if (v > 0) v.toDouble() else null
                                        }
                                    }
                                },
                                modifier = Modifier.width(64.dp),
                            )
                            Text(Strings.bpmUnit, style = MaterialTheme.typography.body2)
                            Text(Strings.finalSizeLabel, style = MaterialTheme.typography.body2)
                            var sizeMenuOpen by remember { mutableStateOf(false) }
                            TextButton(onClick = { sizeMenuOpen = true }) {
                                Text(
                                    finalSizeOverride?.let { "${it.first}/${it.second}" } ?: finalSizeDetected,
                                    style = MaterialTheme.typography.body2)
                            }
                            DropdownMenu(expanded = sizeMenuOpen, onDismissRequest = { sizeMenuOpen = false }) {
                                DropdownMenuItem(onClick = { finalSizeOverride = null; sizeMenuOpen = false }) {
                                    Text(
                                        if (finalSizeDetected == Strings.auto) Strings.auto
                                        else String.format(Locale.ROOT, Strings.autoDetected, finalSizeDetected),
                                        style = MaterialTheme.typography.body2)
                                }
                                for ((name, ts) in Strings.SIZE_OPTIONS) {
                                    DropdownMenuItem(onClick = { finalSizeOverride = ts; sizeMenuOpen = false }) {
                                        Text(name, style = MaterialTheme.typography.body2)
                                    }
                                }
                            }
                        }

                        // Ключ (clef) для экспорта MusicXML (скрипичный/басовый)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(Strings.clefLabel, style = MaterialTheme.typography.body2)
                            var clefMenuOpen by remember { mutableStateOf(false) }
                            TextButton(onClick = { clefMenuOpen = true }) {
                                Text(if (clef == 0) Strings.clefTreble else Strings.clefBass,
                                     style = MaterialTheme.typography.body2)
                            }
                            DropdownMenu(expanded = clefMenuOpen, onDismissRequest = { clefMenuOpen = false }) {
                                DropdownMenuItem(onClick = { clef = 0; clefMenuOpen = false }) {
                                    Text(Strings.clefTreble, style = MaterialTheme.typography.body2)
                                }
                                DropdownMenuItem(onClick = { clef = 1; clefMenuOpen = false }) {
                                    Text(Strings.clefBass, style = MaterialTheme.typography.body2)
                                }
                            }
                        }

                        // Затакт: неполный первый такт из N восьмых в экспорте
                        // (0 = выкл, Р5). Применяется к .mid и .musicxml, не к
                        // предпрослушиванию.
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(Strings.anacrusisLabel, style = MaterialTheme.typography.body2)
                            var text by remember { mutableStateOf(anacrusis.toString()) }
                            InlineField(
                                value = text,
                                onValueChange = { s ->
                                    // Пустой ввод разрешён (замечание 10: затакт
                                    // не давал стереть значение); принимается
                                    // только целое 0..8 (0 = выкл).
                                    if (s.isEmpty()) {
                                        text = ""
                                    } else {
                                        val v = s.toIntOrNull()
                                        if (v != null && v in 0..8) {
                                            text = v.toString()
                                            anacrusis = v
                                        }
                                    }
                                },
                                modifier = Modifier.width(36.dp),
                            )
                            Text(Strings.anacrusisHint, style = MaterialTheme.typography.body2)
                        }

                        // Инструмент — выбор GM; на транскрипцию не влияет,
                        // применяется при прослушивании и сохранении
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(Strings.instrumentLabel, style = MaterialTheme.typography.body2)
                            var text by remember { mutableStateOf(instrument.toString()) }
                            var menuOpen by remember { mutableStateOf(false) }
                            InlineField(
                                value = text,
                                onValueChange = { s ->
                                    // Пустой ввод разрешён; принимается только
                                    // целое 1..128 (GM). Число можно стереть и
                                    // ввести заново (замечание 10).
                                    if (s.isEmpty()) {
                                        text = ""
                                    } else {
                                        val v = s.toIntOrNull()
                                        if (v != null && v in 1..128) {
                                            text = v.toString()
                                            instrument = v
                                        }
                                    }
                                },
                                modifier = Modifier.width(44.dp),
                            )
                            Text(gmName(instrument), style = MaterialTheme.typography.body2)
                            TextButton(onClick = { menuOpen = true }) { Text(Strings.chooseInstrument + " ▾") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                // heightIn before verticalScroll: the scrollable must get
                                // bounded constraints, otherwise Compose throws "measured
                                // with an infinity maximum height constraints" (crash on open)
                                Column(
                                    Modifier.widthIn(max = 400.dp).heightIn(max = 420.dp)
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    for ((group, items) in GM_INSTRUMENTS) {
                                        Text(group, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.body2,
                                             modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                                        for ((num, name) in items) {
                                            DropdownMenuItem(onClick = { instrument = num; text = num.toString(); menuOpen = false }) {
                                                Text("$num $name", style = MaterialTheme.typography.body2)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Кнопки сохранения переехали вниз (билд #35, п.6:
                        // [Экспорт ▾] у busy-спиннера) — см. exportVersion ниже.
                        // Чекбокс «кадровые признаки» убран (билд #38,
                        // замечание «в»): сводка извлекается всегда, включение
                        // в экспорт — тип «.mid + признаки» в диалоге
                    }
                }
            }

            // Sticky bottom (п.6; билд #36, п.2): три кнопки на всю ширину
            // низа — [Транскрипт] слева, [▶] посредине (растянута
            // weight(1f), с рамкой — «значимая»), [Экспорт] справа (формат
            // выбирается в системном диалоге сохранения, билд #36, п.3).
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // [Транскрипт]: источник — загруженный wav или свежая запись
                // (замечание 4 приёмки #43: после записи кнопка была недоступна —
                // enabled учитывал только wavFile).
                Button(onClick = ::transcribe,
                    enabled = !busy && (wavFile != null || rec != null)) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (busy) Strings.busy else Strings.transcribe)
                }
                val playIcon = if (playing) Res.drawable.stop else Res.drawable.play
                val playCd = if (playing) Strings.stopIconCd else Strings.listenIconCd
                OutlinedButton(onClick = ::listen,
                    enabled = !busy && versions.isNotEmpty(),
                    modifier = Modifier.weight(1f).height(48.dp)
                        .semantics { contentDescription = playCd },
                    contentPadding = PaddingValues(0.dp)) {
                    Icon(painterResource(playIcon), contentDescription = null)
                }
                val exportName = Strings.EXPORT_FMT_NAMES[exportFmt] ?: exportFmt
                Button(onClick = { current?.let { exportVersion(it) } },
                    enabled = !busy && versions.isNotEmpty(),
                    modifier = Modifier.semantics {
                        contentDescription = Strings.exportCd.format(exportName)
                    }) {
                    // Формат на кнопке не показывается (замечание «г»: он виден
                    // типом в окне экспорта); имя формата — только в CD для тестов
                    Text(Strings.exportLabel)
                }
            }
            }

            // Sandwich menu, flush to the window's top-right corner (no
            // padding — the button's corner meets the window's corner). An
            // anchored panel (замечание 1: меню «уехало» влево-вниз) opens
            // below the button, flush to the right edge of the window;
            // a full-window click-catcher behind it closes the menu on any
            // click outside. Options grouped by section: «Слушать» — one
            // checkbox where the «Слушать» button plays (off = in-app
            // player, on = OS MIDI app); «Вид» — the dark theme switch;
            // «Гистограмма» — the t-scale slider (замечание 7).
            var menuOpen by remember { mutableStateOf(false) }
            // Открытый инфо-диалог меню (замечание А.М. 2026-09-06, 7)
            var infoDlg by remember { mutableStateOf<InfoDlg?>(null) }
            // Диалог «Автор» (билд #38, замечание «б»): имя автора файла
            // признаков; authorDlgText — редактируемая копия (ввод без
            // применения до [OK])
            var authorDlg by remember { mutableStateOf(false) }
            var authorDlgText by remember { mutableStateOf("") }
            SoundButton(Res.drawable.menu, { menuOpen = true },
                Modifier.align(Alignment.TopEnd).size(36.dp))
            if (menuOpen) {
                // Click-catcher: any click outside the panel closes the menu
                // (no visual scrim — the panel appears to float)
                Box(Modifier.fillMaxSize().clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null) { menuOpen = false })
                Surface(
                    // Ширина панели — 2/3 ширины окна (замечание 1)
                    Modifier.align(Alignment.TopEnd).padding(top = 44.dp).fillMaxWidth(2f / 3f),
                    shape = MaterialTheme.shapes.medium,
                    elevation = 6.dp,
                ) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(Strings.listenMenu, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.body2,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                        MenuCheck(Strings.menuListenExternal, listenExternal) {
                            // Переключение источника: включение внешней программы
                            // останавливает встроенное воспроизведение (если звучит)
                            if (!listenExternal && playing) { MidiPlayer.stop(); playing = false }
                            listenExternal = !listenExternal
                            menuOpen = false
                        }
                        Divider()
                        Text(Strings.viewMenu, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.body2,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                        MenuCheck(Strings.menuShowAbc, showAbc) {
                            // Скрытие ABC-вкладки переводит позицию на «Тоны»
                            // и снимает выделение ноты (её панель на «Тонах»)
                            if (showAbc && notesTab == 2) {
                                notesTab = 1
                                selectedKey = null
                            }
                            showAbc = !showAbc
                            menuOpen = false
                        }
                        MenuCheck(Strings.menuDarkTheme, darkTheme) {
                            darkTheme = !darkTheme
                            menuOpen = false
                        }
                        Divider()
                        Text(Strings.chartMenu, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.body2,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
                            Text(String.format(Locale.ROOT, Strings.chartTScale, tScale),
                                style = MaterialTheme.typography.body2)
                            Slider(tScale, { tScale = it }, valueRange = 1f..10f,
                                modifier = Modifier.fillMaxWidth())
                        }
                        // Диапазон нот (билд #46, замечание «б» приёмки #45):
                        // движки ограничивают появление нот вне границ —
                        // «Тоны»/ABC/экспорт (см. Strings.chartRangeHint);
                        // границы — полутоны MIDI, подпись в нотах (A0..C8)
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
                            Text(String.format(Locale.ROOT, Strings.chartRange,
                                    pitchName(pitchLo), pitchName(pitchHi)),
                                style = MaterialTheme.typography.body2)
                            RangeSlider(
                                value = pitchLo.toFloat()..pitchHi.toFloat(),
                                onValueChange = { r ->
                                    pitchLo = r.start.roundToInt()
                                    pitchHi = r.endInclusive.roundToInt()
                                },
                                valueRange = PITCH_LO_DEFAULT.toFloat()..PITCH_HI_DEFAULT.toFloat(),
                                modifier = Modifier.fillMaxWidth())
                            Text(Strings.chartRangeHint, style = MaterialTheme.typography.body2,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f))
                        }
                        Divider()
                        // «Файл признаков» (билд #38): имя автора — в «meta»
                        // сводки кадровых признаков (.frames.json) прогона
                        Text(Strings.framesMenuTitle, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.body2,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                        MenuAction(
                            String.format(Locale.ROOT, Strings.menuAuthor,
                                author.ifEmpty { Strings.authorNone }),
                        ) {
                            authorDlgText = author // форма — с текущим значением
                            menuOpen = false
                            authorDlg = true
                        }
                        Divider()
                        MenuAction(Strings.menuAbout) { menuOpen = false; infoDlg = InfoDlg.About }
                        MenuAction(Strings.menuPrivacy) { menuOpen = false; infoDlg = InfoDlg.Privacy }
                        MenuAction(Strings.menuOss) { menuOpen = false; infoDlg = InfoDlg.Oss }
                    }
                }
            }
            // Инфо-диалоги пунктов меню (замечание А.М. 2026-09-06, 7)
            infoDlg?.let { dlg ->
                when (dlg) {
                    InfoDlg.About -> AlertDialog(
                        onDismissRequest = { infoDlg = null },
                        title = { Text(Strings.menuAbout) },
                        text = {
                            Column {
                                Text("v2m · билд #$BUILD", fontWeight = FontWeight.Bold)
                                Text(Strings.aboutText, style = MaterialTheme.typography.body2)
                                Text(Strings.aboutDev, style = MaterialTheme.typography.body2,
                                    modifier = Modifier.padding(top = 8.dp))
                                TextButton(onClick = { openSite() }) {
                                    Text(Strings.dlgOpenSite)
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { infoDlg = null }) { Text(Strings.dlgClose) }
                        },
                    )
                    InfoDlg.Privacy -> AlertDialog(
                        onDismissRequest = { infoDlg = null },
                        title = { Text(Strings.menuPrivacy) },
                        text = { Text(Strings.privacyText, style = MaterialTheme.typography.body2) },
                        confirmButton = {
                            TextButton(onClick = { infoDlg = null }) { Text(Strings.dlgClose) }
                        },
                    )
                    InfoDlg.Oss -> AlertDialog(
                        onDismissRequest = { infoDlg = null },
                        title = { Text(Strings.menuOss) },
                        text = { Text(Strings.ossCredits, style = MaterialTheme.typography.body2) },
                        confirmButton = {
                            TextButton(onClick = { infoDlg = null }) { Text(Strings.dlgClose) }
                        },
                    )
                }
            }
            // Диалог «Автор» (билд #38, замечание «б»): пусто — автор не
            // указывается (поле «meta.author» файла признаков опускается)
            if (authorDlg) {
                AlertDialog(
                    onDismissRequest = { authorDlg = false },
                    title = { Text(Strings.authorDlgTitle) },
                    text = {
                        Column {
                            Text(Strings.authorDlgText, style = MaterialTheme.typography.body2)
                            OutlinedTextField(
                                value = authorDlgText,
                                onValueChange = { authorDlgText = it },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                singleLine = true,
                            )
                        }
                    },
                    confirmButton = {
                        // Сохранится LaunchedEffect(author) — сразу после применения
                        TextButton(onClick = { author = authorDlgText.trim(); authorDlg = false }) { Text(Strings.dlgOk) }
                    },
                    dismissButton = {
                        TextButton(onClick = { authorDlg = false }) { Text(Strings.dlgCancel) }
                    },
                )
            }
        } // Box (content column + overlay menu)
        } // Surface (theme background)
    }
}

/** The bundled sound sample at [path] (a composeResources files/ entry). */
@OptIn(ExperimentalResourceApi::class)
private suspend fun readSampleBytes(path: String): ByteArray = Res.readBytes(path)

/** Play [midi] in the OS application associated with .mid (Desktop API /
 *  xdg-open): a temp file under ~/.v2m is written and opened. The file is
 *  overwritten on each call and never auto-deleted — an external app may
 *  still be reading it (no end-of-playback signal exists on this path).
 *  Returns null on success or an error text. */
private fun playExternally(midi: ByteArray): String? = try {
    if (!Desktop.isDesktopSupported()) {
        throw IllegalStateException("в этом окружении нет Desktop API")
    }
    val dir = File(System.getProperty("user.home"), ".v2m").apply { mkdirs() }
    val f = File(dir, "v2m-listen.mid")
    f.writeBytes(midi)
    Desktop.getDesktop().open(f)
    null
} catch (e: Exception) {
    Strings.playFailed.format(e.message)
}

/** Мини-SMF одного тона для клика по ноте (замечание 8): штатная
 *  длительность 1/8 с (при 120 BPM и делении 480 это 120 тиков),
 *  инструмент — как в экспорте ([program], 0..127 GM), громкость — [velocity].
 *  format 0, один трек: Tempo 500000 (120 BPM), Program change, NoteOn,
 *  NoteOff, EOT. Дельта первого события обязательна (0x00), иначе
 *  парсеры читают её как длину — см. midiWithMetaTrack (замечание 11).
 *  [cents] (билд #35) — микротон в % полутона: питч-бенд канала перед
 *  NoteOn (raw = 8192 + cents·40.96, ±8192 = ±2 полутона); 0 — без bend. */
internal fun noteToneMidi(pitch: Int, velocity: Int, program: Int, cents: Float = 0f): ByteArray {
    val p = pitch.coerceIn(0, 127)
    val v = velocity.coerceIn(1, 127)
    val pr = program.coerceIn(0, 127)
    val raw = (8192 + cents * 40.96f).roundToInt().coerceIn(0, 16383)
    val bend = if (cents == 0f) byteArrayOf() else
        byteArrayOf(0x00, 0xE0.toByte(), (raw and 0x7F).toByte(), ((raw shr 7) and 0x7F).toByte()) // канал 0
    val tail = byteArrayOf(
        0x00, 0x90.toByte(), p.toByte(), v.toByte(),
        0x78, 0x80.toByte(), p.toByte(), 0x00, // +120 ticks = 1/8 s
        0x00, 0xFF.toByte(), 0x2F.toByte(), 0x00, // end of track
    )
    val body = byteArrayOf(
        0x00, 0xFF.toByte(), 0x51, 0x03, 0x07, 0xA1.toByte(), 0x20, // tempo 500000 us = 120 BPM
        0x00, 0xC0.toByte(), pr.toByte(),
    ) + bend + tail
    val out = ByteArrayOutputStream(body.size + 14 + 8)
    out.write("MThd".toByteArray(Charsets.US_ASCII))
    out.write(0); out.write(0); out.write(0); out.write(6) // header length = 6
    out.write(0); out.write(0) // format 0
    out.write(0); out.write(1) // 1 track
    out.write(1); out.write(0xE0) // division 480
    out.write("MTrk".toByteArray(Charsets.US_ASCII))
    out.write(0); out.write(0); out.write(0); out.write(body.size)
    out.write(body)
    return out.toByteArray()
}

@Composable
private fun ParamSlider(label: String, value: Float, helpKey: String,
                        modifier: Modifier = Modifier, onChange: (Float) -> Unit) {
    val percent = (value.coerceIn(0f, 1f) * 100).roundToInt()
    Column(modifier) {
        ParamLabel("$label: $percent%", helpKey)
        Slider(percent.toFloat(), { p -> onChange(p / 100f) },
               valueRange = 0f..100f, modifier = Modifier.fillMaxWidth())
    }
}

/** Integer slider; [steps] makes the positions discrete (positions = steps + 2). */
@Composable
private fun ParamInt(label: String, value: Int, range: IntRange, helpKey: String,
                     modifier: Modifier = Modifier, steps: Int = 0,
                     onChange: (Int) -> Unit) {
    Column(modifier) {
        ParamLabel("$label: $value", helpKey)
        Slider(value.toFloat(), { onChange(it.toInt()) },
               valueRange = range.first.toFloat()..range.last.toFloat(),
               steps = steps, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ParamCheck(label: String, checked: Boolean, helpKey: String, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        ParamLabel(label, helpKey)
    }
}

/** Frame-count params shown in milliseconds (the engine counts frames:
 *  1 frame = 256 samples @ 22050 Hz ≈ FRAME_MS ms). The slider runs in
 *  milliseconds but snaps to whole frames on change; [minMs]/[maxMs] bound
 *  both the slider and the converted frame value. Default [minMs] is the
 *  histogram resolution limit for the min note length — 9 frames ≈ 104 ms
 *  (замечание А.М.: от 99 мс); the tremolo call passes 5 frames ≈ 58 ms
 *  (от 50 мс) — see Preferences.kt. The label and the thumb always follow
 *  the actual [frames] parameter, so presets show true values. */
@Composable
private fun ParamMs(label: String, frames: Int, helpKey: String, onChange: (Int) -> Unit,
                    minMs: Float = MIN_NOTE_LEN_FRAMES * FRAME_MS,
                    maxMs: Float = 580f, maxFrames: Int = 50) {
    val minFrames = (minMs / FRAME_MS).roundToInt()
    val maxFramesOk = minOf(maxFrames, (maxMs / FRAME_MS).roundToInt())
    val msValue = (frames * FRAME_MS).coerceIn(minMs, maxMs)
    Column {
        ParamLabel("$label: ${msValue.roundToInt()} мс", helpKey)
        Slider(msValue, { v ->
            onChange((v / FRAME_MS).roundToInt().coerceIn(minFrames, maxFramesOk))
        }, valueRange = minMs..maxMs, modifier = Modifier.fillMaxWidth())
    }
}

/** Numeric slider with its own range (not a percent), integer display.
 *  [toValue]/[toPosition] map between the slider position and the stored
 *  value: the tempo slider runs 24..250 with the left edge meaning «Авто»
 *  (stored as 0 and displayed as 0) — no dead zone, no snapping. */
@Composable
private fun ParamRange(label: String, value: Float, range: ClosedFloatingPointRange<Float>, helpKey: String,
                       modifier: Modifier = Modifier, enabled: Boolean = true,
                       toValue: (Float) -> Float = { it },
                       toPosition: (Float) -> Float = { it },
                       onChange: (Float) -> Unit) {
    // Value-driven, как ParamSlider: внешние изменения (пресет) двигают
    // контроль сразу (замечание А.М. 2026-09-06).
    val raw = toPosition(value)
    Column(modifier) {
        ParamLabel("$label: ${toValue(raw).roundToInt()}", helpKey)
        Slider(raw, { onChange(toValue(it)) }, valueRange = range,
               enabled = enabled, modifier = Modifier.fillMaxWidth())
    }
}

/** Choice from a fixed list via a small dropdown. */
@Composable
private fun ParamSelect(label: String, options: List<Pair<String, Int>>, value: Int, helpKey: String, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        ParamLabel("$label:", helpKey)
        TextButton(onClick = { open = true }) {
            Text(options.firstOrNull { it.second == value }?.first ?: "?", style = MaterialTheme.typography.body2)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (name, v) ->
                DropdownMenuItem(onClick = { onChange(v); open = false }) { Text(name, style = MaterialTheme.typography.body2) }
            }
        }
    }
}

/** Small square sound-preview button with a Material icon (metronome by the
 *  tempo slider, music note by the key selector), sitting to the right of
 *  its slider. Disabled in the «Авто» state of its control (2а/3а: no tempo
 *  or key chosen — nothing to play). The icon SVG
 *  (composeResources/drawable, fill #1f1f1f) is tinted by the theme's
 *  content color, so it stays visible in a dark theme too. */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun SoundButton(icon: DrawableResource, onClick: () -> Unit,
                        modifier: Modifier = Modifier, enabled: Boolean = true,
                        cd: String? = null) {
    val acc = if (cd != null) Modifier.semantics { contentDescription = cd } else Modifier
    TextButton(onClick = onClick, enabled = enabled,
        modifier = acc.then(modifier), contentPadding = PaddingValues(0.dp)) {
        Icon(painterResource(icon), contentDescription = null)
    }
}

/** «Тональность»: discrete slider 0..24 (0 = Авто, then 12 major + 12
 *  minor); the name is shown above, the music-note button plays the tonica
 *  in the chosen key (3а: disabled at «Авто»). */
@Composable
private fun KeySelector(keySel: Int, onSelect: (Int) -> Unit, onPlayTriad: () -> Unit) {
    Column {
        ParamLabel(Strings.keySelLabel.format(Strings.KEY_SEL_NAMES[keySel.coerceIn(0, 24)]), "keySelect")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Slider(keySel.toFloat(), { onSelect(it.roundToInt()) },
                   valueRange = 0f..24f, steps = 23, modifier = Modifier.weight(1f))
            SoundButton(Res.drawable.music_note_2, onPlayTriad,
                Modifier.size(36.dp), enabled = keySel > 0)
        }
    }
}

/** Инфо-диалоги пунктов меню (замечание А.М. 2026-09-06, 7). */
private enum class InfoDlg { About, Privacy, Oss }

/** Открыть сайт разработчика в браузере ОС (пункт «О программе»). */
private fun openSite() {
    try {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Action.BROWSE)) {
            throw IllegalStateException("в этом окружении нет Desktop API")
        }
        Desktop.getDesktop().browse(URI("https://attplus.in"))
    } catch (e: Exception) {
        e.printStackTrace() // best-effort: браузер может отсутствовать
    }
}

/** Строка меню без чекбокса — пункт, открывающий инфо-диалог (7). */
@Composable
private fun MenuAction(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.body2)
    }
}

/** Row of the sandwich menu: a checkbox (not clickable by itself — the
 *  whole row is) plus a text label; the row closes the menu via [onClick]. */
@Composable
private fun MenuCheck(label: String, checked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, style = MaterialTheme.typography.body2)
    }
}

/** Compact inline field: no box, just an underline — keeps rows with several
 *  editable values visually light (final tempo/time signature). The text
 *  color is set explicitly: BasicTextField's default is black, invisible on
 *  the dark theme (замечание 9). [placeholder] (билд #46) — серая подсказка
 *  при пустом значении (у ряда файла: «нет файла»). */
@Composable
private fun InlineField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawLine(Color.Gray, Offset(0f, size.height - stroke), Offset(size.width, size.height - stroke), stroke)
            }
            .padding(horizontal = 2.dp),
        singleLine = true,
        textStyle = MaterialTheme.typography.body2.copy(color = MaterialTheme.colors.onBackground),
        cursorBrush = SolidColor(MaterialTheme.colors.onBackground),
        decorationBox = { inner ->
            if (value.isEmpty() && placeholder != null) {
                Box {
                    Text(placeholder, style = MaterialTheme.typography.body2, color = Color.Gray)
                    inner()
                }
            } else inner()
        },
    )
}

/** Панель правки ноты под «Тонами» (билд #35, п.4): текст информации о
 *  ноте (имя с октавой + микротон «+32%»; у чистых микротон не указывается
 *  — centsText пуст) и кнопки [<][>][X]. Кнопки активны, когда нота
 *  выделена кликом по столбику ([selectedKey], постоянная рамка); [X] —
 *  «кнопка с памятью»: пока нота заглушена, она в нажатом виде (залитая),
 *  повторное нажатие возвращает длительность (А.М., п.4). Назначения
 *  кнопок — в contentDescription (строки editDownCd/editUpCd/editMuteCd). */
@Composable
private fun EditNotePanel(
    notes: List<MidiNote>,
    selectedKey: Long?,
    muted: Set<Long>,
    onDelta: (Int) -> Unit,
    onMute: () -> Unit,
) {
    val sel = selectedKey?.let { k -> notes.firstOrNull { noteKeyOf(it) == k } }
    val selMuted = sel != null && noteKeyOf(sel) in muted
    val info = if (sel == null) Strings.editNoSelection
    else Strings.editInfoCd.format(sel.name + sel.centsText)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(info, style = MaterialTheme.typography.body2, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { onDelta(-1) }, enabled = sel != null,
            modifier = Modifier.semantics { contentDescription = Strings.editDownCd }) { Text("<") }
        OutlinedButton(onClick = { onDelta(1) }, enabled = sel != null,
            modifier = Modifier.semantics { contentDescription = Strings.editUpCd }) { Text(">") }
        if (selMuted) {
            // Нажатое состояние защёлки [X]: нота заглушена (длительность 0)
            Button(onClick = onMute,
                modifier = Modifier.semantics { contentDescription = Strings.editMuteCd }) { Text("X") }
        } else {
            OutlinedButton(onClick = onMute, enabled = sel != null,
                modifier = Modifier.semantics { contentDescription = Strings.editMuteCd }) { Text("X") }
        }
    }
}

/** Фазы кнопки записи (Р14): Idle → Countdown → Recording (клик в обратную). */
enum class RecPhase { Idle, Countdown, Recording }

/** Кнопка записи Р14: красный кружок. Отсчёт −0:03..0:00 — в поле таймера
 *  справа (билд #40; клик во время отсчёта отменяет старт); при записи
 *  кружок мигает (клик — стоп). */
@Composable
private fun RecButton(phase: RecPhase, countdown: Int, onClick: () -> Unit, enabled: Boolean) {
    val blink = rememberInfiniteTransition()
        .animateFloat(1f, 0.25f, infiniteRepeatable(tween(400), RepeatMode.Reverse))
    val cd = when (phase) {
        RecPhase.Idle -> Strings.recCd
        RecPhase.Countdown -> Strings.recCountdownCd.format(countdown)
        RecPhase.Recording -> Strings.recStopCd
    }
    Button(onClick = onClick, enabled = enabled,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        modifier = Modifier.semantics { contentDescription = cd }) {
        Box(Modifier.size(18.dp)
            .graphicsLayer { alpha = if (phase == RecPhase.Recording) blink.value else 1f }
            .background(Color(0xFFE53935), CircleShape))
    }
}

/** Поле таймера записи (билд #40): семисегментные цифры, формат «m:ss»,
 *  отрицательное время — с минусом («-0:03»). lit — яркость сегментов:
 *  1 = активно (отсчёт/запись), 0.3 = «покой» (длительность записи тускло). */
@Composable
private fun SevenSegDisplay(text: String, lit: Float, modifier: Modifier = Modifier) {
    val bg = Color(0xFF14171B)
    Box(modifier.width(76.dp).height(32.dp)
        .background(bg, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 4.dp)) {
            val hh = size.height
            val t = hh * 0.16f
            val cw = hh * 0.62f
            val contentW = 4.05f * cw // знак + минуты + «:» + секунды (2)
            val x0 = (size.width - contentW) / 2f
            val col = Color(0xFFE53935).copy(alpha = lit)
            val y0 = t / 2f
            val y1 = hh - t / 2f
            val ym = hh / 2f
            fun seg(line: (x1: Float, y1: Float, x2: Float, y2: Float) -> Unit, s: Char, ax: Float, bx: Float) = when (s) {
                'a' -> line(ax, y0, bx, y0); 'b' -> line(bx, y0, bx, ym)
                'c' -> line(bx, ym, bx, y1); 'd' -> line(ax, y1, bx, y1)
                'e' -> line(ax, ym, ax, y1); 'f' -> line(ax, y0, ax, ym)
                else -> line(ax, ym, bx, ym) // g
            }
            fun digit(d: Char, cx: Float) {
                val m = SEG7[d] ?: return
                val ax = cx + 0.18f * cw
                val bx = cx + 0.82f * cw
                for (s in m) seg({ a, b, c, d2 -> drawLine(col, Offset(a, b), Offset(c, d2),
                    strokeWidth = t, cap = StrokeCap.Round) }, s, ax, bx)
            }
            var rest = text
            if (rest.startsWith("-")) {
                // минус — в знаковой ячейке слева (место резервируется всегда,
                // цифры не прыгают при смене знака)
                val cx = x0
                drawLine(col, Offset(cx + 0.08f * cw, ym), Offset(cx + 0.47f * cw, ym),
                    strokeWidth = t, cap = StrokeCap.Round)
                rest = rest.substring(1)
            }
            if (rest.isNotEmpty()) digit(rest[0], x0 + 0.55f * cw) // минуты
            if (rest.length >= 2 && rest[1] == ':') {
                val xc = x0 + 1.55f * cw + 0.25f * cw
                drawCircle(col, t * 0.55f, Offset(xc, hh * 0.36f))
                drawCircle(col, t * 0.55f, Offset(xc, hh * 0.64f))
            }
            if (rest.length >= 3) digit(rest[2], x0 + 2.05f * cw) // секунды
            if (rest.length >= 4) digit(rest[3], x0 + 3.05f * cw)
        }
    }
}

/** Полоса уровня (билд #40): заливка слева по уровню (meterFill), цвет — по
 *  зоне (meterZoneColor); «молчание» (нет зоны) — полоса невидима, место
 *  резервируется всегда (макет не прыгает). Ширину задаёт вызывающий. */
@Composable
private fun LevelBar(level: Float, modifier: Modifier = Modifier) {
    val zone = meterZoneColor(level)
    Box(modifier.height(12.dp), contentAlignment = Alignment.CenterStart) {
        if (zone != null) {
            Box(Modifier.fillMaxHeight()
                .fillMaxWidth(meterFill(level))
                .background(zone, RoundedCornerShape(6.dp)))
        }
    }
}

/** Секунды → «m:ss» (0:00..1:00 — таймер записи, билд #40). */
private fun recClock(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)

/** Маска сегментов семисегментных цифр (SevenSegDisplay, билд #40). */
private val SEG7 = mapOf(
    '0' to "abcdef", '1' to "bc", '2' to "abged", '3' to "abgcd", '4' to "fgbc",
    '5' to "afgcd", '6' to "afgedc", '7' to "abc", '8' to "abcdefg", '9' to "abcdfg",
)
