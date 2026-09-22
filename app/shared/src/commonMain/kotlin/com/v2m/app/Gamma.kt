package com.v2m.app

/** Гаммы спектрограммы — данные палитр, вынесены из SpectrogramView.kt
 *  на этапе 3 плана docs/260921_android_plan.md: выбор гаммы хранится
 *  в prefs (Preferences.load), отрисовка остаётся в UI. */
/** Позиции узлов палитры (билд #56, OQ-19: «для всех (кроме Магмы) следует
 *  чуть шире сделать среднюю область, а края контрастнее»): крайние отрезки
 *  0.16 вместо 0.20 — рост из чёрного и выход в белый круче; средний отрезок
 *  0.28 вместо 0.20 — средние уровни занимают больше шкалы. */
private val NODE_POS_SHAPED = listOf(0.00f, 0.16f, 0.36f, 0.64f, 0.84f, 1.00f)

/** Прежние равномерные позиции — «Магма» (оставлена как есть, решение А.М.). */
private val NODE_POS_EVEN = listOf(0.00f, 0.20f, 0.40f, 0.60f, 0.80f, 1.00f)

/** Узлы палитры: [colors] по возрастанию уровня, позиции — [pos]. */
private fun stops(colors: List<IntArray>, pos: List<Float> = NODE_POS_SHAPED) =
    pos.mapIndexed { i, t -> t to colors[i] }

/** Гаммы спектрограммы (билд #49): у каждой — 6 опорных узлов, между узлами
 *  линейная интерполяция в RGB (256 цветов); порядок списка — по приёмке
 *  #55 (OQ-19): «Монохром» первым. «Магма» — прежний ручной подбор (билд #47,
 *  оставлен как есть); «Зима-Блю» — от чёрно-синего к белому через голубой,
 *  «Гольф» — от чёрно-зелёного к белому через травяной (билд #56, вместо
 *  «Плазмы» и «Виридиса»; их id из prefs ведут на новые гаммы; билд #57, п.3
 *  приёмки #56: «Тропик» переименована в «Гольф», цвета без изменений).
 *  «Монохром» —
 *  нейтральная серая шкала (билд #52, п.4 приёмки #51: «лучше сделать
 *  монохром нейтральным — без тёплого-холодного»): R = G = B на всём
 *  протяжении, инверсия дневной темы даёт зеркальный серый (белый ↔ чёрный),
 *  без цветного оттенка. */
enum class Gamma(val id: String, val title: String, val stops: List<Pair<Float, IntArray>>) {
    MONOCHROME(
        "monochrome", "Монохром", stops(listOf(
            intArrayOf(0, 0, 0),       // чёрный
            intArrayOf(51, 51, 51),
            intArrayOf(102, 102, 102),
            intArrayOf(153, 153, 153),
            intArrayOf(204, 204, 204),
            intArrayOf(255, 255, 255), // белый
        )),
    ),
    MAGMA(
        "magma", "Магма", stops(listOf(
            intArrayOf(0, 0, 0),
            intArrayOf(28, 16, 68),
            intArrayOf(96, 24, 110),
            intArrayOf(186, 54, 85),
            intArrayOf(248, 142, 40),
            intArrayOf(252, 250, 214),
        ), NODE_POS_EVEN),
    ),
    WINTERBLUE(
        "winter-blue", "Зима-Блю", stops(listOf(
            intArrayOf(0, 6, 20),        // чёрно-синий
            intArrayOf(14, 36, 92),
            intArrayOf(38, 92, 170),
            intArrayOf(96, 166, 222),    // голубой
            intArrayOf(178, 220, 245),
            intArrayOf(255, 255, 255),   // белый
        )),
    ),
    GOLF(
        "golf", "Гольф", stops(listOf(
            intArrayOf(0, 12, 6),        // чёрно-зелёный
            intArrayOf(14, 48, 22),
            intArrayOf(44, 110, 46),
            intArrayOf(108, 178, 74),    // травяной
            intArrayOf(184, 222, 142),
            intArrayOf(255, 255, 255),   // белый
        )),
    );

    companion object {
        val DEFAULT = MAGMA

        /** Гамма по идентификатору из prefs; неизвестный — [DEFAULT].
         *  Прежние id (билд #49) ведут на свои гаммы: сохранённый выбор не
         *  должен прыгать на «Магму» после переименования — "inferno" →
         *  «Монохром» (билд #51), "plasma" → «Зима-Блю», "viridis"/"tropic" →
         *  «Гольф» (билд #56; билд #57 — переименование «Тропика»). */
        fun byId(id: String?): Gamma = when (id) {
            "inferno" -> MONOCHROME
            "plasma" -> WINTERBLUE
            "viridis" -> GOLF
            "tropic" -> GOLF // id пробы билда #56 — не должен прыгать на «Магму» (билд #57)
            else -> values().firstOrNull { it.id == id } ?: DEFAULT
        }
    }
}
