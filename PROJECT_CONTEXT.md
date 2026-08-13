# AR Makeup — контекст и технические решения

Последнее обновление: 2026-08-13.

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

## Текущее состояние репозитория

- Чистый Android-проект на Kotlin.
- Один модуль `:app`.
- UI: XML/View system и `AppCompatActivity`.
- `minSdk = 24`, `targetSdk = 37`, `compileSdk = 37`.
- Android Gradle Plugin 9.3.1.
- Реализованы диагностический camera/ML pipeline, зафиксированный tracking baseline, Vulkan V4 temporal-compute vertical slice и первый production-срез единого Filament-композитора для матовой помады. Semantic face parsing и полноценная normals/BRDF-модель ещё не добавлены.

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

`TemporalLandmarkRefiner` хранит только непрерывную timestamped цепочку принятых transforms, интерполирует первый неполный interval и ограничивает render reprojection 42 ms. Lip mesh получает V4 correction относительно последнего Face Landmarker anchor; любой пропуск timestamp, rejected fit, слишком большой cumulative transform или смена camera transform немедленно очищает цепочку. В этом случае без скачка включается зафиксированный `LandmarkMotionPredictor`; его параметры и 16-ms continuity correction не изменены. Следовательно, ошибочный optical flow не переносится через rejected frame и V4 не ухудшает доказанный baseline при низкой текстуре или резком движении.

Device acceptance V4 на SM-G990B/Adreno 660: camera `1440×1080`, AHB format `34`, external format `506`. В длинном foreground-интервале до pause вычислено 1626 temporal fits, принято 776 (`47.7%`); после стабилизации лица встречались серии 36–40 принятых из 60 кадров. За те же 1655 camera imports получено `cameraDropped=0`, release fence создан для каждого кадра, а released закономерно отставал на два in-flight Filament buffer. Home/resume остановил camera session и продолжил тот же runtime без black frame, crash или накопления очереди. Контрольное прогретое 12-секундное окно `gfxinfo`: 300 UI frames, 5.67% janky, CPU p50/p90/p95/p99 = 11/15/17/24 ms, GPU = 4/6/7/8 ms. GPU укладывается в верхнюю границу бюджета, но CPU p95 и jank хуже V3 baseline; необходимы thermal soak и профиль потока перед production acceptance.

Локальная приёмка V4: native C++20 с `-Werror` и все compute shaders собраны для `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`; 61 unit-тест пройден без failures/errors, Android lint завершён без ошибок, debug APK собран, установлен и запущен на устройстве. Визуальная проверка статического кадра не выявила регрессий orientation/mirror/lip alignment. Она не доказывает превосходство в движении: до удаления predictor fallback обязательны записанные A/B-сценарии со статикой, медленным/резким поворотом, разговором, улыбкой и dropout, а также acceptance на нескольких GPU. V4 считается завершённым как безопасный temporal-compute vertical slice, но ещё не production-calibrated tracker.

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
- согласованное смещение centroid всех landmarks обрабатывается отдельным быстрым translation-каналом: это уменьшает lag как при движении головы, так и при движении телефона относительно неподвижного лица; локальная деформация landmarks по-прежнему сглаживается сильнее;
- реакция cutoff на начало общего движения выполняется без медленной фазы накопления, а скорость быстро затухает при остановке/смене направления, чтобы не возвращать overshoot;
- базовый cutoff повышен с 2 до 3 Hz, верхний — с 12 до 20 Hz; cutoff производной — 6 Hz, общего translation velocity — 20 Hz, dead zone общей скорости — `0.01 normalized units/s`, локальной — `0.015 normalized units/s`;
- render lead уменьшен с 8 до 4 ms, prediction horizon — с 65 до 45 ms, velocity ограничена 2 normalized units/s; скачок centroid сбрасывает состояние, а при единичном dropout последнее состояние удерживается не дольше 120 ms.
- timestamp результата теперь строится из `ImageInfo.timestamp` и переводится из camera realtime clock в uptime; это включает возраст кадра до ImageAnalysis в prediction/telemetry. Для несовместимого camera timebase есть проверяемый fallback на текущий uptime, timestamps для MediaPipe принудительно остаются строго возрастающими.

Эти изменения уменьшают задержку и паузы между inference. Они не могут гарантировать 30/60 ML FPS на любом SoC: если чистое время модели остаётся около 55–60 ms, следующий этап — сравнительный benchmark GPU/CPU delegate и adaptive resolution. Параметры predictor нужно дополнительно откалибровать на записях медленных, средних и резких движений, чтобы найти баланс между lag и overshoot.

Runtime-проверка после оптимизации на Samsung SM-G990B: CameraX выбрал фиксированные 30 FPS, MediaPipe работал через GPU delegate; после прогрева поток результатов стабилизировался примерно на 29–30 ML FPS с наблюдаемой latency преимущественно 30–40 ms. В Logcat debug-сборки раз в секунду выводится срез `ARMakeupPerf` с ML FPS, latency, delegate и выбранным диапазоном камеры. Ошибок CameraX, MediaPipe и Android Runtime в проверочной сессии не зафиксировано.

После первого перехода на адаптивное сглаживание повторная сессия на SM-G990B сохраняла примерно 29–30 ML FPS при измеренных 26–41 ms, но эта старая метрика начиналась только при получении кадра ImageAnalysis и не включала camera pipeline до analyzer.

После включения sensor timestamp и быстрого translation-канала SM-G990B сохранил примерно 29–30 ML FPS. Полная capture→ML-result latency составила 85–128 ms, преимущественно 85–114 ms; эта величина не равна чистому времени MediaPipe и включает sensor/camera/analysis pipeline. `gfxinfo`: 633 кадра, 1 janky frame (0,16%), frame-time percentiles p50=5 ms, p95=6 ms, p99=7 ms; GPU p50=2 ms, p95=4 ms, p99=5 ms. CPU/GPU render bottleneck не появился. Визуальный баланс быстрого канала нужно проверить на устройстве при медленном движении, быстром повороте, резкой остановке и движении самого телефона.

Визуальная приёмка пользователем 2026-08-11: трекинг в этой конфигурации оценён как отличный и считается зафиксированным baseline. Не менять параметры `LandmarkMotionPredictor`, быстрый translation-канал и sensor timestamp при исправлении последующего render jitter. Точка возврата сохраняется Git-тегом `tracking-stable-2026-08-11`.

Исправление render jitter после baseline не меняет состояние или коэффициенты трекера. Раньше `LandmarkRenderFrame.shouldAnimate()` сравнивал render time непосредственно с capture timestamp. При реальной capture→result latency 85–128 ms 45-мс prediction horizon уже был исчерпан к моменту доставки результата, поэтому `LipstickOverlay` обновлялся ступенями с частотой ML около 30 FPS, несмотря на вызов `postInvalidateOnAnimation()`.

Теперь capture-time alignment и render-time animation разделены: при доставке ML-результата `LandmarkRenderFrame` получает отдельный delivery timestamp, сохраняет исходную bounded prediction до 45 ms и открывает дополнительное render-only extrapolation window до 42 ms. Оверлей продолжает перестраивать контур на каждом `vsync` между соседними ML-результатами, а при задержке/потере нового результата экстраполяция автоматически останавливается. На SM-G990B после изменения поток после прогрева сохранил около 29–30 ML FPS; `gfxinfo`: 800 кадров, 2 janky frames (0,25%), p50=5 ms, p95=7 ms, p99=9 ms; GPU p50=2 ms, p95=4 ms, p99=5 ms. Визуальную плавность нужно подтвердить движениями на устройстве.

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

Этот HWUI-прототип больше не является активным render path и временно сохранён в исходниках как точка визуального сравнения. Активный layout использует `FilamentMakeupView`: camera texture и динамическая lip mesh рендерятся в одной сцене. Текущий Filament material уже устраняет отдельный Canvas/HWUI compositing path и работает с линейными значениями, но ещё не моделирует face normals, оценку освещения, semantic lip refinement и полноценные matte/satin/gloss BRDF. Новый путь требует визуальной и performance-проверки на SM-G990B.

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
| Рендер | Google Filament `1.74.0` как временный видимый OpenGL ES bridge; native Vulkan V4 camera importer/temporal compute | Filament сохраняет проверенный camera/makeup visual baseline. Vulkan напрямую импортирует тот же camera AHB, выполняет YCbCr sampling, luma pyramid, pyramidal LK flow, robust similarity fit и fence-controlled handoff без CPU-копии; финальный экранный compositor будет перенесён после следующих vertical slices. |
| Native GPU toolchain | NDK r29 `29.0.14206865`, CMake `3.31.6`, C++20, NDK `glslc` | JNI/Vulkan frame graph, offline SPIR-V и строгая компиляция четырёх ABI с `-Werror`; dynamic Vulkan/media dispatch, AHB external memory, YCbCr conversion и sync-fd semaphores подключены. |
| Семантические маски | MediaPipe Image Segmenter API + собственная LiteRT-модель face parsing | Нужны точные вероятностные маски губ, кожи, век и глаз; стандартной selfie segmentation для этого недостаточно. |
| Асинхронность | Kotlin Coroutines + Flow | Изоляция camera, inference и render потоков; latest-only state без очереди устаревших кадров. |
| Профилирование | Perfetto, Android GPU Inspector, Jetpack Benchmark/Macrobenchmark | Измерение motion-to-photon latency, CPU/GPU времени, пропусков кадров, памяти и нагрева. |

UI первого этапа остаётся на Views: камера и макияж уже используют единую `FilamentMakeupView` render surface, поверх которой находятся диагностический overlay и обычные Android-контролы. Compose можно подключить для каталога и экранов приложения позднее; он не должен находиться в горячем цикле обработки кадра.

## Главные архитектурные решения

### 1. MediaPipe — основной face tracker

Настройки первого прототипа:

- `RunningMode.LIVE_STREAM`;
- `numFaces = 1`, поскольку это try-on одного пользователя и MediaPipe применяет встроенное сглаживание только при одном лице;
- включать face blendshapes только для эффекта, который реально использует эти коэффициенты;
- включать facial transformation matrix только для renderer/profile, который её потребляет;
- сначала пробовать GPU delegate, иметь CPU fallback;
- результаты всегда связывать с timestamp исходного кадра.

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

**Карандаш для губ**

- Стабильный spline вдоль контура с шириной, нормализованной к размеру лица.
- Edge-aware feathering и корректное перекрытие уголков рта.

**Румяна**

- Мягкая анатомическая маска в UV лица, ограниченная skin confidence mask.
- Плавное падение плотности; исключение глаз, губ, волос и ноздрей.
- Цвет должен смешиваться с исходным тоном кожи, сохраняя поры и светотень.

**Тени**

- Отдельные маски верхнего века, crease и внешнего уголка.
- Учет закрытия глаза, поворота головы и окклюзии ресницами/веком.
- Поддержка градиентов и нескольких оттенков в одной палитре.

**Подводка**

- Кривая по краю века с настраиваемой толщиной и wing.
- Толщина задаётся относительно межзрачкового расстояния, а не в пикселях экрана.
- При моргании линия должна деформироваться вместе с веком без скачков.

## Порядок реализации

1. **Camera + diagnostics — Vulkan V3 vertical slice реализован:** фронтальная камера, единые transforms/timestamps, FPS/latency overlay; CameraX пишет в native `AImageReader`, тот же `AHardwareBuffer` импортируется и семплируется Vulkan с acquire/release synchronization, затем передаётся видимому Filament fallback.
2. **Face mesh — реализована диагностическая версия:** Face Landmarker в live-stream режиме, синхронизация timestamp и mesh overlay. Стабильность ещё нужно проверить на реальных устройствах.
3. **Вертикальный срез «помада» — первый Filament production-срез реализован:** camera texture и динамическая upper/lower lip mesh сведены в одной GPU scene; есть linear-RGB luminance-preserving pigment, cubic subdivision, мягкое coverage и исключение рта/зубов. Остаются device runtime calibration, semantic lip refinement, face normals/lighting, полноценные BRDF-профили matte/satin/gloss и калибровка на разных губах/освещении.
4. **Temporal quality — Vulkan V4 vertical slice реализован:** V3 camera AHB в compute-программе преобразуется в трёхуровневую luma pyramid; 48-точечный pyramidal LK и robust similarity fit дают timestamped translation/rotation/scale для lip mesh. Confidence/geometric gate и немедленный fallback сохраняют зафиксированный predictor при недостоверном flow. Остаются записанное динамическое A/B, multi-device/thermal calibration и стабилизация будущих semantic masks.
5. **Face parsing:** подготовленная и лицензированная модель для skin/lips/eyes/eyelids; LiteRT GPU/NPU/CPU benchmark.
6. **Остальные эффекты:** lip liner, blush, eyeshadow, eyeliner.
7. **Калибровка:** разные тона кожи, освещение, front-camera mirroring, HDR/SDR и цветовые пространства устройств.
8. **Adaptive quality tiers:** автоматическое снижение разрешения масок/частоты inference при нагреве или слабом GPU.

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

- Конкретная архитектура и лицензия собственного face-parsing датасета/модели.
- Минимальный поддерживаемый класс GPU и окончательный список устройств.
- Нужны ли запись фото/видео и сравнение before/after в первом релизе.
- Формат каталога косметических продуктов и источник спектрально/цветометрически корректных оттенков.
- Требуется ли офлайн-калибровка камеры/дисплея для устройств премиального уровня.

## Проверенные первичные источники

- MediaPipe Face Landmarker for Android: https://developers.google.com/edge/mediapipe/solutions/vision/face_landmarker/android
- MediaPipe Face Landmarker overview/models: https://developers.google.com/edge/mediapipe/solutions/vision/face_landmarker
- MediaPipe Image Segmenter: https://developers.google.com/edge/mediapipe/solutions/vision/image_segmenter
- MediaPipe Android setup and GPU delegate: https://developers.google.com/edge/mediapipe/solutions/setup_android
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
- ARCore Augmented Faces: https://developers.google.com/ar/develop/augmented-faces
