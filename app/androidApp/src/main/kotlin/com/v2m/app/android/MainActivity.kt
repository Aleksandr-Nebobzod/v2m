package com.v2m.app.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.v2m.app.AndroidHost
import com.v2m.app.AndroidMime
import com.v2m.app.App
import com.v2m.app.AppData
import com.v2m.app.Log
import com.v2m.app.installAndroidPlatform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Android-приложение v2m (план docs/260921_android_plan.md, этапы 1–4).
 *  Площадка платформы ([AndroidHost] — выбор файлов SAF, внешние открытия,
 *  разрешение на запись) плюс общий экран: `setContent { App() }` — тот же
 *  интерфейс Compose, что и на desktop (этап 4в). `ComponentActivity` нужен
 *  ради `registerForActivityResult`. */
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

        // Порядок важен: каталог данных приложения (этап 3) — до первых
        // обращений к prefs и журналу; платформенный слой (этап 4) — до первого
        // кадра интерфейса; журнал — до `App()` (заголовок сессии первым).
        AppData.setDir(filesDir.absolutePath)
        installAndroidPlatform(this)
        Log.install() // журнал в filesDir/v2m-debug.log (см. Log.DEBUG)

        // Экран — общий код :shared (этап 4в): тот же App(), что на desktop.
        setContent { App() }
    }
}
