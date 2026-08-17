# ARMakeup — компактный контекст для нового чата

Актуально на 2026-08-17. Полный источник истины — `PROJECT_CONTEXT.md`; правила репозитория — `AGENTS.md`.

## Цель и приоритет

Native Android-приложение виртуальной примерки макияжа через фронтальную камеру. Главный приоритет — максимально реалистичный результат и стабильный трекинг в реальном времени. Скорость разработки и простота реализации вторичны. Нельзя заменять GPU/ML-пайплайн простым 2D alpha overlay.

Целевой продукт включает помаду/контур губ, румяна, тени и подводку. Поэтому следующий tracking design обязан быть full-face: один timestamped 3D pose/canonical face state, единая temporal fusion и согласованные visibility/occlusion для губ, щёк и обоих глаз. Нельзя добавлять независимый tracker/filter для каждого продукта.

Принятый детальный порядок дальнейшей разработки находится в `FULL_FACE_ROADMAP.md`. Этапы: `FF0` сохранить V6.3 experimental checkpoint; `FF1` разложить end-to-end latency; `FF2` записывать MediaPipe facial transformation matrix в shadow-режиме; `FF3` ввести `FaceTrackingBackend`/`FaceObservation`/`FullFaceRenderState`; `FF4` построить единый visual-inertial 3D tracker с late reprojection; `FF5` перейти к видимому canonical 3D face renderer; `FF6A` собрать бесплатный baseline без обучения (3D/product masks/Vulkan refinement и optional Apache Selfie Multiclass только для broad skin/hair/background); `FF6B` обучать собственный beauty parser в бесплатном cloud runtime только если FF6A недостаточно; `FF7` реализовать продукты поверх общего state; `FF8` только по результатам benchmark решить вопрос собственной landmark/mesh model.

## Репозиторий и состояние

- Путь: `C:\Users\User\AndroidStudioProjects\ARMakeup`.
- Ветка: `master`; стабильный V6.2 baseline — commit `cc82370` / tag `tracking-v6.2-stable-2026-08-17`; принятый fast-motion вариант — commit `d4636ac` / tag `tracking-v6.2-fast-motion-2026-08-17`; material-temporal candidate — commit `ab8796a` / tag `material-temporal-v1-candidate-2026-08-17` (runtime принят, visual acceptance ожидается). Текущий незакоммиченный diff после `ab8796a` — V6.3 timestamped gyroscope camera-motion correction с coverage-aware timestamp, `.arv6` v5, тесты и контекст; predictor geometry не меняется, добавлен только честный signal реально применённой доли global prediction.
- Основные коммиты: `cc82370 [V6.2]`, `cd1a560 [UpdateContext]`, `8d48b46 [V6]`, `4f8039b [V5]`, `3d3572e [V4]`, `395d3f3 [V3]`.
- Kotlin, XML/View UI, один модуль `:app`; `minSdk 24`, `targetSdk/compileSdk 37`.
- Последняя полная проверка: 111 unit-тестов, 0 failures/errors, lint и debug APK успешно; native код собирается для `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`.

## Текущий pipeline

- CameraX 1.6.1: Preview и `ImageAnalysis` с общим ViewPort, latest-only, analysis 640×480.
- MediaPipe Face Landmarker 1.0.0: 478 landmarks, `LIVE_STREAM`, GPU delegate с CPU fallback. Он остаётся текущим заменяемым anchor-backend; следующий архитектурный слой должен скрыть его за `FaceTrackingBackend`/`FaceObservation`, чтобы renderer и temporal fusion не зависели от MediaPipe-specific типов.
- Видимый compositor: Filament 1.74.0 / OpenGL bridge. Camera texture и lip mesh находятся в одной GPU scene.
- Native Vulkan V3 напрямую импортирует camera `AHardwareBuffer`, использует YCbCr sampling и fences без CPU-копии. V4 luma pyramid + pyramidal LK optical flow + similarity fit остаётся невидимым и в V6.2 вообще не запускается вне активной telemetry-записи.
- Ориентация, front-camera mirror и lip/camera alignment на SM-G990B исправлены и покрыты тестами. Не менять display/camera transforms без отдельной regression-проверки.

## Состояние трекера

- Текущий `LandmarkMotionPredictor` — принятый fast-motion вариант поверх V6.2 baseline. Все stable states/robust fit/quality response сохранены. Только при быстром согласованном high-quality motion alignment horizon плавно растёт `45→85 ms`; первый impulse, stop/reversal и poor/unknown fit остаются на `45 ms`. Пользователь подтвердил улучшение без возврата прежних улётов, но небольшой sharp-motion lag ещё виден. Точки отката: `tracking-v6.2-fast-motion-2026-08-17` и более консервативный `cc82370` / `tracking-v6.2-stable-2026-08-17`.
- V4 optical flow раньше применялся к видимой mesh и создавал сильное дрожание: accepted/rejected fits чередовались почти покадрово, переключая координаты между flow и predictor.
- Это исправлено: `TemporalLandmarkRefiner.visibleApplicationEnabled` по умолчанию `false`, в логах должно быть `temporalFlowVisible=false`. Flow не имеет права двигать видимую mesh, а его luma/flow/fit compute выполняется только во время диагностической записи.
- Тег `tracking-stable-2026-08-11` — историческая точка сравнения, а не доказательство текущей production-стабильности.

## Текущий обязательный этап — V6

V6.0 measurement foundation, V6.1 predictor и первый V6.2 robust/quality-aware slice реализованы; production acceptance ещё нет.

- Весь V6.0 slice вместе с `PROJECT_CONTEXT.md` и этим handoff зафиксирован remote-checkpoint `8d48b46 [V6]`. Использовать его как baseline для replay/A/B следующего predictor.

- Явно включаемый только в debug `.arv6` recorder пишет raw 478 landmarks, predictor base/velocities, stable-anchor global pose, head-local lip deformation, capture/delivery timestamps и реально загруженный lip contour на каждом render-vsync. Camera pixels не записываются; queue bounded/non-blocking, есть `droppedEventCount`.
- Есть versioned codec/replay и analyzer для stationary RMS/peak, lag, stop overshoot и reacquisition. Stationary берётся из автоматически выбранного примерно двухсекундного спокойного окна; displayed jitter также считается в viewport pixels. FaceLandmarker 1.0.0 не даёт единый калиброванный confidence, поэтому поле честно хранит `NaN`.
- Synthetic regression покрывает binary round-trip, similarity-invariant local geometry, выбор stationary window, известный lag 66 ms, stop overshoot и reacquisition.
- Два device proof на SM-G990B дали `dropped=0` и успешный decode. Последний 5 s warm-up + 15 s run: 437 ML / 900 render events, median ML FPS 29,78, latency median/p95 111/125 ms. Старый predictor в выбранном окне не уменьшил разброс: raw/filtered/displayed RMS 0,005868/0,006324/0,006348, displayed 11,86 px RMS. Устройство было thermal status 2, поэтому это functional signal, не финальный threshold.
- Пользовательский сценарий «неподвижная голова + резкий рывок телефона» локализовал overshoot baseline: первый motion result почти сразу включал полную velocity; camera rotation/scale попадали в local velocities; 45 ms capture prediction суммировались с 42 ms render extrapolation; короткий dropout сохранял trajectory при reacquisition jump ниже старого порога 0,25.
- V6.1 начинает с candidate gain `0.2`, подтверждает движение по трём согласованным results, использует render extrapolation `20 ms`, discontinuity reset после любого missing-face result и continuous jump threshold `0.12`. После обнаруженного sharp-motion jitter discrete переход `0.2→1.0` заменён на time-based confidence ramp: attack `8.0/s`, release `4.0/s`; position response использует тот же плавный gain. Один шумный direction sample больше не сбрасывает `MOVING`; немедленный reset сохранён для stop и истинного разворота с cosine `<= -0.25`. Camera transforms и shadow-only Vulkan flow не менялись.
- Две 20 s device-записи с `dropped=0`: до reacquisition reset `stopOvershoot=0.116007`, `reacquisitionJump=0.180085`; после reset `stopOvershoot=0.008268`, `reacquisitionJump=n/a`. Снижение около 14× — functional signal, не контролируемое A/B; вторая stationary-window попала в движение и не используется для jitter acceptance.
- Пользователь подтвердил, что forward overshoot исчез, но заметил дрожание во время резких движений. До correction два regression-теста воспроизвели ступень prediction gain и collapse на одном direction outlier; после correction они проходят вместе с отдельным тестом rigid rotation полной 478-точечной geometry. Device run `sharp_motion_v61_smooth_gain`: 497 measurement / 1106 render, `dropped=0`, ML FPS median `28.31`, latency median/p95 `116/140 ms`, lag `33 ms`, `stopOvershoot=0.005836`, reacquisition отсутствует. Автоматическое stationary-window этой динамической записи не является jitter acceptance; нужен повторный визуальный ответ пользователя.
- Пользователь предположил зависимость оставшегося подёргивания от освещения и нагрева. V6.2 regression доказал ещё одну конкретную причину: выброс одного eye-anchor давал ложное вертикальное lip prediction около `0.0091`. Новый estimator использует 22 жёстких anchors, weighted Procrustes + 3 Huber IRLS pass и отдаёт residual/inlier/quality; prediction становится консервативнее плавно, без смены координатного источника.
- `.arv6` codec version 2 обратно читает version 1 и добавляет capture interval, sparse luma/contrast/gradient, Camera2 exposure/ISO/frame duration/rolling-shutter/AE, pose-fit quality, thermal status и battery temperature. Analyzer публикует median/p95. Capture callback и sparse analysis работают только при debug recording.
- Первый V6.2 локальный gate: 90 тестов, lint, APK и все четыре ABI успешны. APK установлен на SM-G990B. Functional run `phone_motion_v62_robust`: 556 ML / 1215 render, `dropped=0`, ML FPS `28.96`, latency median/p95 `106/131 ms`, capture interval `33/66 ms`, exposure `8.31 ms`, ISO `359`, rolling shutter `32.44 ms`, pose quality/residual/inliers `0.766/0.0217/0.909`, thermal max `0`, battery `35.7 °C`, stop overshoot `0.013325`. `.arv6` v2 автоматически декодирован; это proof формата/runtime, а не controlled A/B или visual acceptance.
- Пользовательская проверка после этого прогона: «сейчас всё хорошо», jitter не отмечен; исключение — tracker отстаёт при резких движениях. Текущее состояние нужно сохранить commit/tag до fast-motion коррекции. Ускорять разрешено только высококачественное согласованное движение, не ослабляя stationary/stop/dropout/outlier safeguards.
- Baseline сохранён commit `cc82370` и тегом `tracking-v6.2-stable-2026-08-17`. Regression до исправления измерил synthetic lag `0.0526 normalized` при motion `0.8/s` и latency `100 ms`: жёсткий prediction cap `45 ms` не компенсировал device latency `106/131 ms`.
- Fast-motion candidate плавно расширяет cap максимум до `85 ms` только по speed + prediction confidence + pose quality; poor fit и stop остаются на `45 ms`. Новый test ограничивает residual lag `<=0.030`, horizon step `<=15 ms` и запрещает long horizon при corrupted anchors. Все 92 теста и полный gate проходят.
- Candidate установлен на SM-G990B. Run `sharp_motion_v62_latency85`: 570 ML / 1210 render, `dropped=0`, ML FPS `29.62`, latency `103/128 ms`, pose quality `0.912`, thermal `0`, analyzer lag `0 ms`, stop overshoot `0.006537`, displayed stationary-window jitter `7.85 px RMS`. Это functional signal, не controlled A/B; нужен пользовательский визуальный ответ.

Fast-motion вариант визуально принят и сохранён. Следующий обнаруженный дефект состоит из остаточного общего geometric lag и более сильного воспринимаемого lag продуктовых finish. Все finish используют одну mesh/predictor и один compiled shader, но `MATTE`/`SATIN`/`GLOSS` семплируют camera luminance, микротекстуру и блики из актуального camera frame внутри mesh более старого ML timestamp; плоский `TRACKING_TEST` это скрывает.

Material candidate `LipstickMaterialTemporalController` измеряет скорость фактически загруженного lip contour и timestamp mismatch между camera buffer и predicted mesh. Он управляет `cameraDetailCoherence` только для camera-derived high-frequency деталей; pigment, coverage, mesh и predictor неизменны. `.arv6` v3 записывает finish/coherence/timing. Этот slice сохранён commit `ab8796a` и candidate-tag до начала V6.3.

Device gate material выполнен на холодном SM-G990B. `material_satin_v3_sharp_motion`: 599 ML / 1198 render, dropped 0, ML FPS 30.31, latency 102/116 ms, frame CPU 2.75/5.55 ms. `material_finish_ab_v3`: 697/1441, dropped 0; per-finish CPU median/p95 GLOSS 3.05/6.30, MATTE 3.11/6.56, SATIN 3.12/6.62, TRACK 2.99/6.81 ms. `gfxinfo` GPU p95 6 ms, p99 7 ms, thermal 0. Осталась пользовательская visual acceptance фактуры.

V6.3 candidate использует `TYPE_GYROSCOPE_UNCALIBRATED` с hardware-bias removal и samples каждые 5 ms. Он интегрирует только непрерывный интервал между effective mesh camera timestamp и timestamp реально показанного camera buffer. Effective timestamp равен `landmarkSensorTimestamp + predictionSeconds × globalPredictionCoverage`, где coverage отражает реально применённые global velocity gain, quality response и согласованность filtered/measured velocity; stationary даёт `0`, candidate — только подтверждённую долю. Это исправляет пропуск 1–2 кадров gyro motion на первом рывке без изменения predictor geometry и без эмпирического усиления gyro. Camera2 focal length/physical sensor size задают projection scale. После существующего rotation/mirror transform ко всему lip contour применяется одна bounded global pitch/yaw translation + roll; local deformation, topology, camera UV и V4 flow не меняются. При отсутствии camera timestamp/calibration/sensor coverage fallback равен `NONE`. Debug A/B: intent-extra `com.example.armakeup.extra.DISABLE_GYROSCOPE_CORRECTION=true`.

`.arv6` v5 обратно читает v1–v4, пишет gyro applied/interval/rotation/translation/roll плюс camera-motion prediction seconds/coverage; analyzer публикует median новых signal. Локальный gate: 111 tests, lint, APK, четыре ABI. До coverage-fix SM-G990B показал: LSM6DSO gyro 200 Hz, raw normalized camera focal `0.74798/0.99731`; enabled `gyro_v63_phone_motion` 441 ML / 901 render, dropped 0, FPS 29.99, latency 105/124 ms, frame CPU 3.13/7.04 ms, rendered 1.0, gyro applied 94.1%, interval p95 98 ms, rotation p95 6.95°, translation p95 0.0924, thermal 0. Disabled control: 444/901, dropped 0, FPS 30.21, latency 102/118 ms, CPU 2.81/6.08 ms, gyro 0. User video `18.93 s`, `884×1920`, ~46.1 FPS подтвердило: 0–2 s покой стабилен, 2–10 s head motion имеет остаточный predictor lag, после 10 s phone-only jerk даёт промах на 1–3 кадра. Coverage-fix уже установлен, но ещё не принят визуально; нужен повтор того же сценария с сильными phone-only рывками.

Coverage-fix установлен на SM-G990B и запущен с gyro enabled / `TRACKING_TEST`. Startup/runtime без ошибок. Первый v5 functional run `gyro_v63_coverage_same_scenario`: 585 ML / 1200 render, dropped 0, FPS 29.93, latency 107/127 ms, frame CPU 3.12/6.35 ms, rendered 1.0, thermal 0. Он почти не содержит сильного движения телефона: gyro rotation p95 0.15°, translation p95 0.00199, поэтому подтверждает runtime/codec, но не visual acceptance. Файл лежит в `app/build/tracking-telemetry`; после run батарея около 35.9 °C. Нужна отдельная запись с явно сильными phone-only рывками либо пользовательский визуальный ответ.

FF0 завершён: V6.3 сохранён commit `dd095f7` и experimental-тегом `tracking-v6.3-gyro-experimental-2026-08-17` после полного unit/lint/assemble gate. Поверх checkpoint реализован FF1/FF2 shadow slice: `.arv6` v6 пишет tracker-side latency stages, RGBA/quality/result-processing durations и MediaPipe 4×4 facial transformation matrix. Matrix включена только при debug recording и не влияет на predictor или renderer. Codec читает v1–v6. Device stationary/head/phone/roll runs на SM-G990B: во всех валидных файлах `dropped=0`, transform coverage/right-handed/similarity `1.0`; stationary/head/phone latency `126/135`, `136/157`, `129/148 ms`, frame CPU p95 `5.11/6.54/5.87 ms`, thermal `0`. Camera→analysis `~78–88 ms` и inference `~31–33 ms` доминируют; callback/result CPU мал. Phone-only displayed error остаётся `27.83 px RMS`, peak `73.21 px`, значит нужен display-time alignment/fusion, а не упрощение shader.

Добавлен `CanonicalFaceTransform`: официальный MediaPipe column-major layout, right-handed metric camera space, translation indices 12..14, affine/similarity checks, baseline-centered yaw/pitch/roll и отдельный rotation→mirror→crop display contract. Matrix raw не зеркалится. Device correlations: X→pose X `0.879–0.994`, metric Y→image Y `-0.974…-0.993`; roll run `438/902`, latency `116/139 ms`, roll correlation с 22-anchor pose `0.999461`, p95 deviations yaw/pitch/roll `9.30°/4.58°/41.56°`. Актуальный gate: 124 tests, 0 failures/errors, lint/APK/четыре ABI. Валидные `.arv6` лежат в `app/build/tracking-telemetry`. Один cold-start empty и один no-face combined run отклонены; нужен отдельный face-visible yaw/pitch run.

1. Записать face-visible yaw/pitch, stop/dropout/weak-light/thermal matrix runs; cold-start и no-face файлы не включать в acceptance.
2. Добавить FF1 geometry-upload и camera-presentation/vsync timestamps. До этого capture-to-result не является полной display latency.
3. Сравнить baseline-centered matrix continuity с 22-anchor/gyro по sensor/display timestamps и только затем принимать FF2.
4. После FF1/FF2 перейти к FF3 model-independent `FaceObservation` / `FullFaceRenderState`.
5. V6.3 strong phone-motion visual acceptance выполнить отдельно; matrix не подключать в renderer до численного и visual acceptance.

Acceptance V6: нет заметного jitter на неподвижном лице, отставания при движении и скачка после остановки; нет regressions orientation/mirror/lip alignment; pipeline остаётся latest-only и укладывается в GPU compositor budget 6–8 ms на целевом устройстве.

## Состояние материала V5

V5 реализует reconstructed lip normals, camera-conditioned lighting и runtime-профили matte/satin/gloss в linear working space. Device/runtime acceptance на Samsung SM-G990B / Adreno 660 пройдена: camera drops и crashes не обнаружены, GPU p95 около 6–7 ms в нормальном прогретом окне.

Визуальная material acceptance не пройдена: профили различаются слишком слабо, gloss-блик неубедителен, пигмент местами плоский, внешний контур слишком жёсткий. Эти задачи отложены до V6.

Для визуальной проверки V6 tracker добавлен временный четвёртый режим `TRACKING_TEST` / кнопка `Трек`, видимая только в debug-сборке. Это намеренно плоский hot-magenta `#FF00D4`: coverage в shader умножается на `4` и saturate/clamp до `1`, luminance-preserving подстройка выключена. Он меняет только материал и не меняет lip mesh, predictor, camera/landmark transforms или нулевые границы у кожи/рта. Три продуктовых режима не изменены. После приёмки V6 tracker режим нужно удалить. Debug APK проверен на SM-G990B: материал компилируется и плотная magenta-маска отображается с правильной ориентацией.

## Ограничения и важные решения

- Коммерческий запрет не относится к «ML вообще» и должен проверяться по каждому компоненту. Текущий проект использует MediaPipe, не ML Kit. MediaPipe repository и model cards BlazeFace/Face Mesh V2/Blendshape V2 указывают Apache 2.0; точный `face_landmarker.task` и SHA-256 задокументированы в `app/src/main/assets/MODEL_LICENSES.md`. Перед релизом всё равно нужны повторный audit и third-party notices.
- MediaPipe остаётся текущим backend, пока controlled benchmark не докажет, что он не выполняет требования качества. Собственная landmark model допустима только с прослеживаемыми коммерческими правами на dataset, labels, pretrained/teacher weights и итоговые weights; факт собственного обучения сам по себе этого не гарантирует.
- Для skin/lips/mouth-teeth/eyes/eyelids/brows/hair нужна отдельная semantic face parsing model. MediaPipe Image Segmenter может быть runtime, но не заменяет выбор/обучение beauty-модели. Предпочтительный путь — собственная компактная LiteRT-модель после полного license audit.
- Не подбирать коэффициенты predictor только по краткому live-наблюдению — сначала telemetry/replay и воспроизводимые метрики.
- Не включать V4 flow в видимый путь простым ослаблением thresholds.
- Не ломать единые camera/landmark transforms и компенсацию обеих display-осей для API 29+ acquired HardwareBuffer path.
- После архитектурных решений, новых измерений или изменений performance budget обновлять `PROJECT_CONTEXT.md`.

## Ключевые файлы

- `PROJECT_CONTEXT.md` — полный контекст и решения.
- `LICENSE_COMPLIANCE.md` — актуальный реестр коммерческих лицензий, model/data/asset gates и release checklist; обновлять до добавления новых компонентов.
- `app/src/main/java/com/example/armakeup/tracking/LandmarkMotionPredictor.kt` — текущий baseline predictor.
- `app/src/main/java/com/example/armakeup/tracking/LandmarkRenderFrame.kt` — render-time prediction и continuity correction.
- `app/src/main/java/com/example/armakeup/tracking/TemporalLandmarkRefiner.kt` — V4 flow gate, сейчас shadow-only.
- `app/src/main/java/com/example/armakeup/tracking/GyroscopeRotationHistory.kt`, `GyroscopeLipCompensator.kt`, `AndroidGyroscopeSource.kt` — V6.3 timestamped camera-motion channel.
- `app/src/main/java/com/example/armakeup/tracking/FaceLandmarkerTracker.kt` — MediaPipe и camera timestamp path.
- `app/src/main/java/com/example/armakeup/tracking/TrackingTelemetry*.kt` — V6 recorder/codec/analyzer и global/local decomposition.
- `app/src/main/java/com/example/armakeup/render/FilamentMakeupRenderer.kt` — видимая lip mesh, camera bridge и flow integration.
- `app/src/main/java/com/example/armakeup/makeup/LipstickMaterialProfile.kt` — продуктовые render-профили и временный `TRACKING_TEST`.
- `app/src/main/java/com/example/armakeup/render/FilamentMaterialFactory.kt` — lipstick shader, включая диагностические coverage/luminance uniforms.
- `app/src/test/java/com/example/armakeup/tracking/` — tracking regression tests.

Начни новый чат с изучения `AGENTS.md`, `PROJECT_CONTEXT.md`, `FULL_FACE_ROADMAP.md` и перечисленных tracking/render-файлов. Точки отката: baseline `cc82370`, fast-motion `d4636ac`, material candidate `ab8796a`, V6.3 experimental `dd095f7` / `tracking-v6.3-gyro-experimental-2026-08-17`. В отдельном commit поверх `dd095f7` сохранён FF1/FF2 shadow telemetry v6 + MediaPipe 4×4 transform; полный локальный gate прошёл, видимый renderer не менялся. Следующий шаг — device `.arv6` v6 benchmark и axis/layout tests; не подключать matrix к рендеру до acceptance.
