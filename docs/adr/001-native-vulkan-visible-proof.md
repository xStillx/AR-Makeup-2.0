# ADR 001: Native Vulkan visible-present proof

Status: accepted for a debug-only vertical slice on 2026-08-18.

## Context

The current production-visible path imports each latest camera `AHardwareBuffer` into the native
Vulkan runtime, renders a verified camera pass, exports a release fence, and then hands the same
buffer to the Filament/OpenGL compositor. Device measurements show a smooth 60 Hz Filament layer,
but actual SurfaceFlinger presentation is about 44 ms after the desired presentation time. The
Filament display-queue guard and presentation-hint A/Bs did not reduce that delay.

FF1 therefore needs an isolated proof in which the application owns the visible swapchain and can
associate camera sensor timestamps with native presentation feedback. The proof must not combine
the display cutover with the later full-face, semantic-mask, or production-material migration.

## Decision

Add a debug-only launch mode in which native Vulkan, rather than Filament, exclusively owns the
existing full-screen `SurfaceView`:

- renderer ownership is selected before CameraX is bound;
- the regular launch continues to construct the unchanged Filament renderer;
- the Vulkan launch reuses the existing native `AImageReader`, latest-only AHB acquisition,
  external-format YCbCr sampling, acquire/release fences, and verified transform contract;
- `Choreographer` drives native acquisition/present without starting the diagnostic 10 FPS clear
  loop;
- the first visible slice draws the camera and the current bright tracking-test lip geometry only;
- native presentation feedback uses `VK_GOOGLE_display_timing` when the device exposes it and the
  existing layer-specific SurfaceFlinger sidecar remains the validation fallback;
- destroying or invalidating the renderer waits for CameraX to return its provided camera surface
  before native resources are released.

The mode is an A/B candidate, not a new default. Filament stays the rollback baseline until
orientation, mirror, crop, lip alignment, timing, jank, and thermal gates pass on device.

## Options considered

### Keep tuning Filament scheduling

Rejected for FF1. Queue protection was already enabled and produced no measurable improvement;
disabling Android presentation hints made the queue worse by roughly one frame.

### Stack a second Vulkan `SurfaceView` above Filament

Rejected. Two independently queued visible layers would make camera-to-makeup synchronization and
actual-presentation attribution ambiguous, while retaining the very queue boundary being tested.

### Replace the only visible surface in a debug A/B mode

Accepted. It gives one owner, one swapchain, explicit fences, and reversible comparison without
changing the normal Filament launch.

## Trade-offs

- The first proof intentionally supports only the diagnostic tracking-test material; physical
  matte/satin/gloss migration remains a later slice.
- Surface loss or size change recreates the debug renderer rather than introducing a second owner.
- Presentation timing is capability-dependent; absence of the Vulkan extension is not disguised as
  actual display feedback.
- The proof may initially present only when a fresh 30 FPS camera buffer is available. A retained
  camera image and independent 60 Hz geometry present loop are a follow-up only if measurements
  show that it is required.

## Consequences

- Filament and Vulkan cannot render to the same visible surface in one process run.
- Camera transforms and lip coordinates must remain shared contracts with regression tests; no
  device-specific visual offset is allowed in the tracker.
- Native timing samples must retain the camera sensor timestamp and explicit clock-domain
  conversion before they are reported as sensor-to-actual latency.
- Failure to create the debug Vulkan renderer is reported as a renderer error; it does not silently
  claim a successful Vulkan A/B.

## Action items

1. Introduce a renderer delegate boundary in `FilamentMakeupView`.
2. Add the debug-only native visible renderer and lifecycle/surface ownership.
3. Add the Vulkan tracking-test lip pipeline and optional display-timing feedback.
4. Run local tests/lint/assemble, then install the exact APK on SM-G990B.
5. Compare native Vulkan and unchanged Filament with the same device scenarios and record the
   accepted/rejected result in `PROJECT_CONTEXT.md`, `FULL_FACE_ROADMAP.md`, and `CHAT_HANDOFF.md`.
