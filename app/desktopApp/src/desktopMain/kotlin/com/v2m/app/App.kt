package com.v2m.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlin.math.roundToInt

/** One transcription result (added to the version list on each run). */
data class Version(
    val midi: ByteArray,
    val notes: List<MidiNote>,
    val report: String,
    val wavName: String,
    val params: V2mEngine.Params,
)

/** Long-press help: English name (bold), purpose (plain), examples (italic). */
data class ParamHelp(val english: String, val purpose: String, val examples: String)

private val HELP: Map<String, ParamHelp> = mapOf(
    "onsetThreshold" to ParamHelp("onset-threshold",
        "порог вероятности начала ноты в кадре (0..1; 0.5 = 50%)",
        "0.7: меньше нот, теряются тихие атаки; 0.3: больше ложных начал"),
    "frameThreshold" to ParamHelp("frame-threshold",
        "порог вероятности звучания ноты в кадре (0..1); ниже — нота закончилась",
        "выше: ноты короче; 0.2: «мягкие» границы, ноты сливаются"),
    "velocityCompress" to ParamHelp("velocity-compress",
        "компрессия динамики громкости (0 — тихие/громкие различимы, 1 — сильная)",
        "0: громкость 41..114; 1: 87..123 (ровнее, но менее выразительно)"),
    "globalShift" to ParamHelp("global-shift",
        "медианный сдвиг всех нот при глобальной расстройке записи (0..1 — сила)",
        "1 на записи +25 центов: возврат в ровный строй (бенды ≈ +1.3 цента)"),
    "modeSnap" to ParamHelp("mode-snap",
        "подобрать лад (12 тональностей × 6 ладов, допуск 30 центов) и притянуть ноты к ступеням (0..1 — сила)",
        "0: выкл; 1: ровно на ступенях (test4: C major, 100%); 0.9: мягче"),
    "minNoteLen" to ParamHelp("min-note-len",
        "минимальная длина ноты в кадрах (1 кадр ≈ 11.6 мс; 11 ≈ 128 мс)",
        "20: только длинные ноты; меньше: сохраняются стаккато и щелчки"),
    "harmonizeMerge" to ParamHelp("harmonize-merge",
        "слить соседние фрагменты с разницей ≤ N полутонов (вибрато-дробление) в одну ноту",
        "0: вибрато дробит ноту (test1: 46 фрагментов); 1: целые ноты (22; test4: 4)"),
    "useMelodiaTrick" to ParamHelp("melodia-trick",
        "мелодический проход: ноты без выраженного onset по остаточной энергии кадров",
        "вкл: ловит протяжные ноты (легато); выкл: только чёткие начала"),
    "includePitchBends" to ParamHelp("pitch-bends",
        "питч-бенды (глиссандо между нотами) в выходном MIDI",
        "вкл: вибрато как бенды (точнее звук); выкл: ноты на хроматической сетке"),
    "detectKey" to ParamHelp("определение тональности",
        "показывать тональность (mode fit) в отчёте и знаки альтерации у нот (^/_/=)",
        "вкл: C major — ноты без знаков, G major — F#; выкл: только фактические знаки"),
)

/** Label with long-press popup (English bold, purpose plain, examples italic). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ParamLabel(text: String, helpKey: String) {
    val h = HELP[helpKey]
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
fun App() {
    MaterialTheme {
        var params by remember { mutableStateOf(V2mEngine.Params.defaults()) }
        var wavFile by remember { mutableStateOf<File?>(null) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var detectKey by remember { mutableStateOf(true) }
        var versions by remember { mutableStateOf(listOf<Version>()) }
        var selected by remember { mutableStateOf(0) }
        val scope = rememberCoroutineScope()

        val current: Version? = versions.getOrNull(selected)

        fun chooseWav() {
            val dlg = FileDialog(null as Frame?, "Выбрать WAV", FileDialog.LOAD)
            dlg.isVisible = true
            val f = dlg.files.firstOrNull() ?: return
            wavFile = f
            error = null
        }

        fun transcribe() {
            val f = wavFile ?: return
            busy = true
            error = null
            scope.launch(Dispatchers.Default) {
                try {
                    val (pcm, sr) = readWavMono(f)
                    val bytes = V2mEngine.transcribe(pcm, sr, params)
                    if (bytes == null) {
                        error = "транскрипция не удалась (см. stderr)"
                    } else {
                        val song = runCatching { parseMidiSong(bytes) }.getOrNull()
                        val v = Version(
                            midi = bytes,
                            notes = song?.notes ?: emptyList(),
                            report = V2mEngine.lastReport(),
                            wavName = f.name,
                            params = params,
                        )
                        versions = versions + v
                        selected = versions.size - 1
                    }
                } catch (e: Exception) {
                    error = e.message ?: e.toString()
                } finally {
                    busy = false
                }
            }
        }

        fun listen() {
            val v = versions.getOrNull(selected) ?: return
            try {
                val tmp = File.createTempFile("v2m-listen", ".mid")
                tmp.writeBytes(v.midi)
                Desktop.getDesktop().open(tmp)
            } catch (e: Exception) {
                error = "не удалось открыть плеер: ${e.message}"
            }
        }

        fun saveAs(ext: String, v: Version, writer: (String, Version) -> Boolean) {
            val base = (wavFile?.nameWithoutExtension ?: "result") + ".$ext"
            val dlg = FileDialog(null as Frame?, "Сохранить .$ext", FileDialog.SAVE)
            dlg.file = base
            dlg.isVisible = true
            val dir = dlg.directory ?: return
            val name = dlg.file ?: return
            val path = File(dir, name).absolutePath
            if (!writer(path, v)) error = "не удалось записать $path (см. stderr)"
        }

        Column(Modifier.fillMaxSize()) {
            // Scrollable content area (buttons stick to the bottom)
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = ::chooseWav, enabled = !busy) { Text("Выбрать WAV") }
                    Text(wavFile?.name ?: "файл не выбран", style = MaterialTheme.typography.body2)
                }

                // 1. Параметры — свёртываемые, подсказки по долгому тапу, значения в %
                Collapsible("Параметры", defaultOpen = true) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ParamSlider("Порог начала ноты", params.onsetThreshold, "onsetThreshold") { params = params.copy(onsetThreshold = it) }
                        ParamSlider("Порог звучания", params.frameThreshold, "frameThreshold") { params = params.copy(frameThreshold = it) }
                        ParamSlider("Компрессия громкости", params.velocityCompress, "velocityCompress") { params = params.copy(velocityCompress = it) }
                        ParamSlider("Гармонизация: сдвиг", params.globalShift, "globalShift") { params = params.copy(globalShift = it) }
                        ParamSlider("Гармонизация: лад-снап", params.modeSnap, "modeSnap") { params = params.copy(modeSnap = it) }
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            ParamInt("Мин. длина ноты (кадр≈11.6 мс)", params.minNoteLen, 1..50, "minNoteLen") { params = params.copy(minNoteLen = it) }
                            ParamInt("Слияние фрагментов (п/т)", params.harmonizeMerge, 0..3, "harmonizeMerge") { params = params.copy(harmonizeMerge = it) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            ParamCheck("мелодический проход", params.useMelodiaTrick, "useMelodiaTrick") { params = params.copy(useMelodiaTrick = it) }
                            ParamCheck("питч-бенды", params.includePitchBends, "includePitchBends") { params = params.copy(includePitchBends = it) }
                        }
                        ParamCheck("определять тональность", detectKey, "detectKey") { detectKey = it }
                    }
                }

                error?.let { Text(it, color = MaterialTheme.colors.error, style = MaterialTheme.typography.body2) }

                // 3. Версии — скроллер с радиокнопками (первая серая до первого результата)
                Collapsible("Версии", defaultOpen = true) {
                    LazyColumn(Modifier.heightIn(max = 96.dp)) {
                        items(maxOf(versions.size, 1)) { i ->
                            val v = versions.getOrNull(i)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = v != null && i == selected,
                                    onClick = { selected = i },
                                    enabled = v != null,
                                )
                                Text("Версия ${i + 1}" + (v?.let { " — ${it.wavName}, ${it.notes.size} нот" } ?: " (нет результата)"),
                                     style = MaterialTheme.typography.body2)
                            }
                        }
                    }
                }

                // 2. Отчёт — свёртываемый, только полезное (без Schema error),
                //    текст выделяется для копирования
                Collapsible("Отчёт") {
                    val v = current
                    if (v != null) {
                        val song = runCatching { parseMidiSong(v.midi) }.getOrNull()
                        val key = if (detectKey) parseKeyFromReport(v.report) else null
                        val head = buildString {
                            appendLine("параметры: ${paramsCli(v.params)}")
                            appendLine("файл: ${v.wavName}")
                            if (song != null) {
                                appendLine("темп: %.2f BPM, размер %d/%d, нот: %d".format(song.tempoBpm, song.tsNum, song.tsDen, v.notes.size))
                            }
                            if (key != null) {
                                appendLine("тональность: ${key.name} (${key.alterationsText})")
                            }
                        }
                        SelectionContainer {
                            Text(head + (v.report.ifEmpty { "(пусто)" }), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.body2)
                        }
                    } else {
                        Text("(результатов нет)")
                    }
                }

                // 4. Ноты — свёртываемые, моноширинно, фиксированные маски колонок
                Collapsible("Ноты", defaultOpen = true) {
                    val v = current
                    if (v != null) {
                        val song = runCatching { parseMidiSong(v.midi) }.getOrNull()
                        if (song != null) {
                            val key = if (detectKey) parseKeyFromReport(v.report) else null
                            val rows = buildRows(song)
                            SelectionContainer {
                                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                                    item {
                                        Text("  такт:доля | нота        | длит.с  | громк.",
                                             fontFamily = FontFamily.Monospace,
                                             style = MaterialTheme.typography.body2,
                                             color = MaterialTheme.colors.primary)
                                    }
                                    items(rows) { r ->
                                        val (carry, beat) = beatFrac(r.beat - 1.0, song.tsNum)
                                        val measure = r.measure + carry
                                        val frac = durationFrac(r.durationQuarters, song.tsNum)
                                        val label = if (r.isRest) "z" + frac
                                        else {
                                            val n = noteLabel(r.pitch, key)
                                            if (n[0] == '^' || n[0] == '_' || n[0] == '=') n + frac else " " + n + frac
                                        }
                                        Text(
                                            "  %02d:%-5s | %-11s | %7.2f | %3d".format(
                                                measure, beat, label, r.durationQuarters, r.velocity),
                                            fontFamily = FontFamily.Monospace,
                                            style = MaterialTheme.typography.body2,
                                        )
                                    }
                                }
                            }
                        } else {
                            Text("(не удалось разобрать MIDI)")
                        }
                    } else {
                        Text("(результатов нет)")
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        current?.let { v ->
                            saveAs("mid", v) { p, ver ->
                                val json = buildParamsJson(ver.wavName, ver.params, ver.report,
                                    parseKeyFromReport(ver.report))
                                File(p).writeBytes(midiWithMetaTrack(ver.midi, json)); true
                            }
                        }
                    }, enabled = current != null) { Text("Сохранить .mid") }
                    Button(onClick = { current?.let { v -> saveAs("musicxml", v) { p, ver -> V2mEngine.midiToMusicXml(ver.midi, p) } } },
                           enabled = current != null) { Text("Сохранить .musicxml") }
                }
            }

            // Sticky bottom: Транскрипт / Слушать stay visible
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = ::transcribe, enabled = !busy && wavFile != null) { Text(if (busy) "Идёт обработка..." else "Транскрипт") }
                Button(onClick = ::listen, enabled = !busy && versions.isNotEmpty()) { Text("Слушать") }
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun ParamSlider(label: String, value: Float, helpKey: String, onChange: (Float) -> Unit) {
    var percent by remember { mutableStateOf((value * 100).roundToInt()) }
    Column {
        ParamLabel("$label: $percent%", helpKey)
        Slider(percent.toFloat(), { p -> percent = p.roundToInt(); onChange(p / 100f) },
               valueRange = 0f..100f, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ParamInt(label: String, value: Int, range: IntRange, helpKey: String, onChange: (Int) -> Unit) {
    Column {
        ParamLabel("$label: $value", helpKey)
        Slider(value.toFloat(), { onChange(it.toInt()) }, valueRange = range.first.toFloat()..range.last.toFloat(), modifier = Modifier.fillMaxWidth(0.5f))
    }
}

@Composable
private fun ParamCheck(label: String, checked: Boolean, helpKey: String, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        ParamLabel(label, helpKey)
    }
}
