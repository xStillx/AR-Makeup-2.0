# AR Makeup — лицензии и коммерческий release gate

Актуально на 2026-09-08. Это инженерный реестр, не юридическое заключение. Перед коммерческим релизом нужен review профильного юриста в целевых юрисдикциях.

Обновлять до добавления или замены dependency, SDK, модели, weights, датасета, разметки, шрифта, изображения, текстуры, бренда или другого внешнего asset.

## Статус

- Публичный коммерческий релиз пока не готов по документам.
- Собственный Kotlin/C++/GLSL код планируется как proprietary; правообладатель, передача прав, EULA и distribution notice не оформлены.
- Активный ARCore/OpenGL path и legacy CameraX/Filament/native Vulkan rollback входят в Gradle graph; их лицензии остаются до фактического удаления из release artifact.
- Face processing выполняется on-device, но MediaPipe Tasks имеет отдельный metrics/privacy gate.

## Текущий реестр

Источник версий — `gradle/libs.versions.toml`, состава — `app/build.gradle.kts`.

### Android/Kotlin

- AndroidX: Core `1.19.0`, AppCompat `1.7.1`, Activity `1.13.0`, ConstraintLayout `2.2.2`, CameraX `1.6.1`; Material Components `1.14.0`; Kotlin runtime.
- Основной license family: Apache License 2.0. Перед релизом собрать exact transitive graph, copyright, LICENSE и NOTICE.

### MediaPipe Tasks Vision `1.0.0`

- `com.google.mediapipe:tasks-vision:1.0.0`; repository — Apache License 2.0. Exact AAR, native/transitive components и NOTICE требуют release audit.
- Privacy Notice: input обрабатывается on-device и не отправляется Google, но Tasks APIs отправляют Google performance/utilization metrics. Приложение отвечает за informed consent, если он требуется законом.
- До релиза подтвердить telemetry/network поведение pinned Android runtime и подготовить disclosure/consent, Privacy Policy и Data Safety.

### Bundled Face Landmarker

- Единственный ML asset: `app/src/main/assets/face_landmarker.task`; BlazeFace Short Range, Face Mesh V2 и Blendshape V2.
- Model cards указывают Apache License 2.0. Source и cards: `app/src/main/assets/MODEL_LICENSES.md`.
- SHA-256: `64184E229B263107BC2B804C6625DB1341FF2BB731874B0BCC2FE6544E0BC9FF`. Перед релизом повторить hash/model-card/NOTICE audit shipped asset.

### Google ARCore `1.54.0`

- `com.google.ar:core:1.54.0`; Gradle `.aar` регулируется ARCore Additional Terms, а не автоматически Apache 2.0.
- Требуются Google APIs/ARCore Terms, prominent privacy disclosure и уведомление в Terms приложения об ARCore functionality, Google Terms и Google Privacy Policy.
- Используются только локальные Augmented Faces; Cloud Anchors, Geospatial API и API key не подключены. Повторно проверить release artifact/runtime traffic.
- Нужны ARCore-certified device check, Google Play Services for AR handling и unsupported-device/fallback UX.

### Filament, Vulkan и toolchain

- `filament-android` и `filamat-android` `1.74.0`: Apache License 2.0; пока нужны legacy rollback. После host-side material compilation удалить `filamat-android` и обновить реестр.
- NDK `29.0.14206865`, CMake `3.31.6`, C++20 и `glslc`: учесть Android SDK/NDK/toolchain notices и packaged native libraries по ABI.
- Vulkan driver — системный компонент; приложение распространяет собственный native код и compiled shaders.

### Test/build-only

- JUnit `4.13.2`: Eclipse Public License 1.0. AndroidX Test JUnit `1.3.0` и Espresso `3.7.0`: test-only.
- Android Gradle Plugin `9.3.1`, Android SDK/NDK, CMake и `glslc` регулируются соответствующими agreements/notices.
- Release audit должен доказать отсутствие test/debug dependencies, recorders и diagnostics в APK/AAB.

## Будущие модели и assets

По решению пользователя от 2026-09-04 новые ML-модели исключены из текущего scope, включая MediaPipe Selfie Multiclass Segmenter, другие pretrained models и собственное обучение/дообучение. Существующий bundled Face Landmarker сохраняется. Это ограничение плана и ресурсов, а не вывод о запрете коммерческого использования любых готовых моделей.

Следующий gate сохраняется только для случая, если пользователь новой явной командой разрешит вернуться к новым моделям. Сам по себе положительный лицензионный результат не меняет scope. До добавления внешней или собственной ML-модели нужны:

- exact name/publisher/version/source/SHA-256, license, model card и NOTICE;
- commercial rights на code, weights, teacher checkpoints, изображения, labels и ML-training use;
- consent/privacy/biometric review для данных лиц;
- права на synthetic generators/assets и итоговые weights;
- mobile latency/size benchmark и telemetry/privacy review.

Запрещены без письменного разрешения `non-commercial`, `research only`, неизвестные лицензии и непрослеживаемые права.

Отдельно проверяются fonts, icons, textures/HDRI, фото/видео, каталог/упаковка косметики, логотипы, товарные знаки, marketing names, beauty SDK и cloud API. Open-source license не даёт право использовать товарный знак как знак одобрения продукта.

## Gate нового компонента

1. Зафиксировать primary source, publisher, version и SHA-256.
2. Сохранить license/model card/NOTICE и проверить commercial use, redistribution, attribution, patents и trademarks.
3. Проверить transitive/native dependencies; для ML — code, weights, data, labels, teachers и права на результат.
4. Для данных лиц выполнить consent/privacy/biometric review целевых стран.
5. Обновить этот файл и `MODEL_LICENSES.md`; архитектурное изменение — также `PROJECT_CONTEXT.md`.
6. Не добавлять компонент в production graph при неизвестном обязательном источнике или праве.

## Release checklist

- [ ] Правообладатель, передача прав, proprietary EULA/Terms и root distribution notice.
- [ ] `THIRD_PARTY_NOTICES`, полные licenses/NOTICE и экран Open source licenses.
- [ ] SBOM и архив exact dependency/model/hash reports release artifact.
- [x] Source и SHA-256 текущего `face_landmarker.task` зафиксированы.
- [ ] Release audit AAR, native/transitive dependencies, models и assets.
- [ ] Privacy Policy, camera/MediaPipe metrics disclosure/consent и Google Play Data Safety.
- [ ] ARCore notice/Terms legal review, device support и fallback UX.
- [ ] Фактический network/telemetry audit release APK.
- [ ] Права на product images, fonts, icons, textures и brands.
- [ ] Test/debug code и dependencies отсутствуют в release artifact.
- [ ] Финальный юридический review до публичного запуска.

## Первичные источники

- Apache 2.0: https://www.apache.org/licenses/LICENSE-2.0.html
- MediaPipe license/privacy: https://github.com/google-ai-edge/mediapipe
- Face Landmarker: https://ai.google.dev/edge/mediapipe/solutions/vision/face_landmarker
- AndroidX: https://github.com/androidx/androidx
- Material Components: https://github.com/material-components/material-components-android
- Kotlin: https://github.com/JetBrains/kotlin
- Filament: https://github.com/google/filament
- ARCore Terms: https://developers.google.com/ar/develop/terms
- ARCore binary/source boundary: https://github.com/google-ar/arcore-android-sdk/blob/main/LICENSE
- Google Play Data Safety: https://developer.android.com/privacy-and-security/declare-data-use
- Android SDK Terms: https://developer.android.com/studio/terms
- JUnit license: https://github.com/junit-team/junit4/blob/main/LICENSE-junit.txt


## Banuba beauty-ios — только внешний reference

Публичный `Banuba/beauty-ios` исследован на commit `79e41745638c46788e4faba95dd68114a3f3094a`; корневой sample code опубликован под MIT. Сам Banuba Face AR SDK, client token, бинарные библиотеки и генерируемые SDK masks `LIPS`/`LIPS_SHINING` регулируются отдельной коммерческой лицензией и в проект не добавлены. Новых dependency, моделей, shaders, textures и assets из Banuba в текущем checkpoint нет.

Общий принцип camera-value color transfer реализован самостоятельно в GLSL на существующем ARCore/MediaPipe pipeline: стандартные RGB↔HSV функции и смешивание написаны в проекте без буквального или существенного переноса Banuba-кода. Из reference использован только алгоритмический принцип hue/saturation от пигмента, value от камеры и опубликованный нормализующий коэффициент 0.85. Banuba SDK, бинарники, token, модели, masks, shaders и assets в проект не добавлены.

## Происхождение переноса сатина — 2026-09-04

IosSatinLipMaterial.kt адаптирует формулы MetalLipColorCompositor.swift, параметры LipstickTypes.swift и нормализованные UV-константы CanonicalLipGeometry.swift из предоставленного пользователем локального iOS-проекта virtual-makeup-main. Файлы iOS не изменены. Новые модели, datasets, texture/mesh assets, SDK и зависимости не добавлены; заимствованные данные здесь — константы координат материала в коде, не новая лицевая mesh. Hash исходника и точный локальный путь зафиксированы в PROJECT_CONTEXT.md. Перед release происхождение/права на этот код и UV-константы входят в существующий аудит собственного кода; перенос не означает отдельного завершённого юридического release gate.
