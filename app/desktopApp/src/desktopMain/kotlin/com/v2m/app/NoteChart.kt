package com.v2m.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** Pitch chart («Гистограмма», замечания А.М. 2026-09-05): notes as
 *  horizontal bars on a pitch × time canvas. The bar of a note starts at the
 *  left edge and ends at the note's exact frequency — pitch + cents/100 in
 *  semitone slots (билд #36: «окончание столбика означает частоту ноты»),
 *  so a note sitting on a scale degree just touches its divider line, a
 *  microtonal one ends a few percents of the slot past it. The left edge of
 *  the canvas sits at (the lowest note minus 3 semitones); the horizontal
 *  extent of a bar IS its pitch. Bars are drawn high → low: a lower note
 *  that starts later never covers the tail of a higher one (замечание 5 —
 *  «незакрытие при наслаивании»). Y — time, running down at [dpPerSec];
 *  the fixed view window shows [tScale] seconds of music, longer recordings
 *  scroll. The header row (not scrolling) marks the scale degrees I (tonic,
 *  thicker line), IV (subdominant) and V (dominant) of [key] — thin
 *  vertical divider lines run down the whole canvas under the labels; the
 *  lines stand on the frequencies of the degrees (left edges of their
 *  pitch slots in 12-EDO). */
/**
 * @param onNoteClick тап/клик в любом месте столбика ноты (замечание 8 и
 *  А.М. 2026-09-06): вызывается с самой нотой (билд #35 — панели правки
 *  нужны её тики/канал/микротон) — видимой в точке клика, чья заливка
 *  покрывает точку; при наложении — низшая из покрывающих (она рисуется
 *  поверх, отрисовка high → low). Клик мимо заливок — без звука.
 *  Попавший столбик подсвечивается рамкой, пока звучит тон (200 мс);
 *  [mutedKeys] — заглушенные ноты: не звучат, на гистограмме — полоска-
 *  «рамка вокруг нуля» (длительность 0), клик в ±[MUTED_HIT_PX] px от
 *  полоски тоже попадает в ноту — чтобы [X] можно было вернуть.
 *  [selectedKey] — выделенная нота (постоянная рамка, А.М., п.4). */
@Composable
fun NoteChart(
    notes: List<MidiNote>,
    key: KeyInfo?,
    tScale: Float,
    mutedKeys: Set<Long> = emptySet(),
    selectedKey: Long? = null,
    onNoteClick: ((MidiNote) -> Unit)? = null,
) {
    // (билд #35) радиус клика вокруг полоски заглушенной ноты (в px)
    // — полоска нулевой длины слишком тонка для точного попадания
    val mutedHitPx = 4f
    if (notes.isEmpty()) return
    val lowPitch = notes.minOf { it.pitch } - 3 // «самая нижняя нота минус 3 тона»
    val maxPitch = notes.maxOf { it.pitch }
    val nSlots = maxPitch - lowPitch + 1 // one slot per semitone
    val minVel = notes.minOf { it.velocity }
    val maxVel = notes.maxOf { it.velocity }
    val duration = notes.maxOf { it.endSec }
    val effDur = max(duration, tScale.toDouble()) // the window is never empty
    val viewHeight = 300.dp // fixed view; the canvas inside scrolls
    val dpPerSec = viewHeight.value / tScale // e.g. t=3 -> 100 dp per second
    val fill = MaterialTheme.colors.onBackground
    // Разметка и метки ступеней — «основной» цвет (onSurface), акцент
    // (primary) — только рамка звучащей ноты (замечание А.М. 2026-09-06, 4.1)
    val lineColor = MaterialTheme.colors.onSurface
    val accent = MaterialTheme.colors.primary
    val textStyle = TextStyle(fontSize = 10.sp, color = lineColor)
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val pxPerSec = with(density) { (viewHeight.value / tScale).dp.toPx() } // px of one second of music
    val scrollState = rememberScrollState()

    // The bar of the note tapped last is outlined while its tone sounds
    // (~1/8 s; the counter restarts the timer on a repeat tap of the same
    // note — A new click replaces the current playback).
    var played by remember { mutableStateOf<Pair<MidiNote, Int>?>(null) }
    LaunchedEffect(played) {
        if (played != null) {
            delay(200)
            played = null
        }
    }

    // Scale degrees of the key: I tonic (bold, thicker line), IV subdominant
    // (+5; lydian raises it to +6), V dominant. Null key — no header.
    data class Degree(val label: String, val pc: Int, val bold: Boolean)
    val degrees = key?.let { k ->
        val iv = if (k.modeName.startsWith("lydian")) (k.rootPc + 6) % 12 else (k.rootPc + 5) % 12
        listOf(
            Degree("I", k.rootPc, true),
            Degree("IV", iv, false),
            Degree("V", (k.rootPc + 7) % 12, false),
        )
    }

    Column {
        if (degrees != null) {
            // Header: degree labels over the left edge of their pitch-slot
            // column (the vertical line below each label divides the columns).
            Canvas(Modifier.fillMaxWidth().height(16.dp)) {
                val slot = size.width / nSlots
                for (d in degrees) {
                    for (p in lowPitch..maxPitch) {
                        if (p % 12 != d.pc) continue
                        val x = slot * (p - lowPitch)
                        val m = textMeasurer.measure(
                            d.label,
                            style = textStyle.copy(
                                fontWeight = if (d.bold) FontWeight.Bold else FontWeight.Normal,
                                color = if (d.bold) lineColor else lineColor.copy(alpha = 0.75f),
                            ),
                        )
                        drawText(m, topLeft = Offset(x - m.size.width / 2f, (size.height - m.size.height) / 2f))
                    }
                }
            }
        }
        // Scrollable canvas below the header; degree dividers run its full
        // height and scroll together with the music.
        //
        // Hit-testing lives on the scroll container, not on the canvas. The
        // pointer coordinates arriving here are in the CONTENT space (they
        // already include the scroll offset — the pointerInput modifier
        // sits inside the scrollable content), so the time is
        // t = pos.y / pxPerSec with no extra scroll term: adding
        // scrollState.value again counted the offset twice and every click
        // after the first scroll fell seconds past the bar top (журнал
        // билда #33: клик в макушку p60 при scroll=156 давал t=4.66 вместо
        // t=2.81 — «мимо»).
        //
        // The note fires on the press, not on the release: detectTapGestures
        // cancelled by a few pixels of finger/mouse travel read as "very
        // imprecise taps", and the sound must start with the click
        // (замечания А.М. 2026-09-06, 5 и 5.1).
        Column(
            Modifier.heightIn(max = viewHeight).verticalScroll(scrollState)
                .pointerInput(notes, tScale, onNoteClick, mutedKeys) {
                    if (onNoteClick == null) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val slotW = size.width.toFloat() / nSlots // ширина слота-полутона
                        // Время по content-координате клика (она уже включает
                        // сдвиг скролла — см. комментарий над контейнером).
                        val t = (down.position.y / pxPerSec).toDouble() // startSec/endSec — Double
                        // Хит — по «заливке» столбика (билд #34): столбик ноты
                        // залит от левого края канвы до правого края слота её
                        // питча, клик по любой части заливки должен звучать.
                        // Журнал билда #33 показал: активна была только правая
                        // полоска шириной в слот — клики левее давали «мимо»,
                        // хотя заливка под курсором была. Звучит низшая из нот,
                        // покрывающих точку клика: она рисуется поверх остальных
                        // (отрисовка high → low), т.е. видимая в этой точке нота.
                        // Заглушенные ноты (нулевая длительность) ловят клик в
                        // ±MUTED_HIT_PX от полоски — иначе в них нельзя попасть.
                        val hit = notes.asSequence()
                            .filter {
                                val muted = noteKeyOf(it.startTick, it.pitch) in mutedKeys
                                val inT = if (muted) kotlin.math.abs(t - it.startSec) <= mutedHitPx / pxPerSec
                                else t >= it.startSec && t < it.endSec
                                // Окончание столбика — частота ноты (билд #36): клик в пределах заливки
                                inT && down.position.x <= slotW * (it.pitch + it.cents / 100f - lowPitch)
                            }
                            .minByOrNull { it.pitch }
                        // Журнал клика: координаты и что произошло — какая нота
                        // поймана и позиция в столбике от макушки (0 % = верхняя
                        // кромка столбика, 100 % = низ).
                        if (Log.DEBUG) {
                            val at = "x=${down.position.x.roundToInt()} y=${down.position.y.roundToInt()} " +
                                "scroll=${scrollState.value} t=${"%.3f".format(Locale.ROOT, t)}"
                            if (hit == null) {
                                Log.d("tap", "$at → мимо: нет ноты, покрывающей клик")
                            } else {
                                val muted = noteKeyOf(hit) in mutedKeys
                                val barPct = if (muted) -1 else ((t - hit.startSec) / (hit.endSec - hit.startSec) * 100).toInt()
                                Log.d("tap", "$at → нота p=${hit.pitch} v=${hit.velocity}" +
                                    (if (muted) " (заглушена)" else "") +
                                    " start=${"%.3f".format(Locale.ROOT, hit.startSec)} " +
                                    "длит=${"%.3f".format(Locale.ROOT, hit.endSec - hit.startSec)} " +
                                    "клик: $barPct% от макушки столбика")
                            }
                        }
                        if (hit != null) {
                            // Заглушенная нота не звучит — только подсветка играющей
                            if (noteKeyOf(hit) !in mutedKeys) {
                                played = hit to ((played?.second ?: 0) + 1)
                            }
                            onNoteClick(hit)
                        }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxWidth().height((effDur * dpPerSec).dp)) {
                val slot = size.width / nSlots
                val scaleY = size.height / effDur.toFloat()
                // Degree dividers first — the bars are drawn on top of them
                if (degrees != null) {
                    for (d in degrees) {
                        val w = (if (d.bold) 1.6f else 1f).dp.toPx()
                        val alpha = if (d.bold) 0.9f else 0.5f
                        for (p in lowPitch..maxPitch) {
                            if (p % 12 != d.pc) continue
                            val x = slot * (p - lowPitch)
                            drawRect(lineColor.copy(alpha = alpha),
                                Offset(x - w / 2f, 0f), Size(w, size.height))
                        }
                    }
                }
                // Bars high → low so a lower note never hides the tail of a
                // higher one that started earlier (замечание 5). A muted note
                // (билд #35) has zero duration: it is drawn as a thin outline
                // bar at its start line — «полоска (рамка вокруг нуля)».
                val sorted = notes.sortedByDescending { it.pitch }
                val vRange = maxVel - minVel
                val minH = 1.dp.toPx()
                val muteH = 3.dp.toPx() // полоска заглушенной ноты (заметнее 1dp)
                val mutedColor = lineColor.copy(alpha = 0.55f)
                for (n in sorted) {
                    val y0 = (n.startSec * scaleY).toFloat()
                    // Конец столбика = частота ноты (билд #36): нота на ступени
                    // ровно дотягивается до её линии, микротон — доля слота
                    val xRight = slot * (n.pitch + n.cents / 100f - lowPitch)
                    if (noteKeyOf(n) in mutedKeys) {
                        drawRect(mutedColor, Offset(0f, y0), Size(xRight, muteH), style = Stroke(width = 1.dp.toPx()))
                        continue
                    }
                    val y1 = max((n.endSec * scaleY).toFloat(), y0 + minH) // zero-length stays visible
                    val alpha = if (vRange == 0) 0.9f
                    else 0.25f + 0.7f * (n.velocity - minVel).toFloat() / vRange.toFloat()
                    drawRect(fill.copy(alpha = alpha), Offset(0f, y0), Size(xRight, y1 - y0))
                }
                // The playing bar gets an outline (замечание А.М. 2026-09-06)
                played?.first?.let { n ->
                    val y0 = (n.startSec * scaleY).toFloat()
                    val y1 = max((n.endSec * scaleY).toFloat(), y0 + minH)
                    val xRight = slot * (n.pitch + n.cents / 100f - lowPitch)
                    drawRect(accent, Offset(0f, y0), Size(xRight, y1 - y0), style = Stroke(width = 2.dp.toPx()))
                }
                // The selected note keeps a permanent outline (билд #35, п.4:
                // «по клику нота выделяется постоянной рамкой») — поверх
                // подсветки играющей, пока длится тон.
                if (selectedKey != null) {
                    val n = notes.firstOrNull { noteKeyOf(it) == selectedKey } ?: return@Canvas
                    val muted = noteKeyOf(n) in mutedKeys
                    val y0 = (n.startSec * scaleY).toFloat()
                    val y1 = max((n.endSec * scaleY).toFloat(), y0 + if (muted) muteH else minH)
                    val xRight = slot * (n.pitch + n.cents / 100f - lowPitch)
                    drawRect(accent, Offset(0f, y0), Size(xRight, y1 - y0), style = Stroke(width = 2.dp.toPx()))
                }
            }
        }
    }
}
