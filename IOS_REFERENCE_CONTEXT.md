# iOS reference — устройство проекта и ориентиры переноса

Изучено 2026-09-04. Источник: `C:\Users\User\Desktop\ios makeup\virtual-makeup-main`. В предоставленной копии нет Git-метаданных. Это статическое исследование состава проекта, основных вызовов и реализации материалов, а не запуск iOS и не подтверждение визуального паритета. Номера строк относятся к этой копии; контрольные суммы в конце позволяют проверить её актуальность.

## Последняя задача пользователя

После первого переноса сатин был слишком ярким. После изменения Android-композитинга на linear light результат «стал чуть ближе, но всё ещё не тот». Сравнение с тем же **Dior Rouge Dior Satin 999, #B8202D, high, creamy**; скриншот iOS пользователь предоставить не может. Результат Android не принят.

Пользователь поручил сначала изучить весь iOS-проект и сохранить контекст. **В рамках этого этапа менять только документацию; код, зависимости, assets и настройки приложения не менять, сборку/установку не выполнять.** Следующая реализация — после новой команды.

Зафиксированы три требования дальнейшей работы:

1. Приблизить сатин к одобренному iOS по реальному пути от камеры до экрана, без угадывания коэффициентов яркости.
2. Добавить небольшое размытие/мягкий переход на краях помады для реалистичности; сохранять фактуру губ и плотность центральной области, не создавать мерцание и широкий ореол на коже.
3. Устранить незакрашенную полоску между верхней и нижней губой. Не заливать реальное отверстие рта и зубы; проверить сомкнутый и открытый рот, разговор и поворот головы.

Это отдельная задача мягкости/непрерывности покрытия. Она не отменяет отрицательные результаты прежнего поиска видимого контура, не разрешает возвращать anchor/live-mesh эксперименты и не возвращает новые ML-модели в scope. Принятый Android exact-pair Sync сохраняется.

## Карта проекта

В `Virtual Makeup/` находятся 12 Swift-файлов:

- `Virtual_MakeupApp.swift`: SwiftUI entry point → `ContentView`.
- `ContentView.swift`: 13 карточек продуктов, оттенки, параметры finish/density/texture, выбор помады/румян, интенсивность, просмотр оригинала. Это локальный каталог, не серверная интеграция.
- `FaceTrackingView.swift`: мост SwiftUI → ARSCNView, lifecycle, viewport/orientation, передача выбранного стиля координатору.
- `FaceTrackingCoordinator.swift`: ARSession/renderer/MediaPipe delegates, временные контексты, преобразование камеры, принятие/фильтрация точек, привязка глубины, компенсация позы/мимики, очередь текстуры, освещение и сбросы.
- `LipModels.swift`: контур и 80 mesh points, UV, AR surface bindings, poses/expressions, aperture visibility, общие параметры внешнего feather/carrier.
- `CanonicalLipGeometry.swift`: списки landmark indices, фиксированные UV/XYZ и треугольники губ; отдельные треугольники закрытия внутреннего отверстия.
- `MetalLipColorCompositor.swift`: **основная формула цвета всех finish**, камера → canonical texture, аналитическая маска, GPU Gaussian alpha blur, выдача отдельных изображений цвета и прозрачности.
- `LipTextureRenderer.swift`: выбор Metal или CPU fallback, хранение стиля и состояния CPU relief, аварийная процедурная текстура. Наличие старых формул здесь не означает их использование в обычном Metal-пути.
- `LipMeshRenderer.swift`: размещение и отображение текстуры в SceneKit, screen-locked геометрия с AR depth, opacity/freshness, диагностические линии и depth occluder. Это не главный источник формулы сатина.
- `LipstickTypes.swift`: finish, категории продуктов, плотность, тип текстуры, sRGB/OKLCH.
- `BlushRenderer.swift`: отдельный SceneKit-эффект румян на AR face geometry.
- `LipDiagnostics.swift`: throttled logging и FPS/work-time counters; сообщения переданы через autoclosure и формируются после проверки интервала.

Другие части: CocoaPods/Xcode workspace/project/scheme, Info.plist, asset catalog, `face_landmarker.task`, `face_model_with_iris.obj`, отдельный `ml-training/`. Сохранённых PNG/JPG-эталонов, записей одобренного сатина и готового CoreML segmentation artifact в копии нет. Продуктовых renderer для eyeliner/eyeshadow/lip liner в этой копии нет.

## Камера, координаты и время

`FaceTrackingView` создаёт ARSCNView: непрерывный render, preferred 60 FPS, `automaticallyUpdatesLighting=false`, новая SceneKit scene. `Coordinator.startSession` (181) проверяет поддержку ARFaceTrackingConfiguration, включает light estimation, выбирает максимальный FPS доступных TrueDepth video formats, затем максимальное разрешение при этом FPS. Указанные в комментариях FPS/латентности не являются измерениями текущего Android.

`session(_:didUpdate:)` (403) сохраняет capturedImage, timestamp, displayTransform, AR anchor/geometry/camera, viewport revision и tracking epoch. Поток MediaPipe имеет один active request и latest pending frame; текстуры — отдельную serial queue с одним pending replacement. NSLock защищают общие состояния; вычисления уходят с renderer callback. Watchdog callback 0.30 s, accepted source/result age ≤0.25 s; старые epoch/anchor/viewport/timestamp/engine callbacks отклоняются.

**Для материала используется уменьшенный BGRA-вход MediaPipe, а не исходное полноразмерное ARKit изображение.** Цепочка:

1. `makeMediaPipeInputBuffer` (2916): CIImage из capturedImage → ориентация → нормализация extent → downscale длинной стороны до **512** → CVPixelBuffer 32BGRA через CIContext, выходной colorSpace DeviceRGB.
2. `submitLiveStreamFrame` (859–883) сохраняет именно preparedInput.pixelBuffer в PendingLiveFrame.
3. `lipDetection` (1351–1400) возвращает этот buffer; принятый request текстуры (1286) использует detection.pixelBuffer.
4. `mappedLipLandmarks`/`mappedLipMeshPoints` (3000/3046): normalized MP → inputToCapturedImageTransform → capturedImageToViewportTransform → viewport points. Material sourceUV строится обратным aspect-fill из screen points к размеру BGRA-входа (`Metal…makeVertices`, 1277).

MediaPipe: bundled face_landmarker.task, liveStream, один face, confidence 0.48 для detection/presence/tracking, transformation matrices включены, MP blendshapes выключены. GPU delegate с CPU fallback при создании (`Coordinator` 546). ARKit blendshapes при этом используются отдельно.

iOS **не соответствует Android Sync по способу представления**: фон ARSCNView остаётся живым, canonical texture/последний принятый контур переиспользуются и переносятся текущей позой/мимикой. Android показывает сохранённую точную пару и не должен получать iOS live prediction поверх неё.

## Геометрия, фильтры, смыкание

Внешний и внутренний контуры имеют по 20 точек, attention mesh — 80. Внешние/внутренние indices совпадают с Android; структура промежуточной сетки различается. `CanonicalLipGeometry.normalizedUV` (491) использует bounds x=0.381974, y=0.262981, width=0.236052, height=0.086615 и padding x=0.08/y=0.12. Простая прямоугольная lip UV и эти canonical UV не взаимозаменяемы.

`lipMeshTriangles` (292) явно равен checked-in `fallbackLipMeshTriangles`; OBJ не выбирает рабочую топологию. В файле остался OBJ loader. `innerFillTriangles` (298) добавляет 18 треугольников между внутренними верхней/нижней дугами. Они **добавляются в SceneKit mesh** (`LipMeshRenderer` 1147–1159); **Metal color raster использует только annulus** (`Metal…makeIndices`, 1601). Цвет в разрешённую переходную область отверстия восстанавливает compute-проход из соседних texels. Поэтому перенос одних fill triangles или одной alpha-маски не воспроизводит всю связку.

Принятие MP обновления проверяет пригодность контура/матрицы, скачки формы и topology. Adaptive smoothing сначала выравнивает прошлый контур текущей AR позой (3091); крупные изменения формы идут по raw route (3202). AR surface carrier хранит barycentric/depth bindings; свежие MP XY могут публиковаться с временно удержанной глубиной. `currentLipMeshState` (4645) проверяет epoch/anchor/viewport, возраст/texture generation, затем компенсирует rigid pose. При покое остаётся rigid-only; при активной мимике приоритет AR expression delta; иначе ограниченная surface residual или краткий MP velocity prediction. Это реально вызываемые ветки, не рекомендуемый перенос в Android.

Границы удержания из кода: contour source age 0.82 s, freshness fade по времени без принятого обновления 0.40–0.62 s; carrier hold 0.50 s, depth-only hold 0.20 s. Есть ограничение expression prediction 45 ms/2 viewport points и rigid display lead 20 ms/3.5 points. Они не являются acceptance budgets Android. `softenedCupidsBow` (4858) сдвигает только точку 0 к середине 37/267 на 28% с cap 0.35–0.90 viewport points; это геометрическая коррекция, отдельная от blur.

Рабочая SceneKit mesh (`makeGeometry`, 388) берёт MP screen XY и AR depth с lift 0.00150 m; `screenLockedScenePoint` (538) unproject-ит эти XY на глубину carrier. Для липматериала чтение/запись depth выключены; наличие отдельного AR depth occluder само по себе не защищает губы от окрашивания рта.

Отверстие: `LipContour.innerApertureVisibility` — smoothstep(effectiveInnerOpeningRatio; 0.018, 0.055). Opening измеряется по размаху inner contour относительно ширины рта. При принятии результата (`Coordinator` 1222) rendered opening = 0 при ARKit confidentlyClosed, иначе max(MP opening, AR opening ×0.85). Closed condition (`LipModels` 879): jawOpen <0.055 и geometric opening <0.005 + clamp(smile)×0.020. Это ARKit-сигнал, не доказанный эквивалент текущих Android/MP scores; нельзя механически подставить другой score.

## Продуктовые варианты и цвет

Независимые поля: finish matte=0/gloss=1/satin=2; density low=0.54/medium=0.78/high=0.92; texture creamy/mousse/liquid. `opacityScale` CPU = coverage/0.92. Категории lipstick/oil/tint/gloss существуют как metadata; все текущие карточки оставляют default productType=lipstick, даже названные Lip Gloss. Отдельной shader-ветки oil/tint нет.

Каталог (`ContentView`, 69–311):

- Guerlain Rouge G Satin: satin/medium/creamy, 131 C3776A, 518 D26B6B, 06 AF5655.
- Dior Rouge Dior Satin: satin/high/creamy, 999 B8202D — текущий эталон.
- Guerlain Rouge G Velvet: matte/high/creamy, 770 AC2936, 360 A35B4C.
- MAC Powder Kiss: matte/high/mousse, Burning Love 7E1331, Marrakeshmere 782C24.
- MAC Satin Lipstick: satin/medium/creamy, Brave B44255.
- Clarins Satin Lipstick: satin/medium/creamy, 705S A34545.
- Clarins Lip Gloss: gloss/low/liquid, 01 E39294.
- Dior Lip Gloss Addict: gloss/low/liquid, 006 A74C89, 012 BC6065.
- Dior Lip Gloss: gloss/low/creamy, 040 A52D3C, 004 EB8571.
- Shik Lip Gloss Care: gloss/low/liquid, 08 371A12.
- Make Up Forever Lip Gloss: gloss/low/liquid, 06 BE7C6E.
- Shik Total repair Balm: gloss/low/liquid, 01 F7C8BE, 02 AF584F, 05 2E0201.
- Love Generation Lip Gloss Wet Dream: gloss/low/liquid, 06 B0453D.

Первый запуск UI выбирает первый Guerlain, а не Dior. lipstickOpacity=1.0; в mesh ограничивается до 0.99. «Оригинал» передаёт opacity=0 обоим эффектам. Переключение инструмента выбирает панель, оба renderer могут работать одновременно. Румяна по умолчанию opacity=0.55.

LipstickColor хранит sRGB hex → RGB/255. OKLCH вычисляется для описания/логирования, а не используется как рабочее цветовое пространство Metal. Текстурные множители из `LipstickTypes`:

- creamy: detail 0.92, highlight 1.32, limit 1.0, concentration 1.15;
- mousse: 0.80, 0.40, 0.56, 1.35;
- liquid: 0.94, 1.65, 1.25, 1.85.

## Основной Metal-путь

`LipTextureRenderer.makeTexture` (507) сначала вызывает Metal. В текущем Coordinator lowLatency=true: **224×112**; альтернативный размер 256×128. Комментарий о 128×64 в Coordinator устарел. Камера подключается через CVMetalTexture BGRA8Unorm; render targets RGBA8Unorm. Shader выполняет pigment/detail расчёты над этими значениями без явной sRGB linearization.

`lip_fragment` (217) формирует camera-conditioned corrective RGB и coverage:

- Базовая luma = dot(RGB, 0.299/0.587/0.114). Низкая частота — центр с весом 4 плюс 8 соседей на радиусе 5 texels, сумма /12. Surround для бликов — 4 отсчёта ±12 по X и ±8 по Y. **Texel здесь относится к уменьшенному 512-long-edge BGRA**, не к экрану/полному camera texture.
- Catalogue RGB задаёт pigment hue. Локальный логарифмический контраст камеры передаёт складки/тени; brightScene ослабляет detail. Satin shadow/highlight отличаются от matte/gloss. Finish parameters (`parameters`, 883): matte 1.9/0/0; satin 1.7/1.16/0.48; gloss 1.9/1.05/0.20 для detail/highlight/max, далее множители texture.
- Satin/gloss усиливают реальные camera highlights, с разными порогами и концентрацией. Corner/seam suppression и tooth-like bright/low-saturation guard ограничивают блик. Это не полноценная BRDF с геометрическим источником света и не статичные белые фигуры в UV.
- Low-density gloss — особый режим: lowDensityGloss=1-smoothstep(0.60,0.72,coverage); target дополнительно смешивается с натуральными губами на 0.60×этот factor, output coverage идёт к 0.45. Нельзя получать его только снижением общей opacity сатина.
- Компенсация camera base: correctiveRGB=clamp((targetPigment - blurredCamera×(1-a))/a). Для high a=0.92. Для других плотностей базовое a=mix(coverage,0.92,0.55), кроме специальной gloss-ветки. Выходная alpha=outputCoverage×cornerOpacity; mask и пользовательская opacity применяются дальше.

Свет: ARKit ambientIntensity /950, при normalized≥0.90 neutral=1; ниже response 0.45 + (normalized/0.90)^0.72×0.55, temporal tau 0.40 s/hysteresis 0.020 (`Coordinator` 499). В Metal uniform clamp 0.5–1; combinedLighting=clamp(mix(uniform, localLighting,0.25),0.45,1), localLighting=smoothstep(0.10,0.55,blurredLuma). Это влияет на detail adaptation; catalogue RGB не умножается напрямую на global dimmer.

**Исправление прежнего вывода:** `Coordinator` 321–326 передаёт в `LipMeshRenderer.updateLightingFactor` значение **1 при доступном Metal**. Множитель SceneKit 0.90–1.0 применяется только в CPU fallback. Комментарии fragment о применении SceneKit света «к обоим путям» расходятся с действительным call site. Нельзя объяснять яркий Android отсутствием постоянного iOS ×0.90.

После маски/blur GPU command buffer ожидается **на textureQueue**, затем есть CPU getBytes/readback и создание CGImage. Это не zero-copy pipeline; его ожидание/readback не переносить в Android GL thread. Diffuse CGImage: sRGB + noneSkipLast (RGB без alpha), opacity CGImage: sRGB + last; один provider с двумя представлениями байтов (`Metal` 1158–1217).

SceneKit material: constant, blendMode alpha, transparencyMode aOne, double-sided, RGB diffuse + отдельный alpha transparent channel, linear min/mag, mipFilter none, clamp wrap (`LipMeshRenderer` 148–175/272). Effective transparency=requestedOpacity×maskVisibility. Отдельные RGB/alpha избегают повторного применения прозрачности к цвету.

sRGB-tagged CGImage и настройки материала подтверждены статически. Полный device color-management путь AR background → SceneKit render target → экран из этих файлов не измерен. Уже добавленный Android linear blend — текущая гипотеза переноса, а не доказанное точное воспроизведение всей цепочки iOS.

## Мягкий край и непрерывность внутреннего покрытия

Основной путь: color raster → `lip_composite` (492) → Gaussian alpha horizontal/vertical (677) → separate diffuse/opacity images → расширенная SceneKit carrier mesh.

Внешний край задаётся signed distance в canonical texture pixels. UV polygon остаётся по 20 исходным точкам (subdivisionPasses=0); нижнее смещение SDF выключено: lowerCoverageExtensionPixels=0 (`makeOuterDistancePoints`, 1355). Общие `LipOuterFeatherLayout` (LipModels 585): canonical carrier distance 0.052 texture-height units, aspect=2, interiorTransitionPixels=**8**, exteriorTransitionPixels=4. Комментарий «one-pixel rim» устарел. Значения масштабируются на textureHeight/96: для 112 high это ≈9.33 и 4.67 texels, **не пиксели экрана**.

На внешнем contour alpha-mask=0.68; smoothstep внутри от 1 до 0.68 и снаружи от 0.68 до 0. Размер видимого перехода зависит от проекции UV в screen space. Carrier расширяется вдоль нормалей, не простым radial scale; screen carrier margin clamp(lipWidth×0.03,3.5,5.0) viewport points, camera sampling margin min(lipWidth×0.075,10). UV расширение обоих путей согласовано, чтобы край polygon не обрезал прозрачный хвост.

Внутренний край асимметричен: **на стороне губы alpha не ослабляется**, переход расположен внутрь aperture. При openingBlend o: solid reach=1.45×(1-o)×height/96; transition=(0.55-0.20×o)×height/96. В узком отверстии покрытие с двух сторон перекрывается; при открытом рте сохраняется только короткий переход. В допустимой области compute восстанавливает пустой color texel из strongest-alpha соседа в окне 9×9, не расширяя pigment на всё отверстие. Далее fill triangles SceneKit дают поверхность для показа этой маски.

Gaussian — два separable 13-tap прохода, radius=6 canonical texels, strength=1. Веса: 0.00735029, 0.01909834, 0.04171460, 0.07659181, 0.11821653, 0.15338247, 0.16729190 и симметрично назад. **Размывается только alpha; RGB и camera detail остаются.** Финальная alpha=min(blurredAlpha, outerBlurGate×apertureFeather). Gate сохраняет прозрачность реального рта и ограничивает внешний хвост. Это важнее буквального переноса «6 пикселей» на экран Android.

## CPU fallback и неактивные заготовки

Metal init unavailable или три последовательных runtime failure отключают Metal (`recordRuntimeFailure`, 1579). При единичном nil пока Metal доступен кадр пропускается; CPU loop не запускается автоматически на каждый сбой.

CPU camera path 128×64 при lowLatency, 160×80 иначе: canonical barycentric sampler, analytic alpha, camera tone/detail + temporal tone map, density/corner opacity, corrective premultiplied RGBA. Это **другая реализация**, не численно тождественная Metal. Low-density gloss здесь naturalLipWeight=0.52 и coverage=0.82, вместо Metal 0.60/0.45. CPU texture refresh после первой текстуры ограничен сменой стиля/стабильного состояния рта (`shouldSubmitStableTextureRender`, 2777); не обновляется на каждом принятом кадре как Metal.

`usesStableProceduralLipstickRenderer=false` (2053): постоянный procedural path выключен. Но `makeFallbackRGBA` достижим при слишком малом числе painted pixels в CPU camera path. Его `finishColor` и UV gloss shapes — аварийная ветка, не обычный одобренный Metal-сатин. CPU `edgeSoftenedRGBA` и `makeAlphaMask` не имеют call sites; второй CPU blur не применяется. AR projection helper/debug geometry ниже основного mesh-кода не нужно принимать за рабочий screenLockedScenePoint.

В Swift runtime не обнаружены MPSImageGaussianBlur/отдельный MetalPerformanceShaders path: актуальный blur — собственные Metal compute kernels. Ранние описания «Metal/MPS» слишком общие.

## Румяна, сборка, assets и ML tooling

`BlushRenderer.render` (63) каждый раз строит две cheek mesh из ARFaceGeometry, отбирая треугольники по cheek score; shader constant/alpha без depth checks. Генерируемая 256×180 UIImage содержит эллиптический radial gradient плюс слабый светлый центр. Четыре UI оттенка; opacity cap 0.85. Здесь нет lip-style camera relief, segmentation кожи или общей full-face material системы. Старые helper для cheek placement присутствуют, но рабочий render вызывает makeCheekMesh.

Podfile задаёт iOS15, Podfile.lock фиксирует MediaPipeTasksVision/Common **0.10.35**, CocoaPods1.16.2. Xcode app target реально имеет iOS deployment18.0 и Swift5.0; один application target с filesystem synchronized group. Workspace ожидает Pods; сами Pods в копии отсутствуют. Перечень macOS/xros в build settings не доказывает поддержку ARFaceTracking на этих платформах. Info.plist содержит camera purpose string. В просмотренном приложении нет сети, загрузки пользовательских кадров или сохранения фото; это характеристика этой копии, не privacy-аудит всех платформенных SDK.

`ml-training/` — отдельный **не подключённый** прототип scratch-only ROI segmentation: PyTorch depthwise encoder/decoder, RGB192×96 → logits1×4×96×192 (background/upper/lower/inner mouth), CE+Dice/AdamW, foreground IoU, split по subject hash 80/10/10, augmentation, подготовка ROI из масок и manifest validation. Export CoreML FP16 mlprogram min iOS17. Есть smoke_test.py, но в этой задаче он не запускался. Нет обученных weights/datasets/exported model; README фраза «model used by Virtual Makeup» не соответствует IOS_INTEGRATION.md и Swift runtime, где модель не подключена.

Policy/manifest требуют происхождение/коммерческие права/согласие; автоматическая проверка полей не является доказательством лицензии. Наличие этого прототипа не отменяет запрета пользователя на новые модели/обучение. Никакие iOS model/assets из этой задачи в Android не добавлялись.

## Подтверждённые различия с текущим Android

- Формула satin перенесена, но sampling scale различен: Android векторы через camera.textureIntrinsics + inverse UV (`ArCoreFaceAnchorRenderer` 383) относятся к GPU camera texels; iOS — к downscaled BGRA512. До настройки блика/рельефа надо согласовать пространственный масштаб, ориентацию и предварительную фильтрацию exact frame.
- Android сразу рисует в viewport из paired camera; iOS сначала rasterizes 224×112 canonical texture и затем фильтрует её в SceneKit. Это влияет на частоты detail и мягкость.
- Android пока использует прежнюю coverage mesh. `LipMeshTessellator.coverageRings` (225) = [0,edge,mid,core,core,mid,edge,0]; alpha падает к нулю на обоих краях каждой губы. Дополнительно `semanticRefinedCoverage` (`ArCoreFaceAnchorRenderer` 1531) применяет innerGuard, зависящий от mouth openness/confidence. В iOS lip-side inner alpha остаётся плотной, плюс есть fill triangles и aperture-only feather. Эти механизмы — **обоснованные кандидаты причины полоски, не доказанная по снимку локализация дефекта**.
- iOS маска/blur/carrier не перенесены; один smoothing коэффициент Android не заменяет эту цепочку. Нельзя просто расширить весь pigment или закрасить отверстие.
- Android имеет 40 reference UV knots и свою tessellation; iOS 80 actual points/другую topology. Геометрические фильтры и lighting/input pipeline различаются. Полный port tracking не нужен для текущей задачи материала.
- ARCore light estimation в launcher DISABLED; neutral uniform=1 в Android сохраняется. iOS в обычном Metal-пути имеет ambient-dependent detail, но **не** blanket ×0.90 material dimming.
- Цвета/экспозиция камер разных устройств и фактический SceneKit color management остаются непроверенными источниками разницы. Без iOS screenshot/device capture нельзя заявлять, что конкретная поправка доказанно решит жалобу.

## Следующая работа после команды на реализацию

Сначала опираться на перечисленные различия: согласовать входные условия satin (масштаб camera samples/цветовая цепочка), затем воспроизвести мягкую alpha-маску и непрерывный внутренний край вместе с необходимой геометрией. Требования к проверке: тот же B8202D и intensity, неподвижные/сомкнутые губы, разговор/открытый рот, поворот; отсутствие белой полоски, окрашивания зубов, ореола и мерцания, сохранение принятого Sync. Тяжёлый LipFrameTrace выключен. Debug/device проверка — когда начнётся реализация, unit-тесты только по отдельной команде.

В этом исследовании код приложения, iOS-reference, сборка и установка не менялись. Существующие незакоммиченные изменения сатина оставлены как были; commit/push не выполнялись.

## Контрольные суммы источника

SHA-256 ключевых файлов предоставленной копии; ссылки ниже ведут в локальный reference. Фотографии/модели в Git Android этой документацией не добавляются.

- [BlushRenderer.swift](<C:/Users/User/Desktop/ios makeup/virtual-makeup-main/Virtual Makeup/BlushRenderer.swift>): `f5fdd6b62816a28d4d532ccfa014ec29a9383fd9f684d0299275152828b50609`.

- [CanonicalLipGeometry.swift](<C:/Users/User/Desktop/ios makeup/virtual-makeup-main/Virtual Makeup/CanonicalLipGeometry.swift>): `000ca28eded1b2f1bf534f741c20e4ec235eeb8b58245f70a3cc8c63b53cff56`.

- [ContentView.swift](<C:/Users/User/Desktop/ios makeup/virtual-makeup-main/Virtual Makeup/ContentView.swift>): `1c62c104c5b9bd4d7d0479a5e8194246fbeace64152906157d68320fd42e5b56`.

- [FaceTrackingCoordinator.swift](<C:/Users/User/Desktop/ios makeup/virtual-makeup-main/Virtual Makeup/FaceTrackingCoordinator.swift>): `6c28969fb185e6ac368b35db14064f7ad907671770488083831c6e2cb445df94`.

- [FaceTrackingView.swift](<C:/Users/User/Desktop/ios makeup/virtual-makeup-main/Virtual Makeup/FaceTrackingView.swift>): `2f001766a5dd53354a518173084501bce9569581c5ef55a895162ce04df93f18`.

- [LipDiagnostics.swift](<C:/Users/User/Desktop/ios makeup/virtual-makeup-main/Virtual Makeup/LipDiagnostics.swift>): `d7645953d5f823e233a62ce680cd69801cdb3bb8521f161405d9be055d82590e`.

- [LipMeshRenderer.swift](<C:/Users/User/Desktop/ios makeup/virtual-makeup-main/Virtual Makeup/LipMeshRenderer.swift>): `1dbc1b04865d5e96c0eb231d6e15fe3e755d2ea13a37b5fd3a2c3d64bf4ad1ea`.

- [LipModels.swift](<C:/Users/User/Desktop/ios makeup/virtual-makeup-main/Virtual Makeup/LipModels.swift>): `de6782ef832aab1921ee21544a96c3f1e4938b7c941283ed4eb4146b1800efc5`.

- [LipstickTypes.swift](<C:/Users/User/Desktop/ios makeup/virtual-makeup-main/Virtual Makeup/LipstickTypes.swift>): `a5d5de84184914df0055107783bb2b08f3723bef28e870a280ae226fda012e4b`.

- [LipTextureRenderer.swift](<C:/Users/User/Desktop/ios makeup/virtual-makeup-main/Virtual Makeup/LipTextureRenderer.swift>): `2d3f3b3012a0a703f293d56d42292271ebb905f45a1cc8e4e99d292470c091cc`.

- [MetalLipColorCompositor.swift](<C:/Users/User/Desktop/ios makeup/virtual-makeup-main/Virtual Makeup/MetalLipColorCompositor.swift>): `d07a655dfd47417658c5564fa64e900071224b4b83bbd9059e4f7cf185053d05`.

- [Virtual_MakeupApp.swift](<C:/Users/User/Desktop/ios makeup/virtual-makeup-main/Virtual Makeup/Virtual_MakeupApp.swift>): `de577c94eb5bd8ce8b25937cb934649f41503c8c29975aa9d8b50450c16e11f5`.
