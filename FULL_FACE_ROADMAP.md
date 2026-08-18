# AR Makeup — план перехода к full-face 3D tracking

Статус: принят 2026-08-17.

Текущий прогресс 2026-08-18:

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
- До завершения FF1 нужен следующий native slice: удерживать ровно один последний camera image и представлять его с актуальной predicted geometry на каждом 60-Hz display vsync, затем controlled telemetry-on A/B против Filament. До завершения FF2 — face-visible yaw/pitch, stop/dropout/light/thermal matrix runs и controlled continuity/overhead acceptance.

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

## FF4 — единый visual-inertial 3D tracker

Новый tracker объединяет:

- MediaPipe как периодический абсолютный 3D anchor;
- Vulkan optical flow по устойчивым full-face anchors между ML-results;
- IMU prior для быстрого движения устройства;
- camera timestamps и intrinsics;
- canonical face pose и dynamic local deformation.

Глобальная rigid 3D pose отделяется от локальной мимики губ и век. Вводится один непрерывный fused state без покадрового переключения источника координат. Старый V4 flow не включается в видимый путь простым изменением thresholds: alternating accepted/rejected flow уже вызывал jitter.

Перед построением product masks выполняется late reprojection общей 3D-позы к timestamp реально показанного camera buffer. Rolling-shutter correction добавляется отдельным подэтапом только после базовой 3D fusion.

Критерий завершения: controlled A/B показывает уменьшение head-motion и phone-motion lag без возврата stationary jitter, forward overshoot и stop/reacquisition jump.

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

1. Разделить camera acquisition и display cadence: native runtime удерживает один последний завершённый camera AHB/image, безопасно заменяет его latest-кадром и представляет camera + новую predicted tracking-test geometry на каждом 60-Hz vsync. Не добавлять camera queue и не переносить product materials/full-face renderer в том же slice.
2. Записать controlled telemetry-on Filament/Vulkan A/B: direct sensor→actual, desired/ready→actual, actual interval/jank, duplicated frames, CPU/GPU/thermal и head/phone-motion visual response. Gate: около `16.7 ms` interval при сохранении orientation/mirror/crop и `cameraDropped=0`.
3. После display-cadence gate исследовать сообщение iOS-разработчика об ARKit + «точке на губах»: получить точный код/coordinate ownership и реализовать Android local 3D lip anchor (центр, оси, normal), а не одиночную 2D-точку. В shadow A/B сравнить MediaPipe facial transformation matrix и ARCore Augmented Faces pose/mesh при одинаковом renderer/predictor; ARCore не добавлять до license review.
4. Записать face-visible yaw и pitch, затем stop/dropout/weak-light/thermal и сопоставить matrix/lip-anchor continuity с 22-anchor pose/gyro по sensor/actual-display timestamps; абсолютный Euler zero не использовать.
5. После полного FF1/FF2 numerical + visual gate перейти к FF3 model-independent `FaceObservation` / `FullFaceRenderState`.
6. Matrix или ARCore не подключать в production renderer до этого gate; V6.3 strong phone-motion visual acceptance остаётся отдельной задачей.
