package dev.piotrprus.audiocapture.sample

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.Foundation.NSURL

private var player: AVAudioPlayer? = null

@OptIn(ExperimentalForeignApi::class)
actual fun play(path: String) {
    AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, null)
    AVAudioSession.sharedInstance().setActive(true, null)
    player = AVAudioPlayer(contentsOfURL = NSURL.fileURLWithPath(path), error = null).apply { play() }
}
