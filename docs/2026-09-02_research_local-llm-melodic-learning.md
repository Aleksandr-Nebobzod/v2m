# Research: локальное дообучение LLM для накопления опыта вокального (мелодического) взаимодействия

Дата: 2026-09-02. Источник: веб-поиск (3 волны, ~12 запросов). Язык: русский.

## 1. Резюме

Запрос: найти свежие репозитории по **локальному дообучению LLM**, чтобы ядро накапливало опыт **вокального (именно мелодического, не речевого)** взаимодействия с пользователем.

Главный вывод: готового репозитория «LLM, дообучаемый на мелодическом диалоге с пользователем» нет. Но задача распадается на три зрелые составляющие, по каждой из которых свежие открытые проекты есть:

1. **Локальный файнтюн-конвейер «накопления опыта»** — Lamark Agent (ночной LoRA из архива диалогов), Nova (ошибка → урок → DPO → A/B), MiniNeuroModel (LoRA + опыт-реплей + EWC), Memento. Стек: Unsloth/QLoRA → GGUF → llama.cpp/Ollama.
2. **Мелодический (символьный) домен LLM** — SongComposer (ACL 2025), VocalParse (2026), YNote, LilyPond-LoRA — доказано, что LLM хорошо дообучаются на символьных мелодических представлениях (ноты/ритм), в отличие от аудио.
3. **Исследовательский выбор «память vs веса»** — RAG выигрывает на фактах и свежести, per-user LoRA — на стилистике/поведенческой согласованности; лучшая практика 2025 — гибрид (RAG для свежего, периодическая компакция старого в LoRA).

Рекомендуемая форма (для решения v2m, !ai): гибридная — журнал мелодических сессий (RAG/файлы) + периодический локальный LoRA на символьном представлении мелодий; звук в веса не учить (мелодия ≠ тембр).

## 2. Рамка задачи: два прочтения

- **A. Персонализация транскрипции**: ядро (транскриптор напевок) адаптируется к манере/ошибкам конкретного пользователя — аналог OQ-12 в backlog v2m («тренировка нейросети на вокале пользователя»).
- **B. Мелодический диалог**: LLM-ядро, с которым пользователь взаимодействует пением/напевкой, и которое помнит историю (учитель пения, «поющий ассистент»).

Отчёт покрывает оба; инструменты накопления опыта общие.

## 3. Локальное дообучение: инструменты (репозитории, 2025–2026)

| Проект | Назначение | Особенности |
|---|---|---|
| [Unsloth](https://hivebook.wiki/wiki/unsloth-20265x-open-source-llm-fine-tuning-library) | Стандарт быстрого LoRA/QLoRA на потребительском GPU (16–24 ГБ) | 4-бит QLoRA, ~70% экономии VRAM, экспорт GGUF; 500+ моделей |
| [LLaMA-Factory](https://blog.csdn.net/m0_60827485/article/details/159384113) | No-code локальный файнтюн с WebUI | full/Freeze/LoRA/QLoRA 2–8 бит, экспорт в Ollama |
| [Axolotl](https://blog.csdn.net/m0_60827485/article/details/159384113) | Инженерный/исследовательский YAML-конвейер | SFT, DPO, IPO, KTO, ORPO, GRPO, QAT |
| [torchtune](https://blog.csdn.net/m0_60827485/article/details/159384113) (Meta/PyTorch) | Код-ориентированный пост-тренинг | SFT LoRA/QLoRA, однократные рецепты |
| [MLX-LM](https://github.com/Sriramdayal/Unsloth-LLM-finetuningv1) | Apple Silicon | low-rank и полный файнтюн, квантование |
| [LitGPT](https://blog.csdn.net/m0_60827485/article/details/159384113) | Лёгкий, читаемый | LoRA/QLoRA/Adapter, fp4–fp32 |
| llama.cpp ([`convert_lora_to_gguf.py`](https://huggingface.co/spaces/build-small-hackathon/BuzzwordsMisdemeanors/blob/dfe151a6a8318199247cbc7519e0032823822d6d/docs/ARCHITECTURE.md)) | Локальный инференс | GGUF, в т.ч. LoRA-адаптеры без слияния, горячая загрузка |
| [Unsloth-LLM-finetuningv1](https://github.com/Sriramdayal/Unsloth-LLM-finetuningv1) | Мультиплатформенный тулкит | unsloth-cli, GUI, REST API очереди обучения; QLoRA/Windows, MLX/macOS |

Типовой конвейер 2025–2026: QLoRA-обучение (Unsloth/TRL SFTTrainer) → слияние адаптера → GGUF (Q4_K_M) → локальный инференс llama.cpp/Ollama. Полный пример «под ключ»: [qwen3-8b-andrew-resume-v2](https://huggingface.co/2stacks/qwen3-8b-andrew-resume-v2/blob/main/README.md), практический курс: [The Practical LLM Fine-Tuning Lab](https://aisignal.dev/analysis/r6410418-jackrong-llm-finetuning-guide).

Компактность персонализации: адаптер QLoRA может быть ~4 МБ (0.19% параметров) — [InnerSpace](https://huggingface.co/spaces/build-small-hackathon/innerspace/blob/f19a6db473ee033ec88203f36f6d39193c21afe5/README.md).

## 4. Накопление опыта: репозитории, которые «учатся на пользователе» (2025)

| Репозиторий | Механика накопления | Стек |
|---|---|---|
| [Lamark Agent](https://github.com/merocle/lamark-agent) — самое прямое попадание | Диалоги копятся в JSONL; **ночной LoRA-файнтюн** «запекает» накопленный контекст в веса; порог min_pairs (50–100) перед ре-тренингом | vLLM + LoRA (Qwen), systemd-timer |
| [Nova](https://www.hazumi.news/posts/47393086) | Цикл «учится на ошибках»: детект коррекции → извлечение урока → генерация **DPO-пар** → авто-файнтюн → **A/B-оценка перед деплоем** | Ollama (Qwen), temporal knowledge graph, ChromaDB |
| [MiniNeuroModel](https://github.com/DrDrewCain/MiniNeuroModel) | Continual learning на одной машине: **LoRA (≈2.2% параметров) + Experience Replay (50/50) + EWC** против забывания; per-domain адаптеры | Mac, single base model |
| [Memento](https://github.com/Memento-Teams/Memento) | «Файнтюн агентов без файнтюна LLM»: continual-learning конвейер сбора данных и ре-тренинга; параметрическая память (нейро-ретривер) | arXiv 2508.16153 |
| [OPPU](https://arxiv.org/html/2402.04401v3) (One PEFT Per User) | Per-user PEFT-модуль (<1% параметров) на истории поведения пользователя | Исследование (LaMP) |
| [DoMIX](https://snu.elsevierpure.com/en/publications/domix-an-efficient-framework-for-exploiting-domain-knowledge-in-f/) | Continual domain-адаптация через LoRA-модули: решает забывание и чувствительность к порядку данных | ACL 2025 |
| [OpenPersona persona-model-trainer](https://raw.githubusercontent.com/acnlabs/OpenPersona/refs/heads/main/skills/persona-model-trainer/README.md) | «Модель — это персона»: файнтюн персоны на Unsloth/MLX, экспорт GGUF/Ollama/ONNX; 4 ГБ RAM, без GPU | Qwen/Llama/Gemma |
| [Thelgevold/fine-tuned-classifier](https://github.com/thelgevold/fine-tuned-classifier) | Цикл обратной связи: курьеры-мис классификации сливаются в тренировочные данные | Docker, Unsloth+llama.cpp, Ollama |

Общие паттерны: накопление пар взаимодействий → порог накопления → периодический (ночной) LoRA/DPO-файнтюн → локальный сервинг; защита от забывания — EWC, replay или замороженная база + лёгкие адаптеры; приватность через локальность.

## 5. Память-в-файлах (RAG) против памяти-в-весах: выбор подхода

Диагностическое исследование [«Substrate Asymmetry in User-Side Memory»](https://sinoxiv.napstic.cn/article/25971628):
- **LoRA/веса** решающе выигрывают на *поведенческой согласованности* (стиль, манера) — это именно «опыт взаимодействия»;
- **RAG** решающе выигрывает на *фактах и абстиненции* (не выдумывать, чего не было в истории) и на свежести;
- Один и тот же слой внимания (21–35) отвечает за оба эффекта в противоположные стороны — один субстрат не даёт обоих.

Осторожно: на сильно RLHF-моделях (Llama-3.1-8B-Instruct) поведенческое преимущество LoRA ослабевает («alignment tax»); LoRA может ухудшать общее рассуждение. Тренд 2025 — **гибрид**: RAG для свежего состояния, периодическая «компакция» старого в семантически сгруппированные LoRA-адаптеры ([EuroMLSys 2025](https://euromlsys.eu/pdf/euromlsys25-25.pdf)), маршрутизация классификатором; третий путь — [Latent Personal Memory](https://arxiv-org.ezproxy.obspm.fr/pdf/2606.20911) (пер-пользовательские soft prompts: точность как у LoRA при ~120× меньше обучаемых параметров).

## 6. Мелодический домен: LLM и «вокальное, а не голосовое»

Ключевой принцип для формулировки запроса: **мелодия = символьная структура (высоты, ритм), тембр = звук**. Дообучать LLM на мелодическом опыте следует в символьном представлении:

| Проект | Что делает | Релевантность |
|---|---|---|
| [SongComposer](https://github.com/pjlab-songcomposer/songcomposer) (ACL 2025) | LLM (InternLM2-7B) для текста+мелодии: tuple-формат word-level выравнивания, расширенный токенайзер нот, открыты код, веса и датасет SongCompose | Эталон дообучения на символьной мелодии |
| [VocalParse](https://github.com/pymaster17/VocalParse) (arXiv 2605.04613) | Транскрипция пения LLM (Qwen3-ASR-1.7B): слова+высоты+длительности+темп интерливинг-токенами (`<P_68> <NOTE_4> <BPM_89>`); SOTA на Opencpop/GTSinger/M4Singer | Формат «мелодия токенами» как мост: аудио → символьный опыт |
| [GAME](https://github.com/openvpi/GAME) (Generative Adaptive MIDI Extractor) + [Vocal2Midi](https://github.com/Xiantaidu/Vocal2Midi) | Извлечение нот из вокала (ONNX), GUI с выравниванием слов | Аналоги ядра v2m, без LLM-накопления |
| [LilyPond LoRA](https://thesis.unipd.it/handle/20.500.12608/106863) | LoRA-файнтюн GPT-OSS-20B на нормализованной нотации LilyPond (391 партитура): надёжнее, чем zero-shot | Доказательство: LoRA хорошо учит мелодический синтаксис |
| [YNote](https://arxiv.org/abs/2502.10467), [NotaGen](https://arxiv.org/abs/2502.18008) | Новые нотации/парадигмы обучения LLM символьной музыке | Ориентиры представлений |
| [小音老师](https://m.toutiao.com/article/7557339390058463783/) (Little Music Teacher) | Оффлайн «учитель пения»: CREPE (±10 центов) + Qwen2-1.5B + DiffSinger; понотовая обратная связь («3-й звук ниже на 60 центов»), голосовой Q&A | Архитектурно ближайший аналог «мелодического взаимодействия» v2m; LLM — поверх детектора питча |
| [SingingSDS](https://ar5iv.labs.arxiv.org/html/2511.20972) | Диалоговый агент, отвечающий **пением** (ASR→LLM→SVS, VISinger2) | «Мелодический диалог» в чистом виде; открыт код |
| [VITA-QinYu](https://github.com/VITA-MLLM/VITA-QinYu) | Речевая LLM (Qwen3-8B): пение по «напевной» команде без нот, ролеплей | Край: генерация пения одним проходом |
| [flstudio-mcp](https://github.com/ohhalim/flstudio-mcp) | Обучение **мелодических предпочтений**: рейтинги 1–5, профиль `user_preferences.pkl`, learning rate | Простейшая «память вкуса к мелодиям» |
| [Jim Studio](https://github.com/gabrielmaialva33/jim_studio) | Open-source музыкальный педагог: транскрипция (Parakeet), фидбек (LLaMA 3.1), **метрики прогресса во времени**, недельные дайджесты | Прогресс-память поверх транскрипции |

## 7. Синтез и рекомендация (для решения А.М., не реализация)

Для ядра, накапливающего опыт мелодического взаимодействия, складывается следующая архитектура-образец (собирается из открытых компонентов):

1. **Символьное представление опыта**: напевки → ноты/ритм/темп (ядро v2m / VocalParse-подобный формат) → записи вида «входная мелодия + транскрипция + коррекция пользователя + оценка». Это «мелодические диалоги».
2. **Свежий опыт — в файлах** (журнал сессий, как Lamark/Memento/ai-jam-sessions): мгновенно доступно, без переобучения.
3. **Периодическое запекание — локальный LoRA/QLoRA** (Unsloth; ночной джоб, порог накопления, DPO-пары из коррекций как в Nova; A/B-проверка перед активацией адаптера).
4. **Защита от забывания**: замороженная база + адаптеры (MiniNeuroModel/OPPU) или EWC/replay.
5. **Деплой адаптера**: llama.cpp/GGUF/Ollama; если ONNX — OpenPersona показывает экспорт в ONNX.

Ограничения поиска: не найдено ни одного репозитория, сочетающего *все* элементы (локальное дообучение + мелодический домен + персонализация); VocalParse и GTSinger — мандарин-центричны; часть «музыкальных учителей» — хакертон-проекты без зрелого кода; VITA-QinYu и SingingSDS — про генерацию пения (ответ мелодией), а не про обучение на мелодии пользователя.

## 8. Источники

Локальный файнтюн: [Unsloth](https://www.unsloth.ai/docs/jp/moderu/gpt-oss-how-to-run-and-fine-tune/tutorial-how-to-fine-tune-gpt-oss), [обзор тулкитов](https://blog.csdn.net/m0_60827485/article/details/159384113), [Unsloth-LLM-finetuningv1](https://github.com/Sriramdayal/Unsloth-LLM-finetuningv1), [Practical Fine-Tuning Lab](https://aisignal.dev/analysis/r6410418-jackrong-llm-finetuning-guide), [resume-v2 пример](https://huggingface.co/2stacks/qwen3-8b-andrew-resume-v2/blob/main/README.md), [BuzzwordsMisdemeanors (LoRA в llama.cpp)](https://huggingface.co/spaces/build-small-hackathon/BuzzwordsMisdemeanors/blob/dfe151a6a8318199247cbc7519e0032823822d6d/docs/ARCHITECTURE.md).

Накопление опыта: [Lamark Agent](https://github.com/merocle/lamark-agent), [Nova](https://www.hazumi.news/posts/47393086), [MiniNeuroModel](https://github.com/DrDrewCain/MiniNeuroModel), [Memento](https://github.com/Memento-Teams/Memento), [OPPU](https://arxiv.org/html/2402.04401v3), [DoMIX](https://snu.elsevierpure.com/en/publications/domix-an-efficient-framework-for-exploiting-domain-knowledge-in-f/), [OpenPersona](https://raw.githubusercontent.com/acnlabs/OpenPersona/refs/heads/main/skills/persona-model-trainer/README.md).

Память vs веса: [Substrate Asymmetry](https://sinoxiv.napstic.cn/article/25971628), [EuroMLSys 2025 гибрид](https://euromlsys.eu/pdf/euromlsys25-25.pdf), [Latent Personal Memory](https://arxiv-org.ezproxy.obspm.fr/pdf/2606.20911), [обзор персонализации email](https://www.mdpi.com/1999-5903/17/12/536).

Мелодический домен: [SongComposer](https://aclanthology.org/2025.acl-long.352/), [VocalParse](https://scirate.com/arxiv/2605.04613), [GAME](https://github.com/openvpi/GAME), [Vocal2Midi](https://github.com/Xiantaidu/Vocal2Midi), [LilyPond-LoRA](https://thesis.unipd.it/handle/20.500.12608/106863), [SingingSDS](https://papers.cool/arxiv/2511.20972), [VITA-QinYu](https://github.com/VITA-MLLM/VITA-QinYu), [小音老师](https://m.toutiao.com/article/7557339390058463783/), [flstudio-mcp](https://github.com/ohhalim/flstudio-mcp), [Jim Studio](https://github.com/gabrielmaialva33/jim_studio), [ai-jam-sessions](https://www.npmjs.com/package/@mcptoolshop/ai-jam-sessions).
