# AR Makeup — лицензии и коммерческое использование

Статус: живой инженерный реестр, создан 2026-08-17.

Этот документ является единым актуальным реестром лицензий проекта. Его нужно обновлять **до** добавления новой runtime/build зависимости, ML-модели, pretrained/teacher weights, датасета, разметки, canonical mesh/UV, текстуры, шрифта, иконки, фотографии, брендового материала или внешнего SDK.

Это инженерный аудит первичных источников, а не юридическое заключение. Перед коммерческим релизом итоговый пакет должен проверить профильный юрист в юрисдикциях распространения приложения.

## Базовое решение

- Текущий Android/MediaPipe/Filament стек не требует покупки отдельной коммерческой лицензии или выплаты royalty.
- Основные runtime-компоненты и текущая face-landmark model используют разрешительную Apache License 2.0. Она допускает коммерческое закрытое приложение, но требует сохранить применимые license/copyright/attribution/NOTICE материалы.
- Собственный код приложения планируется распространять как закрытый proprietary product. До релиза нужно определить юридического правообладателя и подготовить EULA/Terms of Use.
- Нельзя считать компонент коммерчески безопасным только потому, что его исходный код открыт, модель обучена самостоятельно или файл доступен для скачивания.

## Текущий реестр

### Компоненты release APK

1. **Собственный Kotlin/C++/GLSL код AR Makeup**
   - Статус: собственный код, предполагаемая proprietary-лицензия.
   - До релиза: зафиксировать правообладателя; получить передачу исключительных прав от сотрудников/подрядчиков; оформить EULA/Terms.

2. **AndroidX / CameraX / AppCompat / ConstraintLayout / Activity / Core**
   - Используемые версии находятся в `gradle/libs.versions.toml`.
   - Основная лицензия: Apache License 2.0.
   - Требование: включить применимые license/copyright/NOTICE из точного release dependency graph.

3. **Material Components for Android 1.14.0**
   - Лицензия: Apache License 2.0.
   - Источник: https://github.com/material-components/material-components-android

4. **Kotlin runtime, попадающий в APK через Android/Kotlin toolchain**
   - Лицензия: Apache License 2.0.
   - Источник: https://github.com/JetBrains/kotlin

5. **MediaPipe Tasks Vision 1.0.0**
   - Лицензия runtime/repository: Apache License 2.0.
   - Источник: https://github.com/google-ai-edge/mediapipe
   - Дополнительный gate: проверить точный AAR и все его transitive/native notices в release graph; перед релизом повторно проверить актуальное privacy/telemetry поведение выбранной версии.

6. **`face_landmarker.task`**
   - Source, состав bundle, лицензия и SHA-256 зафиксированы в `app/src/main/assets/MODEL_LICENSES.md`.
   - Компоненты BlazeFace Short Range, Face Mesh V2 и Blendshape V2: Apache License 2.0 по официальным model cards.
   - Коммерческий статус: допустимый текущий кандидат при выполнении Apache attribution/NOTICE требований.

7. **Google Filament / Filamat 1.74.0**
   - Лицензия: Apache License 2.0.
   - Источник: https://github.com/google/filament
   - Требование: включить применимые license/NOTICE из AAR и транзитивных native-компонентов.

8. **Vulkan API и Android system driver**
   - Vulkan driver используется как системный компонент устройства и не распространяется внутри APK.
   - Заголовки и build tools приходят из Android NDK; их точные third-party notices должны войти в release audit, если соответствующий код/материалы распространяются в итоговом artifact.
   - Использование API само по себе не требует отдельной коммерческой runtime-лицензии.

### Build/test-only компоненты

- **JUnit 4.13.2** — Eclipse Public License 1.0; подключён только через `testImplementation` и не должен попадать в release APK.
- Android Gradle Plugin, Android SDK/NDK, CMake и `glslc` используются как инструменты сборки. Их использование регулируется Android SDK License Agreement и соответствующими toolchain notices; это не лицензия конечного приложения.
- Перед релизом необходимо проверить, что test/debug-only зависимости действительно отсутствуют в `releaseRuntimeClasspath` и APK/AAB.

## Будущие компоненты

### MediaPipe Selfie Multiclass Segmenter — разрешён только после отдельного gate

- Пока не добавлен в проект.
- Официальная model card указывает Apache License 2.0.
- Может использоваться для broad-классов `background / hair / body-skin / face-skin / clothes / accessories`.
- Не является точной beauty parsing model для губ, зубов, глаз или век.
- Перед добавлением: зафиксировать точный URL/version, SHA-256, model card, license/NOTICE, размер, latency, telemetry/privacy и коммерческий статус.

### Собственная semantic parsing model

Собственное обучение не отменяет проверку прав. До первого training run нужно зафиксировать:

- лицензию architecture/training code;
- лицензию каждого pretrained/teacher checkpoint;
- права на изображения датасета и его коммерческое ML-training использование;
- права на разметку и работу аннотаторов;
- согласия/договоры для изображений лиц и применимые privacy/biometric требования;
- лицензии synthetic assets/generators;
- право распространять или закрыто использовать итоговые weights;
- model card, provenance, version и SHA-256 итоговой модели.

Запрещены без отдельного письменного разрешения: `non-commercial`, `research only`, неизвестная лицензия, неоднозначные ограничения на обучение/производные weights, а также датасеты без прослеживаемых прав на изображения лиц.

### Внешние assets и бренды

Отдельный коммерческий документ/договор нужен для каждого внешнего:

- шрифта, иконки, текстуры, HDRI и изображения;
- фото/видео модели или пользователя, используемого не только для локального теста;
- фото упаковки и каталога косметики;
- логотипа, товарного знака и маркетингового названия бренда;
- коммерческого beauty SDK или cloud API.

Apache 2.0 не предоставляет права использовать товарные знаки Google, MediaPipe, Filament или косметических брендов как знак одобрения продукта.

## Gate добавления нового компонента

До merge нового компонента нужно:

1. Зафиксировать точное имя, publisher/author, version, source URL и SHA-256 для скачиваемого binary/model/asset.
2. Получить полный текст лицензии из первичного источника, model card и NOTICE, если он существует.
3. Проверить коммерческое использование, модификацию, redistribution, source-disclosure, attribution, patent и trademark условия.
4. Проверить все transitive dependencies и native binaries, а не только верхнеуровневый Maven package.
5. Для моделей отдельно проверить weights, training code, datasets, labels, teacher models и право на итоговые weights.
6. Для данных лиц отдельно проверить privacy/consent/biometric требования целевых стран.
7. Обновить этот файл, `app/src/main/assets/MODEL_LICENSES.md` для bundled ML assets и `PROJECT_CONTEXT.md`, если решение меняет архитектуру или release risk.
8. Не добавлять компонент в production/release graph, пока хотя бы один обязательный источник или право остаётся неизвестным.

## Обязательный пакет коммерческого релиза

Текущий статус — **ещё не release-ready по документам**. До публикации нужны:

- [ ] Правообладатель проекта и proprietary EULA/Terms of Use.
- [ ] Root `LICENSE`/distribution notice для собственного продукта.
- [ ] Сгенерированный `THIRD_PARTY_NOTICES` по точному `releaseRuntimeClasspath` и native/model assets.
- [ ] Полные тексты применимых лицензий, включая Apache License 2.0.
- [ ] Экран или раздел `Open source licenses` в приложении.
- [ ] SBOM и архив dependency/license report для конкретной release-сборки.
- [x] Source и SHA-256 текущего `face_landmarker.task` зафиксированы.
- [ ] Release-аудит всех ML model cards/NOTICE повторён на конкретных shipped versions.
- [ ] Privacy Policy, camera disclosure/consent и корректная Google Play Data Safety декларация.
- [ ] Проверено фактическое MediaPipe/third-party telemetry поведение release APK.
- [ ] Права на все продуктовые изображения, шрифты, иконки, текстуры и бренды подтверждены.
- [ ] Финальный юридический review выполнен перед публичным коммерческим запуском.

## Первичные источники

- Apache License 2.0: https://www.apache.org/licenses/LICENSE-2.0.html
- MediaPipe repository/license: https://github.com/google-ai-edge/mediapipe
- MediaPipe Face Landmarker models: https://developers.google.com/edge/mediapipe/solutions/vision/face_landmarker
- MediaPipe Multiclass Segmentation model card: https://storage.googleapis.com/mediapipe-assets/Model%20Card%20Multiclass%20Segmentation.pdf
- AndroidX: https://github.com/androidx/androidx
- Material Components Android: https://github.com/material-components/material-components-android
- Filament: https://github.com/google/filament
- Kotlin: https://github.com/JetBrains/kotlin
- JUnit 4 EPL 1.0: https://github.com/junit-team/junit4/blob/main/LICENSE-junit.txt
- Android SDK License Agreement: https://developer.android.com/studio/terms
- Google Play Data Safety guidance: https://developer.android.com/privacy-and-security/declare-data-use

## История изменений

- 2026-08-17 — создан единый коммерческий лицензионный реестр; зафиксирован текущий Apache 2.0 runtime/model baseline, test-only EPL dependency, future ML/data/asset gates и обязательный release package.
