# AR Makeup — план развития 2D hybrid full-face

Актуально на 2026-09-04. Это forward-only roadmap: выполненная и отменённая история хранится в Git. Текущее состояние и измерения находятся в `PROJECT_CONTEXT.md`.

## Целевое состояние

Один on-device timestamped `FullFaceRenderState` обслуживает все эффекты макияжа:

- ARCore-compatible backend даёт camera timeline и быстрый global pose/anchor;
- MediaPipe-compatible backend даёт локальные 2D-контуры мимики;
- semantic parsing уточняет продуктовые области и окклюзии;
- один temporal fusion слой согласует timestamps, confidence и visibility;
- один GPU compositor рисует помаду, lip liner, blush, eyeshadow и eyeliner.

ARCore, MediaPipe и конкретный renderer остаются заменяемыми реализациями за model-independent контрактами. Видимый canonical 3D face renderer не является целью.

## Коротко: готовая основа

- ARCore владеет launcher camera session и render timeline.
- MediaPipe получает latest-only image той же камеры и выдаёт lip contour.
- Реализованы `FaceObservation`, `LipContourObservation` и analytic `LipSemanticState`.
- Работают timestamped translation anchor, local lip refiner и tessellated lip mesh.
- Верхняя губа принята; основное отставание нижней губы значительно уменьшено.
- Есть рабочие matte/satin/gloss materials и OpenGL ES launcher.
- Есть диагностический legacy CameraX/Filament/native Vulkan path, но он не является product launcher.
- Bundled Face Landmarker и текущий runtime stack имеют инженерный license audit.

## Now — принять tracking губ

### T1. Сохранить принятый Sync без блокировки GL

Пользователь принял `fe8ffe2`, затем предварительно подтвердил визуальный результат неблокирующего GPU-pair Sync и нормальную работу pause/resume и reacquisition. Sync выбран основным по умолчанию; Async оставлен для ручного сравнения. Прежние anchor/live-mesh эксперименты не повторяем без новых данных.

1. Проверить старт с Sync по умолчанию, ручное Async/Sync, error/fallback и смену поверхности; exact pairing и обычные pause/resume/reacquisition уже проверены.
2. Расширить visual acceptance материалов и краёв после GPU camera-copy на разные условия освещения.
3. Измерить частоту новых пар, задержку изображения, GPU cost и thermal. Свободный GL thread не означает 60 уникальных MP кадров.
4. Расширить device matrix, освещение, движение телефона и dropout/reacquisition.

Gate: прежний jitter не возвращается; рот и быстрые движения не хуже checkpoint; фон и макияж относятся к одному sensor timestamp; локальные фильтры не меняются.

### T2. Проверить latency без профилактического изменения губ

В принятом Sync пользователь не отмечает проблем открытия/закрытия рта. Residual lag исследовать только при воспроизводимой регрессии нового candidate.

- Разделять alignment age и задержку всего предъявленного изображения.
- Проверять речь, улыбку и быстрое открытие/закрытие с выключенным тяжёлым trace.
- При регрессии сначала проверить delivery/presentation cadence, не подбирать коэффициенты губ вслепую.

## Next — общий full-face state

### F1. Завершить model-independent contracts

- Ввести `FaceTrackingBackend` и `FullFaceRenderState`.
- Расширить observation с губ до глаз, век, бровей, щёк и face visibility.
- Хранить sensor timestamp, topology version, confidence, occlusion и backend provenance.
- Оставить ARCore/MediaPipe classes внутри adapter/backend слоёв.
- Все эффекты должны получать один state, а не независимые фильтры.

Gate: renderer и material API компилируются без прямых зависимостей на ARCore/MediaPipe типы.

### F2. Semantic masks без новой собственной модели

- Улучшить lips и mouth/teeth exclusion текущими contour/edge cues.
- Добавить broad skin/hair/background mask только после отдельного model/license gate.
- Определить контракты вероятностей для lips, mouth-teeth, skin, eyes, eyelids, brows и hair-background.
- Не запускать segmentation на каждом render frame; обновлять face crop latest-only и репроецировать mask на общий state.

Gate: нет окрашивания зубов, рта, глаз и фона; границы стабильнее landmark-only baseline.

### F3. Решение о собственной parsing model

Собственная компактная LiteRT-модель допускается только если F2 не проходит visual acceptance.

До обучения обязательны:

- права на изображения лиц и коммерческое ML-training использование;
- права на labels, annotator work, pretrained/teacher weights и synthetic assets;
- model card, provenance, version и SHA-256;
- mobile benchmark GPU/NPU/CPU и privacy review.

## Then — production compositor и материалы

### G1. Production GPU/Vulkan cutover

- Перенести принятый full-face tracking/material state в единый retained-camera GPU pipeline.
- Исключить production CPU bitmap copies и runtime material compilation.
- Сохранить OpenGL ES launcher как visual rollback до полной parity.
- Подтвердить orientation, crop, mirror, color space, HDR/SDR и actual-present timing.

Gate: визуальная parity не хуже launcher baseline, compositor p95 укладывается в `6–8 ms`, camera/render остаются latest-only.

### G2. Завершить материалы

- Matte: плотный pigment при сохранении складок и теней.
- Satin: мягкий направленный specular без пластикового блеска.
- Gloss: локальный camera-conditioned highlight без статичной белой полосы.
- Для всех finish: edge refinement, auto-exposure stability, temporal coherence, HDR и display color calibration.

Gate: разные finish отличаются оптикой, а не только alpha/цветом; нет shimmer при движении и смене экспозиции.

## Later — продуктовые эффекты

### P1. Lip liner

Стабильная spline по semantic lip boundary, ширина относительно лица, корректные уголки рта и feathering.

### P2. Blush

Парные cheek masks из общего face state, skin-only coverage, исключение глаз/волос/ноздрей, сохранение пор и светотени.

### P3. Eyeshadow

Раздельные eyelid/crease masks, blink confidence, окклюзия ресницами и стабильность обоих глаз на одном timestamp.

### P4. Eyeliner

Sub-pixel eyelid contour, относительная толщина/wing, корректная деформация при моргании и отсутствие окрашивания глаза.

## Release readiness

1. Device matrix: слабый, средний и флагманский классы; ARCore-compatible и fallback сценарии.
2. Performance: стабильные render FPS, bounded CPU/GPU memory, latest-only queues.
3. Thermal: 10–15 минут непрерывной работы без неприемлемого throttling.
4. Visual: разные лица, оттенки кожи, освещение, мимика, повороты и движение устройства.
5. Privacy: on-device camera/inference, camera disclosure, Privacy Policy и Data Safety.
6. Licenses: закрыть checklist из `LICENSE_COMPLIANCE.md`, собрать notices и SBOM.
7. Product: unsupported-device UX, сохранение/съёмка только после отдельного privacy/product решения.

## Общие правила этапов

- Один candidate решает одну измеренную причину.
- Сначала controlled trace/A/B, затем изменение алгоритма.
- Покадровая диагностика не должна влиять на visual acceptance; тяжёлый trace после capture выключается.
- Не возвращать predictor, gyro, optical flow, visible 3D mesh или face-wide warp без измеримого преимущества.
- Не добавлять ML-модели, datasets, weights, SDK или assets до обновления `LICENSE_COMPLIANCE.md`.
- Unit-тесты запускать только по явной команде пользователя; обычный debug/device gate разрешён.
- После visual acceptance обновить `PROJECT_CONTEXT.md` и `CHAT_HANDOFF.md`; Git checkpoint создавать только по команде пользователя.
