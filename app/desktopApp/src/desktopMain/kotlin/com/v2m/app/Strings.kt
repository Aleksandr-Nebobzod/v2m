package com.v2m.app

/** All user-facing strings in one place (single language for now).
 *  If multi-language support is needed later, move to Compose Multiplatform
 *  resources (composeResources/values/strings.xml + stringResource) — the
 *  call sites stay the same, only the source of the text changes. */
object Strings {
    // Buttons and file picker
    const val wavButton = "Выбрать WAV"
    const val noFile = "файл не выбран"
    const val transcribe = "Транскрипт"
    const val busy = "Идёт обработка..."
    const val listen = "Слушать"
    const val saveMid = "Сохранить .mid"
    const val saveMusicXml = ".musicxml" // кнопка без слова «Сохранить» (решение А.М.)
    const val saveTitle = "Сохранить %s"
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
    const val secNotes = "Ноты"

    // Version list
    const val versionRow = "В.%02d — %d/%d, %.1f BPM, %d нот%s%s, %s" // %s1 = ", <тональность>", %s2 = ", NN% гарм." или ""
    const val versionNoResult = "В.%02d (нет результата)"

    // Report and notes placeholders
    const val noResults = "(результатов нет)"
    const val empty = "(пусто)"
    const val parseFailed = "(не удалось разобрать MIDI)"
    const val paramsLine = "параметры: %s"
    const val fileLine = "файл: %s"
    const val tempoLine = "темп: %.2f BPM, размер %d/%d, нот: %d, L=1/8"
    const val keyLine = "тональность: %s (%s)"
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

    // Errors
    const val transcribeFailed = "транскрипция не удалась (см. stderr)"
    const val playFailed = "не удалось воспроизвести: %s"
    const val saveFailed = "не удалось записать %s (см. stderr)"

    // Listen button: toggles into a stop control while MIDI playback runs
    const val stopListen = "Остановить"

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
