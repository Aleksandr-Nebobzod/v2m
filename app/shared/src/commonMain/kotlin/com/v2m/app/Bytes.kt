package com.v2m.app

/** Чтение целых из массива байтов — замена `java.nio.ByteBuffer` в общем коде
 *  (этап 4в). Знаковость — как у `getShort`/`getInt`: 16-битное значение
 *  возвращается знаковым, беззнаковое берётся маской `and 0xFFFF` на месте
 *  вызова (как было с `ByteBuffer`). */

/** 16 бит, младшими вперёд (WAV), знаковое. */
fun int16LE(b: ByteArray, off: Int): Int =
    ((b[off + 1].toInt() shl 8) or (b[off].toInt() and 0xFF)).toShort().toInt()

/** 32 бита, младшими вперёд (WAV), знаковое. */
fun int32LE(b: ByteArray, off: Int): Int =
    (b[off].toInt() and 0xFF) or
        ((b[off + 1].toInt() and 0xFF) shl 8) or
        ((b[off + 2].toInt() and 0xFF) shl 16) or
        ((b[off + 3].toInt() and 0xFF) shl 24)

/** 32 бита, старшими вперёд (SMF), знаковое. */
fun int32BE(b: ByteArray, off: Int): Int =
    ((b[off].toInt() and 0xFF) shl 24) or
        ((b[off + 1].toInt() and 0xFF) shl 16) or
        ((b[off + 2].toInt() and 0xFF) shl 8) or
        (b[off + 3].toInt() and 0xFF)
