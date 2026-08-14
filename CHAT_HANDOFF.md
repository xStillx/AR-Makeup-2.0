# ARMakeup — компактный контекст для нового чата

Актуально на 2026-08-14. Полный источник истины — `PROJECT_CONTEXT.md`; правила репозитория — `AGENTS.md`.

## Цель и приоритет

Native Android-приложение виртуальной примерки макияжа через фронтальную камеру. Главный приоритет — максимально реалистичный результат и стабильный трекинг в реальном времени. Скорость разработки и простота реализации вторичны. Нельзя заменять GPU/ML-пайплайн простым 2D alpha overlay.

## Репозиторий и состояние

- Путь: `C:\Users\User\AndroidStudioProjects\ARMakeup`.
- Ветка: `master`, HEAD `e58dfe5` (`[UpdateContext]`). Текущие V6.0 edits ещё не закоммичены; сам `CHAT_HANDOFF.md` также untracked.
- Основные коммиты: `4f8039b [V5]`, `3d3572e [V4]`, `395d3f3 [V3]`.
- Kotlin, XML/View UI, один модуль `:app`; `minSdk 24`, `targetSdk/compileSdk 37`.
- Последняя полная проверка: 72 unit-теста, 0 failures/errors, lint и debug APK успешно; native код собирается для `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`.

## Текущий pipeline

- CameraX 1.6.1: Preview и `ImageAnalysis` с общим ViewPort, latest-only, analysis 640×480.
- MediaPipe Face Landmarker 1.0.0: 478 landmarks, `LIVE_STREAM`, GPU delegate с CPU fallback. Он остаётся anchor-источником raw landmarks; менять ML-модель пока не решено.
- Видимый compositor: Filament 1.74.0 / OpenGL bridge. Camera texture и lip mesh находятся в одной GPU scene.
- Native Vulkan V3 напрямую импортирует camera `AHardwareBuffer`, использует YCbCr sampling и fences без CPU-копии. V4 считает luma pyramid + pyramidal LK optical flow + similarity fit, но только в shadow/telemetry-режиме.
- Ориентация, front-camera mirror и lip/camera alignment на SM-G990B исправлены и покрыты тестами. Не менять display/camera transforms без отдельной regression-проверки.

## Состояние трекера

- Текущий `LandmarkMotionPredictor` — только сравнительный baseline, не production-ready. Пользователь всё ещё видит остаточный jitter; также нужно проверить lag, overshoot после остановки и поведение при изменении ML FPS.
- V4 optical flow раньше применялся к видимой mesh и создавал сильное дрожание: accepted/rejected fits чередовались почти покадрово, переключая координаты между flow и predictor.
- Это исправлено: `TemporalLandmarkRefiner.visibleApplicationEnabled` по умолчанию `false`, в логах должно быть `temporalFlowVisible=false`. Flow продолжает считаться для telemetry, но не имеет права двигать видимую mesh до измеренного превосходства.
- Тег `tracking-stable-2026-08-11` — историческая точка сравнения, а не доказательство текущей production-стабильности.

## Текущий обязательный этап — V6

V6.0 measurement foundation уже реализован; новый production predictor ещё нет.

- Явно включаемый только в debug `.arv6` recorder пишет raw 478 landmarks, predictor base/velocities, stable-anchor global pose, head-local lip deformation, capture/delivery timestamps и реально загруженный lip contour на каждом render-vsync. Camera pixels не записываются; queue bounded/non-blocking, есть `droppedEventCount`.
- Есть versioned codec/replay и analyzer для stationary RMS/peak, lag, stop overshoot и reacquisition. Stationary берётся из автоматически выбранного примерно двухсекундного спокойного окна; displayed jitter также считается в viewport pixels. FaceLandmarker 1.0.0 не даёт единый калиброванный confidence, поэтому поле честно хранит `NaN`.
- Synthetic regression покрывает binary round-trip, similarity-invariant local geometry, выбор stationary window, известный lag 66 ms, stop overshoot и reacquisition.
- Два device proof на SM-G990B дали `dropped=0` и успешный decode. Последний 5 s warm-up + 15 s run: 437 ML / 900 render events, median ML FPS 29,78, latency median/p95 111/125 ms. Старый predictor в выбранном окне не уменьшил разброс: raw/filtered/displayed RMS 0,005868/0,006324/0,006348, displayed 11,86 px RMS. Устройство было thermal status 2, поэтому это functional signal, не финальный threshold.

Дальше нужно переработать predictor и production temporal tracking до дальнейшего material/semantic этапа.

1. На холодном устройстве записать матрицу: неподвижное лицо, медленное/быстрое движение, резкая остановка, разговор, улыбка, движение телефона, dropout, 15/20/30 ML FPS и thermal throttling.
2. На одних `.arv6` replay отделить global pose от local deformation и сравнить baseline с новым алгоритмом.
3. Ввести явные режимы покоя, движения, резкой остановки и dropout с hysteresis. В покое prediction/velocity должны быстро затухать и не создавать drift; при движении задержка должна оставаться минимальной.
4. Vulkan flow включать в видимый путь только после записанного A/B и численно подтверждённого улучшения относительно обновлённого predictor.

Acceptance V6: нет заметного jitter на неподвижном лице, отставания при движении и скачка после остановки; нет regressions orientation/mirror/lip alignment; pipeline остаётся latest-only и укладывается в GPU compositor budget 6–8 ms на целевом устройстве.

## Состояние материала V5

V5 реализует reconstructed lip normals, camera-conditioned lighting и runtime-профили matte/satin/gloss в linear working space. Device/runtime acceptance на Samsung SM-G990B / Adreno 660 пройдена: camera drops и crashes не обнаружены, GPU p95 около 6–7 ms в нормальном прогретом окне.

Визуальная material acceptance не пройдена: профили различаются слишком слабо, gloss-блик неубедителен, пигмент местами плоский, внешний контур слишком жёсткий. Эти задачи отложены до V6.

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
- `app/src/test/java/com/example/armakeup/tracking/` — tracking regression tests.

Начни новый чат с изучения `AGENTS.md`, `PROJECT_CONTEXT.md` и перечисленных tracking-файлов. Первая задача: продолжить V6 с холодной scenario matrix и offline A/B нового stateful global/local predictor; не настраивать коэффициенты по краткому live-наблюдению.
