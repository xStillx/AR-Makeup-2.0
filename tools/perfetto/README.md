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

- [FrameTimeline data source and SQL tables](https://perfetto.dev/docs/data-sources/frametimeline)
- [System trace recording](https://perfetto.dev/docs/getting-started/system-tracing)
- [Trace Processor](https://perfetto.dev/docs/contributing/embedding)
- [AOSP FrameTracker source](https://android.googlesource.com/platform/frameworks/native/+/master/services/surfaceflinger/FrameTracker.cpp)
