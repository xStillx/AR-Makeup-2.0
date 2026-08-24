# ADR 002: Model-independent full-face tracking contract

Status: accepted for the FF3 shadow integration and device visual gate on 2026-08-24. Production
Vulkan and material cutover remains pending.

## Context

The accepted Android tracking proof has one ARCore front-camera session. ARCore provides the
current global face pose and 468-point mesh, while MediaPipe asynchronously provides the local lip
shape from a latest-only analysis image of that same session. Mapping the old MediaPipe result into
the current projected ARCore anchors eliminated the visible global detachment seen in the earlier
CameraX/predictor/gyro experiments.

The proof originally performed this composition directly inside `ArCoreFaceAnchorRenderer` and
passed MediaPipe-specific observation storage into its drawing code. That coupling cannot support
the planned blush, eyeshadow, eyeliner, replay backend, or an eventual replacement landmark model
without spreading SDK-specific classes and timestamp rules through every renderer and material.

The FF3 slice must introduce the boundary without changing camera ownership, point sizes, colors,
or the accepted global attachment. Device validation may refine local composition when a visible
failure is reproduced and protected by regression tests.

## Decision

Introduce an immutable domain contract under `tracking.face`:

- `FaceTrackingBackend` describes a replaceable source, its role and topology;
- `FaceObservation` represents one backend measurement at one camera sensor timestamp;
- explicit `FaceCoordinateSpace`, `FaceTopologyDescriptor`, `FaceLandmarkSet`, `FacePose` and
  `FaceCameraFrameMetadata` prevent implicit axis/topology assumptions;
- `HybridFullFaceStateComposer` combines a current global observation with an optional latest local
  observation;
- `FullFaceRenderState` is the only geometry/state type consumed by the diagnostic drawing methods.

`ArCoreFaceObservationAdapter` is the global backend boundary. It converts ARCore face-local mesh,
pose, camera matrices and current normalized-display projection into `FaceObservation`.
`ArCoreMediaPipeLipTracker` is the local-deformation backend. It converts MediaPipe results into the
same observation contract and exposes no MediaPipe result object outside the adapter.

The composer keeps current ARCore display landmarks authoritative for global attachment. It fits
the same eight shared eye/nose/cheek anchors to obtain the rigid affine linear mapping, then changes
only its translation so the MediaPipe mouth centre is pinned to the current ARCore centre from
vertices `13/14`. It intentionally does not inherit scale from deforming ARCore mouth vertices:
device A/B showed that this widened the contour for an open mouth and large yaw. An absent or
rejected local result publishes current ARCore lip regions as a fallback. The global pose is never
delayed to the MediaPipe timestamp.

ARCore remains the only camera owner. This ADR does not authorize a second CameraX session, a
production OpenGL cutover, or direct ARCore/MediaPipe types in Vulkan or makeup material APIs.

## Options considered

### Keep composing SDK objects inside the renderer

Rejected. It is the smallest code change, but it couples future makeup products to two SDKs,
prevents a replay/fallback backend, and makes timestamp and coordinate-space mistakes hard to test.

### Select either ARCore or MediaPipe as one complete backend

Rejected for the current product goal. Device testing showed that ARCore provides the stronger
global attachment, while the single ARCore anchor does not reproduce local mouth deformation.
MediaPipe provides the required local expression detail but its old global observation caused the
visible lag. Switching the entire source per frame would also reintroduce discontinuities.

### Compose immutable model-independent observations

Accepted. It preserves the measured strengths of both sources, makes coordinate/topology contracts
explicit, allows fake/replay/fallback backends, and gives Vulkan one renderer-facing state.

## Trade-offs

- The debug adapter currently allocates immutable packed snapshots each ARCore frame. This is
  acceptable for the FF3 correctness slice; production Vulkan migration must benchmark and may add
  bounded pooling without weakening ownership or immutability at the public boundary.
- ARCore 468 and MediaPipe 478 are distinct topology descriptors even though the current shared
  indices are compatible. Future topology conversion must be explicit rather than inferred from
  point count.
- The first `FullFaceRenderState` contains lip regions and the full global mesh, but eye/cheek
  regions, visibility, occlusion and semantic probabilities remain later FF3/FF5 work.
- The accepted anchored affine mapping is still a diagnostic 2D bridge, not the final 3D
  deformation model. Large-pose correctness must be revalidated after canonical 3D cutover.

## Consequences

- Diagnostic draw code no longer reads ARCore mesh buffers or MediaPipe landmark arrays directly.
- ARCore and MediaPipe can be replaced by fake/replay backends at the composer boundary.
- Every future backend must declare topology, coordinate space, sensor timestamp, role and quality.
- Product renderers must consume `FullFaceRenderState`; importing SDK result classes across that
  boundary is an architecture regression.
- The accepted OpenGL proof remains the rollback while the same state is moved into the production
  Vulkan timeline.

## Action items

1. Implement the contract, both current adapters, composer and JVM regression tests. Completed.
2. Run unit, lint, APK and four-ABI native gate. Completed.
3. Install the exact APK and verify that magenta/cyan alignment and motion behavior are visually
   accepted for sharp motion, open mouth and large yaw. Completed on SM-G990B with APK SHA-256
   `88453CDDD81DD63A265B9F668BA8634858923787536358E1F7BC7ADE2F88F229`.
4. Add explicit mouth/left-eye/right-eye state and visibility fields before product full-face masks.
5. Feed `FullFaceRenderState` into the production Vulkan camera/render timeline.
6. Add ARCore unsupported-device/fallback backend and controlled device matrix before default
   cutover.
