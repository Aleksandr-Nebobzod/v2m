package com.v2m.app

/** Сборка массива байтов — замена `java.io.ByteArrayOutputStream` в общем коде
 *  (этап 4в: модули логики переезжают в `commonMain`, где `java.*` недоступен).
 *  Ёмкость растёт удвоением; [append] принимает `Int` как `BAOS.write` —
 *  записываются младшие 8 бит. Однобайтовые вызовы (`append(0xFF)`) и срезы
 *  (`append(bytes, off, len)`) покрывают употребления MIDI-модулей. */
class ByteBuilder(initialCapacity: Int = 64) {

    private var buf = ByteArray(if (initialCapacity > 0) initialCapacity else 16)
    private var len = 0

    /** Число записанных байт (без учёта свободной ёмкости). */
    val size: Int get() = len

    private fun ensure(extra: Int) {
        if (len + extra <= buf.size) return
        var cap = buf.size
        while (cap < len + extra) cap *= 2
        buf = buf.copyOf(cap)
    }

    /** Записать младшие 8 бит [b] (как `ByteArrayOutputStream.write(int)`). */
    fun append(b: Int) {
        ensure(1)
        buf[len++] = b.toByte()
    }

    /** Записать срез [bytes] — [length] байт с [offset]. */
    fun append(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        ensure(length)
        bytes.copyInto(buf, len, offset, offset + length)
        len += length
    }

    /** Записать 16 бит младшими вперёд (WAV). */
    fun appendI16LE(v: Int) {
        ensure(2)
        buf[len++] = v.toByte()
        buf[len++] = (v shr 8).toByte()
    }

    /** Записать 32 бита младшими вперёд (WAV). */
    fun appendI32LE(v: Int) {
        ensure(4)
        buf[len++] = v.toByte()
        buf[len++] = (v shr 8).toByte()
        buf[len++] = (v shr 16).toByte()
        buf[len++] = (v shr 24).toByte()
    }

    /** Записать 32 бита старшими вперёд (SMF-заголовки). */
    fun appendI32BE(v: Int) {
        ensure(4)
        buf[len++] = (v shr 24).toByte()
        buf[len++] = (v shr 16).toByte()
        buf[len++] = (v shr 8).toByte()
        buf[len++] = v.toByte()
    }

    /** Записать строку как ASCII (младшие 8 бит каждого символа). */
    fun appendAscii(s: String) {
        ensure(s.length)
        for (c in s) buf[len++] = c.code.toByte()
    }

    /** Собранные байты (копия — буфер дальше не наблюдаем). */
    fun toByteArray(): ByteArray = buf.copyOf(len)
}
