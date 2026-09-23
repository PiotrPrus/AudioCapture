# AudioCapture

Microphone capture for Kotlin Multiplatform, on Android and iOS.

- **Record** to AAC (`.m4a`) or WAV in one line.
- **Stream** raw audio as a `Flow` of chunks, for speech-to-text, visualisers or your own processing.
- **Both at once** from one microphone: send audio to a speech API while keeping the file.
- A ready-made **0..1 level** for meters and animations, plus peak and RMS in dBFS.
- **Pause and resume**, including after phone calls and Siri.
- Microphone **permission handled for you**.
- Input devices, the Android audio source and the iOS audio session, all from common code.

📖 **[API documentation](https://piotrprus.github.io/AudioCapture/)**

## Quick start (30 seconds)

**1. Add the dependency**

```kotlin
commonMain.dependencies {
    implementation("io.github.piotrprus:audio-capture:0.1.0")
}
```

**2. iOS only:** add a microphone description to `iosApp/Info.plist`. Android needs nothing: the library declares the permission.

```xml
<key>NSMicrophoneUsageDescription</key>
<string>Records your voice.</string>
```

**3. Record**

```kotlin
val capture = AudioCapture()

val session = capture.record("hello.m4a") // asks for the microphone the first time
delay(3.seconds)
val recording = session.stop()           // Recording(path, encoder, durationMillis)
```

That's it: `recording.path` is a playable `.m4a` in your app's private storage.

### In Compose

```kotlin
@Composable
fun Recorder() {
    val capture = remember { AudioCapture() }
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<CaptureSession?>(null) }

    val active = session
    if (active == null) {
        Button(onClick = { scope.launch { session = capture.record("memo.m4a") } }) { Text("Record") }
    } else {
        val level by active.normalizedLevel.collectAsState() // 0..1, ready for UI
        LinearProgressIndicator(progress = { level })
        Button(onClick = { scope.launch { active.stop(); session = null } }) { Text("Stop") }
    }
}
```

The sample's **Quick start** screen ([`QuickStart.kt`](samples/shared/src/commonMain/kotlin/dev/piotrprus/audiocapture/sample/QuickStart.kt)) adds a live waveform and playback in about 80 lines.

## Two ways in

| You want | Use | The microphone is on |
|---|---|---|
| A file, maybe with live levels or chunks | `capture.record(name)` or `capture.start(config)` | from `start` until `stop()` or `cancel()` |
| Only the live audio, no file | `capture.stream(config)` | while the `Flow` is collected |

### Stream raw audio

```kotlin
capture.stream().collect { chunk ->
    speechApi.send(chunk.bytes)       // 16 kHz mono 16-bit PCM, 100 ms per chunk
    val samples = chunk.toFloatArray() // or just numbers from -1.0 to 1.0
}
```

### Stream and record together

```kotlin
val session = capture.start(CaptureConfig(file = FileOutput(capture.recordingPath("memo.m4a"))))

launch { session.chunks.collect { speechApi.send(it.bytes) } }
launch { session.normalizedLevel.collect { meter = it } }

session.pause()
session.resume()
val recording = session.stop()
```

`session.state` is a `StateFlow` of `Recording`, `Paused(reason)` or `Stopped(error)`. If the microphone fails mid-session, `chunks` fails with `AudioCaptureException`, the state becomes `Stopped(error)`, and the audio captured so far is still saved. `stop()` returns `null` when no audio was captured at all.

### Where files go

`capture.recordingPath("memo.m4a")` returns an absolute path in the app's private storage: `filesDir/recordings` on Android, `Application Support/recordings` on iOS. The extension picks the format: `.wav` records WAV, anything else AAC. Any absolute path works too.

### Playing a recording back

The library records; playing is one call on each platform:

```kotlin
// Android
MediaPlayer().apply { setDataSource(path); prepare(); start() }

// iOS
AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, null)
AVAudioPlayer(contentsOfURL = NSURL.fileURLWithPath(path), error = null).play()
```

The sample wraps these in an `expect fun play(path)`: [`Player.kt`](samples/shared/src/commonMain/kotlin/dev/piotrprus/audiocapture/sample/Player.kt).

### Permissions

`start()` and `record()` ask for the microphone when needed and throw `AudioCaptureException` if the user refuses. To ask at a moment of your choosing, for example on an onboarding screen:

```kotlin
when (capture.requestPermission()) {
    MicPermission.Granted -> startRecording()
    else -> showWhyWeNeedTheMicrophone()
}
```

`capture.permission()` reads the current state without asking.

- **Android:** the library's manifest declares `RECORD_AUDIO` and shows the system dialog from a transparent activity; the app must be in the foreground. A build flavour that must not ask for it can drop it with `<uses-permission android:name="android.permission.RECORD_AUDIO" tools:node="remove" />`. `AudioCapture()` gets the application context through `androidx.startup`; if your app disables startup initializers, call `AudioCapture(context)`.
- **iOS:** add `NSMicrophoneUsageDescription` to `Info.plist`.

## Configuration

```kotlin
CaptureConfig(
    sampleRate = 48_000,
    channels = 2,
    chunkDuration = 50.milliseconds,
    pcm = PcmOutput(encoding = PcmEncoding.Float32),
    file = FileOutput(capture.recordingPath("take.wav")),
    device = capture.inputDevices().first { it.type == InputDeviceType.Usb },
    interruption = InterruptionMode.PauseResume,
    android = AndroidOptions(audioSource = AndroidAudioSource.Unprocessed),
    ios = IosOptions(mode = IosSessionMode.Measurement, bluetoothInput = IosBluetoothInput.Hfp),
)
```

The defaults (16 kHz, mono, 16-bit, 100 ms chunks) suit speech and most speech-to-text APIs.

| | Android | iOS |
|---|---|---|
| Streaming | `AudioRecord`, float with a 16-bit fallback | `AVAudioEngine` tap, resampled by `AVAudioConverter` |
| AAC file | `MediaCodec` + `MediaMuxer` | `ExtAudioFile` |
| WAV file | 16-bit PCM | 16-bit PCM |
| Interruptions | Recording callback `isClientSilenced` (API 29+) | `AVAudioSession` interruption notifications |
| Route changes | Handled by the system | Tap rebuilt for the new input format |
| Input devices | `AudioManager.getDevices` + `setPreferredDevice` (Bluetooth headsets not listed yet) | `availableInputs` + `setPreferredInput` |

### Voice processing (experimental)

```kotlin
@OptIn(ExperimentalVoiceProcessing::class)
val config = CaptureConfig(voiceProcessing = VoiceProcessing(echoCancel = true, noiseSuppress = true))
```

- **Android:** uses `AcousticEchoCanceler`, `NoiseSuppressor` and `AutomaticGainControl` where the device provides them. Usually subtle.
- **iOS:** switches on Apple's voice processing on the input node, which always does echo cancellation and noise suppression together. It is tuned for calls, so the voice sounds noticeably different, like a phone call. Pass `applyOnIos = false` to keep the Android effects only, and `iosDucking` to control how much other apps' audio is lowered.

`session.voiceProcessing` tells what actually took effect. It is behind an opt-in because the two platforms sound so different, and the API may change.

### Recording in the background

**Android.** When your app leaves the foreground without a foreground service of type `microphone`, the system silences the recorder. The session sees that as an interruption (`PauseReason.Interruption`, API 29+). To keep recording, run a foreground service:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<service android:name=".RecordingService" android:foregroundServiceType="microphone" />
```

Start it while the app is visible; since Android 14 a microphone service cannot be started from the background.

**iOS.** Add the `audio` background mode to `Info.plist`, and start the session while the app is in the foreground. Without it, capture stops when the app is suspended.

```xml
<key>UIBackgroundModes</key>
<array><string>audio</string></array>
```

## Glossary

- **PCM:** raw, uncompressed audio: a list of numbers, one per sample.
- **Sample rate:** samples per second. 16 000 is plenty for speech; 44 100 or 48 000 for music.
- **Frame:** one sample for every channel at the same moment. In mono, a frame is one sample.
- **Chunk:** a block of frames delivered together, `chunkDuration` long (100 ms by default).
- **`Int16` / `Float32`:** how each sample is stored in `chunk.bytes`. You rarely need to care: `chunk.toFloatArray()` always gives -1.0..1.0.
- **dBFS:** decibels relative to the loudest possible sample. 0 is the maximum and everything else is negative; speech near a phone peaks around -20 to -10. For UI, use `normalizedLevel` instead.

## Sample

`samples/` has a Compose Multiplatform app for Android (`samples/androidApp`) and iOS (`samples/iosApp`). **Quick start** records with a live waveform and plays it back; **Everything** shows every mode and option.

## Roadmap

- Speech-to-text module on top of the capture: on-device (`SpeechRecognizer`, `SFSpeechRecognizer`) and cloud.
- JVM desktop and Wasm targets.
- Bluetooth SCO routing on Android.

## License

```
Copyright 2026 Piotr Prus

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
