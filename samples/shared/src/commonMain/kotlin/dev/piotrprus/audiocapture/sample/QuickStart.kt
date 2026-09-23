package dev.piotrprus.audiocapture.sample

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import dev.piotrprus.audiocapture.AudioCapture
import dev.piotrprus.audiocapture.AudioCaptureException
import dev.piotrprus.audiocapture.CaptureSession
import dev.piotrprus.audiocapture.Recording
import dev.piotrprus.audiocapture.record
import kotlinx.coroutines.launch

/** The smallest useful screen: record, watch a live waveform, stop, play it back. */
@Composable
fun QuickStart() {
    val capture = remember { AudioCapture() }
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<CaptureSession?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val active = session
        if (active == null) {
            Button(onClick = {
                scope.launch {
                    error = null
                    // Asks for microphone permission the first time.
                    try { session = capture.record("quick-start.m4a") } catch (e: AudioCaptureException) { error = e.message }
                }
            }) { Text("Record") }
        } else {
            Waveform(active)
            Button(onClick = { scope.launch { recording = active.stop(); session = null } }) { Text("Stop") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        recording?.let { finished ->
            Text("Recorded ${finished.durationMillis / 1000.0} s")
            OutlinedButton(onClick = { play(finished.path) }) { Text("Play") }
        }
    }
}

@Composable
private fun Waveform(session: CaptureSession) {
    val bars = remember(session) { mutableStateListOf<Float>() }
    LaunchedEffect(session) {
        // One bar per chunk; normalizedLevel is already 0..1 and updated just before each chunk.
        session.chunks.collect {
            bars += session.normalizedLevel.value
            if (bars.size > BARS) bars.removeAt(0)
        }
    }
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(96.dp)) {
        val step = size.width / BARS
        val middle = size.height / 2
        bars.forEachIndexed { index, level ->
            val half = maxOf(2f, level * middle)
            val x = index * step + step / 2
            drawLine(color, Offset(x, middle - half), Offset(x, middle + half), strokeWidth = step * 0.6f)
        }
    }
}

private const val BARS = 60
