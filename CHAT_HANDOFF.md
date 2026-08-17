# ARMakeup — компактный контекст для нового чата

Актуально на 2026-08-17. Полный источник истины — `PROJECT_CONTEXT.md`; правила репозитория — `AGENTS.md`.

## Цель и приоритет

Native Android-приложение виртуальной примерки макияжа через фронтальную камеру. Главный приоритет — максимально реалистичный результат и стабильный трекинг в реальном времени. Скорость разработки и простота реализации вторичны. Нельзя заменять GPU/ML-пайплайн простым 2D alpha overlay.

## Репозиторий и состояние

- Путь: `C:\Users\User\AndroidStudioProjects\ARMakeup`.
- Ветка: `master`; стабильный V6.2 baseline зафиксирован commit `cc82370` и annotated tag `tracking-v6.2-stable-2026-08-17`. Улучшенный fast-motion вариант принят пользователем как новый rollback-checkpoint и фиксируется отдельным commit/tag `tracking-v6.2-fast-motion-2026-08-17`; старый baseline остаётся доступен для отката.
- Основные коммиты: `cc82370 [V6.2]`, `cd1a560 [UpdateContext]`, `8d48b46 [V6]`, `4f8039b [V5]`, `3d3572e [V4]`, `395d3f3 [V3]`.
- Kotlin, XML/View UI, один модуль `:app`; `minSdk 24`, `targetSdk/compileSdk 37`.
- Последняя полная проверка: 92 unit-теста, 0 failures/errors, lint и debug APK успешно; native код собирается для `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`.

## Текущий pipeline

- CameraX 1.6.1: Preview и `ImageAnalysis` с общим ViewPort, latest-only, analysis 640×480.
- MediaPipe Face Landmarker 1.0.0: 478 landmarks, `LIVE_STREAM`, GPU delegate с CPU fallback. Он остаётся anchor-источником raw landmarks; менять ML-модель пока не решено.
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

Fast-motion вариант визуально принят как улучшение и должен быть сохранён отдельным commit/tag. Следующий обнаруженный дефект состоит из остаточного общего geometric lag и более сильного воспринимаемого lag продуктовых finish. Все finish используют одну mesh/predictor и один compiled shader, но `MATTE`/`SATIN`/`GLOSS` семплируют camera luminance, микротекстуру и блики из актуального camera frame внутри mesh более старого ML timestamp; плоский `TRACKING_TEST` это скрывает. Следующий slice должен добавить finish/GPU timing telemetry и motion-aware temporal coherence camera-conditioned material, не меняя принятую predictor geometry.

1. На холодном устройстве записать матрицу: неподвижное лицо, медленное/быстрое движение, резкая остановка, разговор, улыбка, движение телефона, dropout, 15/20/30 ML FPS и thermal throttling.
2. Проверить, что V6.2 metadata на устройстве не `unknown`, и сопоставить jitter с exposure/ISO/gradient/capture interval/pose quality/thermal.
3. На одних `.arv6` replay сравнить V6.0 baseline, V6.1 и V6.2 robust candidate.
4. Если запись подтвердит отдельный вклад движения телефона, добавить timestamped gyro/camera-motion fusion и снова сравнить на тех же сценариях.
5. Vulkan flow включать в видимый путь только после записанного A/B и численно подтверждённого улучшения относительно обновлённого predictor.

Acceptance V6: нет заметного jitter на неподвижном лице, отставания при движении и скачка после остановки; нет regressions orientation/mirror/lip alignment; pipeline остаётся latest-only и укладывается в GPU compositor budget 6–8 ms на целевом устройстве.

## Состояние материала V5

V5 реализует reconstructed lip normals, camera-conditioned lighting и runtime-профили matte/satin/gloss в linear working space. Device/runtime acceptance на Samsung SM-G990B / Adreno 660 пройдена: camera drops и crashes не обнаружены, GPU p95 около 6–7 ms в нормальном прогретом окне.

Визуальная material acceptance не пройдена: профили различаются слишком слабо, gloss-блик неубедителен, пигмент местами плоский, внешний контур слишком жёсткий. Эти задачи отложены до V6.

Для визуальной проверки V6 tracker добавлен временный четвёртый режим `TRACKING_TEST` / кнопка `Трек`, видимая только в debug-сборке. Это намеренно плоский hot-magenta `#FF00D4`: coverage в shader умножается на `4` и saturate/clamp до `1`, luminance-preserving подстройка выключена. Он меняет только материал и не меняет lip mesh, predictor, camera/landmark transforms или нулевые границы у кожи/рта. Три продуктовых режима не изменены. После приёмки V6 tracker режим нужно удалить. Debug APK проверен на SM-G990B: материал компилируется и плотная magenta-маска отображается с правильной ориентацией.

## Ограничения и важные решения

- Не добавлять новую ML-модель или датасет без проверки лицензии и коммерческой пригодности. Текущий официальный `face_landmarker.task` и его компоненты задокументированы как Apache 2.0 в `app/src/main/assets/MODEL_LICENSES.md`.
- Не подбирать коэффициенты predictor только по краткому live-наблюдению — сначала telemetry/replay и воспроизводимые метрики.
- Не включать V4 flow в видимый путь простым ослаблением thresholds.
- Не ломать единые camera/landmark transforms и компенсацию обеих display-осей для API 29+ acquired HardwareBuffer path.
- После архитектурных решений, новых измерений или изменений performance budget обновлять `PROJECT_CONTEXT.md`.

## Ключевые файлы

- `PROJECT_CONTEXT.md` — полный контекст и решения.
- `app/src/main/java/com/example/armakeup/tracking/LandmarkMotionPredictor.kt` — текущий baseline predictor.
- `app/src/main/java/com/example/armakeup/tracking/LandmarkRenderFrame.kt` — render-time prediction и continuity correction.
- `app/src/main/java/com/example/armakeup/tracking/TemporalLandmarkRefiner.kt` — V4 flow gate, сейчас shadow-only.
- `app/src/main/java/com/example/armakeup/tracking/FaceLandmarkerTracker.kt` — MediaPipe и camera timestamp path.
- `app/src/main/java/com/example/armakeup/tracking/TrackingTelemetry*.kt` — V6 recorder/codec/analyzer и global/local decomposition.
- `app/src/main/java/com/example/armakeup/render/FilamentMakeupRenderer.kt` — видимая lip mesh, camera bridge и flow integration.
- `app/src/main/java/com/example/armakeup/makeup/LipstickMaterialProfile.kt` — продуктовые render-профили и временный `TRACKING_TEST`.
- `app/src/main/java/com/example/armakeup/render/FilamentMaterialFactory.kt` — lipstick shader, включая диагностические coverage/luminance uniforms.
- `app/src/test/java/com/example/armakeup/tracking/` — tracking regression tests.

Начни новый чат с изучения `AGENTS.md`, `PROJECT_CONTEXT.md` и перечисленных tracking/render-файлов. Baseline `cc82370` / `tracking-v6.2-stable-2026-08-17` визуально стабилен; fast-motion `45→85 ms` принят как улучшенный checkpoint `tracking-v6.2-fast-motion-2026-08-17`, хотя небольшой общий lag сохраняется. Следующая задача — измерить finish/GPU timing и устранить temporal mismatch camera-conditioned материала без изменения принятой predictor geometry. Не подбирать коэффициенты по краткому live-наблюдению без regression/replay.
