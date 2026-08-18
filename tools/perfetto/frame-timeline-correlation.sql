-- ARMK_FRAME:<vsyncId> is emitted only while debug .arv6 telemetry is active.
-- A successful SurfaceView correlation requires an app actual-timeline row whose layer_name is
-- the Filament SurfaceView. Do not treat an HWUI/root-window row as the makeup buffer.
WITH app_markers AS (
  SELECT
    CAST(substr(s.name, length('ARMK_FRAME:') + 1) AS INT) AS vsync_id,
    s.ts AS render_callback_ts,
    s.ts + s.dur AS render_callback_end_ts,
    s.dur AS render_callback_dur,
    t.name AS thread_name,
    p.pid AS app_pid
  FROM slice s
  JOIN thread_track tt ON tt.id = s.track_id
  JOIN thread t USING (utid)
  JOIN process p USING (upid)
  WHERE s.name GLOB 'ARMK_FRAME:*'
),
app_expected AS (
  SELECT
    e.surface_frame_token AS vsync_id,
    e.ts AS expected_start_ts,
    e.ts + e.dur AS expected_present_ts
  FROM expected_frame_timeline_slice e
),
app_actual AS (
  SELECT
    a.surface_frame_token AS vsync_id,
    a.display_frame_token,
    a.ts AS app_actual_start_ts,
    a.ts + a.dur AS app_gpu_or_post_end_ts,
    a.layer_name,
    a.present_type,
    a.jank_type,
    a.on_time_finish
  FROM actual_frame_timeline_slice a
),
surface_flinger_actual AS (
  SELECT
    a.display_frame_token,
    a.ts + a.dur AS on_screen_update_ts,
    a.present_type AS sf_present_type,
    a.jank_type AS sf_jank_type
  FROM actual_frame_timeline_slice a
  WHERE (a.surface_frame_token = 0 OR a.surface_frame_token IS NULL)
    AND a.layer_name IS NULL
)
SELECT
  m.vsync_id,
  m.render_callback_ts,
  m.render_callback_end_ts,
  round(m.render_callback_dur / 1e6, 3) AS render_callback_ms,
  e.expected_present_ts,
  a.app_gpu_or_post_end_ts,
  sf.on_screen_update_ts,
  round((sf.on_screen_update_ts - m.render_callback_ts) / 1e6, 3)
    AS callback_to_on_screen_ms,
  round((sf.on_screen_update_ts - e.expected_present_ts) / 1e6, 3)
    AS expected_to_on_screen_ms,
  a.layer_name,
  a.present_type,
  a.jank_type,
  a.on_time_finish,
  sf.sf_present_type,
  sf.sf_jank_type,
  m.thread_name,
  m.app_pid,
  CASE WHEN a.layer_name LIKE '%SurfaceView%' THEN 1 ELSE 0 END
    AS is_target_surfaceview
FROM app_markers m
LEFT JOIN app_expected e USING (vsync_id)
LEFT JOIN app_actual a USING (vsync_id)
LEFT JOIN surface_flinger_actual sf USING (display_frame_token)
ORDER BY m.render_callback_ts, a.layer_name;
