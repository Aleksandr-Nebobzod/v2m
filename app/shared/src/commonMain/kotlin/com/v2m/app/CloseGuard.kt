package com.v2m.app

/** Связь `Window.onCloseRequest` ↔ `App()`: колбэк «закрывать ли окно?»
 *  ставит App() (билд #57, п.2в приёмки #56). [inProgress] — защита от
 *  повторного запроса: диалоги создаются без parent (не модальны), и клик
 *  «×» поверх открытого диалога дошёл бы до onCloseRequest вложенно.
 *  Этап 4в: переехал в общий код — точка входа платформы (Main.kt desktop,
 *  MainActivity Android) создаёт его и передаёт в [App]. */
class CloseGuard {
    /** Спросить о несохранённой записи; [onProceed] — закрывать окно.
     *  Этап 4б: вопрос задаёт Compose-диалог, ответ приходит позже —
     *  поэтому колбэк с продолжением, а не синхронный Boolean. */
    var confirm: ((onProceed: () -> Unit) -> Unit)? = null
    var inProgress = false
}
