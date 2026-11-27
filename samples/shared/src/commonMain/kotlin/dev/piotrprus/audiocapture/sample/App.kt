@file:OptIn(ExperimentalVoiceProcessing::class)

package dev.piotrprus.audiocapture.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.piotrprus.audiocapture.AudioCapture
import dev.piotrprus.audiocapture.AudioCaptureException
import dev.piotrprus.audiocapture.AudioEncoder
import dev.piotrprus.audiocapture.CaptureConfig
import dev.piotrprus.audiocapture.CaptureSession
import dev.piotrprus.audiocapture.CaptureState
import dev.piotrprus.audiocapture.ExperimentalVoiceProcessing
import dev.piotrprus.audiocapture.FileOutput
import dev.piotrprus.audiocapture.InputDevice
import dev.piotrprus.audiocapture.Recording
import dev.piotrprus.audiocapture.PcmOutput
import dev.piotrprus.audiocapture.VoiceProcessing
import kotlinx.coroutines.launch
import kotlin.time.Duration

private enum class Mode(val label: String, val stream: Boolean, val encoder: AudioEncoder?) {
    Stream("Stream", true, null),
    Aac("AAC file", false, AudioEncoder.AacLc),
    Wav("WAV file", false, AudioEncoder.Wav),
    Both("Stream + AAC", true, AudioEncoder.AacLc),
}

@Composable
fun App() {
    var fullDemo by remember { mutableStateOf(false) }
    MaterialTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("AudioCapture", style = MaterialTheme.typography.headlineMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !fullDemo, onClick = { fullDemo = false }, label = { Text("Quick start") })
                FilterChip(selected = fullDemo, onClick = { fullDemo = true }, label = { Text("Everything") })
            }
            if (fullDemo) FullDemo() else QuickStart()
        }
    }
}

/** Every mode and option: stream, AAC, WAV, both at once, voice processing, pause and resume. */
@Composable
private fun FullDemo() {
    val capture = remember { AudioCapture() }
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(Mode.Both) }
    var voiceProcessing by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf<CaptureSession?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var chunkCount by remember { mutableIntStateOf(0) }
    var streamed by remember { mutableStateOf(Duration.ZERO) }
    val devices by remember { capture.inputDevicesFlow() }.collectAsState(initial = emptyList())
    // Saved by stableKey, so the choice survives a headset reconnecting with a new id.
    var deviceKey by remember { mutableStateOf<String?>(null) }
    val device = devices.firstOrNull { it.stableKey == deviceKey }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Permission: ${capture.permission()}", style = MaterialTheme.typography.bodyMedium)

        val active = session
        if (active == null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Mode.entries.forEach { option ->
                    FilterChip(selected = mode == option, onClick = { mode = option }, label = { Text(option.label) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = voiceProcessing, onCheckedChange = { voiceProcessing = it })
                Text("Voice processing (experimental)")
            }
            Button(onClick = {
                error = null
                recording = null
                chunkCount = 0
                streamed = Duration.ZERO
                val config = CaptureConfig(
                    pcm = if (mode.stream) PcmOutput() else null,
                    file = mode.encoder?.let { encoder ->
                        val extension = if (encoder == AudioEncoder.Wav) "wav" else "m4a"
                        FileOutput(capture.recordingPath("capture.$extension"))
                    },
                    voiceProcessing = if (voiceProcessing) VoiceProcessing() else null,
                    device = device,
                )
                scope.launch {
                    try {
                        session = capture.start(config)
                    } catch (e: AudioCaptureException) {
                        error = e.message
                    }
                }
            }) { Text("Start") }
            MicrophonePicker(devices, selected = device, onSelect = { deviceKey = it?.stableKey })
        } else {
            ActiveSession(
                session = active,
                onChunk = { chunkCount++; streamed += it },
                onFinished = { result, failure ->
                    recording = result
                    error = failure
                    session = null
                },
            )
            Text("Recording from: ${active.inputDevice?.let { "${it.name} (${it.type})" } ?: "unknown"}")
            Text("Chunks: $chunkCount · streamed: $streamed")
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        recording?.let { finished ->
            Text("Saved ${finished.durationMillis} ms to ${finished.path}")
            OutlinedButton(onClick = { play(finished.path) }) { Text("Play") }
        }
    }
}

@Composable
private fun ActiveSession(
    session: CaptureSession,
    onChunk: (Duration) -> Unit,
    onFinished: (Recording?, String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state by session.state.collectAsState()
    val level by session.level.collectAsState()
    val meter by session.normalizedLevel.collectAsState()

    LaunchedEffect(session) {
        try {
            session.chunks.collect { onChunk(it.duration) }
        } catch (e: AudioCaptureException) {
            onFinished(null, e.message)
        }
    }

    Text("State: $state")
    Text("Peak ${level.peakDbfs.toInt()} dBFS · RMS ${level.rmsDbfs.toInt()} dBFS")
    Box(
        Modifier
            .fillMaxWidth()
            .height(16.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(meter)
                .height(16.dp)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state is CaptureState.Paused) {
            OutlinedButton(onClick = session::resume) { Text("Resume") }
        } else {
            OutlinedButton(onClick = session::pause) { Text("Pause") }
        }
        Button(onClick = { scope.launch { onFinished(session.stop(), null) } }) { Text("Stop") }
        OutlinedButton(onClick = { scope.launch { session.cancel(); onFinished(null, null) } }) { Text("Cancel") }
    }
}

/** Dropdown of "System default" plus every connected microphone. Devices with the same name get a number. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MicrophonePicker(devices: List<InputDevice>, selected: InputDevice?, onSelect: (InputDevice?) -> Unit) {
    fun label(device: InputDevice?): String {
        if (device == null) return "System default"
        val sameName = devices.filter { it.name == device.name }
        val name = if (sameName.size > 1) "${device.name} (${sameName.indexOf(device) + 1})" else device.name
        return "$name · ${device.type}"
    }
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text("Microphone") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            (listOf(null) + devices).forEach { device ->
                DropdownMenuItem(
                    text = { Text(label(device)) },
                    onClick = {
                        onSelect(device)
                        expanded = false
                    },
                )
            }
        }
    }
}
