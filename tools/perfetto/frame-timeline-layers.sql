-- A target build is directly correlatable only if this includes the Filament SurfaceView layer.
-- The root activity/HWUI layer is not a substitute.
SELECT
  a.layer_name,
  count(*) AS frame_count,
  min(a.present_type) AS example_present_type
FROM actual_frame_timeline_slice a
JOIN process p USING (upid)
WHERE p.pid IN (
  SELECT DISTINCT p2.pid
  FROM slice s
  JOIN thread_track tt ON tt.id = s.track_id
  JOIN thread t USING (utid)
  JOIN process p2 USING (upid)
  WHERE s.name GLOB 'ARMK_FRAME:*'
)
GROUP BY a.layer_name
ORDER BY frame_count DESC;
