# AR Makeup — контекст и технические решения

Последнее обновление: 2026-08-17.

Этот файл — источник долгоживущего контекста проекта. Его нужно обновлять, когда меняются продуктовые требования, выбранный стек, архитектура или измеренные ограничения устройств.

## Цель продукта

Android-приложение для виртуальной примерки макияжа в реальном времени через фронтальную камеру.

Поддерживаемые эффекты:

- помада;
- карандаш и контур для губ;
- румяна;
- тени для век;
- подводка.

Главный приоритет — максимально правдоподобный результат. Скорость разработки, простота кода и количество эффектов вторичны по отношению к качеству трекинга, правильному наложению, сохранению фактуры кожи и низкой задержке.

Трекинг больше нельзя проектировать как отдельное решение только для губ. Помада остаётся первым вертикальным срезом, но production-архитектура должна выдавать один согласованный full-face state для губ, щёк и обоих глаз: общую 3D-позу, деформацию мимики, visibility/confidence, окклюзии и привязанные к тому же camera timestamp семантические области. Помада, румяна, тени и подводка обязаны потреблять этот общий state, а не иметь независимые трекеры и фильтры с разной задержкой.

## Лицензии ML-компонентов и заменяемая граница модели — решение 2026-08-17

Актуальный единый реестр лицензий, будущих model/data/asset gates и обязательного release-пакета находится в `LICENSE_COMPLIANCE.md`. Его нужно обновлять до добавления каждой новой зависимости, модели, датасета, разметки, внешнего asset или SDK. Этот раздел хранит архитектурное решение; оперативный статус компонентов не следует дублировать здесь.

Формулировка «ML нельзя использовать в коммерческом продукте» сама по себе неверна: коммерческая пригодность определяется отдельно для runtime/library, конкретных весов модели, исходных pretrained weights, обучающих данных и разметки. ML Kit и MediaPipe — разные продукты с разными условиями; текущий проект использует `com.google.mediapipe:tasks-vision:1.0.0` и официальный MediaPipe `face_landmarker.task`, а не ML Kit Face Detection.

Текущий технический лицензионный аудит не выявил запрета на коммерческое использование существующего face-landmark backend:

- исходный код MediaPipe опубликован под Apache License 2.0;
- официальные model cards трёх компонентов подключённого bundle — BlazeFace Short Range, Face Mesh V2 и Blendshape V2 — указывают Apache License 2.0;
- точный bundle и его SHA-256 уже зафиксированы в `app/src/main/assets/MODEL_LICENSES.md`;
- Apache 2.0 не содержит ограничения «только non-commercial», но при распространении нужно выполнить требования лицензии и сохранить применимые copyright/attribution/NOTICE;
- это инженерная проверка первичных источников, а не юридическое заключение: перед релизом обязателен повторный dependency/model audit и оформление third-party notices.

Поэтому MediaPipe Face Landmarker сохраняется как текущий коммерчески допустимый кандидат и anchor-backend. Он не должен быть жёстко зашит в renderer. Вводится архитектурная граница `FaceTrackingBackend` с модель-независимым результатом `FaceObservation`, содержащим как минимум camera sensor timestamp, 3D landmarks/canonical face mapping, 6DoF head pose, confidence/visibility, состояние глаз/рта и версию topology. Текущие MediaPipe-specific типы не должны распространяться в material/render API.

Production temporal fusion также становится общей для всего лица: camera-frame optical flow, IMU prior и периодические model anchors обновляют один timestamped 3D face state. Поздняя репроекция к реально показанному camera frame применяется к общей позе и canonical surface до построения масок отдельных продуктов. Это исключает ситуацию, когда помада, тени и подводка имеют разные lag/jitter.

Для реалистичных границ всё равно понадобится отдельный semantic face parsing backend с вероятностями как минимум `skin / lips / mouth-teeth / left-right eyelid / eye / brow / hair-background`. MediaPipe Image Segmenter можно использовать как inference API, но он сам по себе не предоставляет подходящую beauty parsing model. Базовый план — своя компактная LiteRT-модель, обученная только на данных с документированным правом коммерческого использования. «Обучили сами» не является достаточной лицензией: отдельно проверяются датасет, разметка, исходные weights/teacher models, synthetic generator/assets и право распространять получившиеся weights.

Собственная landmark/mesh model рассматривается как возможная замена MediaPipe, но не как автоматическое следствие коммерческого релиза. Переход оправдан только если контролируемый benchmark покажет, что MediaPipe не достигает требований по lag, jitter, окклюзиям глаз/губ или нужной topology, и одновременно подготовлен полностью прослеживаемый коммерчески пригодный training pipeline. Интерфейс backend должен позволить такую замену без переписывания Vulkan compositor, temporal fusion и makeup materials.

## Принятый план дальнейшей разработки

Детальный исполняемый roadmap зафиксирован в `FULL_FACE_ROADMAP.md`. Он задаёт этапы `FF0–FF8`: сохранение V6.3 experimental checkpoint, разложение end-to-end latency, shadow-проверку MediaPipe facial transformation matrix, модель-независимый full-face контракт, visual-inertial 3D fusion и late reprojection, видимый canonical 3D renderer, semantic parsing, продуктовые эффекты и только затем условное решение о собственной landmark/mesh model.

Ближайшее действие после сохранения текущего candidate — совместный shadow vertical slice `FF1/FF2`: расширить latency telemetry и записывать facial transformation matrix с camera timestamp, не меняя видимую lip mesh. Переход к видимому 3D path разрешён только после replay/device A/B с текущим 22-anchor estimator и gyro channel.

Этап semantic parsing разделён на `FF6A/FF6B`. `FF6A` — бесплатный baseline без собственного обучения: MediaPipe 3D geometry, canonical/product masks, Vulkan edge refinement/flow и опциональный официальный Apache 2.0 Selfie Multiclass Segmenter только для broad `face-skin/hair/background`. `FF6B` — собственная детальная модель для lips/mouth-teeth/eyelids только если FF6A не проходит visual acceptance; мощный локальный ПК не обязателен, допускается бесплатный cloud training, но права на dataset/labels/weights остаются обязательным gate.

## Текущее состояние репозитория

- Чистый Android-проект на Kotlin.
- Один модуль `:app`.
- UI: XML/View system и `AppCompatActivity`.
- `minSdk = 24`, `targetSdk = 37`, `compileSdk = 37`.
- Android Gradle Plugin 9.3.1.
- Реализованы диагностический camera/ML pipeline, Vulkan V4 temporal-compute shadow vertical slice, V5.0 camera-conditioned material foundation, V6.0 `.arv6` telemetry/replay, V6.1 stateful global/local predictor и device-проверенный V6.2 robust/quality-aware tracking baseline. Поверх baseline принят quality-gated fast-motion вариант `45→85 ms`, material-temporal coherence candidate сохранён отдельным checkpoint, а текущий V6.3 candidate добавляет timestamped gyroscope camera-motion correction только к глобальной render-геометрии. Semantic face parsing и финальная production BRDF/HDR-калибровка не добавлены.
- Roadmap перехода от текущего lip-focused candidate к model-independent full-face 3D tracking принят и вынесен в `FULL_FACE_ROADMAP.md`; реализация его этапов ещё не начата.
- Git checkpoints: V6.0 — commit `8d48b46` (`[V6]`); визуально стабильный V6.2 baseline — commit `cc82370` и tag `tracking-v6.2-stable-2026-08-17`; принятый fast-motion вариант — commit `d4636ac` и tag `tracking-v6.2-fast-motion-2026-08-17`; motion-aware material candidate — commit `ab8796a` и tag `material-temporal-v1-candidate-2026-08-17` (runtime принят, visual acceptance ещё ожидается). Текущий V6.3 gyro slice находится поверх `ab8796a` и до пользовательской visual acceptance остаётся незакоммиченным candidate.

Пока нет причин менять `minSdk = 24`: он совместим с выбранным ML-стеком и позволяет использовать современный GPU-пайплайн на Android 7+.

`compileSdk` поднят с 36.1 до 37, потому что уже выбранный в исходном шаблоне `androidx.core:core-ktx:1.19.0` требует API 37. Для нового приложения также выбран `targetSdk = 37`; поведение Android 17 должно проверяться на устройстве/эмуляторе API 37 до релиза.

## Реализовано на 2026-08-11

- CameraX 1.6.1: фронтальный `Preview` и отдельный `ImageAnalysis` в одной `SessionConfig` с общим `ViewPort` и согласованным диапазоном FPS.
- Analysis работает в `RGBA_8888`, `STRATEGY_KEEP_ONLY_LATEST` и с целевым размером 640×480; камера не блокируется очередью устаревших кадров.
- MediaPipe Tasks Vision 1.0.0 и официальный `face_landmarker.task` подключены в `LIVE_STREAM`.
- GPU delegate используется первым, при ошибке инициализации автоматически включается CPU fallback.
- В realtime-профиле включены все 478 landmarks. Необязательные 52 blendshapes и facial transformation matrix временно отключены, поскольку текущие эффекты их не потребляют; это исключает лишнюю модель из каждого кадра. Включать их нужно по требованию конкретного эффекта/quality profile.
- Добавлены runtime camera permission, проверка фронтальной камеры, FPS/latency telemetry и обработка ошибок.
- Добавлены unit-тесты преобразований координат `FILL_CENTER`, camera rotation и front-camera mirror.
- Команда `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug` успешно выполнена после добавления первого Vulkan-среза: 50 unit-тестов пройдено, Android lint сообщает `No issues found`.
- Debug APK формируется в `app/build/outputs/apk/debug/app-debug.apk` и проверяется на подключённом Samsung SM-G990B.
- Добавлен Google Filament `1.74.0`: CameraX `Preview.SurfaceProvider` передаёт camera frames непосредственно в Filament `Stream`, поэтому активный preview и помада теперь сводятся в одной GPU scene вместо двух Android View-слоёв. На API 29+ используется синхронизируемый `ACQUIRED` stream через `ImageReader`/`HardwareBuffer` и release-callback Filament; на API 24–28 остаётся copy-free `NATIVE` stream через `SurfaceTexture` без гарантии camera/render synchronization.
- CameraX `TransformationInfo` преобразуется в единую Filament UV→camera texture matrix с учётом crop rect, rotation и front-camera mirror; обратимость transform покрыта unit-тестами. CameraX/MediaPipe используют top-left image origin, а Filament UV и external texture — bottom-left origin, поэтому origin меняется на обеих границах матрицы. На API 29+ OpenGL `ACQUIRED` path дополнительно компенсирует наблюдавшуюся на SM-G990B инверсию обеих display-осей импортированного `HardwareBuffer`; API 24–28 `NATIVE SurfaceTexture` path эту компенсацию не применяет. Camera quad и динамическая lip mesh используют одинаковые Filament UV, поэтому поправка не изменяет координаты трекера и сохраняет совпадение выборки camera color внутри lipstick material с фоном.
- Landmark-контуры губ преобразуются в динамическую GPU mesh: отдельные upper/lower triangle strips, cubic subdivision и восемь поперечных coverage rings. Покрытие равно нулю у кожи и у внутренней границы рта, поэтому полость рта и зубы не входят в геометрию материала.
- Первый Filament lipstick material работает в linear RGB: camera preview переводится через `inverseTonemapSRGB`, оттенок пигмента смешивается с сохранением luminance исходных губ, а matte compression применяется только к ярким участкам. Это foundation для дальнейших normals, lighting и BRDF, а не завершённая физическая модель.
- Первая визуальная проверка Filament-композитора на SM-G990B выявила перевёрнутый camera preview при правильно ориентированной lip mesh. Нормализация top-left/bottom-left origin сама по себе не изменила наблюдаемую вертикальную инверсию, потому что смена UV mesh погасила экранную коррекцию. Отдельная вертикальная acquired-stream компенсация выровняла ориентацию, после чего визуально проявилось оставшееся горизонтальное отражение camera stream относительно landmarks: при движении головы вправо маска смещалась влево относительно изображения. Текущая компенсация отражает обе display-оси только для `ACQUIRED HardwareBuffer` и покрыта regression-тестом. Последующие V2.1/V3 device runs и контрольный screenshot подтвердили вертикальную ориентацию camera preview, правильный mirror и совпадение lip mesh с губами.

## Vulkan-native миграция — начата 2026-08-11

Принято направление: целевой production hot path будет единым Vulkan 1.1 camera/compute/render graph для API 29+. Это не только замена OpenGL backend: camera `AHardwareBuffer`, temporal refinement, semantic masks, normals/lighting и многослойный lipstick material должны находиться в одном GPU-пайплайне с явными timestamps и synchronization fences. MediaPipe Face Landmarker на первом этапе остаётся anchor detector; текущий Filament/OpenGL путь сохраняется как визуальный baseline и fallback до прохождения Vulkan acceptance.

Миграция выполняется вертикальными этапами, и каждый этап обязан оставлять запускаемую сборку:

1. **V0 — baseline зафиксирован:** текущий CameraX + MediaPipe + Filament/OpenGL compositor, unit-тесты transforms/tracking/material geometry и device telemetry. До его удаления Vulkan должен воспроизвести ориентацию, mirror, crop, цвет и lip alignment.
2. **V1 — Vulkan backend proof:** capability policy Vulkan 1.1/API 29+, явный backend selection, material packages для OpenGL и Vulkan, запуск существующего camera/lip vertical slice на SM-G990B. Этот этап проверяет драйвер, swapchain, external `HardwareBuffer` и shader variants, но ещё не считается native frame graph.
3. **V2 — native bootstrap:** подключение NDK/CMake, C++ Vulkan device/swapchain/resource lifecycle, offline SPIR-V compilation, JNI boundary и диагностический camera pass. Kotlin сохраняет Android lifecycle/permissions; native слой владеет GPU resources.
4. **V3 — единый camera frame:** прямой импорт `AHardwareBuffer`, acquire/release fences, единый `FrameState` с sensor timestamp, crop/rotation/mirror и latest-only ownership; удаление лишних camera copies из render path.
5. **V4 — temporal GPU tracking:** Face Landmarker используется как 25–30 FPS anchor, между результатами Vulkan compute выполняет pyramidal optical flow по ROI лица/губ, robust pose/deformation fit, confidence gating и репроекцию на каждый render frame. Текущий predictor остаётся baseline до измеренного превосходства нового пути.
6. **V5 — semantic/material quality:** лицензированная lip parsing model после отдельной проверки, temporal warp маски, per-pixel normals, lighting estimation и физические matte/satin/gloss materials с diffuse/specular разделением, micro-roughness, влажной внутренней кромкой и корректным linear/HDR color pipeline.
7. **V6 — predictor и production temporal tracking:** MediaPipe Face Landmarker остаётся anchor-источником raw landmarks, но текущий `LandmarkMotionPredictor` не сохраняется как production-решение. Сначала добавляются записываемая/replayable покадровая телеметрия и метрики stationary RMS/peak jitter, motion lag, stop overshoot и reacquisition jump. Затем глобальные translation/scale/rotation головы отделяются от локальной деформации губ; для покоя, движения, резкой остановки, dropout и изменения ML FPS вводятся явные состояния, hysteresis и bounded prediction. Vulkan flow остаётся shadow-сигналом и может войти в видимый путь только после измеренного улучшения. V6 считается завершённым лишь после записанного A/B и device acceptance без заметного jitter на неподвижном лице, отставания при движении и скачка после остановки.

Критерии Vulkan acceptance: отсутствие black frame/crash; полное совпадение camera и landmarks при движениях и поворотах; корректные 0/90/180/270°, mirror и crop; неокрашенные рот/зубы/кожа; отсутствие дополнительной frame queue; GPU compositor budget не более 6–8 ms при 60 FPS; отсутствие заметного jitter/lag; 10–15 минут без неприемлемого thermal throttling. До выполнения критериев старый path не удаляется.

Первичная проверка целевого SM-G990B: Android API 36, `arm64-v8a`, Adreno 660, Vulkan device API 1.1, присутствуют `VK_ANDROID_external_memory_android_hardware_buffer`, `VK_KHR_sampler_ycbcr_conversion`, timeline semaphore и timestamp support.

Статус V1: реализована чистая capability policy — целевой Vulkan запрашивается только для API 29+, 64-битного процесса, объявленного Vulkan hardware level и Vulkan API не ниже 1.1; во всех остальных случаях запрашивается OpenGL. Material factory умеет собирать packages под оба target API, и Vulkan engine/material proof на SM-G990B был успешен до подключения camera stream. После выявленной несовместимости camera stream Filament factory намеренно разрешает production compositor только на OpenGL и сообщает requested/active backend с причиной fallback. Активный `Vulkan`/`OpenGL fallback` виден в экранной telemetry. Unit-тесты, lint и debug-сборка проходят; до появления native renderer OpenGL path остаётся эталонным compositor.

Первый V1 device run на SM-G990B подтвердил `requested=VULKAN active=VULKAN`, Filament выбрал Adreno Vulkan driver и оба Vulkan material packages успешно загрузились. Однако при поступлении первых кадров через Filament `ACQUIRED HardwareBuffer` процесс получил native `SIGSEGV` в `FEngine::loop`; перед падением camera session успела перейти в active state. Попытка временно использовать `NATIVE SurfaceTexture` также отвергнута самим Filament сообщением `createStreamNative not supported in Vulkan`, после чего invalid stream handle приводил к `SIGABRT`.

Принято безопасное решение: Filament compositor теперь всегда является явным OpenGL bridge, на API 29+ продолжает использовать проверенный синхронизированный `ACQUIRED HardwareBuffer`, а запрос Vulkan отображается как `OpenGL fallback` с диагностической причиной. Native Vulkan capability/device probe остаётся активным. Это не отказ от Vulkan-архитектуры: production Vulkan включается только вместе с собственным camera importer/frame graph, где приложение владеет AHardwareBuffer и fences, а не через несовместимый Filament `Stream`. В `FilamentMakeupRenderer` добавлен fail-fast guard, запрещающий случайно вернуть crash-prone Vulkan camera path.

Повторный device run после этого решения стабилен: `requested=VULKAN active=OPENGL`, `cameraInput=AcquiredCameraInput 1440x1080`, native probe `status=ok`; процесс остаётся жив без `AndroidRuntime`/native fatal errors. Статический screenshot подтверждает правильную вертикальную ориентацию камеры, совпадение помады с губами и отсутствие цвета на зубах/полости рта. Динамический mirror/alignment при движении головы всё ещё требует визуального подтверждения. В наблюдавшемся интервале MediaPipe GPU показывал в основном 29–30 FPS с отдельными окнами около 22 FPS, latency 71–131 ms. `gfxinfo` после 1945 UI frames: 6 janky frames (0.31%), p50 8 ms, p90 9 ms, p95 10 ms, p99 13 ms; это показатель Android UI/overlay, а не замена отдельному замеру Filament/Vulkan GPU compositor.

Состояние native toolchain для V2: установлены Android SDK Command-line Tools `15859902`, CMake `3.31.6` и NDK r29 `29.0.14206865`; версии закреплены в Gradle/CMake. Начат подэтап V2.0: добавлен отдельный C++20/JNI-модуль `armakeup_vulkan`, который через `dlopen` безопасно проверяет системный Vulkan loader даже на старом OpenGL-fallback устройстве, создаёт диагностический `VkInstance`, находит physical device и сообщает device API, Android surface, graphics queue, swapchain, AHardwareBuffer external memory, sampler YCbCr, timeline semaphore и timestamp support в лог `ARMakeupVulkan`. Прямой link к `libvulkan.so` намеренно не используется, поэтому отсутствие Vulkan не мешает загрузке приложения. Native library с `-Werror` успешно собирается и входит в debug APK для `arm64-v8a`, `armeabi-v7a`, `x86` и `x86_64`.

V2.0 device proof пройден на SM-G990B: `libarmakeup_vulkan.so` загрузилась, loader сообщил Vulkan `1.4.0`, Adreno 660 — device API `1.1.128`, а Android surface, graphics queue, swapchain, AHardwareBuffer, YCbCr, timeline semaphore и timestamps доступны. V2.0 ещё не является native renderer: bootstrap создаёт и освобождает только диагностический instance; Filament/OpenGL пока владеет swapchain, camera stream и makeup scene. Следующий V2-подэтап — persistent native device/swapchain lifecycle и диагностический pass, после чего можно начинать перенос `AHardwareBuffer` ownership и fences.

Подэтап V2.1 реализован локально 2026-08-12. Добавлен независимый `NativeVulkanDiagnosticRuntime`, который создаёт и удерживает собственные `VkInstance`, `VkSurfaceKHR`, `VkDevice`, graphics/present queue, Android swapchain, image views, render pass, framebuffers, command pool/buffers и два frames-in-flight с отдельными binary semaphores/fences. Native dispatch загружается через `dlopen`/`vkGet*ProcAddr`; ELF по-прежнему не имеет жёсткой зависимости от `libvulkan.so` и зависит только от `libandroid`, `libdl`, `liblog`, `libm`, `libc`.

Для V2.1 выбран изолированный offscreen proof вместо второго видимого renderer: маленький `ImageReader` 64×64 `RGBA_8888` с CPU-read usage предоставляет настоящий Android `Surface` для Vulkan swapchain. Native render thread с частотой 10 FPS выполняет полный `acquire → clear render pass → queue submit → present`, а отдельный Kotlin `HandlerThread` работает latest-only, закрывает представленные `Image` и проверяет центральный RGBA pixel на ненулевой диагностический clear color. Этот surface не получает camera frames и не участвует в compositing, поэтому не может изменить ориентацию, crop, цвет или latency рабочего Filament/OpenGL пути.

V2.1 связан с Android lifecycle: resources создаются один раз вместе с renderer, render thread запускается в `resume`, останавливается с ожиданием fences/device idle в `pause`, а при `destroy` native resources освобождаются до закрытия `ImageReader`/`ANativeWindow`. Ошибка создания или отсутствие Vulkan не являются fatal для камеры — diagnostic runtime отключается, а OpenGL baseline продолжает работу. Screen telemetry показывает `OpenGL fallback · VK runtime`, только если persistent native runtime действительно готов.

Локальная приёмка V2.1: C++20 с `-Werror` успешно собран для `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`; все JNI entry points экспортированы; 50 unit-тестов пройдено, debug APK собран, Android lint сообщает `No issues found`.

Device acceptance V2.1 пройдена на SM-G990B. Runtime создал `RGBA8_UNORM` swapchain 64×64 из семи images на Adreno 660 и начал present без native/Android Runtime ошибок. Первый полученный `ImageReader`-кадр имел центральный pixel `RGBA 209,31,87,255`, совпадающий с диагностическим clear color, `clearVisible=true`. За первый непрерывный интервал native runtime представил 335 кадров и Kotlin consumer закрыл ровно 335; после pause/resume тот же persistent device продолжил счётчик с 335 и затем с 360, без пересоздания или накопления очереди. Корректное завершение Activity через системный Back дало `stopped frames=35 consumed=35`, затем `destroyed consumed=35`, подтверждая stop/device-idle/resource teardown.

Параллельный V2.1 runtime не нарушил рабочий pipeline: camera осталась вертикально ориентирована, статическая lip mesh совпала с губами, telemetry показала `OpenGL fallback · VK runtime`. После прогрева MediaPipe GPU сохранил примерно 29–30 FPS; стартовые окна во время инициализации обоих GPU runtime кратковременно были 23–28 FPS. `gfxinfo` при одновременной работе: 3026 UI frames, 3 janky frames (0.10%), p50 7 ms, p90 9 ms, p95 10 ms, p99 11 ms. Diagnostic pass намеренно ограничен 10 FPS и 64×64, поэтому эти цифры доказывают lifecycle/interop stability, но не производительность будущего full-resolution Vulkan compositor.

V2.1 считается завершённым. Следующий этап V3 должен заменить diagnostic surface входом камеры: импортировать `AHardwareBuffer` в `VkDeviceMemory`/`VkImage`, учитывать external format и `VkSamplerYcbcrConversion`, принимать acquire fence, выдавать release fence и связывать camera image, transform и landmarks единым immutable `FrameState` по sensor timestamp. До успешного V3 acceptance Filament/OpenGL compositor не удаляется.

V3 реализован и прошёл функциональную device acceptance 2026-08-12. Native runtime теперь создаёт `AImageReader` формата `PRIVATE` с `AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE` и отдаёт его `Surface` непосредственно CameraX. `AImageReader_acquireLatestImageAsync` сохраняет latest-only семантику; полученный camera `AHardwareBuffer` без CPU-копии импортируется в `VkDeviceMemory`/`VkImage` через `VK_ANDROID_external_memory_android_hardware_buffer`. Драйвер SM-G990B сообщает `VkFormat=UNDEFINED` и `externalFormat=506`, поэтому persistent immutable sampler создаётся с `VkSamplerYcbcrConversion` и предложенными драйвером YCbCr model/range/chroma offsets. Нативные media/Android entry points загружаются динамически, чтобы библиотека по-прежнему безопасно загружалась при `minSdk 24`, а V3 camera bridge активировался только на API 29+ с подходящим Vulkan device.

Добавлен offline SPIR-V camera pass: fullscreen triangle семплирует импортированный camera image с той же immutable 4×4 UV transform, которая содержит crop, rotation и front-camera mirror. CameraX `TransformationInfo`, raw sensor timestamp кадра и raw sensor timestamp последнего результата Face Landmarker объединяются в `VulkanCameraFrameState`; матрица копируется defensively, а `landmarkAgeNs` считается в едином camera clock без смешивания с uptime. Landmark timestamp теперь проходит весь путь `ImageProxy → FaceLandmarkerTracker → renderer`. Это подготавливает строгую temporal association для V4 compute tracking.

Синхронизация V3 явная. Если `AImageReader` возвращает acquire sync fd, он импортируется как temporary semaphore; на текущем Adreno acquire fd равен `-1`, то есть кадр уже готов. После camera sampling Vulkan экспортирует release semaphore в sync fd, неблокирующе проверяет его готовность и только затем передаёт тот же `HardwareBuffer` временному Filament/OpenGL compositor. Callback Filament возвращает native token, после чего удаляется соответствующий `AImage` и закрывается Java-reference буфера. Одновременно удерживаются два доставленных буфера, нужные Filament для замены текущего кадра; очередь приложения не растёт, maxImages ограничен четырьмя, устаревшие необработанные camera frames отбрасываются `acquireLatest`.

Device acceptance V3 на SM-G990B: camera input `1440×1080`, AHB format `34`, usage `131328`, Vulkan device Adreno 660 API `1.1.128`. Контрольный Vulkan output pixel отличался от clear color (`RGBA 170,171,165,255`), поэтому external-format sampling реально читает изображение камеры. В непрерывном прогоне счётчики прошли более 8000 imported/rendered/delivered frames при `cameraDropped=0`; release fence экспортирован для каждого кадра, released отстаёт ровно на два текущих Filament-буфера. Pause закрыл camera session, resume продолжил поток около 30 FPS без black frame, crash или накопления ресурсов. Отдельный финальный lifecycle run завершил Activity через Back после 1837 camera frames и дошёл до `destroyed` без recycled-bitmap warnings или native/runtime ошибок. Визуально камера вертикальна, mirror совпадает с landmarks, помада находится на губах. После прогрева Face Landmarker держал в основном 29–30 FPS, landmark age обычно 0–33 ms и изредка 66 ms. Отдельное 10-секундное окно `gfxinfo`: 554 UI frames, 1 janky frame (0.18%), CPU p50/p90/p95/p99 = 7/9/9/11 ms, GPU = 4/5/5/6 ms.

Локальная приёмка V3: native C++20 с `-Werror` собран для `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`; offline camera shaders компилируются NDK `glslc`; 53 unit-теста пройдены без failures/errors, Android lint сообщает `No Issues Found`, debug APK успешно собран и установлен.

V3 считается завершённым как camera-import/synchronization vertical slice. Видимый lipstick compositor пока остаётся Filament/OpenGL fallback: Vulkan уже владеет импортом, fences и контрольным camera pass, но ещё не выводит финальную lip scene в экранный swapchain. Это сохраняет проверенный visual baseline и позволяет перейти к V4 temporal compute без преждевременного удаления fallback. Перед production cutover всё ещё обязательны 10–15-минутный thermal soak, повороты display 0/90/180/270° и acceptance на нескольких GPU.

V4 temporal GPU tracking реализован 2026-08-13 как следующий Vulkan vertical slice. Тот же импортированный camera `AHardwareBuffer` в одном command buffer преобразуется из external YCbCr в persistent `R32_SFLOAT` luma pyramid `192×192` с тремя mip-уровнями. Две пирамиды работают ping-pong без покадровых allocation. По расширенному ROI губ compute shader считает pyramidal iterative Lucas–Kanade flow для сетки `8×6` (48 точек, три итерации и окно `5×5`), затем второй GPU pass выполняет двухпроходный robust weighted similarity fit. Offline `glslc` теперь компилирует четыре V4 compute shader вместе с camera pass; compute/image/buffer/host переходы имеют явные Vulkan barriers, а camera ownership по-прежнему завершается тем же release sync-fd.

Результат V4 содержит scale+rotation+translation, confidence, RMS residual, число inliers и пару raw sensor timestamps. Native и Kotlin повторно проверяют один строгий контракт: interval не больше 80 ms, минимум 12 из 48 согласованных точек, coverage-weighted confidence не ниже `0.36`, RMS не выше `0.014` display UV, scale `0.975…1.025`, модуль rotation не больше `0.05 rad`, translation не больше `0.045`. Порог confidence учитывает уже встроенный множитель `sqrt(inliers / 48)`: первоначальные `0.52` и 16 inliers почти полностью отключали качественные sparse-texture fits, поэтому после device telemetry они откалиброваны до `0.36` и 12 при сохранении геометрических ограничений.

`TemporalLandmarkRefiner` хранит только непрерывную timestamped цепочку принятых transforms, интерполирует первый неполный interval и ограничивает render reprojection 42 ms. Первоначально предполагалось, что очистка цепочки при rejected fit и возврат к `LandmarkMotionPredictor` безопасны. Последующая проверка опровергла это предположение: само чередование двух источников давало скачки даже без накопления drift. Поэтому описанный live application path сохранён только для алгоритмических тестов, а production-конфигурация возвращает `null` correction и держит flow в shadow-режиме.

Device acceptance V4 на SM-G990B/Adreno 660: camera `1440×1080`, AHB format `34`, external format `506`. В длинном foreground-интервале до pause вычислено 1626 temporal fits, принято 776 (`47.7%`); после стабилизации лица встречались серии 36–40 принятых из 60 кадров. За те же 1655 camera imports получено `cameraDropped=0`, release fence создан для каждого кадра, а released закономерно отставал на два in-flight Filament buffer. Home/resume остановил camera session и продолжил тот же runtime без black frame, crash или накопления очереди. Контрольное прогретое 12-секундное окно `gfxinfo`: 300 UI frames, 5.67% janky, CPU p50/p90/p95/p99 = 11/15/17/24 ms, GPU = 4/6/7/8 ms. GPU укладывается в верхнюю границу бюджета, но CPU p95 и jank хуже V3 baseline; необходимы thermal soak и профиль потока перед production acceptance.

Локальная приёмка V4: native C++20 с `-Werror` и все compute shaders собраны для `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`; 61 unit-тест пройден без failures/errors, Android lint завершён без ошибок, debug APK собран, установлен и запущен на устройстве. Статический кадр не выявил регрессий orientation/mirror/lip alignment, но оказался недостаточным для temporal acceptance. V4 завершён только как вычислительный shadow vertical slice; его влияние на видимую mesh отклонено, а весь tracker остаётся незавершённым.

Повторная пользовательская и device-проверка 2026-08-13 обнаружила сильный jitter видимой lip mesh даже при неподвижных лице и устройстве. Причина резкого дополнительного jitter локализована в интеграции V4 flow: renderer применял каждый принятый similarity fit вместо predictor, а после следующего отклонённого fit мгновенно возвращался к predictor. На SM-G990B GPU flow часто чередовал accepted/rejected кадры и при этом мог считать допустимыми шумовые преобразования с примерно 12–16 inliers, поворотом до нескольких градусов и смещением до нескольких процентов кадра. Такое покадровое переключение двух координатных источников создавало сильное дрожание, но его устранение не означает, что сам `LandmarkMotionPredictor` достиг production-стабильности.

Исправление возвращает V4 optical flow в shadow/telemetry-режим: luma pyramid, LK fit, confidence gate и счётчики продолжают работать на GPU, но `TemporalLandmarkRefiner` по умолчанию не изменяет видимую геометрию. Lip mesh снова всегда получает текущий baseline `LandmarkMotionPredictor`; его коэффициенты, sensor timestamps и render-only continuity correction в этом исправлении не изменены, чтобы отдельно оценить вклад flow. Отдельный unit-тест запрещает экспериментальному flow двигать видимую mesh в конфигурации по умолчанию; алгоритмические тесты flow явно включают live-режим только внутри теста. После установки исправления на SM-G990B длительная сессия сохранила `cameraDropped=0`; flow продолжил показывать нестабильность (719 accepted из 4052 computed к последнему срезу), но больше не переключает источник видимых координат. Контрольное окно `gfxinfo`: 588 кадров, 2,55% janky, CPU p50/p90/p95/p99 = 11/14/15/17 ms, GPU = 5/6/6/8 ms. Predictor и flow оба остаются не принятыми для production; финальная визуальная приёмка статических и динамических сценариев обязательна.

После исправления полный debug suite содержит 66 unit-тестов без failures/errors; lint и debug APK для всех четырёх ABI собираются успешно. В `ARMakeupRender` явно логируется `temporalFlowVisible=false`, чтобы device-сессия однозначно подтверждала shadow-конфигурацию.

Финальный контрольный запуск действительно сообщил `temporalFlowVisible=false`, сохранил правильный camera frame и `cameraDropped=0`. Однако после серии сборок, установок и screen-record тестов устройство находилось под thermal throttling: status 2, AP 62,9 °C, skin 41,8 °C и заряд 7%; Face Landmarker в этом окне восстановился только до 19–21 FPS вместо обычных 29–30. Поэтому этот горячий прогон подтверждает правильную конфигурацию и отсутствие runtime-регрессии, но не используется как финальная оценка плавности. Пользовательская проверка должна выполняться после охлаждения и зарядки устройства; оставшаяся низкочастотная ступенчатость в перегретом состоянии не должна смешиваться с исправленным покадровым jitter от flow.

V5.0 material foundation реализован 2026-08-13 без добавления новой ML-модели. Lip mesh теперь строит устойчивую выпуклую поверхность из фактических внешней/внутренней кривых каждого текущего кадра. Для каждого vertex центральными разностями вычисляется camera-facing normal; после GPU-интерполяции шейдер получает per-pixel normal, pigment coverage, координату outer→inner и положение вдоль дуги губ. Геометрия границ и нулевое coverage у кожи/рта не изменены, поэтому новый материал не расширяет область окрашивания и не затрагивает зафиксированный tracking pipeline.

V5.0 lipstick shader остаётся в linear working space и разделяет три сигнала: luminance-preserving chromatic pigment, исходную микротекстуру камеры и specular response. Четыре широких camera sample вокруг текущего fragment дают low-frequency luminance и направление градиента освещения; реконструированная normal формирует roughness-dependent lobe, а положительная высокочастотная разница исходной камеры привязывает блик к реально снятому свету. Статичная highlight texture не используется. Влажная внутренняя кромка ограничена lip-local coordinate и затухает в уголках. Это camera-conditioned оптическая аппроксимация для AR-композитинга, а не заявление о завершённой спектральной BRDF.

Добавлены параметрические профили `MATTE`, `SATIN`, `GLOSS`: roughness, specular strength, сохранение исходного highlight, микротекстуры и wet inner edge меняются независимо от пигментной геометрии. По умолчанию выбран естественный `SATIN`; нижняя панель позволяет переключать три режима в runtime для прямого визуального A/B. Цвет и проверенное core coverage верхней/нижней губы сохранены. Параметры валидируются unit-тестами, а normals проверяются на конечность, единичную длину и направление к камере.

Приёмка V5.0: 65 unit-тестов пройдено без failures/errors, Android lint и debug assemble завершены, все четыре native ABI продолжают собираться. На API 37 x86_64 emulator runtime `filamat` успешно скомпилировал новый материал, Filament создал OpenGL compositor, native Vulkan V4 создал device/camera path и пропустил более 180 camera frames при `cameraDropped=0`, без `AndroidRuntime`, `SIGSEGV` или `SIGABRT`.

Физическая проверка V5.0 на Samsung SM-G990B / Adreno 660 выполнена 2026-08-13. Camera preview имеет правильные orientation/mirror, lip mesh совпадает с губами при фронтальном и наклонном положении, полость рта остаётся неокрашенной. За длинную сессию доставлено 6720 camera frames при `cameraDropped=0`; отставание release-callback на два кадра соответствует двум разрешённым in-flight buffers. Face Landmarker после прогрева держал 29–30 FPS при наблюдавшейся latency примерно 88–119 ms. В чистом прогретом 15-секундном окне `gfxinfo`: 477 кадров, 1 janky frame (0,21%), CPU p50/p90/p95/p99 = 9/10/11/12 ms, GPU = 4/5/6/7 ms. Следовательно, device/runtime acceptance и GPU budget 6–8 ms пройдены на этом устройстве; crash, black frame и camera frame drops не обнаружены.

Визуальная material acceptance V5.0 при этом не пройдена. Runtime-переключение `MATTE`/`SATIN`/`GLOSS` стабильно, но различия профилей на реальной камере слишком слабы, у `GLOSS` нет убедительно читаемого связанного с освещением блика, пигмент местами выглядит плоским, а внешний контур — слишком жёстким и слегка угловатым, особенно в уголках и на дуге Купидона. V5.0 считается технически стабильным material slice, но не production-realistic материалом. Перед semantic refinement нужны улучшение оптического отклика и границы, контролируемое A/B при фиксированных позе/экспозиции и тёплом/холодном/боковом свете; multi-device и 10–15-минутный thermal soak также остаются обязательными.

Для V6 tracking-проверок 2026-08-17 добавлен временный четвёртый профиль `TRACKING_TEST`. Он доступен кнопкой `Трек` только в debuggable-сборке и намеренно не является продуктовым материалом: используется насыщенный hot-magenta `#FF00D4`, shader coverage умножается на `4` с clamp до `1`, а подстройка яркости пигмента под luminance камеры отключена. Поэтому центральная область губ получается максимально плотной и контрастной, чтобы малые смещения контура и jitter были легко заметны. Профиль использует ту же динамическую Filament lip mesh, те же нулевые coverage-границы у кожи и полости рта и не меняет predictor, landmarks, tessellation или camera transforms. `MATTE`/`SATIN`/`GLOSS` сохраняют прежний цвет, coverage и luminance-preserving mixing. Профиль и его UI нужно удалить после завершения визуальной приёмки V6 tracker.

Semantic refinement намеренно не включён в V5.0: конкретная коммерчески пригодная lip-parsing модель и её обучающий датасет всё ещё не выбраны и не прошли лицензионную проверку. Этот подэтап отложен до улучшения трекера; затем нужно оформить model/license decision и добавить вероятностную lip mask с temporal warp поверх текущей mesh-геометрии.

V6.0 measurement foundation реализован 2026-08-14. Это ещё не новый production predictor: видимая mesh по-прежнему использует сравнительный `LandmarkMotionPredictor`, а Vulkan flow остаётся shadow-сигналом. Цель V6.0 — перестать настраивать temporal tracking по краткому субъективному наблюдению и получить один воспроизводимый поток данных для последующего A/B.

Debug-сборка теперь по явному intent-extra записывает versioned binary `.arv6` session в app-specific external files. По умолчанию перед записью есть 5-секундный warm-up, затем 30 секунд данных; duration, warm-up и имя сценария задаются отдельно. Запись никогда не включается в release/без явного флага, не содержит camera pixels и использует bounded non-blocking queue на отдельный writer thread. При переполнении событие отбрасывается и учитывается в `droppedEventCount`, поэтому диагностическая запись не может создать новую realtime frame queue.

Каждый ML-result сохраняет все raw 478×xyz landmarks, base positions и velocities текущего predictor до render correction, capture sensor/uptime timestamp, delivery timestamp, capture→delivery latency, rolling ML FPS, face-present/dropout и predicted-only. Публичный `FaceLandmarkerResult` используемой версии не отдаёт один калиброванный face-confidence, поэтому это поле записывается как `NaN`, а не заполняется выдуманной оценкой. Дополнительно сохраняется разложение global pose/local deformation: translation берётся по жёстким eye/nose/cheek anchors, scale/rotation — по межглазному вектору, а 40 точек внешнего+внутреннего lip contour переводятся в head-local координаты. В V6.0 это было только telemetry; V6.1 predictor теперь использует то же определение stable-anchor pose для видимой mesh.

Каждый render-vsync сохраняет именно последний реально принятый `DynamicVertexUploader` контур, а не только рассчитанный кандидат: outer/inner lip points после rotation/mirror/FILL_CENTER и temporal correction, viewport, render timestamp, anchor sensor timestamp и prediction horizon. Если все upload slots заняты, запись повторяет фактически оставшуюся на GPU геометрию; отсутствие лица/скрытая mesh записываются отдельным событием. Поэтому replay различает шум raw модели, состояние predictor и то, что действительно видел пользователь.

`TrackingTelemetryCodec` немедленно перечитывает завершённый файл, после чего `TrackingTelemetryAnalyzer` считает latency percentiles, stationary RMS/peak, motion lag, stop overshoot и reacquisition jump. Stationary-метрика не берёт всю сессию: выбирается примерно двухсекундное окно с минимальным robust global pose + local lip motion; raw/filtered считаются в image-normalized domain, displayed — также в реальных viewport pixels. Lag/overshoot намеренно не публикуются для сценария с `stationary` в имени. Synthetic replay-тесты фиксируют binary round-trip, инвариантность head-local lip geometry к similarity motion, выбор спокойного окна вместо намеренного движения, известный lag 66 ms и раздельные stop-overshoot/reacquisition события.

Первый 30-секундный device-файл на SM-G990B доказал целостность формата: 730 measurement и 1586 render events, 13,68 MB, `dropped=0`, автоматический decode успешен. Его первоначальная whole-session jitter-оценка отклонена как методологически неверная, поскольку смешивала startup и реальное движение; именно этот результат привёл к stationary-window анализу. Повторный функциональный run с 5 s warm-up и 15 s recording дал 437 measurement и 900 render events, 8,17 MB, `dropped=0`, median ML FPS `29,78`, median/p95 capture→delivery `111/125 ms`. В выбранном спокойном окне старый predictor показал raw/filtered/displayed RMS `0,005868 / 0,006324 / 0,006348` normalized, displayed `11,86 px RMS` и `41,76 px peak`: в этой записи baseline не уменьшил разброс raw lip points. Однако устройство уже имело thermal status 2 / skin около 40–43 °C, поэтому прогон принимается только как functional proof и сигнал для A/B, не как финальный quality threshold. Перед изменением видимого predictor нужны холодные записи всех V6-сценариев; после последней metric-коррекции stationary window обязан иметь не менее 1,9 s.

Локальный gate V6.0 после добавления recorder/replay содержит 72 unit-теста без failures/errors; debug APK и native C++/shaders собираются для `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`, Android lint проходит. На устройстве recording не вызвал crash/black frame, camera importer продолжил сообщать `cameraDropped=0`. Следующий V6-подэтап — записать холодную матрицу `stationary / slow move / fast turn / abrupt stop / talking-smile / phone motion / dropout / ML 15-20-30 / thermal`, затем на одних `.arv6` replay сравнить baseline с predictor, который использует это stable-anchor global pose, отдельно фильтрует local deformation и имеет явные `stationary/moving/stopping/dropout` состояния с hysteresis и bounded prediction. Только численно лучший вариант допускается к device visual A/B; Vulkan flow остаётся shadow до такого же сравнения.

После добавления временного `TRACKING_TEST` полный gate 2026-08-17 содержит 74 unit-теста без failures/errors; `lintDebug` и `assembleDebug` успешны, все четыре native ABI продолжают собираться. Debug APK установлен и запущен на SM-G990B: Filament material с новыми uniform-параметрами скомпилировался, режим `Трек` отображается и даёт ожидаемое плотное magenta-покрытие без регрессии orientation/mirror. Это функциональная проверка диагностического материала, а не acceptance стабильности tracker.

V6.1 stateful predictor candidate реализован 2026-08-17 после пользовательского воспроизведения двух связанных дефектов: при резком движении маска слегка опережала лицо и возвращалась, а при неподвижной голове и рывке самого телефона начинала дрожать. Корневая причина была составной. Baseline включал почти полную global velocity уже по первому moving result, оценивал translation как centroid всех 478 точек и вычитал только его; поэтому camera rotation/scale попадали в независимые local velocities. Дополнительно capture-time prediction до 45 ms суммировался с render-only extrapolation до 42 ms, позволяя старой скорости действовать до 87 ms. Короткий dropout также мог продолжить старую trajectory, потому что observed reacquisition jump `0.180` оставался ниже прежнего reset threshold `0.25`.

Новый видимый predictor оценивает единый similarity motion по стабильным eye/nose/cheek anchors, вычитает его из local landmark deformation и предсказывает local velocity консервативно. Введены состояния `STATIONARY / CANDIDATE / MOVING`: первый резкий импульс получает только `0.2` prediction gain, состояние движения подтверждается после трёх согласованных по направлению ML-results, а stop/true-reversal обнуляет или перезапускает predictive velocity. После описанной ниже correction сам gain набирается плавно и больше не переключается ступенью на границе подтверждения. Render-only окно сокращено с `42` до `20 ms`. Любой face-missing result теперь помечает discontinuity и заставляет первый reacquired measurement инициализировать tracker без старой скорости; continuous centroid jump threshold снижен до `0.12`. Camera/landmark transforms и Vulkan flow gate не менялись.

Две последовательные 20-секундные записи на SM-G990B прошли с `dropped=0`. Первая stateful-сборка до специального reacquisition reset дала `stopOvershoot=0.116007` и `reacquisitionJump=0.180085`, что выявило недостающий reset. После исправления повторная запись дала `stopOvershoot=0.008268` и `reacquisitionJump=n/a`, то есть измеренный overshoot уменьшился примерно в 14 раз. Это сильный functional signal, но не контролируемое A/B: физические движения в двух сессиях не идентичны, а автоматически выбранное stationary-window второй записи попало в движение и не годится для jitter threshold. Candidate остаётся не production-accepted до пользовательской visual проверки и холодной одинаковой scenario matrix. Полный gate после V6.1 содержит 79 unit-тестов без failures/errors; lint/debug assemble и четыре ABI успешны, APK установлен и запущен на SM-G990B.

Следующая пользовательская проверка подтвердила, что «улёты вперёд» исчезли, но обнаружила новый jitter именно во время резких движений. Дефект воспроизведён отдельными regression-тестами до исправления: на третьем согласованном result prediction gain ступенью переключался с `0.2` на `1.0`, а единичный шумный поворот направления мог так же резко обрушить `MOVING` обратно в `CANDIDATE`. Это меняло predictive velocity до пяти раз между соседними ML-results и создавало видимую смену скорости маски, хотя stop overshoot уже был устранён.

V6.1 correction заменяет дискретный gain на непрерывный time-based ramp: attack `8.0/s`, мягкий release `4.0/s`; position-response gain использует ту же непрерывную confidence. Обычный direction outlier больше не сбрасывает `MOVING` и filtered velocity, а только плавно уменьшает confidence; немедленный reset сохранён для остановки и настоящего разворота с cosine не выше `-0.25`, а также для dropout/reacquisition. Synthetic tests покрывают отсутствие скачка gain при translation, устойчивость к одному direction outlier и тот же smooth ramp для rigid rotation всей 478-точечной face geometry. Полный gate после коррекции содержит 82 unit-теста без failures/errors; `lintDebug`, `assembleDebug` и native build четырёх ABI успешны.

Исправленная сборка установлена на SM-G990B и прошла 20-секундный functional run `sharp_motion_v61_smooth_gain`: 497 measurement / 1106 render events, `dropped=0`, median ML FPS `28.31`, median/p95 capture→delivery `116/140 ms`, lag `33 ms`, `stopOvershoot=0.005836`, `reacquisitionJump=n/a`. Эти цифры подтверждают отсутствие возврата прежнего overshoot и runtime-регрессии, но автоматически выбранное stationary-window динамической сессии не используется для jitter acceptance. Новая visual acceptance резких рывков пользователем и холодное одинаковое A/B всё ещё обязательны.

V6.2 начат 2026-08-17 после пользовательского наблюдения, что оставшееся подёргивание усиливается при резких движениях и, предположительно, зависит от освещения и температуры. До изменения predictor дефект удалось изолировать синтетически: выброс только одного eye-anchor между соседними измерениями создавал ложное вертикальное смещение lip prediction примерно `0.0091` normalized, хотя остальные жёсткие точки не двигались. V6.1 similarity pose опирался всего на восемь равновесных eye/nose/cheek anchors и не умел отличать выброс одной точки от движения всей головы.

В первом V6.2 vertical slice глобальное frame-to-frame движение оценивается по 22 жёстким forehead/nose/eye/cheek/temple anchors через weighted Procrustes similarity и три Huber IRLS-прохода. Из fit публикуются normalized RMS residual, доля inliers и агрегированная quality. Низкая quality непрерывно ограничивает global velocity, adaptive position cutoff и local/global prediction; координатный источник не переключается покадрово. Regression с повреждённым eye-anchor теперь проходит с ошибкой lip mapping не более `0.003`, как и точный translation/rotation/scale fit и invalid-geometry path.

Формат `.arv6` поднят до version 2 с обратным чтением version 1. Только во время явно запрошенной debug-записи каждый measurement дополнительно хранит реальный интервал принятых кадров, sparse 32×24 mean luma/luma standard deviation/mean gradient, сопоставленные по `SENSOR_TIMESTAMP` Camera2 exposure time/ISO/frame duration/rolling-shutter skew/AE state, pose-fit quality, Android thermal status и battery temperature. Analyzer выводит median/p95 этих величин вместе с прежними jitter/lag/overshoot метриками. Это позволит проверять корреляцию jitter с длинной выдержкой/motion blur, низким контрастом, frame pacing и throttling вместо подбора коэффициентов по ощущению.

V4 optical flow по-прежнему не влияет на видимую mesh. Дополнительно устранена его скрытая GPU-нагрузка: когда нет активной telemetry-записи и visible application выключен, Kotlin передаёт invalid ROI, а native runtime не строит luma pyramid и не запускает flow/fit и не обновляет temporal history. Sparse image analysis и Camera2 capture callback также подключаются только для диагностической записи, поэтому V6.2 telemetry не добавляет постоянную production-нагрузку. Gyroscope/camera-motion fusion намеренно оставлен следующим V6.2 подэтапом: сначала новый device replay должен показать, какая доля дефекта связана с camera motion, а какая — с качеством landmarks/экспозицией.

Локальный gate первого V6.2 slice: `90` unit-тестов, `0` failures/errors; `lintDebug`, `assembleDebug` и C++ native build для `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` успешны. APK затем установлен на SM-G990B и прошёл 20-секундный functional run `phone_motion_v62_robust`: `556` measurement / `1215` render events, `dropped=0`, median ML FPS `28.96`, latency median/p95 `106/131 ms`, capture interval median/p95 `33/66 ms`, lag `33 ms`, stop overshoot `0.013325`, reacquisition `n/a`. Новые поля реально заполнены: luma `0.495`, luma std `0.246`, gradient `0.088`, exposure median/p95 `8.31/8.31 ms`, ISO `359`, frame duration `33.33 ms`, rolling shutter `32.44 ms`, pose quality/residual/inliers `0.766/0.0217/0.909`, thermal status max `0`, battery temperature median `35.7 °C`. Запись размером `10,461,080` bytes автоматически декодирована. Это device proof формата и runtime, но не контролируемое A/B и не visual acceptance: автоматически выбранное окно динамического сценария показало displayed jitter `28.33 px RMS`, поэтому следующая работа должна разделить спокойную и резкую фазы отдельными одинаковыми записями.

Пользовательская visual-проверка первого V6.2 slice приняла текущую стабильность: маска больше не дёргается заметно в проверенном сценарии. Единственный отмеченный остаточный дефект — отставание tracker при резких движениях. Перед любым изменением fast-motion response это состояние фиксируется Git commit/tag как rollback baseline. Следующая коррекция должна ускорять только согласованное движение с высокой pose-fit quality; stationary smoothing, stop/reversal reset, dropout reset и robust outlier suppression нельзя ослаблять. Acceptance требует одновременно уменьшить визуальный lag и не вернуть прежние forward overshoot/jitter.

Стабильный вариант зафиксирован commit `cc82370` и тегом `tracking-v6.2-stable-2026-08-17` до следующей правки. Sharp-motion lag затем воспроизведён синтетически до исправления: при согласованном global motion `0.8 normalized/s`, capture→delivery `100 ms` и хорошем fit отображаемая позиция отставала от текущей примерно на `0.0526 normalized`. Причина — общий alignment prediction был жёстко ограничен `45 ms`, хотя device latency в V6.2 run имела median/p95 `106/131 ms`; response/velocity уже были корректными, но большая часть известного возраста измерения намеренно не компенсировалась.

Fast-motion candidate не повышает общий gain и не ослабляет stop safeguards. Он плавно расширяет только alignment horizon от `45` до максимум `85 ms` как произведение трёх факторов: согласованного prediction confidence, global speed (`0.25→0.65 normalized/s`) и robust pose quality (`0.55→0.75`). Первый импульс остаётся на безопасных `45 ms`; при подтверждении horizon растёт не более чем примерно на `13–15 ms` за ML-result. При `STATIONARY`, stop, reversal, invalid/poor fit или неизвестной quality horizon немедленно равен `45 ms`. Новый regression требует для указанного synthetic motion residual lag не более `0.030`, отдельно проверяет плавность horizon и запрещает его расширение при повреждённых pose anchors. Все прежние alternating-jerk, stop, reversal, dropout, gain-ramp и anchor-outlier tests проходят.

Полный локальный gate candidate содержит `92` unit-теста без failures/errors; `lintDebug`, `assembleDebug` и native build четырёх ABI успешны. APK установлен на холодный SM-G990B (`thermal status 0`). Functional run `sharp_motion_v62_latency85`: `570` measurement / `1210` render events, `dropped=0`, ML FPS median `29.62`, latency median/p95 `103/128 ms`, capture interval `33/66 ms`, pose quality/residual/inliers `0.912/0.00999/1.0`, thermal max `0`, battery `34.0 °C`, автоматический lag `0 ms`, stop overshoot `0.006537`, reacquisition `n/a`; displayed stationary-window jitter `7.85 px RMS`. Предыдущий неидентичный `phone_motion_v62_robust` run давал lag `33 ms` и stop overshoot `0.013325`, поэтому новые числа — сильный functional signal, но не controlled A/B. Пользователь подтвердил, что вариант стал лучше и не вернул прежние улёты; он принят как новый rollback-checkpoint. Остаточный sharp-motion lag сохраняется, а в `MATTE`/`SATIN`/`GLOSS` воспринимается сильнее, чем в плоском `TRACKING_TEST`.

Проверка render path показала два разных эффекта. Все четыре finish используют одну lip mesh, один predictor и один скомпилированный Filament material; переключение меняет только uniforms, поэтому finish не меняет координаты tracker напрямую. Однако продуктовые профили сохраняют camera luminance, микротекстуру и specular response, семплируя актуальную camera texture внутри mesh, построенной по более старому ML timestamp. При быстром движении это создаёт temporal material mismatch: camera-derived детали и блик визуально «плывут» внутри запаздывающей геометрии и усиливают ощущение lag. `TRACKING_TEST` скрывает эффект фиксированным непрозрачным цветом. Следующий material-temporal slice должен сначала записывать активный finish и GPU/render timing, затем плавно ограничивать высокочастотные camera-conditioned детали при быстром движении либо репроецировать их в систему координат того же predicted frame; стабильную predictor geometry при этом не менять.

Принятый fast-motion predictor сохранён отдельным commit `d4636ac` (`[V6.2] Accept fast-motion latency compensation`) и annotated tag `tracking-v6.2-fast-motion-2026-08-17`. Следующий material-temporal slice намеренно не меняет `LandmarkMotionPredictor`, lip topology, tessellation, coverage или camera/landmark transforms.

Первый motion-aware material coherence candidate вычисляет RMS-скорость уже загруженного outer+inner lip contour в долях короткой стороны viewport и сопоставляет временную позицию mesh с реально поданным camera buffer. Основной timestamp signal — абсолютная разница между sensor timestamp camera frame и `landmarkSensorTimestamp + predictionSeconds`; для старого camera path без timestamp используется bounded uptime fallback. Произведение отфильтрованной скорости и временного расхождения оценивает пространственную ошибку camera-derived деталей. В диапазоне `0.003→0.018` короткой стороны коэффициент `cameraDetailCoherence` плавно уменьшается от `1.0` до `0.18`; motion attack/release равны `18/6 s⁻¹`, detail attack/release — `16/3.5 s⁻¹`. После разрыва более `250 ms`, скрытия губ или reacquisition material-state полностью сбрасывается, чтобы не переносить старую скорость.

Новый uniform не ослабляет пигмент или геометрию. При рассогласовании shader заменяет per-pixel luminance более широким neighborhood luminance, уменьшает только camera high-frequency texture, native highlight и быстро меняющуюся часть lighting gradient; normal-based lobe, цвет, coverage и нулевые границы губ остаются. `TRACKING_TEST` всегда принудительно использует coherence `1.0`, поэтому остаётся независимым геометрическим эталоном. Это минимальный temporal-coherence слой, а не замена будущей timestamped reprojection/optical flow материала.

Формат `.arv6` поднят до version 3 с обратным чтением v1/v2. Каждый render event дополнительно хранит finish, фактически применённый camera-detail coherence, motion speed, material timestamp mismatch, CPU-время camera acquisition + geometry + Filament frame submission и результат `Renderer.beginFrame`. Analyzer публикует общие и per-finish CPU median/p95 и долю принятых Filament frames. Эта метрика помогает обнаружить GPU backpressure, но не называется GPU time: точный видимый Filament GPU duration по текущему Java API недоступен и должен проверяться `gfxinfo`/AGI либо Vulkan timestamp queries после native compositor cutover. Локальный gate candidate прошёл: `99` unit-тестов без failures/errors, `lintDebug`, `assembleDebug` и native C++ build для `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` успешны.

Device/runtime gate material candidate выполнен на SM-G990B после подключения устройства. Стартовые условия: thermal status `0`, battery temperature около `32 °C`; новый Filament material с uniform `cameraDetailCoherence` скомпилировался, процесс остался жив, camera `1440×1080` продолжила Vulkan AHB import/GL handoff без black frame/crash и с `cameraDropped=0`. Первый 20-секундный `SATIN` run `material_satin_v3_sharp_motion`: `599` measurement / `1198` render events, `dropped=0`, ML FPS median `30.31`, latency median/p95 `102/116 ms`, frame-submission CPU median/p95 `2.75/5.55 ms`, `filamentRenderedFraction=1.0`. При повторяющихся резких движениях controller реально активировался: coherence median/p05 `0.183/0.180`, motion median `0.702` короткой стороны/с, timestamp mismatch p95 `65 ms`. Автоматические tracking-метрики этой физически невоспроизводимой сессии (`lag 33 ms`, stop overshoot `0.0159`) являются только functional signal, не controlled predictor A/B.

Отдельный 24-секундный runtime A/B `material_finish_ab_v3` автоматически переключал `SATIN → MATTE → GLOSS → TRACKING_TEST` при одинаковом типе резких движений. Получено `697` measurement / `1441` render events, `dropped=0`, ML FPS median `29.86`, latency median/p95 `113/130 ms`, thermal max `0`; каждый finish имел `filamentRenderedFraction=1.0`. Frame-submission CPU median/p95 практически одинаковы: `GLOSS 3.05/6.30 ms`, `MATTE 3.11/6.56 ms`, `SATIN 3.12/6.62 ms`, `TRACKING_TEST 2.99/6.81 ms`. Следовательно, наблюдавшийся более сильный lag product finish не объясняется отдельной стоимостью профиля shader и согласуется с temporal camera-detail mismatch. Итоговый `gfxinfo`: около `0.22–0.25%` janky frames, CPU p50/p90/p95/p99 `9/11/12/13 ms`, GPU `4/5/6/7 ms`; thermal status остался `0`, battery temperature после двух прогонов около `36 °C`. Записи сохранены локально в `app/build/tracking-telemetry/tracking-v6-20260817-145300-956-material_satin_v3_sharp_motion.arv6` и `tracking-v6-20260817-145534-301-material_finish_ab_v3.arv6`. Runtime/performance gate пройден; обязательна пользовательская visual acceptance, что product finish теперь не создаёт прежний дополнительный шлейф и не получил заметное размытие/пульсацию фактуры.

Перед V6.3 material-temporal candidate зафиксирован commit `ab8796a` (`[V6.2] Add motion-aware material coherence`) и annotated tag `material-temporal-v1-candidate-2026-08-17`. Tag намеренно помечен candidate: он является чистой точкой отката с пройденным runtime/performance gate, но не утверждает пользовательскую visual acceptance материала.

V6.3 gyroscope camera-motion candidate реализован как отдельный render-time канал поверх `ab8796a`. `TYPE_GYROSCOPE_UNCALIBRATED` предпочтителен; оценённый hardware bias вычитается, samples запрашиваются без batching каждые `5 ms`. Короткая timestamped history интегрирует angular velocity трапецеидально и отвечает только если оба endpoint покрыты непрерывными samples, interval не превышает `160 ms`, а gap между samples не превышает `50 ms`. Camera2 `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` и `SENSOR_INFO_PHYSICAL_SIZE` задают реальные normalized focal lengths. Между эффективным camera-motion timestamp mesh и timestamp реально acquired camera buffer yaw/pitch переводятся в bounded global shift, roll — в rotation вокруг optical center. Поправка применяется после существующего `NormalizedImageTransform` и одинаково ко всему outer/inner contour; local deformation, predictor geometry/velocities/states, lip topology, camera UV, mirror, material coverage и V4 flow не меняются. При отсутствии gyro, calibration, camera timestamp, полной sensor history либо при активном optical-flow correction применяется точный fallback `NONE` без смены координатного источника. Debug intent `com.example.armakeup.extra.DISABLE_GYROSCOPE_CORRECTION=true` даёт A/B на одном APK.

`.arv6` поднят до version `4` с обратным чтением v1–v3. Render event сохраняет флаг применения gyro, interval, device-axis rotation, display translation и roll; analyzer публикует applied fraction и p95 interval/rotation/translation. Локальный gate содержит `107` unit-тестов без failures/errors; `lintDebug`, `assembleDebug` и native build четырёх ABI успешны. Regression покрывает timestamp interpolation, sensor gaps, bounds/fallback, 0/90° display-axis remap, front-camera mirror sign, global rigid distance preservation, physical focal calibration, codec v3 compatibility и analyzer metrics.

Device vertical slice выполнен на SM-G990B при thermal status `0`. Обнаружен `LSM6DSO Gyroscope-Uncalibrated`, `minDelay=5000 us`; front-camera calibration прочитана как raw normalized `fx=0.74798`, `fy=0.99731`. Первый холодный recorder window закончился до camera/ML startup и дал пустой 43-byte файл, поэтому отклонён. Прогретый enabled run `gyro_v63_phone_motion`: `441` measurement / `901` render, `dropped=0`, ML FPS `29.99`, latency `105/124 ms`, frame CPU `3.13/7.04 ms`, rendered fraction `1.0`, thermal max `0`; gyro применялся к `94.1%` видимых render frames, interval p95 `98 ms`, rotation p95 `6.95°`, translation p95 `0.0924`. Disabled control на том же APK: `444/901`, dropped `0`, ML FPS `30.21`, latency `102/118 ms`, frame CPU `2.81/6.08 ms`, rendered fraction `1.0`, gyro fraction `0`. Enabled/disabled displayed stationary-window RMS были `4.80 px` и `11.83 px`, но движения выполнялись вручную и не идентичны, поэтому разницу нельзя считать controlled численным доказательством качества. Файлы `tracking-v6-20260817-152445-584-gyro_v63_phone_motion.arv6` и `tracking-v6-20260817-152552-540-gyro_v63_disabled_control.arv6` сохранены локально. Crash, black frame, camera drop и GPU backpressure не обнаружены; обязательна пользовательская visual sign/scale acceptance при одинаковых рывках телефона. После двух прогонов приложение оставлено с gyro включённым и `TRACKING_TEST`; thermal status `0`, но AP около `54.5 °C`, поэтому окончательную плавность нужно оценивать после охлаждения.

Пользовательская screen-recording `video_2026-08-17_15-34-58.mp4` локально разобрана покадрово без загрузки наружу: `18.93 s`, `884×1920`, около `46.1 FPS`. В первые `0–2 s` неподвижный контур стабилен; в `2–10 s` при движениях головы остаётся обычный ML/predictor lag; после `10 s`, когда голова неподвижна и резко движется только телефон, виден краткий промах маски на `1–3` кадра с возвратом. Это подтвердило ошибку temporal contract V6.3: `predictionSeconds` всегда описывал полный геометрический horizon `45→85 ms`, хотя stationary/candidate/quality gates реально применяли только часть глобальной velocity. Gyro начинал интеграцию с `landmarkSensorTimestamp + predictionSeconds` и поэтому на первом рывке ошибочно пропускал до одного–двух camera frames реального движения.

Исправление не меняет видимую predictor geometry и не вводит эмпирический gyro gain. `LandmarkMotionPredictor` теперь публикует `globalPredictionCoverage`: проекцию filtered global velocity на текущую measured global velocity, умноженную на фактические prediction gain и quality response. В stationary coverage точно `0`; на первом candidate impulse она ограничена реально применённой долей; при подтверждённом coherent motion плавно приближается к `1`. Gyro endpoint теперь равен `landmarkSensorTimestamp + predictionSeconds × globalPredictionCoverage`, поэтому компенсирует только ещё не предсказанную camera-motion часть. Тот же effective timestamp используется material temporal mismatch. `.arv6` поднят до version `5`, обратно читает v1–v4 и добавляет `cameraMotionPredictionSeconds` и `globalPredictionCoverage`; analyzer публикует их median. Regression проверяет stationary zero coverage, bounded candidate coverage, рост coherent coverage и v4 compatibility. Обновлённый локальный gate: `111` unit-тестов, `0` failures/errors, `lintDebug`, `assembleDebug` и native build четырёх ABI успешны. Device/visual acceptance этого уточнения ещё не выполнена.

Coverage-aware APK установлен на SM-G990B. Startup gate успешен: `LSM6DSO Gyroscope-Uncalibrated` зарегистрирован на `5 ms`, camera calibration `0.7479799/0.99730647`, Vulkan camera input `1440×1080`, `temporalFlowVisible=false`, crash/black frame отсутствуют. Первый v5 functional run `gyro_v63_coverage_same_scenario` сохранил `585` measurement / `1200` render events за `20.03 s`, `dropped=0`, ML FPS median `29.93`, latency median/p95 `107/127 ms`, capture interval `33/34 ms`, pose quality `0.948`, frame CPU median/p95 `3.12/6.35 ms`, rendered fraction `1.0`, thermal max `0`, battery median `34.4 °C`. Codec v5 автоматически декодирован; median camera-motion prediction/coverage равны `0/0`, gyro applied fraction `0.342`, rotation p95 всего `0.15°`, translation p95 `0.00199`. Следовательно, run подтверждает runtime/format, но не содержит достаточно сильных phone-only рывков для visual/lag acceptance. Файл сохранён как `app/build/tracking-telemetry/tracking-v6-20260817-161212-389-gyro_v63_coverage_same_scenario.arv6`. После run батарея около `35.9 °C`, thermal throttling не зафиксирован; приложение оставлено в `TRACKING_TEST` с gyro enabled.

FF0 выполнен перед full-face работой: coverage-aware V6.3 сохранён отдельным commit `dd095f7` и тегом `tracking-v6.3-gyro-experimental-2026-08-17`. Перед checkpoint повторно прошли `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`; статус остаётся experimental, потому что сильный phone-motion visual acceptance не завершён.

Первый FF1/FF2 shadow slice реализован поверх этого checkpoint без изменения predictor, lip mesh и Vulkan renderer. MediaPipe `outputFacialTransformationMatrixes` включается только когда создан debug `TrackingTelemetryRecorder`; production path без записи сохраняет прежний output contract. `.arv6` codec v6 обратно читает v1–v5 и добавляет 4×4 canonical-face transform вместе с исходным capture/sensor timestamp. Tracker-side latency разделена на camera→analysis, analysis→submit (включая ожидание latest pending frame), submit→native MediaPipe callback и callback→camera-executor handler start; отдельно записываются RGBA copy, sparse quality analysis и result/predictor processing CPU durations. Analyzer публикует p50/p95 и `transform3dCoverage`. Старые v1 и v5 записи покрыты regression tests. Локальный gate: `113` tests, `0` failures/errors, lint, debug APK и native build четырёх ABI успешны. Новых моделей, assets или зависимостей не добавлено; matrix пока используется только офлайн. Следующий gate: device `.arv6` v6 runs и проверка matrix axes/layout/overhead до любого подключения к renderer.

V6.0 вместе с актуальным на тот момент `PROJECT_CONTEXT.md` и `CHAT_HANDOFF.md` зафиксирован и отправлен в remote commit `8d48b46` (`[V6]`). Этот commit является воспроизводимой точкой старта для V6.1; дальнейшие tracking-изменения должны сравниваться с ним на одинаковых `.arv6` записях.

Официальный model bundle сохранён в `app/src/main/assets/face_landmarker.task`. Его SHA-256: `64184E229B263107BC2B804C6625DB1341FF2BB731874B0BCC2FE6544E0BC9FF`. BlazeFace, Face Mesh V2 и Blendshape V2 проверены по официальным model cards; все три компонента имеют лицензию Apache 2.0. Детали находятся в `app/src/main/assets/MODEL_LICENSES.md`.

Текущий `FaceMeshOverlay` на Canvas является только диагностическим инструментом для проверки координат и jitter. Он не используется и не будет использоваться для финального макияжа.

Оптимизация responsiveness от 2026-08-10:

- убран искусственный gate «ждать callback, затем ждать следующий camera frame»;
- один кадр обрабатывается, а второй слот постоянно перезаписывается самым свежим кадром; после callback новый inference начинается сразу;
- два direct RGBA `ByteBuffer` переиспользуются вместо создания и поворота новых объектов на каждом кадре; `BitmapImageBuilder` больше не используется, поскольку закрытие MediaPipe `MPImage` владеет и вызывает `recycle()` исходного `Bitmap`, что делало прежний pool некорректным и вызывало lifecycle warnings;
- rotation выполняет `ImageProcessingOptions`; возвращённые landmarks явно преобразуются из ориентации analysis-buffer в ориентацию экрана для 0/90/180/270°, после чего применяется зеркалирование фронтальной камеры;
- CameraX запрашивает лучший совместимый диапазон в пределах 30–60 FPS для связки Preview + ImageAnalysis;
- telemetry отдельно показывает ML FPS и запрошенный диапазон камеры;
- диагностический Canvas рисует только овал, губы, глаза, брови и радужки вместо полной тесселяции из тысяч линий.
- alpha-beta predictor заменён адаптивным low-pass/One Euro-подобным фильтром для 478 landmarks: при малой скорости cutoff остаётся низким и подавляет ML/camera jitter, а при реальном движении лица или отдельных точек повышается для уменьшения задержки;
- исторический baseline обрабатывал согласованное смещение centroid всех landmarks отдельным быстрым translation-каналом; в V6.1 этот путь заменён stable-anchor similarity pose, чтобы rotation/scale телефона не превращались в независимые local velocities;
- реакция cutoff на начало общего движения выполняется без медленной фазы накопления, а скорость быстро затухает при остановке/смене направления, чтобы не возвращать overshoot;
- базовый cutoff повышен с 2 до 3 Hz, верхний — с 12 до 20 Hz; cutoff производной — 6 Hz, общего translation velocity — 20 Hz, dead zone общей скорости — `0.01 normalized units/s`, локальной — `0.015 normalized units/s`;
- render lead уменьшен с 8 до 4 ms, prediction horizon — с 65 до 45 ms, velocity ограничена 2 normalized units/s; скачок centroid сбрасывает состояние, а при единичном dropout последнее состояние удерживается не дольше 120 ms.
- timestamp результата теперь строится из `ImageInfo.timestamp` и переводится из camera realtime clock в uptime; это включает возраст кадра до ImageAnalysis в prediction/telemetry. Для несовместимого camera timebase есть проверяемый fallback на текущий uptime, timestamps для MediaPipe принудительно остаются строго возрастающими.

Эти изменения уменьшают задержку и паузы между inference. Они не могут гарантировать 30/60 ML FPS на любом SoC: если чистое время модели остаётся около 55–60 ms, следующий этап — сравнительный benchmark GPU/CPU delegate и adaptive resolution. Параметры predictor нужно дополнительно откалибровать на записях медленных, средних и резких движений, чтобы найти баланс между lag и overshoot.

Runtime-проверка после оптимизации на Samsung SM-G990B: CameraX выбрал фиксированные 30 FPS, MediaPipe работал через GPU delegate; после прогрева поток результатов стабилизировался примерно на 29–30 ML FPS с наблюдаемой latency преимущественно 30–40 ms. В Logcat debug-сборки раз в секунду выводится срез `ARMakeupPerf` с ML FPS, latency, delegate и выбранным диапазоном камеры. Ошибок CameraX, MediaPipe и Android Runtime в проверочной сессии не зафиксировано.

После первого перехода на адаптивное сглаживание повторная сессия на SM-G990B сохраняла примерно 29–30 ML FPS при измеренных 26–41 ms, но эта старая метрика начиналась только при получении кадра ImageAnalysis и не включала camera pipeline до analyzer.

После включения sensor timestamp и быстрого translation-канала SM-G990B сохранил примерно 29–30 ML FPS. Полная capture→ML-result latency составила 85–128 ms, преимущественно 85–114 ms; эта величина не равна чистому времени MediaPipe и включает sensor/camera/analysis pipeline. `gfxinfo`: 633 кадра, 1 janky frame (0,16%), frame-time percentiles p50=5 ms, p95=6 ms, p99=7 ms; GPU p50=2 ms, p95=4 ms, p99=5 ms. CPU/GPU render bottleneck не появился. Визуальный баланс быстрого канала нужно проверить на устройстве при медленном движении, быстром повороте, резкой остановке и движении самого телефона.

Визуальная проверка пользователем 2026-08-11 первоначально оценила эту конфигурацию как отличную для того этапа, поэтому она была сохранена Git-тегом `tracking-stable-2026-08-11`. Последующая длительная эксплуатация отозвала production-приёмку: `LandmarkMotionPredictor` всё ещё имеет заметную нестабильность и считается только сравнительным baseline, а не завершённым трекером. Ограничение «не менять коэффициенты» действовало при изоляции render/Vulkan-регрессий и больше не запрещает отдельную измеримую переработку predictor.

Следующий обязательный tracking-подэтап выполняется до дальнейшей разработки материала. Сначала нужно добавить записываемую покадровую телеметрию raw landmarks, filtered landmarks, global translation/scale/rotation, local lip deformation, velocities, capture/delivery/render timestamps и фактически показанной lip mesh. Затем на одинаковых записях сравнивать варианты для неподвижного лица, медленного движения, быстрого поворота, резкой остановки, разговора/улыбки, движения телефона, dropout и разных ML FPS. Коэффициенты нельзя снова подбирать только по краткому live-наблюдению: acceptance требует численных метрик stationary RMS/peak jitter, stop overshoot, motion lag и reacquisition jump вместе с визуальным A/B на устройстве.

Исправление render jitter после baseline не меняет состояние или коэффициенты трекера. Раньше `LandmarkRenderFrame.shouldAnimate()` сравнивал render time непосредственно с capture timestamp. При реальной capture→result latency 85–128 ms 45-мс prediction horizon уже был исчерпан к моменту доставки результата, поэтому `LipstickOverlay` обновлялся ступенями с частотой ML около 30 FPS, несмотря на вызов `postInvalidateOnAnimation()`.

Capture-time alignment и render-time animation разделены: при доставке ML-результата `LandmarkRenderFrame` получает отдельный delivery timestamp и сохраняет bounded prediction до 45 ms. Первоначальное дополнительное render-only extrapolation window составляло 42 ms; V6.1 сократил его до 20 ms после измеренного stop overshoot, чтобы старая скорость мостила один vsync, а не почти весь следующий ML-интервал. Оверлей продолжает перестраивать контур на каждом `vsync`, а при задержке/потере нового результата экстраполяция останавливается. На SM-G990B при первоначальном разделении поток после прогрева сохранял около 29–30 ML FPS; `gfxinfo`: 800 кадров, 2 janky frames (0,25%), p50=5 ms, p95=7 ms, p99=9 ms; GPU p50=2 ms, p95=4 ms, p99=5 ms.

После визуальной проверки обнаружена резкая фаза «догоняющей» коррекции в момент доставки следующего ML-результата. Она устранена render-only continuity correction: новый delivered frame вычисляет разницу с фактически показанной позицией предыдущего frame и плавно погашает её по smoothstep-кривой с нулевой скоростью на концах. Первоначальные 28 ms последовательно сокращены до 22 ms и затем до 16 ms по запросу пользователя. Текущие 16 ms соответствуют примерно одному кадру дисплея 60 Hz и минимизируют ощущение «резиновости». Tracking state, velocity и коэффициенты predictor не изменяются. Коррекция ограничена `0.12` normalized units на координату и полностью отключается при centroid jump больше `0.15`, чтобы не интерполировать между разными захватами лица. До изменения длительности на SM-G990B после прогрева сохранялось 29–30 ML FPS; `gfxinfo`: 799 кадров, 3 janky frames (0,38%), p50=5 ms, p95=6 ms, p99=8 ms; GPU p50=2 ms, p95=4 ms, p99=5 ms. Render bottleneck не появился; вариант 16 ms требует повторной визуальной проверки движениями.

На SM-G990B portrait analysis-кадры приходят с `rotationDegrees = 270`. MediaPipe корректно использует этот угол для inference, но landmark-координаты требуют отдельного преобразования в display space. Для этого добавлен `NormalizedImageTransform`: сначала применяется clockwise camera rotation, затем horizontal mirror. Это исправляет диагностическую сетку, которая после первоначальной FPS-оптимизации отображалась повёрнутой на 90°.

Референсный HWUI-срез матовой розово-кирпичной помады, использованный для калибровки до перехода на Filament:

- `LipstickOverlay` получает predicted landmarks на каждый `vsync`;
- внешний и внутренний контуры состоят из двух согласованных 20-точечных MediaPipe loops; из них строятся отдельные замкнутые области верхней и нижней губы, поэтому рот и зубы не окрашиваются;
- контуры сглаживаются cubic spline, а три вложенные маски на каждой губе дают мягкое нарастание пигмента без blur и дополнительных bitmap;
- после сравнения с пользовательским референсом неоновый `#C5163A` заменён тёплой розово-кирпичной парой `#B64B49`/`#C15C57`; верхняя губа намеренно темнее и плотнее нижней;
- первая приглушённая калибровка `#A35653`/`#B56964` с покрытием 47,8%/40,6% оказалась практически невидимой на реальной камере и отклонена после пользовательской проверки;
- после уточнения запроса более плотный вариант 71,1%/65,6% отклонён; прозрачность последовательно увеличена сначала до 63,1%/56,4%, затем до рабочего покрытия 56,9% на верхней и 50,7% на нижней губе; это остаётся выше практически невидимого уровня 47,8%/40,6%; внешний pass по-прежнему начинается с inset `0.015`, а matte compression остаётся на alpha 8/3, чтобы сохранить складки, тени и блик нижней губы;
- на Android 10+ аппаратный `BlendMode.COLOR` переносит оттенок/насыщенность помады, сохраняя покадровую яркость исходных губ; слабый `MULTIPLY` pass подавляет только самые сильные блики для matte-профиля;
- на API 24–28 используется texture-preserving multiply fallback;
- `PreviewView` временно переведён в `compatible`/TextureView mode, чтобы HWUI мог смешивать материал с camera backdrop; диагностическая face mesh скрыта.

Этот HWUI-прототип больше не является активным render path и временно сохранён в исходниках как точка визуального сравнения. Активный layout использует `FilamentMakeupView`: camera texture и динамическая lip mesh рендерятся в одной сцене. V5.0 material уже использует реконструированные normals, camera-conditioned lighting gradient и раздельные matte/satin/gloss profiles в линейном рабочем пространстве. Device/runtime acceptance на SM-G990B пройдена, но visual material acceptance не пройдена; следующими задачами остаются оптическая калибровка профилей и границы, semantic lip refinement, полноценный HDR pipeline, multi-device и thermal acceptance.

Временный технический долг первого этапа:

- публичный Face Landmarker Android API принимает RGBA/`Bitmap`, поэтому analysis-ветка пока делает одну копию 640×480 в переиспользуемый буфер. Preview уже идёт прямо в camera surface. До production-эффектов нужно заменить диагностическую ветку на GPU/native integration или измеренно доказать, что копия укладывается в latency/thermal budget;
- material packages первого Filament-среза компилируются on-device через `filamat-android`. Это ускоряет разработку shader foundation, но увеличивает APK и startup cost. До релизного профиля материалы нужно компилировать host-side `matc` той же версии `1.74.0`, хранить как `.filamat` assets и убрать runtime `filamat-android` dependency;
- material factory умеет собирать OpenGL и Vulkan variants, но после device crash Filament compositor намеренно компилирует только активный OpenGL variant. Vulkan material proof сохранён как результат V1, однако возвращать его в camera path запрещено до замены Filament `Stream` собственным importer;
- `NATIVE` fallback на API 24–28 не гарантирует совпадение времени camera texture и render state. Это должно входить в device acceptance; если относительный temporal drift заметен, минимальный поддерживаемый класс production-качества придётся поднять до API 29 либо реализовать отдельную синхронизацию для старых устройств.

Debug APK первого runtime-`filamat` среза имеет размер `105.18 MiB` и содержит универсальные native libraries. Это не релизный size baseline: после host-side компиляции материалов, удаления `filamat-android` и настройки ABI packaging размер нужно измерить заново.

Debug APK после добавления V2.0 native bootstrap имеет размер `118.24 MiB`; рост включает debug symbols/упаковку четырёх ABI и всё ещё присутствующий runtime `filamat-android`, поэтому также не считается релизным size baseline.

## Зафиксированный стек

Версии ниже проверены на дату обновления файла. Перед фактическим добавлением зависимости нужно закрепить точную версию в version catalog и проверить Gradle sync.

| Задача | Выбор | Причина |
|---|---|---|
| Язык и платформа | Native Android, Kotlin | Прямой доступ к CameraX, GPU, профилировщикам и минимальная лишняя задержка. |
| Камера | CameraX 1.6.1, Camera2 backend | Стабильная версия, единое поведение на разных устройствах, lifecycle, точные настройки FPS и неблокирующий analysis-поток. |
| Геометрия лица | MediaPipe Tasks Vision / Face Landmarker `1.0.0` | 478 3D landmarks, 52 blendshape-коэффициента, матрица трансформации, `LIVE_STREAM`, on-device GPU delegate. |
| Рендер | Google Filament `1.74.0` как временный видимый OpenGL ES bridge с V5.0 camera-conditioned lipstick material; native Vulkan V4 camera importer/temporal compute | Filament сохраняет проверенный camera/makeup visual baseline и теперь интерполирует lip normals, camera-light gradient и matte/satin/gloss optics. Vulkan напрямую импортирует тот же camera AHB, выполняет YCbCr sampling, luma pyramid, pyramidal LK flow, robust similarity fit и fence-controlled handoff без CPU-копии; финальный экранный compositor будет перенесён после следующих vertical slices. |
| Native GPU toolchain | NDK r29 `29.0.14206865`, CMake `3.31.6`, C++20, NDK `glslc` | JNI/Vulkan frame graph, offline SPIR-V и строгая компиляция четырёх ABI с `-Werror`; dynamic Vulkan/media dispatch, AHB external memory, YCbCr conversion и sync-fd semaphores подключены. |
| Семантические маски | MediaPipe Image Segmenter API + собственная LiteRT-модель face parsing | Нужны точные вероятностные маски губ, кожи, век и глаз; стандартной selfie segmentation для этого недостаточно. |
| Асинхронность | Kotlin Coroutines + Flow | Изоляция camera, inference и render потоков; latest-only state без очереди устаревших кадров. |
| Профилирование | Perfetto, Android GPU Inspector, Jetpack Benchmark/Macrobenchmark | Измерение motion-to-photon latency, CPU/GPU времени, пропусков кадров, памяти и нагрева. |

UI первого этапа остаётся на Views: камера и макияж уже используют единую `FilamentMakeupView` render surface, поверх которой находятся диагностический overlay и обычные Android-контролы. Compose можно подключить для каталога и экранов приложения позднее; он не должен находиться в горячем цикле обработки кадра.

## Главные архитектурные решения

### 1. MediaPipe — текущий заменяемый face tracker

Настройки первого прототипа:

- `RunningMode.LIVE_STREAM`;
- `numFaces = 1`, поскольку это try-on одного пользователя и MediaPipe применяет встроенное сглаживание только при одном лице;
- включать face blendshapes только для эффекта, который реально использует эти коэффициенты;
- включать facial transformation matrix только для renderer/profile, который её потребляет;
- сначала пробовать GPU delegate, иметь CPU fallback;
- результаты всегда связывать с timestamp исходного кадра.

Для full-face эффектов facial transformation matrix нужно отдельно включить и измерить как источник canonical 3D pose; blendshapes включать только если они измеримо улучшают моргание, смыкание век, мимику губ или confidence gating. Это не меняет модель-независимый контракт `FaceObservation`.

Почему не ARCore Augmented Faces как основа:

- ARCore требует поддержку Google Play Services for AR конкретным устройством;
- Augmented Faces работает только с фронтальной камерой и даёт 468-точечную сетку;
- MediaPipe даёт 478 точек, включая более полезную детализацию глаз, blendshapes и более широкий охват устройств API 24+;
- нормали для MediaPipe-сетки можно вычислять на GPU из треугольников.

ARCore допустимо позже проверить как альтернативный backend на сертифицированных устройствах, только если сравнительный тест покажет заметно более стабильную геометрию. Не поддерживать два backend без измеренной пользы.

### 2. Один GPU-композитор

Предполагаемый поток кадра:

```text
Front Camera
  ├─ GPU camera surface ──────────────┐
  └─ low-resolution analysis frame ─┐ │
                                    │ │
                         Face Landmarker
                                    │
                  landmarks / pose / blendshapes
                                    │
                  face parsing (не на каждом кадре)
                                    │
                       temporal stabilization
                                    │
                                    v
Camera texture ───────────────> Filament compositor ──> экран
                                  ├─ face mesh
                                  ├─ makeup masks
                                  ├─ material shading
                                  └─ color management
```

Кадр камеры нельзя гонять через `Bitmap` в production-пайплайне. Камера должна попадать на GPU через `Surface`/`SurfaceTexture`; inference получает отдельный уменьшенный `ImageAnalysis`-кадр. Для анализа применяется `STRATEGY_KEEP_ONLY_LATEST`, чтобы не накапливать визуально устаревшие кадры.

Preview и ImageAnalysis должны использовать общий `ViewPort`/crop transform. Координаты face tracker нельзя вручную «подгонять» под экран без единой матрицы sensor → buffer → view.

### 3. Гибридные маски вместо одной технологии

Одних landmarks недостаточно для реалистичных биологических границ, а одна низкоразрешённая ML-маска будет дрожать и размывать тонкие линии.

- Геометрия из face mesh задаёт стабильную форму, UV-привязку, поворот и окклюзию.
- Высокоточные векторные маски из landmarks используются для подводки и карандаша для губ.
- Face parsing уточняет фактические границы губ, кожи и век.
- Сегментация запускается по crop лица примерно 15–30 раз/с, а маска репроецируется на каждый render-кадр с помощью текущей сетки.
- Маски хранят confidence, имеют temporal filtering и edge-aware feathering.

До выбора или обучения face-parsing модели необходимо проверить лицензию модели и датасета. Нельзя молча включать случайный BiSeNet/SegFormer checkpoint из GitHub в продукт.

### 4. Материалы макияжа, а не плоские цветные оверлеи

Каждый продукт описывается параметрами, а не только RGB-цветом:

- цвет в линейном пространстве;
- opacity/coverage;
- roughness и specular/gloss;
- сохранение исходной яркости и микротекстуры кожи/губ;
- sparkle/pearlescence при необходимости;
- feather radius и профиль плотности;
- canonical UV mask или параметрическая форма;
- правила окклюзии и ограничения по углу лица.

Рендер выполняется в linear RGB с корректным преобразованием в цветовое пространство дисплея. Нельзя использовать обычный alpha blend в sRGB как финальный алгоритм: он даёт эффект наклейки и ломает светотень.

### 5. Требования по каждому эффекту

**Помада**

- Точная внешняя и внутренняя граница губ, исключение рта и зубов.
- Сохранение складок и естественных теней губ.
- Отдельные режимы matte, satin и gloss через разные параметры BRDF.
- Specular должен следовать освещению и нормалям, а не быть нарисованным статично.
- Использует общий full-face pose/state; рот и зубы являются отдельной окклюзией, а не просто отверстием в 2D-маске.

**Карандаш для губ**

- Стабильный spline вдоль контура с шириной, нормализованной к размеру лица.
- Edge-aware feathering и корректное перекрытие уголков рта.

**Румяна**

- Мягкая анатомическая маска в UV лица, ограниченная skin confidence mask.
- Плавное падение плотности; исключение глаз, губ, волос и ноздрей.
- Цвет должен смешиваться с исходным тоном кожи, сохраняя поры и светотень.
- Обе щеки привязываются к canonical 3D surface и общей позе головы, чтобы не плавать независимо при yaw/pitch и движении камеры.

**Тени**

- Отдельные маски верхнего века, crease и внешнего уголка.
- Учет закрытия глаза, поворота головы и окклюзии ресницами/веком.
- Поддержка градиентов и нескольких оттенков в одной палитре.
- Маски обоих век используют общий timestamped face state, но отдельные visibility/blink confidence для левого и правого глаза.

**Подводка**

- Кривая по краю века с настраиваемой толщиной и wing.
- Толщина задаётся относительно межзрачкового расстояния, а не в пикселях экрана.
- При моргании линия должна деформироваться вместе с веком без скачков.
- Нужны sub-pixel contour refinement и корректная окклюзия краем века/ресницами; одной грубой face segmentation недостаточно.

## Порядок реализации

1. **Camera + diagnostics — Vulkan V3 vertical slice реализован:** фронтальная камера, единые transforms/timestamps, FPS/latency overlay; CameraX пишет в native `AImageReader`, тот же `AHardwareBuffer` импортируется и семплируется Vulkan с acquire/release synchronization, затем передаётся видимому Filament fallback.
2. **Face mesh — реализована диагностическая версия:** Face Landmarker в live-stream режиме, синхронизация timestamp и mesh overlay. Стабильность ещё нужно проверить на реальных устройствах.
3. **Вертикальный срез «помада» — V5.0 material foundation реализован:** camera texture и динамическая upper/lower lip mesh сведены в одной GPU scene; есть linear-RGB luminance-preserving pigment, cubic subdivision, мягкое coverage, исключение рта/зубов, reconstructed per-pixel normals, camera-conditioned lighting и переключаемые matte/satin/gloss optics. Runtime/performance acceptance пройдена на SM-G990B, visual material acceptance не пройдена из-за слабого различия профилей, плоского пигмента и жёсткой границы. Остаются optical/edge tuning, semantic lip refinement, production BRDF/HDR и калибровка на разных губах/освещении/устройствах.
4. **V6 / Temporal quality — активный обязательный этап:** визуально стабильный V6.2 baseline и принятый fast-motion checkpoint сохранены отдельно. Material-temporal candidate сохранён commit `ab8796a`; runtime/performance gate пройден, visual acceptance всё ещё нужна. V6.3 candidate интегрирует 200 Hz uncalibrated gyroscope между coverage-aware effective predicted-mesh sensor timestamp и timestamp реально показанного camera buffer, использует физические Camera2 focal length/sensor size и применяет bounded global translation+roll после проверенного rotation/mirror transform. Predictor geometry, local lip deformation, topology, coverage mask и shadow-only Vulkan flow не изменены; predictor дополнительно публикует долю фактически применённого global prediction для temporal fusion. `.arv6` v5 записывает применённый gyro interval/rotation/translation и camera-motion prediction coverage; обязательны повторная пользовательская visual acceptance и controlled phone-motion A/B после coverage-fix. Затем нужны controlled light/thermal replay A/B.
5. **Full-face tracking contract — вместе со следующим tracking slice:** отделить MediaPipe API от renderer через `FaceTrackingBackend`/`FaceObservation`, перейти от lip-only similarity correction к общей timestamped 3D pose/canonical surface и предусмотреть camera-flow + IMU fusion для всех будущих эффектов. Текущие lip regression tests и rollback checkpoints сохраняются.
6. **Face parsing — после V6:** выбрать или обучить модель только после документальной проверки runtime, weights, датасета и разметки; затем LiteRT GPU/NPU/CPU benchmark, вероятностные masks для skin/lips/mouth-teeth/eyes/eyelids/brows/hair и temporal warp общей face surface.
7. **Остальные эффекты:** lip liner, blush, eyeshadow, eyeliner — поверх общего full-face state, не через отдельные трекеры.
8. **Калибровка:** разные тона кожи, освещение, front-camera mirroring, HDR/SDR и цветовые пространства устройств.
9. **Adaptive quality tiers:** автоматическое снижение разрешения масок/частоты inference при нагреве или слабом GPU.

Помада выбрана первым полноценным эффектом, потому что она быстро выявляет основные проблемы всей системы: точность контура, открытый рот, сохранение текстуры, отражения, задержку и temporal jitter.

## Бюджет производительности

Целевые, а не гарантированные значения; их нужно подтвердить минимум на слабом, среднем и флагманском реальном устройстве.

- Отображение: 60 FPS на производительных устройствах, не ниже стабильных 30 FPS на поддерживаемом минимальном классе.
- Render budget при 60 FPS: до 16.6 ms на кадр, желательно оставить макияжу и композитору не более 6–8 ms GPU.
- Face landmarks: стремиться к 30–60 результатов/с; допускается inference реже рендера с prediction/reprojection.
- Face parsing: 15–30 результатов/с по crop лица; не блокирует камеру и renderer.
- Очереди кадров: глубина 1/latest-only для real-time веток.
- Любое качество считается неприемлемым при заметном «плавании» маски, отставании при повороте головы или мерцании границ, даже если средний FPS высокий.

## Что намеренно не выбрано

- **ML Kit Face Detection:** слишком редкие landmarks для косметических границ.
- **Selfie Segmentation как единственная маска:** отделяет человека/крупные области, но не даёт нужных классов губ и век.
- **OpenCV в основном цикле:** CPU-обработка и копии кадров ухудшат latency; допустим только для offline-инструментов и отладки.
- **Unity:** для этого native Android-приложения добавляет размер и интеграционную сложность без доказанного выигрыша в качестве.
- **Чистый Canvas/2D overlay:** недостаточно для перспективы, нормалей, окклюзии и физически правдоподобных материалов.
- **Облачная обработка:** неприемлемая задержка и риски приватности; камера и inference по умолчанию полностью on-device.
- **Коммерческий beauty SDK:** может ускорить time-to-market, но создаёт лицензионную стоимость и black-box ограничения. Рассматривать только после сравнительного теста качества с собственным пайплайном.

## Критерии качества перед релизом эффекта

- Проверен на разных оттенках кожи, возрасте, форме лица и типах губ/глаз.
- Проверен при тёплом, холодном, слабом и контровом освещении.
- Проверен при улыбке, разговоре, моргании, открытом рте и повороте головы.
- Нет окрашивания зубов, белков глаз, волос и фона.
- Нет заметного jitter на статичном лице и отставания при движении.
- Материал сохраняет естественную текстуру и освещение исходной камеры.
- Есть визуальные golden tests и записи с реальных устройств, а не только скриншоты эмулятора.

## Непринятые решения

- Порог измеримого качества, после которого MediaPipe Face Landmarker заменяется собственной landmark/mesh model; до такого benchmark MediaPipe остаётся текущим backend.
- Конкретная архитектура и лицензия собственного face-parsing датасета/модели.
- Минимальный поддерживаемый класс GPU и окончательный список устройств.
- Нужны ли запись фото/видео и сравнение before/after в первом релизе.
- Формат каталога косметических продуктов и источник спектрально/цветометрически корректных оттенков.
- Требуется ли офлайн-калибровка камеры/дисплея для устройств премиального уровня.

## Проверенные первичные источники

- MediaPipe repository license (Apache 2.0): https://github.com/google-ai-edge/mediapipe/blob/master/LICENSE
- MediaPipe Face Landmarker for Android: https://developers.google.com/edge/mediapipe/solutions/vision/face_landmarker/android
- MediaPipe Face Landmarker overview/models: https://developers.google.com/edge/mediapipe/solutions/vision/face_landmarker
- MediaPipe BlazeFace Short Range model card (Apache 2.0): https://storage.googleapis.com/mediapipe-assets/MediaPipe%20BlazeFace%20Model%20Card%20%28Short%20Range%29.pdf
- MediaPipe Face Mesh V2 model card (Apache 2.0): https://storage.googleapis.com/mediapipe-assets/Model%20Card%20MediaPipe%20Face%20Mesh%20V2.pdf
- MediaPipe Blendshape V2 model card (Apache 2.0): https://storage.googleapis.com/mediapipe-assets/Model%20Card%20Blendshape%20V2.pdf
- MediaPipe Image Segmenter: https://developers.google.com/edge/mediapipe/solutions/vision/image_segmenter
- MediaPipe Android setup and GPU delegate: https://developers.google.com/edge/mediapipe/solutions/setup_android
- ML Kit Terms & Privacy (отдельный продукт, не текущий backend): https://developers.google.com/ml-kit/terms
- Vulkan Android Hardware Buffer external memory: https://docs.vulkan.org/refpages/latest/refpages/source/VK_ANDROID_external_memory_android_hardware_buffer.html
- Vulkan AHB format/YCbCr properties: https://docs.vulkan.org/refpages/latest/refpages/source/VkAndroidHardwareBufferFormatPropertiesANDROID.html
- Vulkan compute shaders: https://docs.vulkan.org/guide/latest/compute_shaders.html
- Vulkan storage images and texel buffers: https://docs.vulkan.org/guide/latest/storage_image_and_texel_buffers.html
- Vulkan synchronization examples: https://docs.vulkan.org/guide/latest/synchronization_examples.html
- Vulkan synchronization specification: https://docs.vulkan.org/spec/latest/chapters/synchronization.html
- Android `AImageReader` / `ImageReader` acquire-latest semantics: https://developer.android.com/reference/android/media/ImageReader
- Android synchronization fences: https://developer.android.com/reference/android/hardware/SyncFence.html
- CameraX releases: https://developer.android.com/jetpack/androidx/releases/camera
- CameraX ImageAnalysis: https://developer.android.com/media/camera/camerax/analyze
- Google Filament: https://github.com/google/filament
- Filament Materials Guide: https://google.github.io/filament/main/materials.html
- ARCore Augmented Faces: https://developers.google.com/ar/develop/augmented-faces
