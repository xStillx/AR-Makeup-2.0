# AR Makeup — актуальный контекст проекта

Актуально на 2026-09-03. Этот файл хранит только текущее состояние и долгоживущие решения. История экспериментов и старые метрики доступны в Git.

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
- новые модели, датасеты, weights, SDK и assets добавляются только после license gate.

## Репозиторий и текущий checkpoint

- Проект: `C:\Users\arc11\AndroidStudioProjects\AR-Makeup-2.0`.
- Ветка: `codex/lipstick-material-v2`.
- Базовый checkpoint перед same-frame candidate: `6e4c613` (`[Tracking] Checkpoint lower lip and anchor diagnostics`).
- Текущее устройство: Samsung SM-G990B.
- Launcher: `ArCoreFaceAnchorActivity`; `MainActivity` сохраняет legacy CameraX/Filament/Vulkan rollback и не экспортируется.
- Текущий candidate добавляет диагностический same-frame lockstep A/B; тесты выбора ARCore-точек, оставшиеся на другом ПК, в этом checkout отсутствуют.

## Активная архитектура

1. ARCore `1.54.0` владеет фронтальной камерой, camera timeline и текущей глобальной позой лица.
2. MediaPipe Tasks Vision `1.0.0` асинхронно получает latest-only `YUV_420_888` image той же ARCore session и выдаёт внешний/внутренний 2D-контур губ.
3. `MediaPipeFaceObservationAdapter` формирует model-independent `FaceObservation`: sensor timestamp, source transform, нормализованные контуры, geometry confidence, mouth openness и analytic `LipSemanticState`.
4. `TimestampedLipAnchorTransport` переносит MediaPipe-контур translation-only между фиксированным face-local mouth carrier на measurement timestamp и текущим ARCore render frame.
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

На SM-G990B открытие/закрытие рта после lower-band candidate улучшилось значительно. На сильных переходах измеренный отрыв `ref` от `corr` снизился примерно с `12–16 px` до `0.2–1.8 px`. Пользователь считает результат пригодным как checkpoint, но небольшое остаточное отставание нижней губы ещё заметно.

Покадровый trace нельзя держать включённым во время visual acceptance: форматирование большой строки на каждом render frame снижало MediaPipe примерно до `15 FPS`. После `adb shell setprop log.tag.LipFrameTrace S` наблюдались ARCore `58–59 FPS`, MediaPipe `24–27 FPS`, YUV `2–3 ms`, ML `22–32 ms`, age около `41 ms`.

Материалы остаются рабочей базой, а не финальным продуктовым качеством. Нужны проверки при разном освещении, защита от shimmer/auto-exposure, BRDF/HDR/edge refinement и multi-device acceptance.

## Активный A/B: same-frame synchronization

Для проверки гипотезы о конфликте частот добавлен переключатель `Async 60 Гц / Sync кадр`.

- `Async 60 Гц` сохраняет основной latest-only путь: камера и ARCore обновляются на display vsync, а более старый результат MediaPipe переносится на текущий кадр через timestamped anchor transport.
- `Sync кадр` является диагностическим lockstep: после одного `Session.update()` renderer отправляет CPU image этого кадра в MediaPipe, ждёт callback до 100 ms и рисует губы только если sensor timestamp результата точно совпадает с timestamp видимого ARCore camera frame.
- В lockstep cross-frame anchor correction обязан быть нулевым, но GL thread намеренно блокируется на YUV + inference. Ожидаемая цена — снижение camera/render cadence примерно до MediaPipe cadence и увеличение end-to-end latency.
- Этот A/B проверяет причинность, но не является production-решением. Если jitter исчезнет, проблема находится в asynchronous rebase/transport seam; если сохранится — различие частот не является первопричиной и нужно продолжить разбор абсолютных ARCore/MediaPipe anchor samples.

В status overlay режим отображается как `ASYNC 60 Hz` либо `SYNC frame · wait N ms`; счётчики `inferred/reused/missing` показывают состав каждого контрольного окна.

Первый device run выявил сильное мерцание lockstep-маски. Причина была не в геометрии: после exact callback накопленный `requestRender()` часто получал повторный ARCore camera timestamp, dedupe не запускал второй inference, а renderer ошибочно рисовал такой повторный кадр без помады. Дополнительно callback первоначально будил renderer до сброса tracker `busy`.

Исправленный вариант сначала полностью освобождает input/`busy`, затем публикует completion, а при повторном camera timestamp повторно использует только observation с точно тем же sensor timestamp. Контрольный run на SM-G990B показал `missing=0` во всех 1-секундных окнах: примерно `14–17` новых inference и `15–30` безопасных same-timestamp reuse, MediaPipe `16–18 FPS`, wait обычно `25–48 ms`, `age 0 ms`, anchor correction `0.0 px`.

Предварительный пользовательский verdict на SM-G990B: после исправления мерцание исчезло, исходное дёрганье при движении головы и глаз визуально выглядит исправленным. Это поддерживает гипотезу об asynchronous cross-tracker rebase seam. Результат ещё нужно повторить на других устройствах; lockstep остаётся диагностикой, а не production-решением из-за блокировки GL thread и низкой inference cadence. Во время этого visual test тяжёлый `LipFrameTrace` был выключен (`S`); более лёгкий `LipJumpMetrics` оставался включён и логировал один раз на новый MediaPipe result.

## Открытые tracking-дефекты

### 1. Global jitter при движении головы и глаз

В основном asynchronous transport мелкое дрожание воспроизводилось при yaw и при движении только глаз. Same-frame lockstep предварительно убрал его на SM-G990B, поэтому дефект локализован к разрыву timelines/rebase, но не закрыт для production async path и device matrix.

Диагностика локализовала дефект в global anchor/fusion path:

- статический p95 шага MediaPipe mouth center: `0.75 px`;
- статический p95 изменения ARCore correction: `3.53 px`;
- статический p95 corrected center: `3.46 px`;
- при движении только глаз local lower-lip shape почти неизменна: p95 `0.0004` lip width;
- при том же сценарии MediaPipe center давал выброс до `5.3 px`, ARCore correction — до `8.3 px`, corrected center — до `9.2 px`;
- `anchorResidualPx` практически равен нулю, поэтому translation применяется арифметически правильно;
- `tessResidualPx=0`, тесселяция скачок не создаёт.

Фиксированная 3D-точка carrier уже не зависит от деформации губ. Наиболее вероятный источник — шум `face.centerPose` и cross-tracker rebase в выражении `MediaPipe center(M) + anchor(R) - anchor(M)` при поступлении нового sensor timestamp.

Следующее исследование должно раздельно записать абсолютные ARCore anchor samples на `M` и `R`, residual `MediaPipe center(M)-anchor(M)` и момент смены measurement. Затем нужен controlled A/B текущего transport против continuity-preserving global owner/bypass. Нельзя начинать с общего low-pass или случайного коэффициента: решение обязано сохранить преимущество ARCore при быстром движении.

### 2. Малый residual lag нижней губы

Основное отставание устранено переносом shared lower-band center. Остаток нужно искать в MediaPipe delivery cadence и sensor-to-visible-camera age, не меняя уже принятую верхнюю губу и не смешивая этот эксперимент с ARCore jitter.

## Действующие архитектурные решения

- Целевое направление — 2D hybrid: ARCore даёт вспомогательный global pose/anchor, MediaPipe-compatible backend — локальные 2D-контуры, semantic parsing — продуктовые границы и окклюзии.
- Видимый canonical 3D face/lip renderer отклонён: он ухудшал контуры при мимике и поворотах.
- Predictor, gyro, optical-flow correction, face-wide affine/projective warp и residual smoothing не возвращать без нового controlled A/B, который показывает преимущество над текущим launcher.
- ARCore mesh не является продуктовой геометрией; он используется только внутренним pose/carrier backend.
- MediaPipe остаётся заменяемым backend. Собственная landmark model рассматривается только после измеримого failure текущего решения и полного data/model license pipeline.
- Semantic parsing начинать с геометрического/бесплатного baseline. Собственную модель обучать только при документированных коммерческих правах на code, weights, данные и разметку.
- iOS-проект не изменять; отдельный общий SDK/JSON contract сейчас не нужен. Сравнивается поведение, а не буквальная реализация.

## Ближайший порядок работы

1. Повторить same-frame verdict на других устройствах: покой, глаза, медленный yaw, быстрый motion, движение телефона и reacquisition.
2. Если результат подтверждается, заменить блокирующий lockstep на production-safe continuity-preserving global owner/rebase без ухудшения fast-motion attachment.
3. Вернуться к малому residual lag нижней губы и проверить delivery/camera age.
4. Закончить model-independent `FullFaceRenderState` для общего pose, локальных областей, confidence, visibility и occlusion.
5. Добавить semantic masks для lips/mouth-teeth, затем глаз/век/кожи; не добавлять модель до license gate.
6. Перенести принятый hybrid/material state в production GPU/Vulkan path, сохранив OpenGL launcher как rollback до visual parity.
7. Добавлять lip liner, blush, eyeshadow и eyeliner только поверх общего full-face state.
8. Завершить device matrix, thermal/fallback, privacy/license и release acceptance.

Подробный forward-only план находится в `FULL_FACE_ROADMAP.md`.

## Performance budgets

- Render: целевые `60 FPS`; минимум стабильные `30 FPS` на поддерживаемом классе устройств.
- GPU makeup/compositor: желательно `6–8 ms` p95 в пределах общего `16.6 ms` кадра.
- Face landmarks: стремиться к `30–60 results/s`; inference реже render допустим только без заметного lag.
- Face parsing: `15–30 results/s` по face crop, без блокировки камеры и renderer.
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
