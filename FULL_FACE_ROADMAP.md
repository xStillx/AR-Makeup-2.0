# AR Makeup — план перехода к full-face 3D tracking

Статус: принят 2026-08-17.

Текущий прогресс 2026-08-24:

- FF0 выполнен: V6.3 сохранён commit `dd095f7` и тегом `tracking-v6.3-gyro-experimental-2026-08-17` после полного unit/lint/assemble gate. Это experimental, не stable.
- Первый shadow slice FF1/FF2 реализован поверх checkpoint: `.arv6` v6 разделяет tracker latency на camera→analysis, analysis→submit, MediaPipe inference и callback queue, отдельно пишет RGBA/quality/result-processing CPU duration.
- MediaPipe 4×4 facial transformation matrix включается только при debug telemetry, сохраняется с timestamp исходного camera frame и пока не передаётся в renderer.
- Device stationary/head-motion/phone-motion/roll benchmark выполнен на SM-G990B при thermal status `0`: во всех валидных runs `dropped=0`, matrix coverage/right-handed/similarity `1.0`; callback/result CPU мал относительно camera→analysis и inference.
- Column-major layout, metric axes/handedness, affine/similarity, yaw/pitch/roll, rotation/mirror/crop зафиксированы кодом и unit tests. Roll device correlation с текущим 22-anchor pose равна `0.999461`; matrix пока остаётся shadow-only.
- `.arv6` v7 добавляет CPU-observable render timeline: нормализованный Choreographer vsync, render callback start, sensor timestamp и принятие camera buffer, принятие geometry upload и CPU render submit. Actual presentation отдельно хранится как unknown (`-1`), потому что Filament Java API не предоставляет feedback о показе кадра.
- `.arv6` v8 добавляет preferred FrameTimeline `vsyncId`, expected presentation и deadline, а trace section `ARMK_FRAME:<vsyncId>` связывает app render callback с Perfetto. Реализация сохранена checkpoint `7bd498f [FF1]`; normal runtime без debug telemetry не получает второго Choreographer callback.
- Первый device v7 run дал полное coverage всех CPU markers, `dropped=0`: vsync callback `0.88/6.25 ms`, camera sensor→vsync `112.75/128.71 ms`, camera selection→submit `13.25/25.69 ms`, geometry upload→submit `0.62/2.99 ms`, render start→submit `3.49/9.39 ms` median/p95. Это functional proof, не controlled A/B.
- Device v8 подтвердил coverage `vsyncId/expected/deadline=1.0`. Perfetto даёт root-window FrameTimeline, но не Filament `SurfaceView`, поэтому root timing не считается actual makeup presentation. Layer-specific SurfaceFlinger sidecar измерил actual: desired→actual `44.18/45.51 ms`, ready→actual `40.23/41.75 ms`, actual interval `16.688/16.799 ms` median/p95; output плавный, но display queue добавляет около `44 ms` после desired target.
- FF1 display-queue protection A/B завершён: constant flag перенесён в `Engine.Builder`, `.arv6` v9 подтвердил fraction `0.0/1.0`, но off/on desired→actual `44.05/44.22 ms`; `filamentRenderedFraction=1.0` в обоих runs. Guard уже включён по умолчанию и candidate отклонён.
- Checkpoint `6be330f` сохраняет device-проверку и `.arv6` v10. Отключение Filament presentation hints ухудшило desired→actual `43.88→59.27 ms` и ready→actual `39.77→49.64 ms`; default hints-on обязателен, candidate отклонён.
- По ADR `docs/adr/001-native-vulkan-visible-proof.md` добавлен debug-only exclusive-surface A/B: обычный запуск не меняется, а extra `ENABLE_NATIVE_VULKAN_VISIBLE=true` вообще не создаёт Filament и передаёт единственный `SurfaceView` native swapchain. Existing camera AHB/import/fences/transform используются повторно; после fullscreen camera pass Vulkan рисует текущую bright tracking-test lip geometry. `VK_GOOGLE_display_timing` связывает native `presentID` с camera sensor timestamp.
- Device proof на SM-G990B прошёл orientation/mirror/crop/lip alignment, lifecycle и более `1020` camera frames при `cameraDropped=0`; direct sensor→actual rolling p50/p95 около `127.1/135.2 ms`. Native sidecar `583` frames: desired→actual `30.78/31.37 ms`, interval `33.38/33.60 ms`; same-APK Filament `1186` frames: desired→actual `29.10/46.04 ms`, interval `16.689/16.788 ms`. Native p95 очереди ровнее, но cadence ограничен camera arrival 30 FPS и поэтому cutover отклонён до независимого 60-Hz present.
- Актуальный локальный gate после proof: `133` unit tests, `0` failures/errors, lint, debug APK и native build четырёх ABI успешны; exact APK установлен и проверен на устройстве. Predictor, MediaPipe, camera transforms, product materials, зависимости и модели не менялись.
- Commit `2fb7cd7` реализует независимый display slice как persistent device-local camera texture: каждый свежий latest AHB один раз копируется на GPU и возвращается CameraX по sync-fd, а texture + новая lip geometry представляются независимо на каждом vsync. Добавлены counters copy/present/reuse и actual-interval telemetry. Локально пройдены `134` tests, lint, APK и четыре ABI.
- Device cadence/lifecycle gate этого slice пройден на SM-G990B: `4800` camera copies, `9574` presents, `4775` reused, `cameraDropped=0`, actual interval около `16.695/16.754 ms` p50/p95; orientation/mirror/crop/lip alignment и Home/resume/Back корректны. Warm SurfaceFlinger run дал native desired→actual `30.13/30.89 ms` против Filament `44.01/45.56 ms`, при одинаковом interval около `16.7 ms`. Thermal status был `2`, поэтому это acceptance 60-Гц cadence и lifecycle, но не окончательный motion-lag/performance A/B. Default остаётся Filament.
- Commit `082d88e` поднимает `.arv6` до v11 и записывает для native exact displayed contours/predictor/gyro state, backend, `presentID` и сопоставленный actual-present. Формат читает v1–v10; локально пройдены `137` tests, lint, APK и четыре ABI. Первый functional run дал `907` renders, `dropped=0` и actual-present coverage `0.99559`; вариативность SurfaceFlinger queue `30→47 ms` запретила прежний вывод о гарантированно более короткой native queue и привела к exact cold repeat следующего checkpoint.
- Commit `be35764` добавляет `MAILBOX` low-latency candidate, exact actual-presentation decomposition и offline `.arv6` analyzer. Exact cold FIFO показал submit→actual `63.48/64.45 ms`; `MAILBOX` снял один refresh до `46.80/47.73 ms`, сохранил actual interval `16.688/16.763 ms`, presentation coverage `0.9945`, `dropped=0` и thermal `0`. Драйвер Adreno при surface minimum `4` всё равно выделяет `5` images, поэтому выигрыш даёт замена pending кадра, а не сокращение фактического buffer count.
- Cold numerical stationary/head-motion/phone-motion A/B завершён. Clean stationary native/Filament: camera sensor→render-vsync `77.55/102.61 ms`, displayed jitter `1.70/3.78 px RMS`, SurfaceFlinger desired→actual `46.89/44.20 ms`; Filament actual не связывается с exact submit через Java API. Head-motion lag одинаков `33 ms`, overshoot `0.008375/0.008626`; phone-motion lag `0/0 ms`, overshoot `0.000687/0.003843`. Во всех принятых sidecar `0` intervals `>25 ms`; повторные движения имели разную силу/pose quality, а native использует `TRACKING_TEST` вместо production `SATIN`.
- Последующие predictor/gyro/flow A/B не устранили одновременно head-motion lag, phone-motion slippage и jitter. Направление изменено после isolated ARCore Augmented Faces proof: ARCore владеет front-camera session и current global face pose, MediaPipe получает latest-only image той же session и восстанавливает local lip shape.
- Device visual acceptance исходного proof на SM-G990B пройдена для ключевой гипотезы: чистый ARCore anchor не слетает при резких движениях головы и устройства, hybrid MediaPipe contour совпадает с губами. Локальная мимика одной ARCore-точкой не описывается, поэтому принят именно hybrid global-pose/local-deformation design. FF3 уже добавил full-face contract; OpenGL ES proof сохранён как rollback, а debug Vulkan camera/state cutover также прошёл функциональную device acceptance. Production materials, canonical depth/occlusion и compatibility fallback ещё не выполнены.
- Финальный локальный gate текущего checkpoint: `154` unit-теста, `0` failures/errors, lint, debug APK и четыре ABI. Warm runtime: ARCore `58–60 FPS`, MediaPipe `27–30 FPS`, YUV→RGBA обычно `2–3 ms`, ML `22–27 ms`, latest observation age около `67 ms`, affine fit обычно `2–4 px`.
- Первый FF3 contract slice реализован и принят на SM-G990B 2026-08-24: immutable `FaceTrackingBackend`/`FaceObservation`/`FullFaceRenderState`, explicit topology/coordinate/camera metadata, ARCore global adapter, MediaPipe local backend и pure hybrid composer. Diagnostic OpenGL draw methods потребляют только `FullFaceRenderState`. Device A/B сохранил отсутствие слёта при резких движениях и уточнил composition: rigid affine берётся по eye/nose/cheek anchors, translation закрепляется в current ARCore lip center, а деформируемый ARCore mouth scale не применяется. Exact accepted APK SHA-256 `88453CDDD81DD63A265B9F668BA8634858923787536358E1F7BC7ADE2F88F229`; unit/lint/APK/four-ABI gate успешен.
- Shadow contract slice сохранён checkpoint `2568a67`: `FaceObservation` и `FullFaceRenderState` имеют отдельные mouth/left-eye/right-eye states, per-feature geometry source/timestamp, nullable confidence/visible fraction и geometric aperture ratio. Добавлены текущие ARCore/MediaPipe eye contours с независимой привязкой каждого глаза и per-feature global fallback. Непроверенные confidence/visibility не синтезируются; debug OpenGL продолжает рисовать только принятую lip-картинку. Gate checkpoint: `164` unit-теста, `0` failures/errors, `lintDebug`, `assembleDebug` и native build четырёх ABI успешны; APK SHA-256 `90BCC7F81E181F0E2598294A52F5536805318950E385C868532AA2B2F67E56BF`.
- ARCore→Vulkan cutover прошёл функциональную device acceptance на SM-G990B. `TextureUpdateMode.EXPOSE_HARDWARE_BUFFER` оставляет ARCore единственным camera owner; Java `HardwareBuffer` zero-copy импортируется в native Vulkan device, native reference живёт до release fence, а camera copy и lip geometry принимаются атомарно по одному ARCore sensor timestamp. Retained camera texture представляется на 60-Hz display cadence при 30-Hz camera cadence; в проверенных runs `cameraDropped=0`. CameraX/IMU/predictor/temporal flow в этом режиме не запускаются; OpenGL proof остаётся rollback.
- Device fixes checkpoint: новая runtime/surface generation сбрасывает retained-camera timestamps и один раз восстанавливает Vulkan runtime после present failure, поэтому Home/resume больше не приводит к `Vulkan retained-camera present failed` и чёрному экрану. Local MediaPipe geometry принимается не старше `120 ms`, плавно ослабляется по yaw/pitch и affine residual (`full <= 0.006`, reject `> 0.016`), а при краткой потере удерживается до `100 ms`. При мимике lips больше не перепривязываются к неподвижному ARCore midpoint: stable-anchor affine сохраняет локальную трансляцию рта вместе с формой, тогда как глобальное head/phone motion остаётся исключительно ARCore-owned. Пользователь принял резкие head/phone движения, открытый рот, большие повороты и боковую мимику как текущий checkpoint; у экстремальных yaw/pitch сохраняется небольшой 2D perspective/occlusion residual. Локальный gate: `173` unit-теста, lint, APK и native build четырёх ABI; APK SHA-256 `2A285DC292E07903165443C21674E2B5BC5C75FDAD229DABE4A34E25AFB5A996`. Полный 10–15-минутный thermal soak ещё не выполнен.

Этот файл задаёт порядок дальнейшей разработки после V6.3. Полный исторический и технический контекст находится в `PROJECT_CONTEXT.md`; компактный перенос между чатами — в `CHAT_HANDOFF.md`.

## Целевое решение

MediaPipe Face Landmarker сохраняется как текущий заменяемый anchor-backend. Целевой production pipeline объединяет:

```text
MediaPipe 3D landmarks + canonical face transform
                         +
            Vulkan optical flow + IMU
                         ↓
           единый timestamped full-face 3D state
                         +
              semantic face parsing
                         ↓
                  Vulkan compositor
```

Один общий face state обслуживает помаду, контур губ, румяна, тени и подводку. Независимые трекеры или temporal filters для отдельных продуктов запрещены: они создадут разные lag/jitter и рассинхронизацию эффектов.

Собственная landmark/mesh model не является текущей задачей. Она рассматривается только после профилирования и controlled benchmark. Собственная semantic parsing model остаётся базовым планом, потому что Face Landmarker не выдаёт точные попиксельные области кожи, губ, зубов и век.

## FF0 — сохранить текущие точки отката

Задачи:

1. Сохранить существующие стабильные точки `tracking-v6.2-stable-2026-08-17`, `tracking-v6.2-fast-motion-2026-08-17` и `material-temporal-v1-candidate-2026-08-17`.
2. После повторного полного build gate зафиксировать текущий V6.3 coverage-aware gyro slice отдельным experimental commit/tag. Он не получает статус stable без пользовательской visual acceptance.
3. Не смешивать сохранение V6.3 с 3D/full-face рефакторингом.
4. Зафиксировать точные команды сборки, число тестов и актуальное состояние устройства в `PROJECT_CONTEXT.md`.

Критерий завершения: любой следующий эксперимент можно откатить независимо к V6.2 fast-motion, material candidate или V6.3 experimental checkpoint.

## FF1 — разложить end-to-end задержку

Нужно измерять отдельные временные точки:

- начало/окончание экспозиции и sensor/readout timestamp;
- поступление CameraX analysis frame;
- начало/окончание RGBA preparation;
- submit в MediaPipe и callback результата;
- pose/fusion/prediction;
- принятие geometry upload;
- sensor timestamp и момент принятия выбранного camera buffer, не смешивая это с presentation;
- render submission, Choreographer vsync/expected/deadline через FrameTimeline/Perfetto и actual presentation текущего `SurfaceView` через layer-specific SurfaceFlinger sidecar; production feedback в будущем должен принадлежать native swapchain.

Использовать `.arv6`, Perfetto/trace sections и существующую Camera2 metadata. Все realtime-ветки остаются latest-only; диагностическая запись не создаёт новую очередь кадров.

Критерий завершения: для stationary, head-motion и phone-motion сценариев известен p50/p95 каждого участка, а решение о собственной landmark model опирается на долю inference в общей задержке, а не на capture-to-result целиком.

Текущий status 2026-08-21: latency decomposition, native/Filament present matrix и predictor/gyro/flow эксперименты завершены как исследовательская база, но не дали приемлемого global attachment. Isolated ARCore + MediaPipe proof визуально подтвердил новый global-pose/local-deformation путь. FF1 считается достаточным для выбора следующей архитектуры, но не production-complete: текущая сцена использует debug OpenGL ES и diagnostic contour. Следующий gate начинается с FF3 contract и только затем переносит принятый гибрид в Vulkan/material path.

## FF2 — MediaPipe 3D transform в shadow-режиме

Задачи:

1. Включить `outputFacialTransformationMatrixes` только для экспериментального профиля.
2. Не менять видимую lip mesh на первом подэтапе.
3. Добавить matrix и её source camera timestamp в `.arv6` с обратной совместимостью codec.
4. Зафиксировать соглашения осей, handedness, matrix layout, rotation, crop и front-camera mirror unit-тестами.
5. Сопоставить virtual camera с реальными Camera2 focal length и physical sensor size.
6. Сравнить MediaPipe transform с текущим 22-anchor robust similarity estimator.

Обязательные сценарии: неподвижное лицо, медленный/быстрый yaw-pitch-roll, резкая остановка, улыбка/разговор, моргание, phone-only motion, dropout/reacquisition, слабый свет и нагрев.

Метрики: translation/rotation jitter, lag, stop overshoot, discontinuity при reacquisition, CPU/GPU overhead и thermal impact.

Критерий завершения: 3D transform имеет проверенные оси/масштаб, не добавляет неприемлемой нагрузки и показывает измеримую пользу хотя бы для yaw/pitch/pose consistency. До этого он не влияет на видимый renderer.

## FF3 — модель-независимый full-face контракт

Ввести границы:

- `FaceTrackingBackend` — выдаёт model observations;
- `FaceObservation` — модель-независимое измерение одного camera timestamp;
- `FullFaceRenderState` — состояние, репроецированное к отображаемому camera/render timestamp.

`FaceObservation` должен предусматривать:

- camera sensor timestamp и image transform metadata;
- 478 xyz landmarks и версию topology;
- canonical face transformation / 6DoF pose;
- confidence/visibility/fit quality;
- отдельные состояния рта, левого и правого глаза;
- optional blendshapes;
- camera intrinsics и quality metadata.

MediaPipe-specific классы не проходят в Vulkan renderer или makeup material API. Старый lip path временно работает через adapter, чтобы рефакторинг не менял изображение одним большим переключением.

Критерий завершения: MediaPipe backend можно заменить test/replay backend без изменений renderer; все старые tracking regressions проходят.

Статус 2026-08-24: первый vertical slice и Vulkan cutover device-accepted. Контракт поддерживает раздельные global/local backends, полную ARCore mesh/pose/camera metadata и MediaPipe local observation; fake backend regression доказывает заменяемость composer без SDK типов. Shadow extension публикует renderer-facing lip/eye regions, отдельные feature states/provenance/timestamps и nullable visibility/confidence; mouth/eyes имеют scale-independent aperture ratio, а локальный отказ одного глаза не отключает остальные feature. Sharp head/phone motion, открытый рот, большие yaw/pitch, lifecycle и боковая мимика губ проверены пользователем в Vulkan path. До полного завершения FF3 нужны reusable replay и unsupported-device fallback; перенос full-face surface/depth/masks становится FF5 vertical slice.

## FF4 — единый visual-inertial 3D tracker

Новый tracker объединяет:

- ARCore как camera-synchronized global 6DoF pose и canonical 468-point surface на поддерживаемых устройствах;
- MediaPipe как latest-only local mouth/eye deformation observation той же camera session;
- Vulkan optical flow по устойчивым full-face anchors только если после production cutover останется измеримый inter-frame residual;
- IMU prior только если controlled phone-motion A/B покажет пользу поверх ARCore pose;
- camera timestamps и intrinsics;
- canonical face pose и dynamic local deformation.

Глобальная rigid 3D pose отделяется от локальной мимики губ и век. Вводится один непрерывный fused state без покадрового переключения источника координат. Старый V4 flow не включается в видимый путь простым изменением thresholds: alternating accepted/rejected flow уже вызывал jitter.

Перед построением product masks выполняется late reprojection общей 3D-позы к timestamp реально показанного camera buffer. Rolling-shutter correction добавляется отдельным подэтапом только после базовой 3D fusion.

Критерий завершения: controlled A/B показывает уменьшение head-motion и phone-motion lag без возврата stationary jitter, forward overshoot и stop/reacquisition jump.

Статус 2026-08-24: глобальное attachment перенесено в общий `FullFaceRenderState` и Vulkan debug renderer без отдельного IMU/flow fusion. ARCore удерживает резкие head/phone сценарии, MediaPipe сохраняет локальную форму и трансляцию губ относительно стабильных face anchors. IMU/flow не возвращаются без измеримого остатка. Для закрытия FF4 остаются controlled dropout/reacquisition и thermal/fallback gates на полном makeup renderer.

## FF5 — видимый canonical 3D face renderer

Задачи:

1. Добавить canonical triangular topology и UV в Vulkan resources; учитывать, что базовая face topology содержит 468 точек, а 478-output добавляет iris landmarks.
2. Строить dynamic runtime face surface из текущих landmarks и общей 3D pose.
3. Вычислять/уточнять normals и depth/occlusion в единой camera coordinate system.
4. Перенести существующую помаду с screen-space lip strips на маску, привязанную к 3D surface, сохранив текущие внутреннюю/внешнюю границы и исключение рта/зубов как baseline.
5. Сохранить debug A/B `2D baseline / 3D candidate` до visual acceptance.

Критерий завершения: при фронтальном и наклонном лице, сильном yaw/pitch, улыбке и открытом рте 3D-вариант не хуже baseline по контуру и лучше по перспективе, surface attachment, normals и окклюзиям. Orientation/mirror/crop regressions отсутствуют.

## FF6 — semantic face parsing

Минимальная схема классов:

- skin;
- upper/lower lip;
- mouth/teeth;
- left/right eye;
- left/right eyelid;
- brow;
- hair/background.

### FF6A — бесплатный baseline без обучения

Первый production-oriented вариант не требует мощного локального ПК и собственного training run:

1. MediaPipe Face Landmarker и canonical 3D surface задают геометрию губ, век и щёк.
2. Параметрические/canonical UV masks задают lipstick, lip liner, blush, eyeshadow и eyeliner regions.
3. Vulkan edge-aware refinement, optical flow и общая 3D reprojection уточняют и стабилизируют границы.
4. Официальный MediaPipe Selfie Multiclass Segmenter под Apache 2.0 можно отдельно проверить для broad classes `face-skin / hair / background`; он не различает губы, зубы, глаза или веки и не считается pixel-perfect beauty parser.
5. Любая готовая модель добавляется только после фиксации model card, SHA-256, license/NOTICE и device benchmark. Broad segmentation запускается редко и переносится общей 3D surface, если её latency не укладывается в render cadence.

FF6A позволяет выпустить коммерчески пригодный baseline без обучения сомнительными датасетами, но не заявляется как финальная точность индивидуальных границ губ/век.

### FF6B — собственная компактная модель при необходимости

Мощный локальный ПК не является обязательным: обучение может выполняться интерактивно на бесплатном cloud GPU/TPU, например Google Colab, с учётом динамических лимитов и отсутствия гарантии ресурса. LiteRT является deployment runtime/format; исходная модель обучается TensorFlow/PyTorch и затем конвертируется/квантуется в `.tflite`.

До обучения или подключения модели обязательны:

1. Аудит лицензий runtime, architecture/code, dataset, labels, pretrained/teacher weights, synthetic assets и права распространять итоговые weights.
2. Документирование источников, версий, SHA-256 и model card проекта.
3. Проверка разнообразия оттенков кожи, возраста, формы губ/глаз, освещения, поз и окклюзий.
4. Юридически чистое разделение train/validation/test по людям и договоры/согласия на изображения лиц и разметку.
5. LiteRT GPU/NPU/CPU benchmark на целевых классах устройств.

Parsing выполняется ориентировочно 15–30 раз/с по face crop, а probability masks переносятся на каждый render frame через общую 3D surface и optical flow. Низкоразрешённая segmentation не используется как единственный источник тонкого контура: подводка и lip liner сохраняют geometry/sub-pixel refinement.

Порядок FF6B vertical slices: lips/mouth-teeth → skin/cheeks → eyelids/eyes/brows → hair/background occlusion. Собственная модель не обязательна, пока FF6A достигает acceptance конкретного эффекта.

Критерий завершения: semantic mask улучшает фактические границы без нового заметного lag, flicker и окрашивания зубов, глаз, волос или фона.

## FF7 — продуктовые эффекты поверх общего state

Последовательность:

1. Помада и контур губ — production acceptance 3D attachment, semantic edge и matte/satin/gloss material.
2. Румяна — canonical cheek UV mask, skin confidence, normals и сохранение пор/светотени.
3. Тени — eyelid/crease masks, отдельные visibility/blink confidence, градиенты и окклюзии.
4. Подводка — sub-pixel eyelid curve, толщина относительно лица, wing и деформация при моргании.

Каждый эффект использует один `FullFaceRenderState`; отдельный tracker допускается только как явно обоснованный локальный refinement, который не создаёт собственную глобальную pose timeline.

## FF8 — решение о собственной landmark/mesh model

Полную замену MediaPipe рассматривать только после FF1–FF6. Условия для запуска собственного training project:

- inference MediaPipe составляет доминирующую и практически устранимую часть end-to-end lag;
- controlled benchmark подтверждает недостаточную устойчивость/точность MediaPipe при целевых позах, свете или окклюзиях;
- нужна topology/detail, которую нельзя получить local refinement;
- существует прослеживаемый коммерчески пригодный dataset/training pipeline;
- рассчитаны стоимость разметки, обучение, on-device optimization, fairness/quality validation и дальнейшая поддержка.

Если эти условия не выполнены, MediaPipe остаётся anchor, а ресурсы направляются на fusion, parsing и материалы. Более компактная local refinement model предпочтительнее полной замены, если проблема ограничена губами или веками.

## Общие gates каждого этапа

- Сборка и unit/regression tests проходят; lint не добавляет ошибок.
- `cameraDropped=0`, realtime очереди latest-only.
- Нет regressions orientation, mirror, crop и camera/makeup alignment.
- Нет заметного stationary jitter, motion lag, stop overshoot или reacquisition jump.
- GPU compositor p95 остаётся в бюджете 6–8 ms на целевом SM-G990B.
- Выполнен 10–15-минутный thermal soak после функционального прогона на охлаждённом устройстве.
- Для ML/assets выполнен license audit до добавления в продукт.
- `LICENSE_COMPLIANCE.md` обновлён для каждой новой зависимости, модели, датасета, разметки, внешнего asset или SDK.
- После каждого принятого подэтапа обновлены `PROJECT_CONTEXT.md`, `CHAT_HANDOFF.md`, тестовые метрики и Git checkpoint.

## Следующее действие

1. Сохранить device-accepted ARCore→Vulkan hybrid отдельным Git checkpoint; rollback остаётся `2568a67`/OpenGL proof.
2. Начать FF5 vertical slice: передать ARCore 468-point face topology в Vulkan, добавить depth attachment и depth-only face occluder, а lip vertices рисовать с корректной clip-space глубиной. Это должно устранить остаточную ошибку перспективы/видимости на экстремальном yaw/pitch, которую 2D thresholds принципиально не решают.
3. Сохранить A/B `2D accepted baseline / 3D depth candidate` и повторить orientation/mirror/crop, stationary, резкие head/phone motion, мимику, открытый рот, yaw/pitch и lifecycle/dropout/reacquisition.
4. После 3D acceptance выполнить actual-present/camera telemetry и 10–15-минутный thermal soak, затем подключить matte/satin/gloss + color/HDR contract поверх того же `FullFaceRenderState`.
5. Добавить reusable replay и проверить поддержку ARCore/Vulkan на целевых устройствах, определив fallback для несовместимых устройств. Перед release выполнить ARCore Terms/privacy/user-notice gate из `LICENSE_COMPLIANCE.md`; IMU/optical flow добавлять только при измеримом residual.
