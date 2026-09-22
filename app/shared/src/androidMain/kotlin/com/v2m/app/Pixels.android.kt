package com.v2m.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** Android-реализация: Bitmap.createBitmap принимает ARGB-пиксели напрямую. */
actual fun argbToImageBitmap(w: Int, h: Int, argb: IntArray): ImageBitmap =
    Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
