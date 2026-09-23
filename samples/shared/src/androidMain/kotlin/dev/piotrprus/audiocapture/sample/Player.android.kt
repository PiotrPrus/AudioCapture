package dev.piotrprus.audiocapture.sample

import android.media.MediaPlayer

private var player: MediaPlayer? = null

actual fun play(path: String) {
    player?.release()
    player = MediaPlayer().apply {
        setDataSource(path)
        setOnCompletionListener { it.release(); player = null }
        prepare()
        start()
    }
}
