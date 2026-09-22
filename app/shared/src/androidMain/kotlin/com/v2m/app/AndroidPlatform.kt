package com.v2m.app

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Площадка выбора файлов — её даёт Activity: `ActivityResultLauncher`
 *  регистрируется только из Activity (androidx.activity), поэтому :shared
 *  диалоги SAF не открывает сам, а просит [pickOpen]/[pickCreate].
 *  Оба вызываются с главного потока и возвращают null при отмене.
 *  [initialUri] — «последнее место» из prefs (строка прежнего Uri); штатные
 *  контракты SAF (`OpenDocument`/`CreateDocument`) подсказку каталога не
 *  передают — система помнит место сама, поэтому параметр здесь не
 *  используется, а ключ хранится ради единой сигнатуры с desktop. */
interface AndroidHost {
    val context: Context

    suspend fun pickOpen(mimes: List<String>, initialUri: String?): Uri?

    suspend fun pickCreate(mime: String, suggestedName: String): Uri?

    /** Разрешение на запись с микрофона (RECORD_AUDIO): запрос идёт только из
     *  Activity. true — разрешено. Вызывается из фонового потока захвата —
     *  реализация сама переходит на главный. */
    suspend fun ensureRecordPermission(): Boolean
}

/** Соответствия «расширение → MIME-тип» для диалогов SAF — единое место
 *  определения: список типов, которые Activity обязана зарегистрировать
 *  заранее, тоже отсюда ([CREATE_MIMES]). */
object AndroidMime {
    const val WAV = "audio/x-wav"
    const val MID = "audio/midi"
    const val MUSICXML = "application/xml"
    const val JSON = "application/json"
    const val ABC = "text/plain"

    /** Типы для диалога создания файла: контракт `CreateDocument` привязывает
     *  MIME к моменту регистрации, поэтому Activity регистрирует по одному
     *  launcher'у на тип. */
    val CREATE_MIMES = listOf(WAV, MID, MUSICXML, JSON, ABC)

    /** Фильтр выбора WAV: поставщики отдают .wav под разными типами. */
    val OPEN_MIMES = listOf("audio/*", WAV, "audio/wav", "application/octet-stream")

    fun ofExt(ext: String): String = when (ext.lowercase()) {
        "wav" -> WAV
        "mid" -> MID
        "musicxml" -> MUSICXML
        "json" -> JSON
        "abc" -> ABC
        else -> "application/octet-stream"
    }
}

/** Реализации служб платформы для Android (план docs/260921_android_plan.md,
 *  4г и 5): файлы — SAF, внешние открытия — Intent, звук — MediaPlayer и
 *  AudioTrack, захват — AudioRecord. Вызывается из `MainActivity.onCreate`
 *  до первого кадра. */
fun installAndroidPlatform(host: AndroidHost) {
    val app = host.context.applicationContext
    Platform.files = AndroidFileService(host)
    Platform.external = ExternalOpener { target -> openExternalAndroid(app, target) }
    Platform.midi = AndroidMidiPlayback(app)
    Platform.wav = AndroidWavPlayback()
    Platform.newCapture = { AndroidCapture(host) }
}

/** Открыть [target] во внешней программе ОС. Android открывает по `content://`
 *  и `http(s)://`; путь к файлу так не открыть без FileProvider, поэтому
 *  «слушать внешним плеером» на Android возвращает ошибку (этап 5 даёт
 *  воспроизведение внутри приложения). Возвращает текст ошибки или null. */
private fun openExternalAndroid(context: Context, target: String): String? = try {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    null
} catch (e: ActivityNotFoundException) {
    e.message ?: e.toString()
}

/** Выбор WAV и места сохранения через SAF (Storage Access Framework).
 *  Каталога у SAF нет — «последним местом» служит Uri прежнего файла
 *  ([PickedFile.dirKey]/[SaveTarget.dirKey]). */
private class AndroidFileService(private val host: AndroidHost) : FileService {

    override fun tempPath(name: String): String =
        File(host.context.filesDir, name).absolutePath

    override suspend fun pickWav(title: String, startDirKey: String?): PickedFile? {
        val uri = host.pickOpen(AndroidMime.OPEN_MIMES, startDirKey) ?: return null
        val resolver = host.context.contentResolver
        val name = withContext(Dispatchers.IO) { displayName(resolver, uri) } ?: "input.wav"
        // Разрешение, выданное выбором, живёт до конца Activity — содержимое
        // читается сразу (весь материал v2m небольшой, см. контракт).
        val bytes = withContext(Dispatchers.IO) {
            resolver.openInputStream(uri)?.use { it.readBytes() }
        } ?: return null
        return PickedFile(name, uri.toString(), bytes)
    }

    override suspend fun chooseSaveTarget(
        title: String,
        suggestedName: String,
        startDirKey: String?,
        formats: List<SaveFormat>,
    ): SaveTarget? {
        val ext = suggestedName.substringAfterLast('.', "")
        val format = formats.firstOrNull { it.ext == ext } ?: formats.firstOrNull() ?: return null
        val uri = host.pickCreate(AndroidMime.ofExt(format.ext), suggestedName) ?: return null
        return AndroidSaveTarget(host.context.contentResolver, uri, format.id, suggestedName)
    }
}

/** Место сохранения на Android: документ SAF. Перезапись SAF подтверждает
 *  сам (диалог создания), поэтому [exists] всегда false — общий код второго
 *  вопроса не задаёт. [SaveTarget.sibling] не поддержан (по умолчанию null):
 *  каталога у SAF нет, признаки «.mid + ctx» остаются в сводке прогона. */
private class AndroidSaveTarget(
    private val resolver: ContentResolver,
    private val uri: Uri,
    override val format: String,
    private val fallbackName: String,
) : SaveTarget {

    override val name: String = displayName(resolver, uri) ?: fallbackName
    override val dirKey: String? = uri.toString()
    override val exists: Boolean = false

    override fun write(bytes: ByteArray): String? = try {
        // "wt" — усечение: повторная запись в выбранный документ не оставляет
        // хвоста прежнего содержимого
        val out = resolver.openOutputStream(uri, "wt")
        if (out == null) "поток не открыт" else {
            out.use { it.write(bytes) }
            null
        }
    } catch (e: Exception) {
        e.message ?: e.toString()
    }
}

/** Имя документа для SAF-ссылки: поставщик отдаёт его в колонке DISPLAY_NAME. */
private fun displayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && c.moveToFirst()) c.getString(i) else null
    }
}.getOrNull()
