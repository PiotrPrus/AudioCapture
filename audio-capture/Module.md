# Module AudioCapture

Microphone capture for Kotlin Multiplatform on Android and iOS.

Start with [AudioCapture][dev.piotrprus.audiocapture.AudioCapture]:

- [stream][dev.piotrprus.audiocapture.stream] gives a cold `Flow` of raw PCM
  [AudioChunk][dev.piotrprus.audiocapture.AudioChunk]s. The microphone is open only while the flow
  is collected.
- [start][dev.piotrprus.audiocapture.AudioCapture.start] returns a
  [CaptureSession][dev.piotrprus.audiocapture.CaptureSession] that can stream, record to an AAC or
  WAV file, or both, with levels, pause/resume and interruption handling.

[CaptureConfig][dev.piotrprus.audiocapture.CaptureConfig] holds every option. Its defaults, 16 kHz
mono PCM16 in 100 ms chunks, suit speech-to-text.

## Quick start

```kotlin
val capture = AudioCapture()

val session = capture.start(CaptureConfig(file = FileOutput("$dir/memo.m4a")))
launch { session.chunks.collect { speechApi.send(it.bytes) } }
// ...
val recording = session.stop()
```

# Package dev.piotrprus.audiocapture

The public API: the capture entry point, sessions, configuration and audio types.
