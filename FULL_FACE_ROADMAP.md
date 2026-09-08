# AR Makeup — план развития 2D hybrid full-face

Актуально на 2026-09-08. Это forward-only roadmap. Текущее состояние, измерения и итоги завершённых исследований находятся в `PROJECT_CONTEXT.md`.

Ограничение пользователя от 2026-09-04: развивать проект без новых ML-моделей — готовых, собственных или дообученных. Существующий MediaPipe Face Landmarker сохраняется. Новые модели не являются запасным вариантом при провале visual acceptance; вернуться к этому направлению можно только по новой явной команде пользователя.

## Текущий инструмент — runtime tuning panel

Для визуальной итерации добавлена сворачиваемая панель 35 material/color/coverage/camera-sampling параметров. Она нужна для контролируемого подбора на одном и том же Sync-пайплайне и экспорта точных значений вместо правок коэффициентов вслепую. Настройки разделены по finish; выбранные пользователем значения записаны как SATIN defaults, matte/gloss пока нейтральны. Camera-value color transfer уже подключён к MATTE, SATIN и GLOSS; Яркость из камеры 0/1 служит A/B прежнего и нового пути. RGB и Яркость пигмента позволяют менять исходный продуктовый цвет отдельно от общей Яркости материала. Следующий gate — runtime и visual acceptance на Samsung. После visual acceptance панель следует убрать или ограничить перед production.
## Текущий приоритет — iOS-сатин, мягкий край и внутренняя полоска

Пользователь 2026-09-04 остановил подбор параметров: после linear compositing стало чуть ближе, но сатин ещё не принят. Исследование iOS завершено и сохранено в checkpoint 3700c9f. Затем пользователь разрешил начать с полоски между губами: первый candidate сохраняет плотное покрытие до внутреннего контура и убирает повторный innerGuard, без расширения отверстия рта. После установки на Samsung пользователь поручил оставить текущий вариант и закоммитить; полная visual acceptance отдельно не подтверждена. Результаты исследования — [IOS_REFERENCE_CONTEXT.md](IOS_REFERENCE_CONTEXT.md).

Порядок реализации: сначала пункт 3 (полоска, текущая задача), затем пункт 2 (мягкий край), затем пункт 1 (доведение сатина).

1. Согласовать satin с основным iOS Metal-путём: #B8202D/high/creamy, масштаб camera samples (iOS BGRA512), detail/highlight, canonical raster/filtering, раздельные RGB/alpha и финальная цветовая цепочка. Не вводить произвольный dimmer; SceneKit ×0.90 относится только к CPU fallback.
2. Добавить небольшое размытие прозрачности внешнего края при сохранении фактуры, плотности тела губ и согласованности с exact Sync frame.
3. Устранить незакрашенную полоску: согласовать внутреннюю coverage и геометрию, сохраняя прозрачное реальное отверстие рта/зубы. Проверить inner coverage ramps/innerGuard Android относительно плотного lip-side края и aperture-only перехода iOS.

Gate: пользователь принимает сходство сатина и мягкость; нет полоски на сомкнутых губах, окрашивания зубов/рта, широкого ореола или нового мерцания при разговоре/поворотах; прежняя стабильность Sync сохранена. Наличие iOS live prediction, ARKit closure veto или ML-training папки не разрешает переносить их в Android. Остальные этапы ниже остаются backlog.

## Целевое состояние

Один on-device timestamped `FullFaceRenderState` обслуживает все эффекты макияжа:

- ARCore-compatible backend даёт camera timeline и быстрый global pose/anchor;
- MediaPipe-compatible backend даёт локальные 2D-контуры мимики;
- геометрия и анализ пикселей согласованного кадра уточняют продуктовые области и окклюзии без новых ML-моделей;
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

1. Проверить ручное Async/Sync, error/fallback и смену поверхности; старт с Sync по умолчанию, exact pairing и обычные pause/resume/reacquisition уже проверены в диагностических сборках.
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

### F2. Маски видимых областей без новых ML-моделей

Исследование точной границы губ приостановлено по решению пользователя. Возвращаться к нему только по новой задаче; текущая точность при сильном сжатии/профиле не прошла acceptance. Непринятые эксперименты и временная диагностика удалены из приложения, их ограничения записаны в PROJECT_CONTEXT.md.

- Для будущих эффектов определить model-independent контракты областей, confidence и occlusion.
- Маски должны соответствовать exact pair; не подмешивать другой timestamp к Sync-изображению.
- Новые модели не планируются. Общее выключение помады при сжатии не заменяет точной границы.

Gate будущего возобновления: подтверждённое улучшение относительно текущего baseline без мерцания и окрашивания зубов, рта, глаз и фона.

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
- Новые ML-модели, weights и обучающие datasets исключены из scope. Для новых SDK/assets сохраняется `LICENSE_COMPLIANCE.md`; отдельное разрешение пользователя на изменение ML-scope не отменяет license gate.
- Unit-тесты запускать только по явной команде пользователя; обычный debug/device gate разрешён.
- После visual acceptance обновить `PROJECT_CONTEXT.md` и `CHAT_HANDOFF.md`; Git checkpoint создавать только по команде пользователя.
