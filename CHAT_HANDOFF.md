# ARMakeup — handoff текущей задачи

Актуально на 2026-09-02. Долгоживущий контекст — `PROJECT_CONTEXT.md`, будущий план — `FULL_FACE_ROADMAP.md`, лицензии — `LICENSE_COMPLIANCE.md`.

## Репозиторий

- Путь: `C:\Users\User\AndroidStudioProjects\ARMakeup`.
- Ветка: `codex/lipstick-material-v2`.
- HEAD: `512aac5` (`[Tracking] Checkpoint lip transition diagnostics`).
- Устройство: Samsung SM-G990B.
- Коммит текущих изменений ещё не создан.

## Активный runtime

- Launcher: `ArCoreFaceAnchorActivity`.
- ARCore `1.54.0` владеет camera session/timeline/global face pose.
- MediaPipe Tasks Vision `1.0.0` получает latest-only image той же камеры и выдаёт 2D lip contour.
- `FaceObservation` отделяет backend от tracking/material кода.
- `TimestampedLipAnchorTransport` даёт global translation; `LipContourTemporalRefiner` обрабатывает только local lip shape.
- OpenGL ES renderer рисует tessellated matte/satin/gloss lipstick поверх той же ARCore camera texture.
- `MainActivity` с CameraX/Filament/native Vulkan остаётся legacy rollback, не launcher.

## Незакоммиченный candidate

- `LipFrameDiagnostics`: opt-in screen-space trace стадий `mp -> corr -> ref -> tess`.
- `MonotonicSensorTimestampGate`: запрещает повторный inference одного ARCore camera image; same-sensor contour changes `140 -> 0`.
- Lower-band transition: shared center outer/inner следует текущему measurement, thickness/detail остаются сглаженными.
- `PROJECT_CONTEXT.md`, `FULL_FACE_ROADMAP.md`, этот handoff и license registry сокращаются до актуального состояния.

## Visual/device verdict

- Верхняя губа при открытии/закрытии принята.
- Нижняя губа улучшилась значительно: сильный measured lag `12–16 px -> 0.2–1.8 px`; остаётся небольшой малозаметный residual.
- Мелкий jitter остаётся при yaw головы и при движении только глаз с неподвижными головой и ртом.
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

Локализовать ARCore cross-tracker rebase seam, не меняя lower-lip candidate:

1. Коротко записать абсолютные `anchor(M)`, `anchor(R)`, `MediaPipe center(M)` и residual `center(M)-anchor(M)`.
2. Повторить три сценария: полный покой, только глаза вверх/вниз, медленный yaw.
3. Выполнить controlled runtime A/B текущего transport против anchor bypass/continuity-preserving global owner.
4. Выбрать вариант, который убирает jitter, но сохраняет fast-motion attachment.

После решения anchor jitter отдельно вернуться к малому residual lag нижней губы и camera/delivery age.

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
