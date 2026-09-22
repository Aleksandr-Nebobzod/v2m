package com.v2m.app

import androidx.compose.ui.graphics.ImageBitmap

/** Пиксели (ARGB, 0xAARRGGBB) → [ImageBitmap]. Первые expect/actual проекта
 *  (план docs/260921_android_plan.md, 4б): у Compose Multiplatform 1.7.3 нет
 *  общего API записи пикселей, реализации принципиально платформенные —
 *  desktop (Skia) и Android (android.graphics.Bitmap). Единственная точка
 *  перевода пикселей в картинку: [argbToImageBitmap] используют
 *  spectrogramBitmap (SpectrogramView) и самотест. */
expect fun argbToImageBitmap(w: Int, h: Int, argb: IntArray): ImageBitmap
