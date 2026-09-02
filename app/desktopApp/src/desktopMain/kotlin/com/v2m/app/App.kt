package com.v2m.app

import java.util.Locale
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
fun App() {
    MaterialTheme {
        val prefs = remember { Preferences.load() }
        var params by remember { mutableStateOf(prefs.params) }
        var wavFile by remember { mutableStateOf<File?>(null) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var detectKey by remember { mutableStateOf(prefs.detectKey) }
        var instrument by remember { mutableStateOf(prefs.instrument ?: (prefs.params.program + 1).coerceIn(1, 128)) } // 1..128 (GM)
        var clef by remember { mutableStateOf(prefs.clef) } // 0 = G (скрипичный), 1 = F (басовый); экспорт MusicXML
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
        val scope = rememberCoroutineScope()

        val current: Version? = versions.getOrNull(selected)

        fun savePrefs() {
            Preferences.save(params, detectKey, instrument, clef, lastWavDir, lastMidiDir, lastXmlDir)
        }

        /** MIDI bytes as exported: normalized note events (no repeated
         *  attacks / stray releases — MuseScore misreads the tempo of such
         *  files), instrument patch + final tempo/size overrides. */
        fun exportMidi(midi: ByteArray): ByteArray =
            finalizeMidi(normalizeMidi(patchProgram(midi, instrument)), finalTempoOverride, finalSizeOverride)

        DisposableEffect(Unit) {
            onDispose { savePrefs() }
        }

        // Persist on every change so an abrupt exit (crash, kill) loses nothing.
        LaunchedEffect(params, detectKey, instrument, clef) { savePrefs() }

        fun chooseWav() {
            val dlg = FileDialog(null as Frame?, Strings.loadTitle, FileDialog.LOAD)
            lastWavDir?.let { dlg.directory = it }
            dlg.isVisible = true
            val f = dlg.files.firstOrNull() ?: return
            wavFile = f
            error = null
            lastWavDir = dlg.directory
            savePrefs()
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
                    savePrefs()
                }
            }
        }

        fun listen() {
            val v = versions.getOrNull(selected) ?: return
            try {
                val tmp = File.createTempFile("v2m-listen", ".mid")
                tmp.writeBytes(exportMidi(v.midi))
                Desktop.getDesktop().open(tmp)
            } catch (e: Exception) {
                error = Strings.listenFailed.format(e.message)
            }
        }

        fun saveAs(ext: String, v: Version, writer: (String, Version) -> Boolean) {
            val base = (wavFile?.nameWithoutExtension ?: "result") + ".$ext"
            val lastDir = if (ext == "mid") lastMidiDir else lastXmlDir
            val dlg = FileDialog(null as Frame?, Strings.saveTitle.format(ext), FileDialog.SAVE)
            dlg.file = base
            lastDir?.let { dlg.directory = it }
            dlg.isVisible = true
            val dir = dlg.directory ?: return
            val name = dlg.file ?: return
            val path = File(dir, name).absolutePath
            if (ext == "mid") lastMidiDir = dir else lastXmlDir = dir
            savePrefs()
            if (!writer(path, v)) error = Strings.saveFailed.format(path)
        }

        Column(Modifier.fillMaxSize()) {
            // Scrollable content area (buttons stick to the bottom)
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = ::chooseWav, enabled = !busy) { Text(Strings.wavButton) }
                    Text(wavFile?.name ?: Strings.noFile, style = MaterialTheme.typography.body2)
                }

                // 1. Параметры — свёртываемые, подсказки по долгому тапу, значения в %
                Collapsible(Strings.secParams, defaultOpen = true) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ParamSlider(Strings.onsetLabel, params.onsetThreshold, "onsetThreshold") { params = params.copy(onsetThreshold = it) }
                        ParamSlider(Strings.frameLabel, params.frameThreshold, "frameThreshold") { params = params.copy(frameThreshold = it) }
                        ParamSlider(Strings.velocityLabel, params.velocityCompress, "velocityCompress") { params = params.copy(velocityCompress = it) }
                        ParamSlider(Strings.shiftLabel, params.globalShift, "globalShift") { params = params.copy(globalShift = it) }
                        ParamSlider(Strings.snapLabel, params.modeSnap, "modeSnap") { params = params.copy(modeSnap = it) }
                        ParamMs(Strings.minLenLabel, params.minNoteLen, "minNoteLen", { params = params.copy(minNoteLen = it) })
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            ParamInt(Strings.mergeLabel, params.harmonizeMerge, 0..3, "harmonizeMerge") { params = params.copy(harmonizeMerge = it) }
                            ParamMs(Strings.energyLabel, params.energyTol, "energyTol", { params = params.copy(energyTol = it) }, rangeMs = 0f..350f, maxFrames = 30)
                            ParamInt(Strings.minBendLabel, params.minBendBins, 0..5, "minBendBins") { params = params.copy(minBendBins = it) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            ParamRange(Strings.tempoLabel, params.tempoBpm, 0f..300f, "tempoBpm") { params = params.copy(tempoBpm = it) }
                            ParamRange(Strings.toleranceLabel, params.toleranceMs, 0f..200f, "toleranceMs") { params = params.copy(toleranceMs = it) }
                        }
                        ParamSelect(Strings.quantizeLabel, Strings.QUANTIZE_OPTIONS, params.quantize, "quantize") { params = params.copy(quantize = it) }
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            ParamCheck(Strings.melodiaLabel, params.useMelodiaTrick, "useMelodiaTrick") { params = params.copy(useMelodiaTrick = it) }
                            ParamCheck(Strings.bendsLabel, params.includePitchBends, "includePitchBends") { params = params.copy(includePitchBends = it) }
                        }
                        ParamCheck(Strings.keyLabel, detectKey, "detectKey") { detectKey = it }
                    }
                }

                error?.let { Text(it, color = MaterialTheme.colors.error, style = MaterialTheme.typography.body2) }

                // 3. Версии — скроллер с радиокнопками (первая серая до первого результата)
                Collapsible(Strings.secVersions, defaultOpen = true) {
                    LazyColumn(Modifier.heightIn(max = 96.dp)) {
                        items(maxOf(versions.size, 1)) { i ->
                            val v = versions.getOrNull(i)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = v != null && i == selected,
                                    onClick = { selected = i },
                                    enabled = v != null,
                                )
                                Text(
                                    if (v != null) {
                                        val song = runCatching { parseMidiSong(v.midi) }.getOrNull()
                                        // The key name sits right after the note count so a
                                        // "NN% гарм." figure is read together with the mode
                                        // it was measured against (the "C# lydian" episode).
                                        val key = parseKeyFromReport(v.report)
                                        val keyPart = key?.let { ", ${it.name}" } ?: ""
                                        val harm = harmonyPct(v.notes, key)
                                            ?.let { ", $it% гарм." } ?: ""
                                        String.format(Locale.ROOT, Strings.versionRow,
                                            i + 1, song?.tsNum ?: 0, song?.tsDen ?: 0,
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

                // 2. Отчёт — свёртываемый, только полезное (без Schema error),
                //    текст выделяется для копирования
                Collapsible(Strings.secReport) {
                    val v = current
                    if (v != null) {
                        val song = runCatching { parseMidiSong(v.midi) }.getOrNull()
                        val key = if (detectKey) parseKeyFromReport(v.report) else null
                        val head = buildString {
                            appendLine(Strings.paramsLine.format(paramsCli(v.params)))
                            appendLine(Strings.fileLine.format(v.wavName))
                            if (song != null) {
                                appendLine(String.format(Locale.ROOT, Strings.tempoLine, song.tempoBpm, song.tsNum, song.tsDen, v.notes.size))
                            }
                            if (key != null) {
                                appendLine(Strings.keyLine.format(key.name, key.alterationsText))
                            }
                            // Native "mode fit:" and "tempo:" lines are shown in
                            // Russian below (Strings.modeFitLine) or dropped as
                            // duplicates — filter them out of the raw report
                            parseModeFit(v.report)?.let {
                                appendLine(String.format(Locale.ROOT, Strings.modeFitLine,
                                    it.keyName, it.pct, it.cents, it.strength))
                            }
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

                // 4. Ноты — свёртываемые, моноширинно, фиксированные маски колонок
                Collapsible(Strings.secNotes, defaultOpen = true) {
                    val v = current
                    if (v != null) {
                        val song = runCatching { parseMidiSong(v.midi) }.getOrNull()
                        if (song != null) {
                            val key = if (detectKey) parseKeyFromReport(v.report) else null
                            val rows = buildRows(song)
                            SelectionContainer {
                                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                                    item {
                                        Text(Strings.notesHeader,
                                             fontFamily = FontFamily.Monospace,
                                             style = MaterialTheme.typography.body2,
                                             color = MaterialTheme.colors.primary)
                                    }
                                    items(rows) { r ->
                                        // The absolute position in quarters is kept; the bar
                                        // grid is re-computed under the effective signature
                                        // (the export override or the detected one). The row
                                        // measure/beat come from a tsNum-quarters bar grid;
                                        // the *4/tsDen term converts them to real quarters,
                                        // and beatPos uses num*4/den per bar, so a den of 8
                                        // halves the bar (a 6/8 bar is 3 quarters long).
                                        val effSize = finalSizeOverride ?: (song.tsNum to song.tsDen)
                                        val rowQ = (r.measure - 1.0) * song.tsNum + (r.beat - 1.0)
                                        val qFromStart = rowQ * 4.0 / song.tsDen
                                        val (measure, tilde, frac) = beatPos(qFromStart, effSize.first, effSize.second)
                                        val cell = noteCell(r.pitch, r.isRest, r.durationQuarters, key)
                                        val vel = if (r.isRest) "" else r.velocity.toString()
                                        Text(
                                            // The "~" marker occupies a fixed 1-char field so
                                            // off-grid beats do not shift the measure number.
                                            String.format(Locale.ROOT, "  %1s%02d:%s | %s | %7.2f | %3s",
                                                tilde, measure, frac, cell, r.endSec - r.startSec, vel),
                                            fontFamily = FontFamily.Monospace,
                                            style = MaterialTheme.typography.body2,
                                        )
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

                // Финальные темп и размер — с каким темпом/размером MIDI будет
                // экспортирован (прослушивание, сохранение). Применяются к
                // байтам MIDI на лету; на транскрипцию не влияют. Правка
                // создаёт override (0 или "0/0" возвращает автодетект).
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(Strings.finalTempoLabel, style = MaterialTheme.typography.body2)
                    InlineField(
                        value = finalTempoText,
                        onValueChange = { s ->
                            val digits = s.filter { it.isDigit() }.take(3)
                            val v = digits.toIntOrNull()
                            if (v != null && v in 0..300) {
                                finalTempoText = v.toString()
                                finalTempoOverride = if (v > 0) v.toDouble() else null
                            } else {
                                finalTempoText = finalTempoOverride?.let { String.format(Locale.ROOT, "%.1f", it) } ?: finalTempoDetected
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

                // Ключ для экспорта MusicXML (скрипичный/басовый); на
                // транскрипцию и MIDI не влияет, применяется при сохранении.
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

                // 5. Инструмент — выбор GM; на транскрипцию не влияет, применяется
                //    при прослушивании и сохранении (в отчёт не попадает)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(Strings.instrumentLabel, style = MaterialTheme.typography.body2)
                    var text by remember { mutableStateOf(instrument.toString()) }
                    var menuOpen by remember { mutableStateOf(false) }
                    InlineField(
                        value = text,
                        onValueChange = { s ->
                            val digits = s.filter { it.isDigit() }.take(3)
                            val v = digits.toIntOrNull()
                            if (v != null && v in 1..128) {
                                text = v.toString()
                                instrument = v
                            } else {
                                text = instrument.toString() // invalid input — show the actual value
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

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        current?.let { v ->
                            saveAs("mid", v) { p, ver ->
                                val json = buildParamsJson(ver.wavName, ver.params, ver.report,
                                    parseKeyFromReport(ver.report))
                                File(p).writeBytes(midiWithMetaTrack(exportMidi(ver.midi), json)); true
                            }
                        }
                    }, enabled = current != null) { Text(Strings.saveMid) }
                    Button(onClick = { current?.let { v -> saveAs("musicxml", v) { p, ver ->
                        V2mEngine.midiToMusicXml(exportMidi(ver.midi), p, clef)
                    } } }, enabled = current != null) { Text(Strings.saveMusicXml) }
                }
            }

            // Sticky bottom: Транскрипт / Слушать stay visible
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = ::transcribe, enabled = !busy && wavFile != null) { Text(if (busy) Strings.busy else Strings.transcribe) }
                Button(onClick = ::listen, enabled = !busy && versions.isNotEmpty()) { Text(Strings.listen) }
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

/** Frame-count params shown in milliseconds (the engine counts frames:
 *  1 frame = 256 samples @ 22050 Hz ≈ 11.61 ms). [rangeMs]/[maxFrames] bound
 *  the slider and the converted frame value (energy-tol: 0..30 frames =
 *  0..~350 ms; min note length: 10..580 ms). */
@Composable
private fun ParamMs(label: String, frames: Int, helpKey: String, onChange: (Int) -> Unit,
                    rangeMs: ClosedFloatingPointRange<Float> = 10f..580f, maxFrames: Int = 50) {
    val ms = (frames * 11.61).roundToInt()
    var msState by remember { mutableStateOf(ms) }
    Column {
        ParamLabel("$label: $msState мс", helpKey)
        Slider(msState.toFloat(), { v ->
            msState = v.roundToInt()
            onChange((v / 11.61).roundToInt().coerceIn(0, maxFrames))
        }, valueRange = rangeMs, modifier = Modifier.fillMaxWidth(0.5f))
    }
}

/** Numeric slider with its own range (not a percent), integer display. */
@Composable
private fun ParamRange(label: String, value: Float, range: ClosedFloatingPointRange<Float>, helpKey: String, onChange: (Float) -> Unit) {
    Column {
        ParamLabel("$label: ${value.roundToInt()}", helpKey)
        Slider(value, onChange, valueRange = range, modifier = Modifier.fillMaxWidth(0.5f))
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

/** Compact inline field: no box, just an underline — keeps rows with several
 *  editable values visually light (final tempo/time signature). */
@Composable
private fun InlineField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
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
        textStyle = MaterialTheme.typography.body2,
    )
}
