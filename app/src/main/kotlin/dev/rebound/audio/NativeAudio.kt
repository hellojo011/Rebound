package dev.rebound.audio

/** Thin binding to the Oboe engine in `librebound.so`. */
object NativeAudio {

    init {
        System.loadLibrary("rebound")
    }

    external fun nativeSetSong(pcm: ShortArray, channels: Int, sampleRate: Int)
    external fun nativeSetSfx(pcm: ShortArray, channels: Int)
    external fun nativeStart(): Boolean
    external fun nativeStop()
    external fun nativePlaySong()
    external fun nativePlaySongFromMs(ms: Double)
    external fun nativePauseSong()
    external fun nativeResumeSong()
    external fun nativeTriggerSfx()
    external fun nativeSongPositionMs(): Double
    external fun nativeIsSongFinished(): Boolean
    external fun nativeIsSongStarted(): Boolean
    external fun nativeStreamSampleRate(): Int
    external fun nativeFramesPerBurst(): Int
    external fun nativeOutputLatencyMs(): Double
}
