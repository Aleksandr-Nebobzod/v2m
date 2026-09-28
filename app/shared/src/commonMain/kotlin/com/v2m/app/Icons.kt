package com.v2m.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/** Иконки интерфейса — [ImageVector] в общем коде (этап 4в, 2026-09-23).
 *  До этого лежали как SVG в `composeResources/drawable`: desktop их рисовал
 *  (Skia), на Android первый же кадр падал — `IllegalStateException: Android
 *  platform doesn't support SVG format` (`svgPainter`). Файлы `*.svg` оставлены
 *  рядом как исходная графика: самотест сверяет по ним отрисовку иконок
 *  (`iconPixels` в DesktopApp/Main.kt).
 *
 *  Данные путей — Material Symbols, перенесены из этих файлов дословно.
 *  Единственное преобразование — сдвиг по Y на [SVG_Y_SHIFT]: исходный
 *  viewBox начинается с -960 (`0 -960 960 960`), а viewport ImageVector — с 0. */
private const val VIEWPORT = 960f
private const val SVG_Y_SHIFT = 960f

/** Иконка из данных пути SVG (формат Material Symbols, размер 24 dp). */
private fun materialIcon(name: String, pathData: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, VIEWPORT, VIEWPORT)
        .group(translationY = SVG_Y_SHIFT) {
            addPath(PathParser().parsePathString(pathData).toNodes(), fill = SolidColor(Color.Black))
        }
        .build()

/** Иконки интерфейса (бывшие файлы `*.svg` в `composeResources/drawable`).
 *  Цвет заливки — чёрный: [androidx.compose.material.Icon] перекрашивает
 *  иконку в цвет содержимого темы. */
object Icons {
    /** `menu.svg`. */
    val Menu: ImageVector by lazy { materialIcon("menu", "M120-240v-80h720v80H120Zm0-200v-80h720v80H120Zm0-200v-80h720v80H120Z") }

    /** `metronome.svg`. */
    val Metronome: ImageVector by lazy { materialIcon("metronome", "M295-119q-36-1-68.5-18.5T165-189q-40-48-62.5-114.5T80-440q0-83 31.5-156T197-723q54-54 127-85.5T480-840q83 0 156 32t127 87q54 55 85.5 129T880-433q0 77-25 144t-71 113q-28 28-59 42.5T662-119q-18 0-36-4.5T590-137l-56-28q-12-6-25.5-9t-28.5-3q-15 0-28.5 3t-25.5 9l-56 28q-19 10-37.5 14.5T295-119Zm2-80q9 0 18.5-2t18.5-7l56-28q21-11 43.5-16t45.5-5q23 0 46 5t44 16l57 28q9 5 18 7t18 2q19 0 36-10t34-30q32-38 50-91t18-109q0-134-93-227.5T480-760q-134 0-227 94t-93 228q0 57 18.5 111t51.5 91q17 20 33 28.5t34 8.5Zm183-281Zm56.5 96.5Q560-407 560-440q0-8-1.5-16t-4.5-16l50-67q10 13 17.5 27.5T634-480h82q-15-88-81.5-144T480-680q-88 0-155 56.5T244-480h82q14-54 57-87t97-33q17 0 32 3t29 9l-51 69q-2 0-5-.5t-5-.5q-33 0-56.5 23.5T400-440q0 33 23.5 56.5T480-360q33 0 56.5-23.5Z") }

    /** `music_note_2.svg`. */
    val MusicNote: ImageVector by lazy { materialIcon("music_note_2", "M127-167q-47-47-47-113t47-113q47-47 113-47 23 0 42.5 5.5T320-418v-342l480-80v480q0 66-47 113t-113 47q-66 0-113-47t-47-113q0-66 47-113t113-47q23 0 42.5 5.5T720-498v-165l-320 63v320q0 66-47 113t-113 47q-66 0-113-47Z") }

    /** `play.svg`. */
    val Play: ImageVector by lazy { materialIcon("play", "M320-760v560l440-280-440-280Z") }

    /** `stop.svg`. */
    val Stop: ImageVector by lazy { materialIcon("stop", "M240-720h480v480H240Z") }
}
