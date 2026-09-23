# Changelog

## 0.1.0 — 2026-09-23

First release.

- `AudioCapture` for Android (minSdk 24) and iOS (`iosArm64`, `iosSimulatorArm64`).
- Raw PCM streaming as a `Flow<AudioChunk>`: `Int16` or `Float32`, 8–96 kHz, mono or stereo, configurable chunk duration. Chunks always arrive in the requested format; iOS resamples with `AVAudioConverter` when the hardware runs at another rate.
- File recording to AAC-LC (`.m4a`) or 16-bit WAV, alone or while streaming.
- `CaptureSession` with `state`, `level` (peak and RMS, linear and dBFS), `pause`, `resume`, `stop` and `cancel`.
- `LevelNormalizer`: an adaptive 0..1 level for meters and visuals that behaves the same on hot iOS inputs and quieter Android ones.
- Interruptions (calls, Siri, another app taking the microphone) with `InterruptionMode.None`, `Pause` or `PauseResume`.
- Input device listing and selection.
- Echo cancellation, noise suppression and automatic gain control.
- Android audio source selection.
- iOS audio session category, mode and options, or leave the session to the app.
- Sample app for Android and iOS.
