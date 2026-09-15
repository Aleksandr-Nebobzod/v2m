package com.v2m.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/** Границы обзора и кадр — те же, что в basicpitch/src/spectrogram.hpp. */
private const val SPEC_F_MIN = 10.0
private const val SPEC_F_MAX = 10000.0
private const val SPEC_HOP = 256.0
private const val SPEC_SR = 22050.0

/** Гаммы спектрограммы (билд #49): у каждой — 6 опорных узлов, между узлами
 *  линейная интерполяция в RGB (256 цветов). «Магма» — прежний ручной подбор
 *  (билд #47, оставлен по решению А.М.); «Плазма»/«Виридис» — узлы, снятые из
 *  таблиц карт plasma/viridis (Nathaniel J. Smith, Stéfan van der Walt, CC0)
 *  в точках 0.0/0.2/0.4/0.6/0.8/1.0. «Монохром» — вместо «Инферно» по
 *  приёмке #50 п.3; нейтральная серая шкала (билд #52, п.4 приёмки #51:
 *  «лучше сделать монохром нейтральным — без тёплого-холодного»):
 *  R = G = B на всём протяжении, инверсия дневной темы даёт зеркальный
 *  серый (белый ↔ чёрный), без цветного оттенка. */
internal enum class Gamma(val id: String, val title: String, val stops: List<Pair<Float, IntArray>>) {
    MAGMA(
        "magma", "Магма", listOf(
            0.00f to intArrayOf(0, 0, 0),
            0.20f to intArrayOf(28, 16, 68),
            0.40f to intArrayOf(96, 24, 110),
            0.60f to intArrayOf(186, 54, 85),
            0.80f to intArrayOf(248, 142, 40),
            1.00f to intArrayOf(252, 250, 214),
        ),
    ),
    MONOCHROME(
        "monochrome", "Монохром", listOf(
            0.00f to intArrayOf(0, 0, 0),       // чёрный
            0.20f to intArrayOf(51, 51, 51),
            0.40f to intArrayOf(102, 102, 102),
            0.60f to intArrayOf(153, 153, 153),
            0.80f to intArrayOf(204, 204, 204),
            1.00f to intArrayOf(255, 255, 255), // белый
        ),
    ),
    PLASMA(
        "plasma", "Плазма", listOf(
            0.00f to intArrayOf(13, 8, 135),
            0.20f to intArrayOf(106, 0, 168),
            0.40f to intArrayOf(177, 42, 144),
            0.60f to intArrayOf(225, 100, 98),
            0.80f to intArrayOf(252, 166, 54),
            1.00f to intArrayOf(240, 249, 33),
        ),
    ),
    VIRIDIS(
        "viridis", "Виридис", listOf(
            0.00f to intArrayOf(68, 1, 84),
            0.20f to intArrayOf(65, 68, 135),
            0.40f to intArrayOf(42, 120, 142),
            0.60f to intArrayOf(34, 168, 132),
            0.80f to intArrayOf(122, 209, 81),
            1.00f to intArrayOf(253, 231, 37),
        ),
    );

    companion object {
        val DEFAULT = MAGMA

        /** Гамма по идентификатору из prefs; неизвестный — [DEFAULT].
         *  "inferno" — прежний id гаммы «Монохром» (билд #49): сохранённый
         *  выбор не должен прыгать на «Магму» после переименования (билд #51). */
        fun byId(id: String?): Gamma = when (id) {
            "inferno" -> MONOCHROME
            else -> values().firstOrNull { it.id == id } ?: DEFAULT
        }
    }
}

/** Палитра гаммы: 0 — тишина, 255 — верх шкалы; ARGB (0xFF в альфе).
 *  [invert] — ровно инвертированные цвета для дневной темы (проба билда #49). */
internal fun gammaPalette(g: Gamma, invert: Boolean = false): IntArray {
    val nodes = g.stops
    return IntArray(256) { i ->
        val t = i / 255f
        var k = 0
        while (k < nodes.size - 2 && t > nodes[k + 1].first) k++
        val (t0, c0) = nodes[k]
        val (t1, c1) = nodes[k + 1]
        val f = if (t1 > t0) (t - t0) / (t1 - t0) else 0f
        fun ch(c: Int, idx: Int): Int {
            val v = (c + (nodes[k + 1].second[idx] - c) * f).roundToInt()
            return if (invert) 255 - v else v
        }
        (0xFF shl 24) or (ch(c0[0], 0) shl 16) or (ch(c0[1], 1) shl 8) or ch(c0[2], 2)
    }
}

/** Матрица спектрограммы в картинку: X — частота (полоса 0 слева), Y — время
 *  (кадр 0 сверху). Пиксели палитры — BGRA (ColorType.BGRA_8888).
 *  internal — тот же код проверяет самотест (сохранение PNG без GUI). */
internal fun spectrogramBitmap(spec: Spectrogram, palette: IntArray): Bitmap? {
    if (spec.frames <= 0 || spec.bands <= 0) return null
    val w = spec.bands
    val h = spec.frames
    val px = ByteArray(w * h * 4)
    var i = 0
    for (frame in 0 until h) {
        for (band in 0 until w) {
            val c = palette[spec[frame, band]]
            px[i++] = (c and 0xFF).toByte()          // B
            px[i++] = ((c shr 8) and 0xFF).toByte()  // G
            px[i++] = ((c shr 16) and 0xFF).toByte() // R
            px[i++] = 0xFF.toByte()                  // A
        }
    }
    val info = ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
    val bmp = Bitmap()
    bmp.allocPixels(info)
    if (!bmp.installPixels(px)) return null
    return bmp
}

/** Спектрограмма входа (билд #47, вкладка «Спектр»): с чем пришлось работать
 *  модели — время (вертикаль, как у [NoteChart]), частота (горизонталь,
 *  логарифмическая шкала 10 Гц..10 кГц), яркость — уровень полосы. Показ
 *  не влияет на транскрипцию: [spec] считается по сырому аудио.
 *
 *  @param tScale окно по времени, с (общее с гистограммой)
 *  @param playPosSec позиция воспроизведения, с; < 0 — не играет
 *  @param gamma гамма цветов (меню «Гамма», билд #49)
 *  @param invert инверсия цветов — дневная тема (проба билда #49)
 *  @param holdSpec что показывать, пока канва нажата (билд #50: RAW —
 *         «перерисовывается данными из п.0», при отпускании снова
 *         обработанный материал); null — канва только показывает [spec]
 *  @param holdLabel метка в углу канвы в нажатом состоянии (RAW)
 */
@Composable
internal fun SpectrogramView(spec: Spectrogram, tScale: Float, playPosSec: Float = -1f,
                             gamma: Gamma = Gamma.DEFAULT, invert: Boolean = false,
                             holdSpec: Spectrogram? = null, holdLabel: String? = null) {
    val palette = remember(gamma, invert) { gammaPalette(gamma, invert) }
    // Нажатие/удержание (билд #50): пока палец/кнопка на канве — [holdSpec]
    var held by remember { mutableStateOf(false) }
    val shown = if (held && holdSpec != null) holdSpec else spec
    val image = remember(shown, palette) { spectrogramBitmap(shown, palette)?.asComposeImageBitmap() }
    if (image == null || shown.frames <= 0) return

    val viewHeight = 300.dp // фиксированное окно; канвас внутри прокручивается
    val secPerFrame = SPEC_HOP / SPEC_SR
    val totalSec = shown.frames * secPerFrame
    val effDur = max(totalSec, tScale.toDouble())
    val dpPerSec = viewHeight.value / tScale
    val density = LocalDensity.current
    val pxPerSec = with(density) { dpPerSec.dp.toPx() }
    val contentHeight = (effDur * dpPerSec).dp
    val scrollState = rememberScrollState()
    val textStyle = TextStyle(fontSize = 10.sp, color = MaterialTheme.colors.onSurface)

    val viewPx = with(density) { viewHeight.toPx() }
    LaunchedEffect(playPosSec) {
        if (playPosSec < 0f || playPosSec.toDouble() > effDur) return@LaunchedEffect
        val y = playPosSec * pxPerSec
        val s = scrollState.value
        val edge = with(density) { 12.dp.toPx() }
        val target = when {
            y < s + edge -> (y - edge).coerceAtLeast(0f)
            y > s + viewPx - edge -> (y - viewPx + edge).coerceAtLeast(0f)
            else -> return@LaunchedEffect
        }
        scrollState.scrollTo(target.roundToInt())
    }

    // X-координата частоты на логарифмической шкале (f_min..f_max = 0..1)
    fun freqX(f: Double, width: Float): Float =
        ((ln(f) - ln(SPEC_F_MIN)) / (ln(SPEC_F_MAX) - ln(SPEC_F_MIN))).toFloat() * width

    val textMeasurer = rememberTextMeasurer()
    // Цвета — до Canvas: внутри DrawScope нет доступа к MaterialTheme
    val gridColor = MaterialTheme.colors.onSurface.copy(alpha = 0.35f)
    val posColor = MaterialTheme.colors.primary
    // Плашка метки нажатого состояния (RAW) — фон сплошной, чтобы читалась
    // на любой картинке
    val badgeColor = MaterialTheme.colors.surface.copy(alpha = 0.85f)
    // Нажатие на канву — RAW (билд #50, п.4 приёмки #49). События не
    // поглощаются (requireUnconsumed = false, без consume), поэтому
    // прокрутка канвы продолжает работать; отпускание возвращает
    // обработанный материал. Без [holdSpec] жест не ставится
    val holdMod = if (holdSpec == null) Modifier else Modifier.pointerInput(holdSpec) {
        awaitPointerEventScope {
            while (true) {
                awaitFirstDown(requireUnconsumed = false)
                held = true
                try {
                    waitForUpOrCancellation()
                } finally {
                    held = false
                }
            }
        }
    }
    Column {
        Canvas(Modifier.fillMaxWidth().height(16.dp)) {
            for (f in listOf(100.0, 1000.0, 10000.0)) {
                val x = freqX(f, size.width)
                val label = if (f >= 1000) "${(f / 1000).roundToInt()}k" else "${f.roundToInt()}"
                val m = textMeasurer.measure(label, style = textStyle)
                drawText(m, topLeft = Offset(x - m.size.width / 2f, (size.height - m.size.height) / 2f))
            }
        }
        Column(Modifier.heightIn(max = viewHeight).verticalScroll(scrollState).then(holdMod)) {
            Canvas(Modifier.fillMaxWidth().height(contentHeight)) {
                drawImage(image, dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
                // Делительные линии частот — поверх картинки
                for (f in listOf(100.0, 1000.0, 10000.0)) {
                    val x = freqX(f, size.width)
                    drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                }
                // Полоска позиции воспроизведения (как в NoteChart, п.5б)
                if (playPosSec >= 0f) {
                    val y = playPosSec * pxPerSec
                    if (y <= size.height) {
                        drawLine(posColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 2f)
                    }
                }
                // Метка нажатого состояния (RAW, билд #50): у верхнего края
                // видимого окна — прокрутка канвы её не сдвигает
                if (held && holdLabel != null) {
                    val m = textMeasurer.measure(holdLabel, style = textStyle)
                    val y = scrollState.value.toFloat() + 20f
                    drawRect(badgeColor, topLeft = Offset(4f, y),
                        size = Size(m.size.width + 8f, m.size.height + 4f))
                    drawText(m, topLeft = Offset(8f, y + 2f))
                }
            }
        }
    }
}
