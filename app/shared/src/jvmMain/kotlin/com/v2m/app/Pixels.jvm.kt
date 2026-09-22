package com.v2m.app

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/** Desktop-реализация (Skia): ARGB-пиксели разворачиваются в BGRA-байты —
 *  порядок, которым пользовался прежний spectrogramBitmap. */
actual fun argbToImageBitmap(w: Int, h: Int, argb: IntArray): ImageBitmap {
    val px = ByteArray(w * h * 4)
    var i = 0
    for (c in argb) {
        px[i++] = (c and 0xFF).toByte()          // B
        px[i++] = ((c shr 8) and 0xFF).toByte()  // G
        px[i++] = ((c shr 16) and 0xFF).toByte() // R
        px[i++] = ((c shr 24) and 0xFF).toByte() // A
    }
    val bmp = Bitmap()
    bmp.allocPixels(ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.OPAQUE))
    check(bmp.installPixels(px)) { "installPixels: $w x $h" }
    return bmp.asComposeImageBitmap()
}
