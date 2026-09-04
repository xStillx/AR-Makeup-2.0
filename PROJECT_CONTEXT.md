# AR Makeup — актуальный контекст проекта

Актуально на 2026-09-04. Этот файл хранит только текущее состояние и долгоживущие решения. Итоги отклонённых экспериментов сохранены кратко; локальные снимки не входят в Git.

Связанные документы:

- `AGENTS.md` — обязательные правила работы в репозитории;
- `FULL_FACE_ROADMAP.md` — только будущий план;
- `CHAT_HANDOFF.md` — короткая оперативная передача контекста;
- `LICENSE_COMPLIANCE.md` — единый реестр лицензий и release gates;
- `app/src/main/assets/MODEL_LICENSES.md` — происхождение и hash bundled ML-модели.

## Цель и обязательные требования

Нативное Android-приложение виртуальной примерки макияжа через фронтальную камеру. Целевые эффекты: помада и контур губ, румяна, тени и подводка.

Главный приоритет — реалистичность в реальном времени: точная привязка к лицу, отсутствие заметных lag/jitter, корректные окклюзии, сохранение фактуры кожи и физически правдоподобные материалы. Скорость разработки и простота кода вторичны.

Обязательные ограничения:

- не заменять GPU/ML-пайплайн простым 2D alpha overlay;
- один camera owner и одна согласованная sensor/render timeline;
- один model-independent timestamped full-face state для всех эффектов;
- ARCore/MediaPipe-типы не должны попадать в material API;
- inference и камера работают on-device, realtime-очереди — latest-only;
- Новые ML-модели исключены из текущего плана по решению пользователя от 2026-09-04: не искать и не подключать готовые модели, не обучать и не дообучать собственные. Существующий MediaPipe Face Landmarker сохраняется. Вернуться к этому направлению можно только по новой явной команде пользователя.
- Для новых SDK/assets и отдельно разрешённых будущих моделей/датасетов/weights сохраняется license gate; прохождение gate само по себе не разрешает добавление модели.

## Репозиторий и текущий checkpoint

- Проект: `C:\Users\User\AndroidStudioProjects\ARMakeup`.
- Ветка: `codex/lipstick-material-v2`.
- Базовый checkpoint кода приложения: `ca80814` (`[Tracking] Make non-blocking same-frame Sync the default`).
- Текущее устройство: Samsung SM-G990B.
- Launcher: `ArCoreFaceAnchorActivity`; `MainActivity` сохраняет legacy CameraX/Filament/Vulkan rollback и не экспортируется.
- В ca80814 блокирующий Sync заменён на неблокирующие GPU-пары. По команде пользователя Sync — основной режим по умолчанию; Async сохранён для ручного сравнения/отката.

## Активная архитектура

1. ARCore `1.54.0` владеет фронтальной камерой, camera timeline и текущей глобальной позой лица.
2. MediaPipe Tasks Vision `1.0.0` асинхронно получает latest-only `YUV_420_888` image той же ARCore session и выдаёт внешний/внутренний 2D-контур губ.
3. `MediaPipeFaceObservationAdapter` формирует model-independent `FaceObservation`: sensor timestamp, source transform, нормализованные контуры, geometry confidence, mouth openness и analytic `LipSemanticState`.
4. В Async `TimestampedLipAnchorTransport` переносит контур translation-only между measurement/render timestamps. В Sync сохраняется GPU-пара camera image + exact MediaPipe observation, поэтому cross-frame correction равна нулю.
5. `LipContourTemporalRefiner` обрабатывает только lip-local deformation относительно текущих mouth center/width. Global translation остаётся ответственностью ARCore transport.
6. `LipMeshTessellator` строит динамическую mesh; OpenGL ES compositor семплирует ARCore camera texture внутри неё и применяет coverage, reconstructed normals, camera luminance/gradient и lip-local UV.
7. `HeadDownLipVisibilityGate` скрывает помаду при устойчивом сильном наклоне головы вниз.

Rotation, front-camera mirror и `FILL_CENTER` обязаны быть общими для camera image, landmarks и mesh. Вторую CameraX session создавать нельзя.

## Коротко: что уже сделано

- Рабочий ARCore + MediaPipe launcher с переключением `Матовая / Сатин / Глянец / Трек`.
- Model-independent lip observation и изоляция MediaPipe topology в adapter-слое.
- Timestamped ARCore translation transport и фиксированный face-local carrier вместо expression-sensitive mouth vertices.
- Динамическая outer/inner lip mesh с защитой рта/зубов и мягкими границами.
- Material foundation для matte, satin и gloss; рабочая база принята, но финальная optical/HDR/lighting калибровка не завершена.
- Текущие pigments: matte/gloss `#9E2620`, satin `#B8202D`.
- Верхняя губа визуально принята при открытии/закрытии рта.
- Debug `LipFrameTrace` разделяет MediaPipe-only, ARCore-corrected, local-refined и tessellated координаты.
- `MonotonicSensorTimestampGate` устраняет повторный inference одного camera image: same-sensor изменения снижены с `140` до `0`.
- Evidence-based lower-band candidate пропускает общий синхронный center outer/inner к текущему measurement во время подтверждённого mouth transition, сохраняя сглаживание толщины и мелкой формы.

## Текущий визуальный статус

Пользователь принял Sync из `fe8ffe2`: рот, медленные/быстрые движения головы и движения глаз — всё отлично, прежнего дрожания не видно. После перехода на неблокирующие GPU-пары пользователь сообщил «вроде всё норм», затем подтвердил нормальную работу сворачивания/возврата и потери/повторного обнаружения лица. Новый Sync выбран основным; малый lag нижней губы не является текущей визуальной претензией. Расширенный device/performance acceptance остаётся открытым.

Покадровый trace нельзя держать включённым во время visual acceptance: форматирование большой строки на каждом render frame снижало MediaPipe примерно до `15 FPS`. После `adb shell setprop log.tag.LipFrameTrace S` наблюдались ARCore `58–59 FPS`, MediaPipe `24–27 FPS`, YUV `2–3 ms`, ML `22–32 ms`, age около `41 ms`.

Материалы остаются рабочей базой, а не финальным продуктовым качеством. Нужны проверки при разном освещении, защита от shimmer/auto-exposure, BRDF/HDR/edge refinement и multi-device acceptance.

## Основной Sync: неблокирующие same-frame GPU-пары (ca80814)

Принятый `fe8ffe2` ожидал callback до 100 ms на GL thread. Его визуальный результат сохраняем как эталон, механизм ожидания заменяем:

- `Session.update()` продолжает обновляться; tracker отдаёт completion через atomic reference без `await`/condition.
- `PairedCameraFrameStore`: две viewport-sized RGBA8 GPU texture/FBO — одна pending, другая presented. Один inference in flight, без очереди и CPU readback.
- CPU image допускается в Sync только при точном равенстве image/frame timestamp. До следующего update камера копируется GPU-проходом с rotation/mirror/crop; pitch/visibility/point сохраняются значениями, не live ARCore объектами.
- Только exact completion продвигает пару. No-face/error completion даёт изображение без помады; отсутствие completion удерживает готовую пару.
- Фон и материал читают одну сохранённую текстуру. В shader меняется только samplerExternalOES на sampler2D; BRDF/coverage/pigment, refiner и tessellator не изменены.
- Повторы пары используют её прежние sensor/render timestamps. Live ARCore pose и локальный render transition не двигают геометрию на удерживаемом изображении.
- Mode/session/tracker/pause/GL context и фактические rotation/viewport changes сбрасывают пары. Максимальное удержание 500 ms; затем live camera без помады. Это failure bound, не коэффициент фильтра.
- Status `SYNC pair · camera lag N ms` — разница latest camera и presented timestamp. `age 0 ms` означает совпадение геометрии и изображения, не нулевой motion-to-photon. Sync counters — promoted/reused/no-ready-pair.
- Цена: `2 × width × height × 4` байт GPU memory и один camera-copy pass на submit. Число уникальных пар ограничено MP throughput; свободный GL thread не гарантирует снижения задержки.

Сборка `:app:assembleDebug` прошла без unit-тестов, включая изменение стартового режима на Sync. После несовпадения debug-подписей пользователь самостоятельно переустановил APK. Runtime `SYNC pair` на SM-G990B подтверждён 2026-09-04: в просмотренных status-окнах 23–26 новых пар, no-ready-pair=0, MP 23–28 FPS, render 42–58 FPS, age=0 ms, anchor=0.0 px; camera lag преимущественно 41 ms, иногда 81 ms. В просмотренном runtime/crash log ошибок не найдено, trace выключен (`S`). Пользователь предварительно принял визуальный результат и подтвердил pause/resume и reacquisition. В последующих диагностических debug-сборках запуск с Sync по умолчанию подтверждён. Ручное переключение режимов и расширенные failure/performance checks остаются открытыми. Зависимости, модели и лицензируемые assets не добавлены.

Успех Sync поддерживает temporal mismatch/rebase гипотезу, но не выделяет единственную причину: одновременно обнуляется anchor correction и меняется cadence. Прежние варианты опорных точек/live mesh не дали принятого результата; bypass уменьшал jitter ценой lag. Повторять эти эксперименты без новых данных не планируется.


## Граница губ: исследование завершено без принятого улучшения

При сильном сжатии/подвороте и некоторых поворотах помада может выходить на кожу. Это известное ограничение, visual acceptance точной границы не пройден. По решению пользователя от 2026-09-04 дальнейшие эксперименты приостановлены до новой явной задачи.

- GPU-уточнение по цвету не дало уверенного улучшения и добавляло мерцание. Временная стабилизация уменьшила мерцание, но не решила сильное сжатие. Офлайн-поиск видимого контура также отклонён: линии неверны.
- Общее ослабление/выключение помады при сжатии отклонено как замена точной границе. Принятый HeadDownLipVisibilityGate к этому эксперименту не относится и сохранён.
- Scores существующего Blendshape V2 реагировали на трубочку и смыкание, но не выделили ключевой сильный подворот. Они выводятся из landmarks, а не независимо из пикселей; оснований деформировать контур по ним не получено. Источник: https://storage.googleapis.com/mediapipe-assets/Model%20Card%20Blendshape%20V2.pdf .
- Финальная проверка на SM-G990B: в семи снимках exact CPU RGBA input + GPU background timestamps совпали; наше display mapping и независимое ARCore IMAGE_NORMALIZED -> VIEW_NORMALIZED различались менее чем на 0.000220 px. Вход 640x480, rotation=270. При сильном подвороте сырые MediaPipe points уже охватывают кожу на CPU-изображении до display mapping, refiner, tessellation и материала. Дополнительное смещение при отображении не обнаружено в проверенных позах; это не гарантия для всех устройств/ориентаций.
- По команде пользователя экспериментальный GPU-проход, переключатель, захват по громкости вниз, CPU/GPU readback-диагностика, blendshape output/контракт и tools/diagnostics удалены. Код приложения снова соответствует ca80814; Sync, материалы, topology и принятые фильтры сохранены. Blendshape output выключен, новых моделей/assets нет.
- Локальные снимки и результаты сохранены только в gitignored captures/lip-boundary (32 снимка; итог CPU mapping — cpu-mapping/report.json и series.jpg). В Git фотографии не добавлять. Не повторять отклонённые варианты и не подбирать коэффициенты без новых данных и задачи пользователя.

## Открытые tracking-дефекты

### 1. Global jitter при движении головы и глаз

В Async дрожание воспроизводилось при yaw и движении глаз. В принятом Sync оно отсутствует визуально. Точная доля шума позы и rebase не доказана; текущий gate — сохранить результат без GL wait и расширить device matrix.

Диагностика локализовала дефект в global anchor/fusion path:

- статический p95 шага MediaPipe mouth center: `0.75 px`;
- статический p95 изменения ARCore correction: `3.53 px`;
- статический p95 corrected center: `3.46 px`;
- при движении только глаз local lower-lip shape почти неизменна: p95 `0.0004` lip width;
- при том же сценарии MediaPipe center давал выброс до `5.3 px`, ARCore correction — до `8.3 px`, corrected center — до `9.2 px`;
- `anchorResidualPx` практически равен нулю, поэтому translation применяется арифметически правильно;
- `tessResidualPx=0`, тесселяция скачок не создаёт.

Фиксированная 3D-точка carrier уже не зависит от деформации губ. Наиболее вероятный источник — шум `face.centerPose` и cross-tracker rebase в выражении `MediaPipe center(M) + anchor(R) - anchor(M)` при поступлении нового sensor timestamp.

Следующий шаг — расширенная проверка основного exact-pair Sync, без изменения anchor/filter coefficients. Стартовое значение renderer и checkedButton интерфейса установлены в Sync; Async включается вручную.

### 2. Малый residual lag нижней губы

Основное отставание устранено переносом shared lower-band center; в принятом Sync пользователь доволен ртом. Локальные фильтры не менять профилактически. Delivery/presentation cadence исследовать только при воспроизводимой регрессии.

## Действующие архитектурные решения

- Целевое направление — 2D hybrid: ARCore даёт вспомогательный global pose/anchor, существующий MediaPipe backend — локальные 2D-контуры, геометрия и анализ изображения — уточнение продуктовых границ и окклюзий без нового ML inference.
- Видимый canonical 3D face/lip renderer отклонён: он ухудшал контуры при мимике и поворотах.
- Predictor, gyro, optical-flow correction, face-wide affine/projective warp и residual smoothing не возвращать без нового controlled A/B, который показывает преимущество над текущим launcher.
- ARCore mesh не является продуктовой геометрией; он используется только внутренним pose/carrier backend.
- MediaPipe остаётся изолированным за model-independent контрактами; замена landmark model и разработка собственной модели исключены из текущего плана.
- Маски видимых областей строить из существующих landmarks, геометрических ограничений и изображения exact pair. Новые pretrained models, training, fine-tuning и сбор обучающих датасетов не входят в scope: пользователь не выделяет на это время и ресурсы. Это продуктовое ограничение, а не утверждение о запрете коммерческого использования всех готовых моделей.
- iOS-проект не изменять; отдельный общий SDK/JSON contract сейчас не нужен. Сравнивается поведение, а не буквальная реализация.

## Ближайший порядок работы

1. Проверить ручное переключение Async/Sync на устройстве; старт с Sync по умолчанию подтверждён в диагностических сборках; runtime exact pairing уже подтверждён, pause/resume и reacquisition приняты пользователем.
2. Оценить latency, GPU memory/copy cost и thermal основного Sync. Локальные фильтры и материал без конкретной регрессии не менять.
3. Расширить acceptance на другие устройства и движение телефона. Не считать render FPS числом уникальных camera/MP пар.
4. Закончить model-independent `FullFaceRenderState` для общего pose, локальных областей, confidence, visibility и occlusion.
5. Исследование точной границы губ приостановлено до новой задачи. Дальнейшие masks для mouth-teeth/глаз/век/кожи планировать в пределах подтверждённого качества и без новых ML-моделей.
6. Перенести принятый hybrid/material state в production GPU/Vulkan path, сохранив OpenGL launcher как rollback до visual parity.
7. Добавлять lip liner, blush, eyeshadow и eyeliner только поверх общего full-face state.
8. Завершить device matrix, thermal/fallback, privacy/license и release acceptance.

Подробный forward-only план находится в `FULL_FACE_ROADMAP.md`.

## Performance budgets

- Render: целевые `60 FPS`; минимум стабильные `30 FPS` на поддерживаемом классе устройств.
- GPU makeup/compositor: желательно `6–8 ms` p95 в пределах общего `16.6 ms` кадра.
- Face landmarks: стремиться к `30–60 results/s`; inference реже render допустим только без заметного lag.
- Уточнение масок по геометрии/изображению: без нового ML inference, внутри существующего GPU budget и без блокировки камеры/renderer. Прежний ориентир отдельного face parsing inference `15–30 results/s` больше не является активным требованием.
- Очереди realtime-веток: глубина `1`, latest-only, без накопления устаревших кадров.
- Любой FPS считается недостаточным при заметном плавании маски, jitter, motion lag или мерцании границ.

## Acceptance gates

- Нет заметного jitter на статичном лице, при движении глаз, yaw/pitch, движении телефона и после dropout/reacquisition.
- Нет заметного отставания при разговоре, улыбке и открытии/закрытии рта.
- Нет окрашивания зубов, рта, белков глаз, волос и фона.
- Проверены разные лица, оттенки кожи, освещение, мимика и поддерживаемые устройства.
- Материал сохраняет текстуру и светотень камеры; gloss не превращается в статичную белую полосу.
- Выполнены functional device runs, visual recordings, thermal soak и fallback checks.
- До релиза закрыты требования `LICENSE_COMPLIANCE.md`.

## Правила текущей работы

- Перед изменениями читать этот файл и `AGENTS.md`.
- Покадровый trace включать только на короткий controlled capture и выключать перед visual acceptance.
- Unit-тесты запускать только по явной команде пользователя; обычный debug build/device check разрешён.
- Не коммитить без явной команды пользователя.
