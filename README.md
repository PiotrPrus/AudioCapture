# AudioCapture

Microphone capture for Kotlin Multiplatform, on Android and iOS.

- **Stream** raw PCM as a `Flow` of chunks: 16-bit or float, any sample rate, mono or stereo.
- **Record** to AAC (`.m4a`) or WAV.
- **Both at once** from one microphone: send audio to a speech-to-text API while keeping the file.
- Live **levels** in dBFS, plus a normaliser that makes meters look the same on every device.
- **Pause and resume**, including after phone calls and Siri, with a choice of what interruptions do.
- **Input devices**, **echo cancellation**, **noise suppression**, **gain control**, the Android audio source and the iOS audio session, all from common code.

📖 **[API documentation](https://piotrprus.github.io/AudioCapture/)**

## Installation

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.piotrprus:audio-capture:0.1.0")
        }
    }
}
```

Targets: Android (minSdk 24), `iosArm64` and `iosSimulatorArm64`.

## Usage

### Stream PCM

```kotlin
val capture = AudioCapture()

// The microphone opens when collection starts and closes when the collector is cancelled.
capture.stream().collect { chunk ->
    speechApi.send(chunk.bytes) // 16 kHz mono PCM16, 100 ms per chunk
}
```

### Record a file

```kotlin
val session = capture.start(
    CaptureConfig(
        stream = null,
        file = FileOutput(path = "$dir/memo.m4a", encoder = AudioEncoder.AacLc),
    ),
)
// ...
val recording = session.stop() // Recording(path, encoder, durationMillis)
```

### Stream and record together

```kotlin
val session = capture.start(CaptureConfig(file = FileOutput("$dir/memo.m4a")))

launch { session.chunks.collect { speechApi.send(it.bytes) } }
launch { session.level.collect { meter = normalizer.normalize(it, 100.milliseconds) } }

session.pause()
session.resume()
val recording = session.stop()
```

`session.state` is a `StateFlow` of `Recording`, `Paused(reason)` or `Stopped(error)`. If the microphone fails mid-session, `chunks` fails with `AudioCaptureException`, the state becomes `Stopped(error)`, and the audio captured so far is still saved to the file.

### Configuration

```kotlin
CaptureConfig(
    sampleRate = 48_000,
    channels = 2,
    chunkDuration = 50.milliseconds,
    stream = StreamOutput(encoding = PcmEncoding.Float32),
    file = FileOutput("$dir/take.wav", AudioEncoder.Wav),
    device = capture.inputDevices().first { it.type == InputDeviceType.Usb },
    echoCancel = true,
    noiseSuppress = true,
    autoGain = false,
    interruption = InterruptionMode.PauseResume,
    android = AndroidOptions(audioSource = AndroidAudioSource.Unprocessed),
    ios = IosOptions(mode = IosSessionMode.Measurement, mixWithOthers = true),
)
```

| Option | Android | iOS |
|---|---|---|
| Streaming | `AudioRecord`, float with a 16-bit fallback | `AVAudioEngine` tap, resampled by `AVAudioConverter` |
| AAC file | `MediaCodec` + `MediaMuxer` | `ExtAudioFile` |
| WAV file | 16-bit PCM | 16-bit PCM |
| Echo cancellation, noise suppression, gain control | `AcousticEchoCanceler`, `NoiseSuppressor`, `AutomaticGainControl` where the device has them | Voice processing on the input node; any of the three switches it on |
| Interruptions | Recording callback `isClientSilenced` (API 29+) | `AVAudioSession` interruption notifications |
| Route changes | Handled by the system | Tap rebuilt for the new input format |
| Input devices | `AudioManager.getDevices` + `setPreferredDevice` | `availableInputs` + `setPreferredInput` |

### Permissions

The library does not request permission. Check `capture.permission()` and ask before the first `start`:

- **Android:** the library's manifest declares `RECORD_AUDIO`. Request it at runtime as usual.
- **iOS:** add `NSMicrophoneUsageDescription` to `Info.plist`. Request with `AVAudioApplication.requestRecordPermission`, or let the first session show the system prompt.

On Android, `AudioCapture()` gets the application context through `androidx.startup`. If your app disables startup initializers, call `AudioCapture(context)` instead.

## Sample

`samples/` has a Compose Multiplatform app for Android (`samples/androidApp`) and iOS (`samples/iosApp`). It streams, records, meters, pauses, and plays back what was recorded.

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
