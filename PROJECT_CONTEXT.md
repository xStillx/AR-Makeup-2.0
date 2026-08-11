# AR Makeup — контекст и технические решения

Последнее обновление: 2026-08-11.

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
- Реализованы диагностический camera/ML pipeline, зафиксированный tracking baseline и первый production-срез единого Filament-композитора для матовой помады. Semantic face parsing и полноценная normals/BRDF-модель ещё не добавлены.

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
- Команда `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug` успешно выполнена после исправления ориентации Filament camera stream: 44 unit-теста пройдено, Android lint сообщает `No issues found`.
- Debug APK формируется в `app/build/outputs/apk/debug/app-debug.apk` и проверяется на подключённом Samsung SM-G990B.
- Добавлен Google Filament `1.74.0`: CameraX `Preview.SurfaceProvider` передаёт camera frames непосредственно в Filament `Stream`, поэтому активный preview и помада теперь сводятся в одной GPU scene вместо двух Android View-слоёв. На API 29+ используется синхронизируемый `ACQUIRED` stream через `ImageReader`/`HardwareBuffer` и release-callback Filament; на API 24–28 остаётся copy-free `NATIVE` stream через `SurfaceTexture` без гарантии camera/render synchronization.
- CameraX `TransformationInfo` преобразуется в единую Filament UV→camera texture matrix с учётом crop rect, rotation и front-camera mirror; обратимость transform покрыта unit-тестами. CameraX/MediaPipe используют top-left image origin, а Filament UV и external texture — bottom-left origin, поэтому origin меняется на обеих границах матрицы. На API 29+ OpenGL `ACQUIRED` path дополнительно компенсирует наблюдавшуюся на SM-G990B инверсию обеих display-осей импортированного `HardwareBuffer`; API 24–28 `NATIVE SurfaceTexture` path эту компенсацию не применяет. Camera quad и динамическая lip mesh используют одинаковые Filament UV, поэтому поправка не изменяет координаты трекера и сохраняет совпадение выборки camera color внутри lipstick material с фоном.
- Landmark-контуры губ преобразуются в динамическую GPU mesh: отдельные upper/lower triangle strips, cubic subdivision и восемь поперечных coverage rings. Покрытие равно нулю у кожи и у внутренней границы рта, поэтому полость рта и зубы не входят в геометрию материала.
- Первый Filament lipstick material работает в linear RGB: camera preview переводится через `inverseTonemapSRGB`, оттенок пигмента смешивается с сохранением luminance исходных губ, а matte compression применяется только к ярким участкам. Это foundation для дальнейших normals, lighting и BRDF, а не завершённая физическая модель.
- Первая визуальная проверка Filament-композитора на SM-G990B выявила перевёрнутый camera preview при правильно ориентированной lip mesh. Нормализация top-left/bottom-left origin сама по себе не изменила наблюдаемую вертикальную инверсию, потому что смена UV mesh погасила экранную коррекцию. Отдельная вертикальная acquired-stream компенсация выровняла ориентацию, после чего визуально проявилось оставшееся горизонтальное отражение camera stream относительно landmarks: при движении головы вправо маска смещалась влево относительно изображения. Текущая компенсация отражает обе display-оси только для `ACQUIRED HardwareBuffer` и покрыта regression-тестом; новый debug APK установлен и запущен на SM-G990B, итоговое совпадение ожидает визуального подтверждения. До продолжения shader/BRDF-работ также обязательны проверка crop и новый `gfxinfo`-замер.

Официальный model bundle сохранён в `app/src/main/assets/face_landmarker.task`. Его SHA-256: `64184E229B263107BC2B804C6625DB1341FF2BB731874B0BCC2FE6544E0BC9FF`. BlazeFace, Face Mesh V2 и Blendshape V2 проверены по официальным model cards; все три компонента имеют лицензию Apache 2.0. Детали находятся в `app/src/main/assets/MODEL_LICENSES.md`.

Текущий `FaceMeshOverlay` на Canvas является только диагностическим инструментом для проверки координат и jitter. Он не используется и не будет использоваться для финального макияжа.

Оптимизация responsiveness от 2026-08-10:

- убран искусственный gate «ждать callback, затем ждать следующий camera frame»;
- один кадр обрабатывается, а второй слот постоянно перезаписывается самым свежим кадром; после callback новый inference начинается сразу;
- два RGBA `Bitmap` переиспользуются вместо создания и поворота новых объектов на каждом кадре;
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
- первый compositor принудительно собирает OpenGL material variants. API 29+ использует `HardwareBuffer`, а совместимый API 24–28 fallback — `SurfaceTexture`; Vulkan backend нужно включать только после отдельной проверки обоих external stream путей, цвета и device coverage;
- `NATIVE` fallback на API 24–28 не гарантирует совпадение времени camera texture и render state. Это должно входить в device acceptance; если относительный temporal drift заметен, минимальный поддерживаемый класс production-качества придётся поднять до API 29 либо реализовать отдельную синхронизацию для старых устройств.

Debug APK первого runtime-`filamat` среза имеет размер `105.18 MiB` и содержит универсальные native libraries. Это не релизный size baseline: после host-side компиляции материалов, удаления `filamat-android` и настройки ABI packaging размер нужно измерить заново.

## Зафиксированный стек

Версии ниже проверены на дату обновления файла. Перед фактическим добавлением зависимости нужно закрепить точную версию в version catalog и проверить Gradle sync.

| Задача | Выбор | Причина |
|---|---|---|
| Язык и платформа | Native Android, Kotlin | Прямой доступ к CameraX, GPU, профилировщикам и минимальная лишняя задержка. |
| Камера | CameraX 1.6.1, Camera2 backend | Стабильная версия, единое поведение на разных устройствах, lifecycle, точные настройки FPS и неблокирующий analysis-поток. |
| Геометрия лица | MediaPipe Tasks Vision / Face Landmarker `1.0.0` | 478 3D landmarks, 52 blendshape-коэффициента, матрица трансформации, `LIVE_STREAM`, on-device GPU delegate. |
| Рендер | Google Filament `1.74.0`, первый активный backend OpenGL ES | PBR, линейный/HDR-рендер, custom materials и Android external camera stream; Vulkan будет включён после сравнительной проверки. |
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

1. **Camera + diagnostics — реализовано:** фронтальная камера, единые transforms, FPS/latency overlay, базовый device capability probe; активный preview поступает в Filament через CameraX custom surface.
2. **Face mesh — реализована диагностическая версия:** Face Landmarker в live-stream режиме, синхронизация timestamp и mesh overlay. Стабильность ещё нужно проверить на реальных устройствах.
3. **Вертикальный срез «помада» — первый Filament production-срез реализован:** camera texture и динамическая upper/lower lip mesh сведены в одной GPU scene; есть linear-RGB luminance-preserving pigment, cubic subdivision, мягкое coverage и исключение рта/зубов. Остаются device runtime calibration, semantic lip refinement, face normals/lighting, полноценные BRDF-профили matte/satin/gloss и калибровка на разных губах/освещении.
4. **Temporal quality — частично реализовано:** зафиксированный adaptive tracking baseline, отдельный быстрый канал общего translation, sensor-based frame timestamp, bounded capture alignment, 42-мс render-only extrapolation window, 16-мс smoothstep continuity correction между delivered frames и удержание при dropout; остаются визуальная device/video calibration, компенсация rotation/scale и стабилизация будущих semantic masks.
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
- CameraX releases: https://developer.android.com/jetpack/androidx/releases/camera
- CameraX ImageAnalysis: https://developer.android.com/media/camera/camerax/analyze
- Google Filament: https://github.com/google/filament
- ARCore Augmented Faces: https://developers.google.com/ar/develop/augmented-faces
