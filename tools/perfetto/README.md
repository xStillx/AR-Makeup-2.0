# ARMakeup FrameTimeline capture

Этот набор нужен для FF1-диагностики и не участвует в production runtime.

1. Запустить debug APK с включённой `.arv6` telemetry (warm-up не меньше 5 секунд и запись
   30 секунд). Приложение должно быть запущено до Perfetto: `atrace_apps` включает app trace
   sections для уже существующего процесса.
2. Во время warm-up записать 45-секундный trace:

   ```powershell
   adb push tools/perfetto/armakeup-frame-timeline.cfg /data/misc/perfetto-configs/armakeup-frame-timeline.cfg
   adb shell perfetto --txt -c /data/misc/perfetto-configs/armakeup-frame-timeline.cfg -o /data/misc/perfetto-traces/armakeup-frame-timeline.perfetto-trace
   adb pull /data/misc/perfetto-traces/armakeup-frame-timeline.perfetto-trace app/build/tracking-telemetry/
   ```

3. Сначала выполнить `frame-timeline-layers.sql`, затем `frame-timeline-correlation.sql` через
   официальный `trace_processor` или открыть trace в Perfetto UI.

`ARMK_FRAME:<vsyncId>` связывает render callback с preferred Choreographer timeline. В `.arv6`
v8+ тот же id хранится вместе с expected presentation и render deadline. Поле actual presentation
в `.arv6` остаётся `-1`, пока trace не докажет связь именно с Filament `SurfaceView` layer.

Официальная документация Perfetto на данный момент отдельно предупреждает, что FrameTimeline не
поддерживает `SurfaceView`. Поэтому отсутствие строк нужного layer — корректный отрицательный
результат; связывать вместо него root-window/HWUI frame запрещено.

Для device-lab измерения actual present текущего Filament layer используйте SurfaceFlinger
`--latency`. Скрипт находит точное BLAST-имя `SurfaceView`, очищает его 128-frame ring buffer,
регулярно снимает `desired / actual / ready` и сохраняет уникальные строки в CSV:

```powershell
powershell -ExecutionPolicy Bypass -File tools/perfetto/capture-surfaceview-latency.ps1 `
  -DurationSeconds 30 `
  -OutputPath app/build/tracking-telemetry/surfaceview-latency.csv
```

Это shell-only diagnostic API, не production runtime contract. AOSP `FrameTracker` определяет
вторую колонку как момент, когда frame стал видим пользователю; pending fences (`INT64_MAX`)
скрипт отбрасывает. Для production actual-present feedback всё ещё нужен собственный native
swapchain path (например, Vulkan display timing).

## FF1 display-queue A/B candidate

В `.arv6` v9 добавлен явный per-render признак
`displayQueueProtectionEnabled`. Кандидат не меняет predictor, mesh, camera transform или shader:
он включает существующий Filament feature `engine.skip_frame_when_cpu_ahead_of_display`, который
может вернуть `false` из `Renderer.beginFrame()` и дать display queue стечь, когда actual-present
отстал от ожидаемого timeline ещё минимум на один refresh interval.

Baseline явно выключает защиту очереди, чтобы результат не зависел от default конкретной сборки
Filament:

```powershell
adb shell am force-stop com.example.armakeup
adb shell am start -n com.example.armakeup/.MainActivity `
  --ez com.example.armakeup.extra.TRACKING_TELEMETRY true `
  --el com.example.armakeup.extra.TRACKING_WARMUP_MS 5000 `
  --el com.example.armakeup.extra.TRACKING_DURATION_MS 30000 `
  --es com.example.armakeup.extra.TRACKING_SCENARIO ff1_display_queue_baseline_v9 `
  --ez com.example.armakeup.extra.ENABLE_DISPLAY_QUEUE_PROTECTION false
```

Candidate отличается только одним extra:

```powershell
adb shell am force-stop com.example.armakeup
adb shell am start -n com.example.armakeup/.MainActivity `
  --ez com.example.armakeup.extra.TRACKING_TELEMETRY true `
  --el com.example.armakeup.extra.TRACKING_WARMUP_MS 5000 `
  --el com.example.armakeup.extra.TRACKING_DURATION_MS 30000 `
  --es com.example.armakeup.extra.TRACKING_SCENARIO ff1_display_queue_guard_v9 `
  --ez com.example.armakeup.extra.ENABLE_DISPLAY_QUEUE_PROTECTION true
```

Для обоих запусков параллельно снять отдельный CSV командой выше. Перед сравнением baseline обязан
показать в `ARMakeupRender` `active=false` и fraction `0.0`, candidate — `active=true` и
`displayQueueProtectionFraction=1.0`. Сравниваются actual sensor→display, desired→actual,
ready→actual, доля `filamentFrameRendered`, actual frame interval p95 и число интервалов `>25 ms`.
Уменьшение queue latency нельзя принимать ценой регулярных дубликатов/рывков.

Device result на SM-G990B: flag является constant после Engine creation и уже включён по умолчанию,
поэтому override задаётся через `Engine.Builder`. Cold stationary off/on дал desired→actual
`44.05/44.22 ms`, ready→actual `39.80/40.00 ms`, actual interval p95 `16.793/16.802 ms` и
`filamentRenderedFraction=1.0/1.0`. Guard не активировал skip и не уменьшил очередь; candidate
отклонён, default остаётся on.

## FF1 native Vulkan visible proof

Debug-only режим выбирает native Vulkan как единственного владельца существующего `SurfaceView`;
обычный запуск без extra остаётся Filament baseline:

```powershell
adb shell am force-stop com.example.armakeup
adb shell am start -n com.example.armakeup/.MainActivity `
  --ez com.example.armakeup.extra.ENABLE_NATIVE_VULKAN_VISIBLE true
```

На SM-G990B `VK_GOOGLE_display_timing` доступен и даёт in-process actual-present для каждого
native `presentID`, связанного с camera sensor timestamp. SurfaceFlinger sidecar всё равно запускать
параллельно: он проверяет BLAST cadence независимо от Vulkan extension.

Первый 20-секундный proof `ff1-native-visible-final.csv`: `583` frames, desired→actual
`30.78/31.37 ms`, ready→actual `29.73/30.56 ms`, actual interval `33.38/33.60 ms` median/p95.
Same-APK Filament control `ff1-filament-baseline-same-apk.csv`: `1186` frames,
desired→actual `29.10/46.04 ms`, ready→actual `25.13/42.07 ms`, interval
`16.689/16.788 ms`. Native proof пока представляет только при новом 30-FPS camera AHB, поэтому
не является production candidate, несмотря на actual feedback и ровный queue p95. Следующий gate —
retained latest camera image и независимый native 60-Hz present; expected interval около `16.7 ms`
при `cameraDropped=0`.

Candidate `2fb7cd7` removes the 30-Hz coupling with a persistent device-local camera texture. Native
diagnostic must show `retainedPresents` growing at roughly twice `cameraCopied`, `retainedReused`
near half of presents, and the Kotlin `actualIntervalP50Ms/actualIntervalP95Ms` near `16.7 ms`.
These are acceptance targets, not measured results: the exact APK has not yet run on SM-G990B.

## FF1 Filament presentation-hints negative control

`.arv6` v10 добавляет `filamentPresentationHintsEnabled`. Этот debug-only negative control
отключает передачу expected-presentation/deadline из Android 13+ `ChoreographerHelper`, не меняя
queue guard, tracker, mesh, camera transform или shader:

```powershell
adb shell am force-stop com.example.armakeup
adb shell am start -n com.example.armakeup/.MainActivity `
  --ez com.example.armakeup.extra.TRACKING_TELEMETRY true `
  --el com.example.armakeup.extra.TRACKING_WARMUP_MS 5000 `
  --el com.example.armakeup.extra.TRACKING_DURATION_MS 15000 `
  --es com.example.armakeup.extra.TRACKING_SCENARIO ff1_stationary_hints_off_v10 `
  --ez com.example.armakeup.extra.ENABLE_DISPLAY_QUEUE_PROTECTION true `
  --ez com.example.armakeup.extra.DISABLE_FILAMENT_PRESENTATION_HINTS true
```

Этот вариант намеренно не является production candidate. Device on/off дал desired→actual
`43.88/59.27 ms`, ready→actual `39.77/49.64 ms`, actual interval p95 `16.796/16.825 ms` и
intervals `>25 ms` `5/7`. Отключение ухудшает очередь примерно на один кадр; presentation hints
обязаны оставаться включёнными. Следующий FF1 proof — native Vulkan visible swapchain/present.

- [FrameTimeline data source and SQL tables](https://perfetto.dev/docs/data-sources/frametimeline)
- [System trace recording](https://perfetto.dev/docs/getting-started/system-tracing)
- [Trace Processor](https://perfetto.dev/docs/contributing/embedding)
- [AOSP FrameTracker source](https://android.googlesource.com/platform/frameworks/native/+/master/services/surfaceflinger/FrameTracker.cpp)
