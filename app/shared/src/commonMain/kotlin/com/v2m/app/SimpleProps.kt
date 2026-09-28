package com.v2m.app

/** Чтение и запись формата `java.util.Properties` (этап 4в: общий код не может
 *  использовать `java.util.Properties` вне JVM, а формат файлов
 *  `prefs.properties` / `presets.properties` менять нельзя — они уже лежат у
 *  пользователей).
 *
 *  Разбор повторяет `Properties.load(InputStream)`: байты декодируются как
 *  ISO-8859-1, строки-комментарии (`#`/`!`), продолжение строки обратным
 *  слэшем (ведущие пробелы следующей строки отбрасываются), разделители
 *  `=`, `:` и пробел, escape-последовательности `\t \n \f \r \\ \uXXXX`.
 *  Запись повторяет `Properties.store(OutputStream)`: чистый ASCII, символы
 *  вне 0x20..0x7E — как `\uXXXX` (прописные цифры), экранирование `= : # !`
 *  и ведущего пробела значения. Заголовок — одна строка `#…`; дату, которую
 *  дописывает Java второй строкой, не пишем (при чтении она игнорируется).
 *  Совместимость в обе стороны проверяется самотестом против
 *  `java.util.Properties`. */
object SimpleProps {

    /** Разобрать содержимое файла в карту (порядок ключей — как в файле). */
    fun parse(bytes: ByteArray): MutableMap<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in logicalLines(bytes.toLatin1())) {
            val (key, value) = splitKeyValue(line)
            out[key] = value
        }
        return out
    }

    /** Записать карту в формате Properties; [header] — строка комментария. */
    fun store(props: Map<String, String>, header: String? = null): ByteArray {
        val sb = StringBuilder()
        if (header != null) {
            sb.append('#').append(header).append('\n')
        }
        for ((k, v) in props) {
            sb.append(escape(k, escapeSpace = true)).append('=')
                .append(escape(v, escapeSpace = false)).append('\n')
        }
        return sb.toString().toLatin1Bytes()
    }

    /** Логические строки: комментарии и пустые пропускаются, продолжения
     *  (нечётное число `\` в конце) склеиваются — как `Properties.LineReader`. */
    private fun logicalLines(text: String): List<String> {
        val physical = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < text.length) {
            when (val c = text[i]) {
                '\r' -> {
                    physical.add(cur.toString()); cur.setLength(0)
                    if (i + 1 < text.length && text[i + 1] == '\n') i++
                }
                '\n' -> {
                    physical.add(cur.toString()); cur.setLength(0)
                }
                else -> cur.append(c)
            }
            i++
        }
        if (cur.isNotEmpty()) physical.add(cur.toString())

        val res = ArrayList<String>()
        var idx = 0
        while (idx < physical.size) {
            val trimmed = physical[idx++].trimStart(' ', '\t', '\u000C')
            if (trimmed.isEmpty() || trimmed[0] == '#' || trimmed[0] == '!') continue
            val line = StringBuilder(trimmed)
            while (endsWithOddBackslash(line)) {
                line.setLength(line.length - 1)
                if (idx >= physical.size) break
                line.append(physical[idx++].trimStart(' ', '\t', '\u000C'))
            }
            res.add(line.toString())
        }
        return res
    }

    private fun endsWithOddBackslash(s: CharSequence): Boolean {
        var n = 0
        var i = s.length - 1
        while (i >= 0 && s[i] == '\\') {
            n++
            i--
        }
        return n % 2 == 1
    }

    /** Ключ и значение логической строки — как `Properties.load0`: ключ до
     *  первого неэкранированного `=`/`:`/пробела, значение — после
     *  разделителей и ведущих пробелов. */
    private fun splitKeyValue(line: String): Pair<String, String> {
        var keyLen = 0
        var valueStart = line.length
        var hasSep = false
        var precedingBackslash = false
        while (keyLen < line.length) {
            val c = line[keyLen]
            if ((c == '=' || c == ':') && !precedingBackslash) {
                valueStart = keyLen + 1
                hasSep = true
                break
            } else if ((c == ' ' || c == '\t' || c == '\u000C') && !precedingBackslash) {
                valueStart = keyLen + 1
                break
            }
            precedingBackslash = if (c == '\\') !precedingBackslash else false
            keyLen++
        }
        while (valueStart < line.length) {
            val c = line[valueStart]
            if (c != ' ' && c != '\t' && c != '\u000C') {
                if (!hasSep && (c == '=' || c == ':')) hasSep = true else break
            }
            valueStart++
        }
        return unescape(line, 0, keyLen) to unescape(line, valueStart, line.length)
    }

    /** Снятие escape-последовательностей — как `Properties.loadConvert`. */
    private fun unescape(s: String, from: Int, to: Int): String {
        val sb = StringBuilder(to - from)
        var i = from
        while (i < to) {
            var c = s[i++]
            if (c == '\\') {
                if (i >= to) break // висячий `\` в конце — отбрасывается
                c = s[i++]
                when (c) {
                    't' -> c = '\t'
                    'n' -> c = '\n'
                    'r' -> c = '\r'
                    'f' -> c = '\u000C'
                    'u' -> {
                        require(i + 4 <= to) { "обрыв \\u-последовательности: $s" }
                        val v = s.substring(i, i + 4).toIntOrNull(16)
                            ?: throw IllegalArgumentException("неверная \\u-последовательность: ${s.substring(i - 2, i + 4)}")
                        sb.append(v.toChar())
                        i += 4
                        continue
                    }
                }
            }
            sb.append(c)
        }
        return sb.toString()
    }

    /** Экранирование при записи — как `Properties.saveConvert`. */
    private fun escape(s: String, escapeSpace: Boolean): String {
        val sb = StringBuilder(s.length + 8)
        for (idx in s.indices) {
            val c = s[idx]
            when (c) {
                ' ' -> {
                    if (idx == 0 || escapeSpace) sb.append('\\')
                    sb.append(' ')
                }
                '\\' -> sb.append("\\\\")
                '\t' -> sb.append("\\t")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\u000C' -> sb.append("\\f")
                else -> if (c.code < 0x20 || c.code > 0x7E) {
                    sb.append("\\u").append(c.code.toString(16).uppercase().padStart(4, '0'))
                } else {
                    if (c == '=' || c == ':' || c == '#' || c == '!') sb.append('\\')
                    sb.append(c)
                }
            }
        }
        return sb.toString()
    }

    /** Байты → ISO-8859-1 (в Properties так читается InputStream). */
    private fun ByteArray.toLatin1(): String =
        buildString(size) { for (b in this@toLatin1) append((b.toInt() and 0xFF).toChar()) }

    /** Символы → ISO-8859-1 (в Properties так пишется OutputStream). */
    private fun String.toLatin1Bytes(): ByteArray = ByteArray(length) { this[it].code.toByte() }
}

/** Прочитать properties-файл [path] в карту; файла нет или ошибка чтения —
 *  пустая карта (prefs и пресеты — best-effort, приложение работает и без
 *  них). Единое место чтения таких файлов для [Preferences] и [PresetStore]. */
fun readProps(path: String): MutableMap<String, String> =
    Platform.storage.readBytes(path)?.let { SimpleProps.parse(it) } ?: LinkedHashMap()

/** Записать карту в properties-файл [path] атомарно (temp + переименование,
 *  см. [StorageService.writeAtomic]) — падение посреди записи не оставит
 *  обрезанный файл. Сбой записи не роняет приложение (настройки — best-effort),
 *  но попадает в журнал. */
fun writeProps(path: String, props: Map<String, String>, header: String) {
    try {
        Platform.storage.writeAtomic(path, SimpleProps.store(props, header))
    } catch (e: Exception) {
        runCatching { Log.d("prefs", "запись $path не удалась: ${e.message}") }
    }
}
