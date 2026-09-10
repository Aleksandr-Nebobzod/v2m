package com.v2m.app

/** All user-facing strings in one place (single language for now).
 *  If multi-language support is needed later, move to Compose Multiplatform
 *  resources (composeResources/values/strings.xml + stringResource) — the
 *  call sites stay the same, only the source of the text changes. */
object Strings {
    // Buttons and file picker
    const val wavButton = "Выбрать WAV"
    const val noFile = "файл не выбран"
    const val recCd = "Записать с микрофона"
    const val recCountdownCd = "Запуск записи через %d… (нажмите — отменить)"
    const val recStopCd = "Остановить запись"
    const val recUnavailable = "микрофон недоступен"
    const val saveRecTitle = "Сохранить запись"
    const val transcribe = "Транскрипт"
    const val busy = "Идёт обработка..."
    const val listen = "Слушать"
    const val loadTitle = "Выбрать WAV"

    // Instrument selector
    const val instrumentLabel = "Инструмент:"
    const val chooseInstrument = "Выбрать"

    // Final tempo / time signature (export values, editable)
    const val finalTempoLabel = "Финальный темп:"
    const val finalSizeLabel = "Размер:"
    const val bpmUnit = "BPM"
    const val auto = "авто"
    const val autoDetected = "авто (%s)" // auto with the detected value in parens

    /** Popular time signatures offered as an export override. */
    val SIZE_OPTIONS: List<Pair<String, Pair<Int, Int>>> = listOf(
        "2/4" to (2 to 4), "3/4" to (3 to 4), "4/4" to (4 to 4),
        "3/8" to (3 to 8), "5/4" to (5 to 4), "6/8" to (6 to 8),
        "7/8" to (7 to 8), "9/8" to (9 to 8), "12/8" to (12 to 8),
    )

    // Collapsible section titles
    const val secRhythm = "Ритмика"
    const val secMelody = "Мелодика"
    const val secExport = "Экспорт"
    const val secVersions = "Версии"
    const val secReport = "Отчёт"
    const val secNotes = "Звучание" // переименовано по приёмке #28 (замечание 5)

    // Version list
    const val versionRow = "В.%02d — %d/%d, %.1f BPM, %d нот%s%s, %s" // %s1 = ", <тональность>", %s2 = ", NN% гарм." или ""
    const val versionNoResult = "В.%02d (нет результата)"

    // «Звучание» — вкладки: «Кванты» — результаты обработки параметров
    // ритма, «Тоны» — параметров мелодии (обе — гистограмма); «ABC» —
    // ноты в abc-нотации (текстовая таблица). Имена вкладок переименованы
    // по А.М. (билд #35, п.3)
    const val tabInput = "Кванты"
    const val tabOutput = "Тоны"
    const val tabAbc = "ABC"

    // Report and notes placeholders
    const val noResults = "(результатов нет)"
    const val empty = "(пусто)"
    const val parseFailed = "(не удалось разобрать MIDI)"
    const val paramsLine = "параметры: %s"
    const val fileLine = "файл: %s"
    const val tempoLine = "темп: %.2f BPM, размер %d/%d, нот: %d, L=1/8"
    const val keyLine = "тональность: %s (%s)"
    // Строка-сводка кадровых признаков (билд #38) во фрейме «Отчёт» —
    // паспорт записи из .frames.json; собирается в framesSummaryLine
    // (FramesSummary.kt) из частей ниже, разделитель частей — ", ".
    const val framesSummary = "признаки: %s"
    const val sumRange = "тесситура %s–%s" // %s: имена нот (C3, C#4)
    const val sumConf = "conf %s" // %s: медианная уверенность кадров (0.59)
    const val sumPoly = "полифония %s" // %s: "1.1" или "1.1/2" (с макс.)
    const val sumOnsets = "атаки %s" // %s: "64 (3.5/с)" — с плотностью
    const val sumStable = "стабильность %.0f%%" // доля стабильных контуров, %
    const val sumDrift = "дрейф %s ц" // %s: "22.7/66.7" (средний/перцентиль 95)
    // Mode-fit line: translated in the UI, the English original is kept for
    // the future en-strings file (same place as the native report line).
    const val modeFitLine = "подбор лада: %s, %d%% нот в пределах %d центов, сила притягивания %.1f"
    const val modeFitRaw = "mode fit: %s, %d%% of notes within %d cents, snap strength %s"
    const val notesHeader = "  такт:доля | нота·длит. | длит.с  | громк."

    // Export clef (MusicXML output)
    const val clefLabel = "Ключ:"
    const val clefTreble = "скрипичный"
    const val clefBass = "басовый"

    // Anacrusis (затакт): the export starts with a partial first measure
    // of N eighth notes; 0 = off (требование Р5)
    const val anacrusisLabel = "Затакт:"
    const val anacrusisHint = "восьмых (0 = выкл)"

    // Parameter labels (short, for the sliders and checkboxes)
    const val onsetLabel = "Порог начала ноты"
    const val frameLabel = "Порог звучания"
    const val velocityLabel = "Компрессия громкости"
    const val shiftLabel = "Гармонизация: общий сдвиг"
    const val snapLabel = "Гармонизация: по ступеням"
    const val minLenLabel = "Мин. длина ноты"
    const val mergeLabel = "Слияние фрагментов"
    const val energyLabel = "Тремоло"
    const val minBendLabel = "Колоратура"
    const val tempoLabel = "Темп (BPM, 0=авто)"
    const val toleranceLabel = "Допуск темпа (мс)"
    const val quantizeLabel = "Квантизация"
    const val smoothingLabel = "Сглаживание"
    const val melodiaLabel = "мелодический проход"
    const val bendsLabel = "питч-бенды"

    // Key selector: 0 = auto (mode fit), 1..12 major, 13..24 minor.
    // Root spellings share ROOT_NAMES (Key.kt) — the circle-of-fifths
    // orthography whose fifths match MAJOR_FIFTHS (Eb, not D#).
    const val keySelLabel = "Тональность: %s"
    const val keyAutoName = "Авто"
    val KEY_SEL_NAMES: List<String> = run {
        val names = mutableListOf(keyAutoName)
        for (r in ROOT_NAMES) names += "$r major"
        for (r in ROOT_NAMES) names += "$r minor"
        names
    }

    /** Quantize modes as shown in the UI (value = engine index). */
    val QUANTIZE_OPTIONS = listOf(
        "off" to 0, "auto" to 1, "beat" to 2, "eighth" to 3, "sixteenth" to 4,
    )

    // Панель правки ноты под «Тонами» (билд #35, п.4): текст информации
    // о ноте строится из её имени (нота + октава) и микротона («+32%»);
    // кнопки [<][>][X] без текста-подписи (символы — сами кнопки),
    // назначение — в contentDescription
    const val editNoSelection = "нота не выбрана: кликните по столбику"
    const val editDownCd = "вниз на полтона (у чистой ноты с микротоном — к чистой)"
    const val editUpCd = "вверх на полтона (у чистой ноты с микротоном — к чистой)"
    const val editMuteCd = "X с памятью: удалить ноту; повторное нажатие возвращает длительность"
    const val editInfoCd = "нота %s" // %s: имя ноты с октавой и микротоном

    // Низ экрана (билд #36, п.2): [Транскрипт], [▶] — растянута с рамкой,
    // [Экспорт] — системный диалог с типами (п.3)
    const val exportLabel = "Экспорт"
    const val exportCd = "Экспорт результата (формат выбирается в диалоге: %s)"
    const val exportDialogTitle = "Сохранить как"
    const val listenIconCd = "слушать текущую версию"
    const val stopIconCd = "остановить прослушивание"
    // Кнопка ▶ источника (п.1 приёмки #43; билд #45 — замечание «б»
    // приёмки #44): запись, а без неё — загруженный внешний файл
    const val recListenCd = "прослушать запись или загруженный файл"
    const val recStopListenCd = "остановить воспроизведение"
    // Форматы кнопки «Экспорт»: имена типов в системном диалоге — расширения
    // (.mid/.musicxml/.abc — формулировка А.М.); «mid+ctx» — .mid и рядом
    // файл кадровых признаков <имя>.frames.json (билд #38, замечание «в» —
    // сводка извлекается из ядра всегда, включение в экспорт решает пользователь).
    // value — Preferences.EXPORT_FORMATS
    val EXPORT_FMT_NAMES = mapOf(
        "mid" to ".mid",
        "mid+ctx" to ".mid + признаки (.frames.json)",
        "musicxml" to ".musicxml",
        "abc" to ".abc",
    )

    // Errors
    const val transcribeFailed = "транскрипция не удалась (см. stderr)"
    const val playFailed = "не удалось воспроизвести: %s"
    const val saveFailed = "не удалось записать %s (см. stderr)"

    // Sandwich menu (top-right corner): options grouped by section. The
    // «Слушать» group: one checkbox — where the «Слушать» button plays
    // (off = in-app player, on = OS MIDI app). The «Вид» group: dark theme.
    // The «Гистограмма» group: the t-scale slider (seconds visible in the
    // chart view window).
    const val listenMenu = "Слушать"
    const val menuListenExternal = "внешний MIDI-плеер"
    const val viewMenu = "Вид"
    const val menuDarkTheme = "тёмная тема"
    const val menuShowAbc = "ABC-notation" // Вид: показывать ABC-вкладку (билд #35, п.2)
    const val chartMenu = "Гистограмма"
    const val chartTScale = "t-масштаб: %.1f с"
    // Фильтр нот (билд #46, замечание «б» приёмки #45): показ и экспорт нот
    // в диапазоне питчей; %s — «A0» / «C8» (границы, pitchName)
    const val chartRange = "диапазон нот: %s – %s"
    const val chartRangeHint = "ноты вне диапазона скрыты на «Тонах» и в ABC и не попадают в экспорт"

    // Инфо-пункты внизу меню (замечание А.М. 2026-09-06, п.7):
    // «О программе» — ссылка на сайт разработчика, «Конфиденциальность» —
    // краткий текст, «OSS credits» — компоненты и их лицензии.
    const val menuAbout = "О программе"
    const val menuPrivacy = "Конфиденциальность"
    const val menuOss = "OSS credits"
    const val dlgClose = "Закрыть"
    const val dlgOk = "OK"
    const val dlgCancel = "Отмена"

    // ☰-меню «Файл признаков» (билд #38, замечание «б»): пункт «Автор»
    // открывает диалог с именем автора — оно попадает в «meta» файла
    // признаков <имя>.frames.json (сводка извлекается из ядра всегда).
    const val framesMenuTitle = "Файл признаков"
    const val menuAuthor = "Автор: %s" // %s — имя автора или authorNone
    const val authorNone = "не указан"
    const val authorDlgTitle = "Автор файла признаков"
    const val authorDlgText = "Имя попадёт в «meta.author» файла признаков. Пусто — поле не указывается."
    const val dlgOpenSite = "attplus.in ↗"
    const val aboutText = "Транскрипция аудио в MIDI (C++-порт Basic Pitch, Kotlin/Compose)"
    const val aboutDev = "Разработчик:"
    const val privacyText = "Программа не собирает и не раскрывает никаких персональных данных. " +
        "Ваше обращение по адресу разработчика является согласием на обработку данных, " +
        "связанных с вашим обращением."
    const val ossCredits = "Kotlin — Apache License 2.0\n" +
        "kotlinx.coroutines — Apache License 2.0\n" +
        "Compose Multiplatform (вкл. Material, Skiko) — Apache License 2.0\n" +
        "Иконки Material — Apache License 2.0\n" +
        "ONNX Runtime — MIT License\n" +
        "Eigen — Mozilla Public License 2.0\n" +
        "libremidi — BSD-2-Clause License\n" +
        "Basic Pitch (Spotify) — Apache License 2.0 (алгоритм-основа)"

    // Presets (замечание 2): метка + [Открыть] (выпадающий список) +
    // [Сохранить] + поле наименования. Пользовательские пресеты хранятся
    // в ~/.v2m/presets.properties как дифф от дефолтов движка.
    const val presetLabel = "Пресет:"
    const val presetOpen = "Открыть"
    const val saveLabel = "Сохранить"
    const val presetNoName = "введите имя пресета"

    // Long-press help: English name, purpose, examples (README «Влияние»)
    val HELP: Map<String, ParamHelp> = mapOf(
        "onsetThreshold" to ParamHelp("onset-threshold",
            "порог вероятности начала ноты в кадре (0..1; 0.5 = 50%)",
            "0.7: меньше нот, теряются тихие атаки; 0.3: больше ложных начал"),
        "frameThreshold" to ParamHelp("frame-threshold",
            "порог вероятности звучания ноты в кадре (0..1); ниже — нота закончилась",
            "выше: ноты короче; 0.2: «мягкие» границы, ноты сливаются"),
        "velocityCompress" to ParamHelp("velocity-compress",
            "компрессия динамики громкости (0 — тихие/громкие различимы, 1 — сильная)",
            "0: громкость 41..114; 1: 87..123 (ровнее, но менее выразительно)"),
        "globalShift" to ParamHelp("global-shift",
            "медианный сдвиг всех нот при глобальной расстройке записи (0..1 — сила)",
            "1 на записи +25 центов: возврат в ровный строй (бенды ≈ +1.3 цента)"),
        "modeSnap" to ParamHelp("mode-snap",
            "подобрать лад (12 тональностей × 6 ладов, допуск 30 центов) и притянуть ноты к ступеням (0..1 — сила)",
            "0: выкл; 1: ровно на ступенях (test4: C major, 100%); 0.9: мягче"),
        "minNoteLen" to ParamHelp("min-note-len",
            "минимальная длина ноты (движок считает кадрами: 1 кадр ≈ 11.6 мс)",
            "128 мс: только длинные ноты; меньше: сохраняются стаккато и щелчки"),
        "harmonizeMerge" to ParamHelp("harmonize-merge",
            "слить соседние фрагменты с разницей ≤ N полутонов (вибрато-дробление) в одну ноту",
            "0: вибрато дробит ноту (test1: 46 фрагментов); 1: целые ноты (22; test4: 4); 2: плюс полутоновые подъезды; 3: до 3 полутонов (может склеить соседние ноты)"),
        "energyTol" to ParamHelp("energy-tol",
            "подряд идущие «пустые» кадры у границ ноты, которые её не прерывают (в мс; движок считает кадрами: 1 кадр ≈ 11.6 мс)",
            "больше: ноты не рвутся при провалах энергии (вибрато); меньше: рвутся чаще"),
        "minBendBins" to ParamHelp("coloratura",
            "«Колоратура»: насколько мелкие питч-бенды сохраняются (инверсия — в движок идёт 5 − значение бинов; 1 бин ≈ 33 цента)",
            "0: ровные ноты — бенды мельче 5 бинов обнулены; 5: максимум украшений — все бенды сохраняются"),
        "medianFilter" to ParamHelp("median-filter",
            "«Сглаживание»: ширина окна медианного фильтра контура высоты (нечётные 3..15)",
            "пока это задел UI: фильтр в движке — следующим шагом (код А.М. — эталон)"),
        "tempoBpm" to ParamHelp("tempo",
            "слайдер 24..250 BPM: левый край (24) — «Авто» (0), темп — от 25 до 250; кнопка «♩» — метроном 4/4 в заданном темпе",
            "0: автодетекция (test1: 120.2 BPM); 90: сетка долей ровно по нему"),
        "toleranceMs" to ParamHelp("tempo-tolerance",
            "допуск прижимания стартов/концов нот к сетке (мс)",
            "больше: старты/концы сильнее прижимаются к сетке; меньше: ближе к сырому ритму"),
        "quantize" to ParamHelp("quantize",
            "квантизация ритма: прижать старты и концы нот к сетке долей",
            "off: сырой ритм (в таблице доли с ~); auto: сетка по вычисленному темпу; eighth/sixteenth: фиксированное деление"),
        "useMelodiaTrick" to ParamHelp("melodia-trick",
            "дополнительный проход: ноты без выраженного onset по остаточной энергии кадров (добавляются отдельными нотами)",
            "вкл: больше нот, ловит протяжные (легато); выкл: только чёткие начала, нот меньше"),
        "includePitchBends" to ParamHelp("pitch-bends",
            "питч-бенды (глиссандо между нотами) в выходном MIDI",
            "вкл: вибрато как бенды (точнее звук); выкл: ноты на хроматической сетке"),
        "keySelect" to ParamHelp("key-select",
            "исполнительская тональность вывода: 0 = Авто (из автоподбора лада), иначе одна из 24; кнопка «♪» справа играет трезвучие этой тональности",
            "Авто: как на записи (test4: C major); выбор: знаки у нот и ключ MusicXML соответствуют выбранной тональности"),
    )
}
