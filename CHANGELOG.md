# Changelog

## Unreleased

- Bluetooth headset microphones are listed by `inputDevices()`. iOS lists every connected headset, so an app can pick between several. Android lists only the headset that is the system's active device for calls. Recording from one uses the headset's call profile (8–16 kHz).
- Android: a Bluetooth `CaptureConfig.device` is routed with `setCommunicationDevice` (API 31+, matched by address) or `startBluetoothSco` (below API 31), and the routing is put back when the session stops. The library manifest adds `MODIFY_AUDIO_SETTINGS` and `BLUETOOTH` (max SDK 30), both granted at install.
- iOS: choosing a Bluetooth `CaptureConfig.device` turns on `IosBluetoothInput.Hfp` for that session when `bluetoothInput` is `Off`.
- `InputDevice.stableKey` identifies the same device across reconnects, for saving the user's choice. A `CaptureConfig.device` whose `id` is gone is found again by this key.
- `InputDeviceType.Car` for CarPlay audio on iOS.
- `AudioCapture.inputDevicesFlow()`: the device list, updated as microphones connect and disconnect.
- iOS: `inputDevicesFlow()` reacts to every route change except category changes, and to the app becoming active.
- Sample app: a microphone dropdown on the Everything screen, kept current with `inputDevicesFlow()`.
- `androidDeviceTest` source set with an on-device routing test: `./gradlew :audio-capture:connectedAndroidDeviceTest`.
- Android: device names no longer include the address, except for built-in microphones, which need it to tell them apart.

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
