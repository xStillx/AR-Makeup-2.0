# Bundled model licenses

## MediaPipe Face Landmarker

- Asset: `face_landmarker.task`
- Source: https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task
- Components: BlazeFace Short Range, Face Mesh V2, Blendshape V2.
- Authors/publisher: Google / MediaPipe.
- License: Apache License 2.0.
- Intended use documented by the publisher: real-time face landmarks and mobile augmented reality.
- SHA-256: `64184E229B263107BC2B804C6625DB1341FF2BB731874B0BCC2FE6544E0BC9FF`.

Verified on 2026-08-10 and rechecked on 2026-08-17 against the official model cards:

- https://storage.googleapis.com/mediapipe-assets/MediaPipe%20BlazeFace%20Model%20Card%20%28Short%20Range%29.pdf
- https://storage.googleapis.com/mediapipe-assets/Model%20Card%20MediaPipe%20Face%20Mesh%20V2.pdf
- https://storage.googleapis.com/mediapipe-assets/Model%20Card%20Blendshape%20V2.pdf

The model cards identify all three components as licensed under Apache License, Version 2.0.

The MediaPipe repository itself is also published under Apache License 2.0:

- https://github.com/google-ai-edge/mediapipe/blob/master/LICENSE

This project uses MediaPipe Tasks Vision, not ML Kit Face Detection. Apache 2.0 does not impose a non-commercial-only restriction, but a release must include the applicable license, copyright, attribution and NOTICE material required by the dependency/model distribution. Re-run the complete dependency and model audit before publishing a commercial build; this file records the engineering audit and is not legal advice.
