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
- Experimental echo cancellation, noise suppression and automatic gain control (`VoiceProcessing`, opt-in with `@ExperimentalVoiceProcessing`). On iOS it runs Apple's voice processing, which noticeably changes the tone of the voice.
- Android audio source selection.
- iOS audio session category, mode (`Default`, `Measurement`, `VoiceChat`), Bluetooth input (`Off`, `Hfp`, `HighQuality` on iOS 26), and whether pausing deactivates the session; or leave the session to the app.
- iOS asks for microphone permission on the first `start()` and fails if declined, rather than recording silence.
- iOS recovers from media services resets and route changes, including those that arrive during an interruption.
- `AudioCapture.record(fileName)`: permission, storage and recording in one call.
- `requestPermission()` on both platforms; `start()` asks when needed.
- `recordingPath(fileName)` for a private storage location; `FileOutput` picks the encoder from the extension.
- `CaptureSession.normalizedLevel` (0..1 for UI), `CaptureSession.config`, and `AudioChunk.level()`.
- `CaptureConfig.pcm` / `PcmOutput` name the raw-audio output.
- Sample app for Android and iOS, with a minimal Quick start screen.
- `CaptureSession.inputDevice` (the microphone actually in use) and `CaptureSession.voiceProcessing` (the effects that actually took effect).
- `stop()` returns `null` and deletes the file when nothing was captured, instead of returning an unplayable file.
