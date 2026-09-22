package com.v2m.app

/** Службы платформы — единое место определения (план docs/260921_android_plan.md,
 *  4б): общий код не знает ни Swing/awt, ни SAF/Intent. Реализации ставит
 *  точка входа приложения до первого кадра: desktop — `Main.kt`
 *  (`installDesktopPlatform`), Android — `MainActivity` (этап 4г).
 *
 *  Контракты узкие — ровно те операции, что вызывает интерфейс. Пути и файлы
 *  в общий код не протекают: desktop работает с `File`, Android — с `Uri`
 *  (SAF), поэтому наружу отдаются имя, содержимое и непрозрачный ключ
 *  каталога для «последнего места» в prefs. */
object Platform {
    lateinit var files: FileService
    lateinit var external: ExternalOpener
    lateinit var midi: MidiPlayback
    lateinit var wav: WavPlayback

    /** Новый захват микрофона: desktop — [NativeCapture] (ALSA через JNI),
     *  Android — AudioRecord (этап 5). */
    lateinit var newCapture: () -> AudioCapture
}

/** Файл, выбранный пользователем. [bytes] — содержимое целиком: у v2m
 *  материал небольшой (WAV записи ≤ 60 с, MIDI — килобайты), а разбор и так
 *  идёт в память. [dirKey] — ключ «последнего каталога» для prefs. */
class PickedFile(val name: String, val dirKey: String?, val bytes: ByteArray)

/** Формат сохранения для диалога (билд #38: тип выбирает пользователь в
 *  диалоге; список — Preferences.EXPORT_FORMATS). */
class SaveFormat(val id: String, val title: String, val ext: String)

/** Место сохранения, выбранное пользователем. */
interface SaveTarget {
    /** Имя файла с расширением (реализация дописывает его сама). */
    val name: String

    /** Выбранный формат — id из переданного списка [SaveFormat]. */
    val format: String

    /** Ключ «последнего каталога» для prefs. */
    val dirKey: String?

    /** Файл уже существует — общий код спрашивает «Заменить?». Desktop: на
     *  JFileChooser встроенной проверки нет (сверено в билде #57); Android:
     *  SAF спрашивает сам, поэтому здесь всегда false. */
    val exists: Boolean

    /** Записать [bytes]; null — записано, иначе текст ошибки. */
    fun write(bytes: ByteArray): String?

    /** Файл с именем [name] рядом с этим — для «.frames.json» экспорта
     *  «.mid + признаки»; null — платформа так не умеет (Android: SAF не
     *  даёт каталога, признаки остаются только в сводке прогона). */
    fun sibling(name: String): SaveTarget? = null
}

/** Диалоги выбора файла (desktop — awt/Swing, Android — SAF). */
interface FileService {

    /** Путь к локальному временному файлу приложения — для инструментов,
     *  которые пишут файл сами (native MusicXML пишет по пути). Desktop —
     *  каталог данных, Android — filesDir; каталог создаётся. */
    fun tempPath(name: String): String

    /** Выбрать существующий WAV. null — отмена. Сигнатура приостанавливающая:
     *  выбор через SAF асинхронен. */
    suspend fun pickWav(title: String, startDirKey: String?): PickedFile?

    /** Выбрать место сохранения. [suggestedName] — имя с расширением, оно же
     *  задаёт формат по умолчанию; [formats] — список типов для диалога
     *  (билд #38: тип выбирается в диалоге). null — отмена. */
    suspend fun chooseSaveTarget(
        title: String,
        suggestedName: String,
        startDirKey: String?,
        formats: List<SaveFormat>,
    ): SaveTarget?
}

/** Открытие во внешней программе ОС. */
fun interface ExternalOpener {
    /** Открыть [target] (URL или путь к файлу); null — запущено, иначе текст ошибки. */
    fun open(target: String): String?
}

/** Воспроизведение MIDI (desktop — javax.sound.midi, Android — MediaPlayer). */
interface MidiPlayback {
    /** Выходная громкость в процентах (0..100 = заводская, до 127 — запас). */
    fun setVolume(pct: Int)

    /** Позиция от начала, секунды; 0 — ничего не играет. */
    val positionSec: Double

    /** Запустить [midi] (полный SMF); null — успех, иначе текст ошибки.
     *  [onEnd] вызывается один раз в фоновом потоке. */
    fun play(midi: ByteArray, onEnd: () -> Unit = {}): String?

    fun stop()
}

/** Воспроизведение WAV (desktop — javax.sound.sampled, Android — AudioTrack). */
interface WavPlayback {
    /** Линейный множитель к сэмплам: 1.0 — как записано. */
    var volume: Float

    /** Позиция от начала, секунды; 0 — ничего не играет. */
    val positionSec: Double

    /** Запустить [pcm] (−1..1); null — успех, иначе текст ошибки.
     *  [onEnd] вызывается один раз в фоновом потоке. */
    fun play(pcm: FloatArray, sr: Int, onEnd: () -> Unit = {}): String?

    fun stop()
}
