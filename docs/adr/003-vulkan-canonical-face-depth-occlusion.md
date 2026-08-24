# ADR 003: Canonical face depth and occlusion in the Vulkan compositor

Status: implemented and preserved as an FF5 device-candidate on 2026-08-24, but rejected by the
first visual device gate. The accepted 2D Vulkan path remains the rollback and default.

## Context

The accepted FF4 path presents the ARCore camera frame and hybrid ARCore/MediaPipe lip geometry in
one Vulkan timeline. Its lip mesh is still a flat 2D surface: large yaw, pitch and local mouth
deformation cannot be depth-tested against the current face. Future blush, eyeshadow and eyeliner
also require one shared geometric surface rather than independent screen-space overlays.

ARCore already exposes a 468-point face-local mesh, triangle indices, pose and camera matrices for
the current camera sensor timestamp. The model-independent FF3 contract previously retained the
projected landmarks but discarded surface connectivity.

## Decision

- Preserve ARCore triangle connectivity as immutable, model-independent `FaceSurfaceTopology`.
- Carry it with the current global `FaceObservation` and `FullFaceRenderState`; no ARCore type is
  allowed beyond the adapter.
- Project the 468 vertices once in the ARCore adapter and keep normalized display x/y plus OpenGL
  NDC z in the renderer-facing landmark set.
- Add a swapchain depth attachment selected at runtime from D32, D24S8 and D16 support.
- Render in one subpass and one presentation: camera color, depth-only canonical face, then
  depth-tested lipstick. The face pass has color writes disabled.
- Interpolate a face-surface depth for each tessellated lip vertex, restricting the search to
  triangles connected to lip landmarks. This keeps work bounded and follows the current mouth
  surface instead of unrelated projected face triangles.
- Apply only a small forward depth bias to lipstick. Keep temporal-flow warping disabled in this
  candidate so FF5 does not reintroduce the rejected predictor behavior.
- Keep a launch-extra switch for a direct 3D-versus-accepted-2D device comparison.

Each swapchain image owns its depth image. The existing per-image fence prevents reuse before its
previous presentation has completed, and every render pass clears depth before drawing.

## Options considered

### Keep a flat 2D lip overlay

Rejected as the production direction. It cannot express self-occlusion and would require separate
ad-hoc geometry rules for every future makeup region.

### Build depth from MediaPipe

Rejected for this slice. MediaPipe is the asynchronous local-expression source and may be older
than the current camera frame. Using it as the global depth owner would reintroduce motion lag.

### Render a canonical ARCore face depth surface

Accepted as the FF5 candidate. It uses the current camera frame's pose, preserves one camera owner,
does not add an inference stage, and creates a shared depth contract for future makeup products.

## Trade-offs

- The candidate uploads 468 vertices and about 2.7k indices on each accepted camera frame. This is
  intentionally simple for correctness; profiling may later replace it with persistent topology
  and bounded mapped-buffer updates.
- The current lip deformation remains a 2D affine local shape whose depth is sampled from the
  current ARCore surface. A later full 3D deformation stage can replace the sampler without
  changing the Vulkan pass contract.
- ARCore availability is still a device capability. A licensed fallback backend and device matrix
  remain required before making this the universal product path.

## Device verdict

The infrastructure gate passed on SM-G990B/Adreno 660: Vulkan selected `D32_SFLOAT`,
`faceDepthUpdates` increased with the current ARCore mesh, no camera drops were observed, and
Home/resume recreated the runtime without the previous black screen/present failure. The exact APK
SHA-256 is `013758091AA4D2C3A0E718BD22744B9955F488A0DC2B537D70BDE27924E2F9EA`; checkpoint
`18cf1e0` preserves the rejected candidate for controlled follow-up.

The visual gate failed:

- at strong yaw the lip tracker moves slightly forward;
- parts of the lipstick fail the depth test and expose the original lip skin;
- at strong downward pitch the mask shifts and flickers.

This result does not reject the shared canonical depth architecture, but it does reject the current
implementation as a product cutover. The lip shape is still a 2D local affine deformation with
sampled surface depth; the next iteration must isolate XY attachment, depth interpolation/bias and
visibility/loss gating, and evaluate true camera-space 3D local lip attachment.

### Follow-up evidence

A controlled A/B on the same SM-G990B separated all three failure classes. Forward drift remained
visible with face depth disabled at yaw around 45–52 degrees, so it is an XY/affine attachment
problem rather than a depth-bias effect. The old 3D path alone produced skin holes. Its face pass
and lip pass interpolate depth over different triangulations; matching sampled depth only at lip
vertices does not make fragment depth coplanar. At strong yaw, overlapping projected triangle
depth spread reached roughly 0.024–0.032. A larger diagnostic bias nearly hid the holes but is not
accepted as a correction.

An unsaved shared-surface proof rendered lipstick with a 109–112 triangle subset of the original
ARCore face topology and applied the composed MediaPipe outer/inner loop displacement to the same
projected vertices used by both passes. With zero bias it reported no fallback/overlap sampling,
removed the depth holes and ran at about 51–61 FPS. Its whole-triangle coverage is visibly coarse,
so this is causal evidence, not visual acceptance. The next implementation should retain the
shared surface but replace triangle selection with canonical UV or analytic coverage and propagate
local deformation through the complete camera-space lip region.

Downward pitch around +28–30 degrees also produced genuine ARCore global-state loss bursts; total
loss increased from 2 to 38 during the short test. This confirms that pitch flicker needs measured
loss-episode/reacquisition handling rather than blind changes to the existing 100 ms hold.

## Validation gates

1. JVM tests cover topology ownership, validation and projected surface-depth interpolation.
2. Kotlin, four-ABI native build and Android lint must pass.
3. On the reference SM-G990B, diagnostics must report a supported depth format and increasing
   `faceDepthUpdates`.
4. The 3D candidate must be compared with the accepted 2D launch mode for sharp head/phone motion,
   open mouth, extreme yaw/pitch, lifecycle resume and tracking loss.
5. A rejected candidate may be checkpointed for reproducibility, but no default cutover is allowed
   until the device image is approved by the user.
