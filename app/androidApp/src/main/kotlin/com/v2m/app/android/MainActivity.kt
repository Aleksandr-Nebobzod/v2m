package com.v2m.app.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.v2m.app.AndroidHost
import com.v2m.app.AndroidMime
import com.v2m.app.AppData
import com.v2m.app.Log
import com.v2m.app.Preferences
import com.v2m.app.V2mEngine
import com.v2m.app.installAndroidPlatform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Android-приложение v2m (план docs/260921_android_plan.md).
 *
 *  Этап 1 — сборка и запуск на устройстве, этап 2 — ядро: строка состояния
 *  ниже грузит libv2m.so и дёргает JNI (число параметров). Этап 3 — слой
 *  логики: prefs пишутся и читаются в каталоге данных приложения. Этап 4 —
 *  интерфейс на Compose: здесь ставится платформенный слой ([AndroidHost] —
 *  выбор файлов SAF, внешние открытия), сам экран `App()` подключается
 *  следом. `ComponentActivity` нужен ради `registerForActivityResult`. */
class MainActivity : ComponentActivity(), AndroidHost {

    override val context: Context get() = this

    // Ожидание ответа диалога SAF: контракт возвращает результат в колбэк,
    // а контракты :shared объявлены приостанавливающими.
    private var pendingOpen: CompletableDeferred<Uri?>? = null
    private var pendingCreate: CompletableDeferred<Uri?>? = null

    private val openLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingOpen?.complete(uri)
        pendingOpen = null
    }

    // Разрешение на запись (Р14): спрашивается при первом обращении к захвату
    private var pendingPermission: CompletableDeferred<Boolean>? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        pendingPermission?.complete(granted)
        pendingPermission = null
    }

    /** По launcher'у на каждый тип создания: контракт `CreateDocument`
     *  привязывает MIME к моменту регистрации (см. [AndroidMime.CREATE_MIMES]). */
    private val createLaunchers: Map<String, ActivityResultLauncher<String>> =
        AndroidMime.CREATE_MIMES.associateWith { mime ->
            registerForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
                pendingCreate?.complete(uri)
                pendingCreate = null
            }
        }

    override suspend fun pickOpen(mimes: List<String>, initialUri: String?): Uri? =
        withContext(Dispatchers.Main) {
            val result = CompletableDeferred<Uri?>()
            pendingOpen = result
            openLauncher.launch(mimes.toTypedArray())
            result.await()
        }

    override suspend fun pickCreate(mime: String, suggestedName: String): Uri? =
        withContext(Dispatchers.Main) {
            val launcher = createLaunchers[mime] ?: return@withContext null
            val result = CompletableDeferred<Uri?>()
            pendingCreate = result
            launcher.launch(suggestedName)
            result.await()
        }

    override suspend fun ensureRecordPermission(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            return true
        }
        return withContext(Dispatchers.Main) {
            val result = CompletableDeferred<Boolean>()
            pendingPermission = result
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            result.await()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Каталог данных приложения (этап 3): на desktop — ~/.v2m, здесь —
        // filesDir. Ставится до первых обращений к prefs и журналу.
        AppData.setDir(filesDir)
        Log.install() // журнал в filesDir/v2m-debug.log (см. Log.DEBUG)

        // Платформенный слой (этап 4): файлы через SAF, внешние открытия через
        // Intent. До первого кадра интерфейса.
        installAndroidPlatform(this)

        val title = TextView(this).apply {
            text = getString(R.string.frame_title)
            textSize = 20f
            gravity = Gravity.CENTER
        }
        // Диагностика выбора языка (жалоба А.М. 2026-09-22: строки пришли на
        // английском при русском интерфейсе устройства) — какая локаль дошла
        // до приложения и какой ресурс из неё выбран.
        val locale = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            text = getString(
                R.string.locale_info,
                resources.configuration.locales[0].toString(),
            )
        }
        val note = TextView(this).apply {
            text = getString(R.string.frame_note)
            textSize = 14f
            gravity = Gravity.CENTER
        }
        // Первое обращение к V2mEngine загружает libv2m.so (System.loadLibrary
        // в init); вызов native-функции проверяет и загрузку, и JNI.
        val core = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
        }
        core.text = runCatching { V2mEngine.paramsDefaultFieldCount() }
            .fold(
                onSuccess = { getString(R.string.core_ok, it) },
                onFailure = { getString(R.string.core_fail, it.message ?: it.toString()) }
            )

        // Слой логики (этап 3): запись prefs в каталог данных и чтение обратно.
        val logic = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
        }
        logic.text = runCatching {
            Preferences.save(
                params = V2mEngine.Params.defaults(), keySel = 3, smoothingWindow = 1,
                pitchMedianWindow = 1, instrument = 1, clef = 0, anacrusis = 0,
                listenExternal = false, midiVolume = 100, wavVolume = 30, sections = emptyMap(),
                darkTheme = false, showAbc = true, notesTab = 0, gamma = "golf", exportFmt = "mid",
                author = "", presetName = null, tScale = 3f, pitchLo = 21, pitchHi = 108,
                lastWav = null, lastMidi = null, lastXml = null,
            )
            val back = Preferences.load()
            check(back.keySel == 3 && back.wavVolume == 30 && back.gamma == "golf") {
                "прочитано: keySel=${back.keySel} wavVolume=${back.wavVolume} gamma=${back.gamma}"
            }
            getString(R.string.logic_ok, AppData.dir.path)
        }.fold(
            onSuccess = { it },
            onFailure = { getString(R.string.logic_fail, it.message ?: it.toString()) }
        )

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(title)
            addView(locale)
            addView(note)
            addView(core)
            addView(logic)
        })
    }
}
