# ARMakeup — handoff текущей задачи

Актуально на 2026-09-03. Долгоживущий контекст — `PROJECT_CONTEXT.md`, будущий план — `FULL_FACE_ROADMAP.md`, лицензии — `LICENSE_COMPLIANCE.md`.

## Репозиторий

- Путь: `C:\Users\arc11\AndroidStudioProjects\AR-Makeup-2.0`.
- Ветка: `codex/lipstick-material-v2`.
- Базовый checkpoint перед same-frame candidate: `6e4c613` (`[Tracking] Checkpoint lower lip and anchor diagnostics`).
- Устройство: Samsung SM-G990B.
- Same-frame synchronization A/B подготовлен как отдельный tracking checkpoint; тесты выбора ARCore-точек с другого ПК в checkout отсутствуют.

## Активный runtime

- Launcher: `ArCoreFaceAnchorActivity`.
- ARCore `1.54.0` владеет camera session/timeline/global face pose.
- MediaPipe Tasks Vision `1.0.0` получает latest-only image той же камеры и выдаёт 2D lip contour.
- `FaceObservation` отделяет backend от tracking/material кода.
- `TimestampedLipAnchorTransport` даёт global translation; `LipContourTemporalRefiner` обрабатывает только local lip shape.
- OpenGL ES renderer рисует tessellated matte/satin/gloss lipstick поверх той же ARCore camera texture.
- `MainActivity` с CameraX/Filament/native Vulkan остаётся legacy rollback, не launcher.

## Последний committed candidate

- `LipFrameDiagnostics`: opt-in screen-space trace стадий `mp -> corr -> ref -> tess`.
- `MonotonicSensorTimestampGate`: запрещает повторный inference одного ARCore camera image; same-sensor contour changes `140 -> 0`.
- Lower-band transition: shared center outer/inner следует текущему measurement, thickness/detail остаются сглаженными.
- `PROJECT_CONTEXT.md`, `FULL_FACE_ROADMAP.md`, этот handoff и license registry сокращаются до актуального состояния.

## Visual/device verdict

- Верхняя губа при открытии/закрытии принята.
- Нижняя губа улучшилась значительно: сильный measured lag `12–16 px -> 0.2–1.8 px`; остаётся небольшой малозаметный residual.
- В async baseline мелкий jitter остаётся при yaw и движении только глаз; corrected same-frame lockstep предварительно визуально убрал его на SM-G990B.
- Local lip shape при движении глаз стабильна; скачет global center.
- Статический p95: MediaPipe center `0.75 px`, ARCore correction `3.53 px`, corrected center `3.46 px`.
- Eye-motion maxima: MediaPipe center `5.3 px`, ARCore correction `8.3 px`, corrected center `9.2 px`.
- Тесселяция и арифметика translation residual скачок не создают.

## Важное про диагностику

`LipFrameTrace` тяжёлый и при постоянном включении снижает MediaPipe примерно до `15 FPS`. Для короткого capture:

```text
adb shell setprop log.tag.LipFrameTrace D
```

Перед visual acceptance обязательно выключить:

```text
adb shell setprop log.tag.LipFrameTrace S
```

С выключенным trace наблюдались ARCore `58–59 FPS`, MediaPipe `24–27 FPS`, age около `41 ms`.

## Следующее действие

Подтвердить same-frame результат на других устройствах, не меняя lower-lip candidate:

1. Повторить `Async 60 Гц / Sync кадр` на нескольких устройствах: полный покой, только глаза, медленный yaw, быстрый motion, движение телефона и reacquisition.
2. Подтвердить в sync status `missing=0`, `age 0 ms`, `anchor 0.0 px`; тяжёлый `LipFrameTrace` держать выключенным.
3. Если jitter исчезает устойчиво, реализовать неблокирующий continuity-preserving global owner/rebase как отдельный production candidate.
4. Перед принятием убедиться, что fast-motion attachment не хуже текущего async baseline.

После решения anchor jitter отдельно вернуться к малому residual lag нижней губы и camera/delivery age.

## Новый same-frame A/B

В launcher добавлен переключатель `Async 60 Гц / Sync кадр`.

- Baseline оставляет текущий 60 Hz latest-only transport.
- `Sync кадр` блокирует GL frame максимум на 100 ms и принимает только MediaPipe result с тем же sensor timestamp, что у видимого ARCore camera frame.
- В status overlay нужно подтвердить `SYNC frame`, `age 0 ms`, `anchor 0.0 px` и записать фактические ARCore/MediaPipe FPS и `wait`.
- Затем сравнить покой, только глаза вверх/вниз, медленный yaw и быстрый поворот. Если jitter исчезает только в lockstep, следующий production-кандидат должен исправлять rebase seam без блокировки renderer; сам lockstep не принимать как product path.
- Первый device run мерцал из-за кадров без маски: callback публиковался до `busy=false`, а повторный ARCore camera timestamp после dedupe не переиспользовал уже готовую exact geometry.
- Исправленный APK установлен на SM-G990B. Callback теперь освобождает input до пробуждения renderer; повторный camera frame использует observation только при точном совпадении timestamp.
- Контрольные окна после исправления: `sync inferred/reused/missing` около `14–17 / 15–30 / 0`, MediaPipe `16–18 FPS`, wait `25–48 ms`, `age 0 ms`, `anchor 0.0 px`.
- Пользовательский verdict на SM-G990B: мерцание устранено, исходное дёрганье выглядит исправленным; требуется повтор на других устройствах.
- Тяжёлый `LipFrameTrace` во время verdict был выключен (`S`). `LipJumpMetrics` оставался включён и логировал на каждом новом MediaPipe result; для чистого performance gate его вычисление следует сделать opt-in.

## Ключевые файлы

- `app/src/main/java/com/example/armakeup/arcore/ArCoreFaceAnchorRenderer.kt`
- `app/src/main/java/com/example/armakeup/arcore/ArCoreMediaPipeLipTracker.kt`
- `app/src/main/java/com/example/armakeup/arcore/TimestampedLipAnchorTransport.kt`
- `app/src/main/java/com/example/armakeup/arcore/LipFrameDiagnostics.kt`
- `app/src/main/java/com/example/armakeup/tracking/FaceObservation.kt`
- `app/src/main/java/com/example/armakeup/tracking/LipContourTemporalRefiner.kt`

## Правила продолжения

- Не подбирать коэффициенты без controlled trace/A/B.
- Не возвращать predictor/gyro/visible 3D mesh как быстрый обход.
- Unit-тесты запускать только по явной команде пользователя; использовать обычный debug/device check.
- Не коммитить без явной команды пользователя.
