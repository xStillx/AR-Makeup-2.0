# ARMakeup — handoff текущей задачи

Актуально на 2026-09-04. Архитектура — `PROJECT_CONTEXT.md`, следующие этапы — `FULL_FACE_ROADMAP.md`, лицензии — `LICENSE_COMPLIANCE.md`.

## Контрольная точка

- Репозиторий: `C:\Users\User\AndroidStudioProjects\ARMakeup`, ветка `codex/lipstick-material-v2`.
- HEAD: `fe8ffe2` — принятый пользователем блокирующий same-frame Sync.
- Пользователь проверил рот, медленные/быстрые движения и глаза: всё отлично. Не просить повторять проверку старой сборки.
- Прежние варианты опорных точек ARCore/live mesh не дали принятого результата. Bypass уменьшал jitter ценой lag.
- Sync одновременно меняет temporal alignment, anchor correction и cadence; единственная причина дрожания не доказана.

## Текущая незакоммиченная работа

По команде пользователя блокирующий Sync заменён неблокирующим предъявлением точных пар:

- камера/ARCore продолжают обновляться, callback читается без `await`;
- два GPU camera buffer: pending и presented; один inference in flight;
- фон и материал читают сохранённую текстуру одной пары;
- timestamp/pitch/visibility/point сохраняются значениями; live ARCore объекты не входят в пару;
- no-face completion предъявляет camera-only; незавершённая пара удерживает предыдущую;
- после 500 ms устаревшая пара отбрасывается, показывается live camera без помады;
- lifecycle/viewport/mode reset отбрасывает пары;
- формулы материала, локальные фильтры, тесселяция, модели и зависимости не изменены;
- `age 0 ms` — alignment, `camera lag` — отставание presented от latest camera, не полный motion-to-photon;
- По команде пользователя Sync — основной режим по умолчанию: renderer стартует с Sync, в интерфейсе выбрана `Sync кадр`. Async сохранён для ручного сравнения/отката.

Частота уникальных пар ограничена MP. Новый неблокирующий Sync получил предварительное визуальное подтверждение пользователя («вроде всё норм»); сворачивание/возврат и потеря/повторное обнаружение лица также работают нормально по его проверке. Расширенный device/performance acceptance остаётся открытым.

## Проверка и продолжение

- Обычная `:app:assembleDebug` прошла без unit-тестов.
- Первое обновление отклонено из-за другой подписи (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Пользователь самостоятельно удалил старую версию и установил новую; повторную переустановку агент не выполнял.
- Runtime нового `SYNC pair` на SM-G990B подтверждён 2026-09-04 около 10:39:40–10:39:54: в status-окнах 23–26 новых пар, 20–35 повторов, no-ready-pair=0; MP 23–28 FPS, render 42–58 FPS, alignment age=0 ms, anchor=0.0 px. Camera lag в отсчётах преимущественно 41 ms, иногда 81 ms; это не motion-to-photon.
- Ошибок в просмотренном runtime/crash log не обнаружено; `LipFrameTrace=S`. Пользователь предварительно принял визуальный результат нового Sync и подтвердил pause/resume и reacquisition.
- Следующий gate: запуск с Sync по умолчанию и ручное Async/Sync на устройстве, затем failure/latency/GPU/device matrix. После смены стартового режима debug-сборка прошла; на устройстве это изменение не проверено, оно отключено.
- Тяжёлый `LipFrameTrace` перед visual acceptance выключать: `adb shell setprop log.tag.LipFrameTrace S`.
- Не добавлять новые коэффициенты, predictor/gyro/видимую 3D-сетку или другой anchor owner в этот эксперимент.
- Unit-тесты не добавлять и не запускать без команды. Не коммитить без команды.

## Основные файлы

- `app/src/main/java/com/example/armakeup/arcore/ArCoreFaceAnchorRenderer.kt`
- `app/src/main/java/com/example/armakeup/arcore/PairedCameraFrameStore.kt`
- `app/src/main/java/com/example/armakeup/arcore/ArCoreMediaPipeLipTracker.kt`
- `app/src/main/java/com/example/armakeup/arcore/ArCoreFaceAnchorActivity.kt`
