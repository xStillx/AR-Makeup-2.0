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
v8 тот же id хранится вместе с expected presentation и render deadline. Поле actual presentation
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

- [FrameTimeline data source and SQL tables](https://perfetto.dev/docs/data-sources/frametimeline)
- [System trace recording](https://perfetto.dev/docs/getting-started/system-tracing)
- [Trace Processor](https://perfetto.dev/docs/contributing/embedding)
- [AOSP FrameTracker source](https://android.googlesource.com/platform/frameworks/native/+/master/services/surfaceflinger/FrameTracker.cpp)
