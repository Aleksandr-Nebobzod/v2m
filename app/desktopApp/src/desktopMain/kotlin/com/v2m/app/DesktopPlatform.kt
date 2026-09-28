package com.v2m.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

/** Реализации служб платформы для desktop (план docs/260921_android_plan.md,
 *  4б). Ставит [main] до первого кадра. Диалоги — awt/Swing, внешние
 *  открытия — Desktop API с запасным `xdg-open`, звук — javax.sound
 *  ([MidiPlayer], [WavPlayer]), захват — [NativeCapture] (ALSA через JNI). */
fun installDesktopPlatform() {
    Platform.files = DesktopFileService
    Platform.external = ExternalOpener(::openExternalTarget)
    Platform.midi = DesktopMidiPlayback
    Platform.wav = DesktopWavPlayback
    Platform.newCapture = { NativeCapture() }
    Platform.storage = DesktopStorage
    Platform.log = DesktopLog
}

/** Разовый перехват System.out/System.err (tee: файл + консоль) — журнал
 *  тестирования собирает все печати приложения, в т.ч. `e.printStackTrace`
 *  в catch-ветках (билд #33). Ставится только при [Log.DEBUG]; вызывать из
 *  точки входа ([main]) до [Log.install] — заголовок сессии уже должен
 *  попасть в файл. Самотест перехват не ставит: его отчёт идёт в консоль. */
fun installDesktopLogTee() {
    if (!Log.DEBUG) return
    try {
        val logFile = File(dataPath("v2m-debug.log"))
        logFile.parentFile?.mkdirs()
        val fos = FileOutputStream(logFile, true) // append — сессии теста не стирают друг друга
        val originalOut = System.out
        val originalErr = System.err
        System.setOut(PrintStream(Tee(fos, originalOut), true, Charsets.UTF_8))
        System.setErr(PrintStream(Tee(fos, originalErr), true, Charsets.UTF_8))
    } catch (e: Exception) {
        e.printStackTrace() // журнал — best-effort; без него приложение работает
    }
}

/** Перенаправляет поток в два вывода одновременно (файл + консоль). */
private class Tee(private val a: OutputStream, private val b: OutputStream) : OutputStream() {
    override fun write(b0: Int) {
        a.write(b0); b.write(b0)
    }

    override fun write(buf: ByteArray, off: Int, len: Int) {
        a.write(buf, off, len); b.write(buf, off, len)
    }

    override fun flush() {
        a.flush(); b.flush()
    }
}

/** Файлы каталога данных: обычные `java.io.File` (desktop). */
private object DesktopStorage : StorageService {
    override fun readBytes(path: String): ByteArray? =
        runCatching { File(path).takeIf { it.isFile }?.readBytes() }.getOrNull()

    override fun writeBytes(path: String, bytes: ByteArray) {
        File(path).apply { parentFile?.mkdirs() }.writeBytes(bytes)
    }

    override fun writeAtomic(path: String, bytes: ByteArray) {
        val f = File(path)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(bytes)
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

/** Журнал desktop: печать в stdout, перехваченный Tee-ом на файл
 *  ([installDesktopLogTee]) — консоль и журнал получают одну и ту же строку. */
private object DesktopLog : LogSink {
    override fun append(line: String) = println(line)
}

/** Открыть [target] (URL или путь) во внешней программе ОС: при доступном
 *  Desktop API — browse/open, при его отсутствии или сбое — `xdg-open`
 *  (билд #57, п.1 приёмки #56: в WSL Desktop API нет, а xdg-open есть).
 *  [ProcessBuilder.start] не ждёт процесс — GUI не блокируется. Возвращает
 *  текст ошибки или null (запущено). */
private fun openExternalTarget(target: String): String? {
    val uri = runCatching { URI(target) }.getOrNull()
        ?: return "неверный адрес: $target"
    val browse = uri.scheme == "http" || uri.scheme == "https"
    try {
        // Порядок важен: getDesktop() бросает, если Desktop API не поддержан
        if (Desktop.isDesktopSupported()) {
            val d = Desktop.getDesktop()
            val action = if (browse) Desktop.Action.BROWSE else Desktop.Action.OPEN
            if (d.isSupported(action)) {
                if (browse) d.browse(uri) else d.open(File(uri))
                return null
            }
        }
    } catch (_: Exception) {
        // сбой Desktop API (нет браузера/приложения) — пробуем xdg-open
    }
    return try {
        ProcessBuilder("xdg-open", uri.toString()).start()
        null
    } catch (e: Exception) {
        e.message ?: e.toString()
    }
}

/** Выбор WAV (awt FileDialog) и места сохранения (Swing JFileChooser).
 *  Билд #59: модальный диалог показывается на EDT из потока ввода-вывода
 *  (`SwingUtilities.invokeAndWait` под `withContext(Dispatchers.IO)`) — вне
 *  Compose-корутины. Показ диалога прямо в корутине ломал Compose-диспетчер:
 *  вложенный цикл событий диалога резюмит уже завершённые продолжения
 *  (`CoroutinesInternalError: CompletedContinuation …`), см. историю #59. */
private object DesktopFileService : FileService {

    override fun tempPath(name: String): String = dataPath(name).also {
        File(it).parentFile?.mkdirs()
    }

    override suspend fun pickWav(title: String, startDirKey: String?): PickedFile? =
        withContext(Dispatchers.IO) {
            var file: File? = null
            var dir: String? = null
            SwingUtilities.invokeAndWait {
                val dlg = FileDialog(null as Frame?, title, FileDialog.LOAD)
                startDirKey?.let { dlg.directory = it }
                dlg.isVisible = true
                dlg.files.firstOrNull()?.let {
                    file = it
                    dir = dlg.directory
                }
            }
            val f = file ?: return@withContext null
            PickedFile(f.name, dir ?: "", f.readBytes())
        }

    override suspend fun chooseSaveTarget(
        title: String,
        suggestedName: String,
        startDirKey: String?,
        formats: List<SaveFormat>,
    ): SaveTarget? = withContext(Dispatchers.IO) {
        var target: SaveTarget? = null
        SwingUtilities.invokeAndWait {
            val suggested = File(suggestedName)
            val ext = suggested.name.substringAfterLast('.', "")
            val fallback = formats.firstOrNull { it.ext == ext } ?: formats.firstOrNull()
            val chooser = JFileChooser(startDirKey ?: System.getProperty("user.home"))
            chooser.dialogTitle = title
            chooser.isAcceptAllFileFilterUsed = false
            val filters = formats.map { FileNameExtensionFilter(it.title, it.ext) to it }
            filters.forEach { (f, _) -> chooser.addChoosableFileFilter(f) }
            // Предвыбран формат, соответствующий предложенному имени (билд #38)
            chooser.fileFilter = filters.firstOrNull { it.second.id == fallback?.id }?.first
                ?: filters.firstOrNull()?.first
            chooser.selectedFile = File(suggested.nameWithoutExtension)
            if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return@invokeAndWait
            val picked = filters.firstOrNull { chooser.fileFilter === it.first }?.second ?: fallback
            val chosen = chooser.selectedFile ?: return@invokeAndWait
            // Без расширения в имени — дописать расширение выбранного типа
            // («mid+ctx» пишет .mid; признаки — отдельным файлом рядом)
            val file = if (chosen.name.contains('.')) chosen
            else File(chosen.parentFile, chosen.name + "." + (picked?.ext ?: "mid"))
            target = DesktopSaveTarget(file, picked?.id ?: "mid")
        }
        target
    }
}

/** Место сохранения на desktop: обычный файл; перезапись подтверждает общий
 *  код (у JFileChooser встроенной проверки нет — сверено в билде #57). */
private class DesktopSaveTarget(private val file: File, override val format: String) : SaveTarget {
    override val name: String get() = file.name
    override val dirKey: String? get() = file.parentFile?.path
    override val exists: Boolean get() = file.exists()

    override fun write(bytes: ByteArray): String? = try {
        file.writeBytes(bytes)
        null
    } catch (e: Exception) {
        e.message ?: e.toString()
    }

    override fun sibling(name: String): SaveTarget =
        DesktopSaveTarget(File(file.parentFile, name), format)
}

private object DesktopMidiPlayback : MidiPlayback {
    override fun setVolume(pct: Int) = MidiPlayer.setVolume(pct)
    override val positionSec: Double get() = MidiPlayer.positionSec
    override fun play(midi: ByteArray, onEnd: () -> Unit): String? = MidiPlayer.play(midi, onEnd)
    override fun stop() = MidiPlayer.stop()
}

private object DesktopWavPlayback : WavPlayback {
    override var volume: Float
        get() = WavPlayer.volume
        set(value) { WavPlayer.volume = value }
    override val positionSec: Double get() = WavPlayer.positionSec
    override fun play(pcm: FloatArray, sr: Int, onEnd: () -> Unit): String? =
        WavPlayer.play(pcm, sr, onEnd)
    override fun stop() = WavPlayer.stop()
}
