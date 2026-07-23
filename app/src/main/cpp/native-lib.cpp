#include <jni.h>

#include <memory>
#include <vector>

#include "AudioEngine.h"

namespace {

// One output stream for the whole process. The engine outlives individual
// activities so a rotation or a brief background trip does not tear down audio.
AudioEngine &engine() {
    static AudioEngine instance;
    return instance;
}

std::vector<int16_t> toVector(JNIEnv *env, jshortArray array) {
    const jsize length = env->GetArrayLength(array);
    std::vector<int16_t> out(static_cast<size_t>(length));
    if (length > 0) {
        env->GetShortArrayRegion(array, 0, length, reinterpret_cast<jshort *>(out.data()));
    }
    return out;
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_dev_rebound_audio_NativeAudio_nativeSetSong(JNIEnv *env, jobject,
                                                 jshortArray pcm,
                                                 jint channels,
                                                 jint sampleRate) {
    engine().setSong(toVector(env, pcm), channels, sampleRate);
}

JNIEXPORT void JNICALL
Java_dev_rebound_audio_NativeAudio_nativeSetSfx(JNIEnv *env, jobject,
                                                jshortArray pcm,
                                                jint channels) {
    engine().setSfx(toVector(env, pcm), channels);
}

JNIEXPORT jboolean JNICALL
Java_dev_rebound_audio_NativeAudio_nativeStart(JNIEnv *, jobject) {
    return engine().start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_rebound_audio_NativeAudio_nativeStop(JNIEnv *, jobject) {
    engine().stop();
}

JNIEXPORT void JNICALL
Java_dev_rebound_audio_NativeAudio_nativePlaySong(JNIEnv *, jobject) {
    engine().playSong();
}

JNIEXPORT void JNICALL
Java_dev_rebound_audio_NativeAudio_nativePlaySongFromMs(JNIEnv *, jobject, jdouble ms) {
    engine().playSongFromMs(ms);
}

JNIEXPORT void JNICALL
Java_dev_rebound_audio_NativeAudio_nativePauseSong(JNIEnv *, jobject) {
    engine().pauseSong();
}

JNIEXPORT void JNICALL
Java_dev_rebound_audio_NativeAudio_nativeResumeSong(JNIEnv *, jobject) {
    engine().resumeSong();
}

JNIEXPORT void JNICALL
Java_dev_rebound_audio_NativeAudio_nativeTriggerSfx(JNIEnv *, jobject) {
    engine().triggerSfx();
}

JNIEXPORT jdouble JNICALL
Java_dev_rebound_audio_NativeAudio_nativeSongPositionMs(JNIEnv *, jobject) {
    return engine().songPositionMs();
}

JNIEXPORT jboolean JNICALL
Java_dev_rebound_audio_NativeAudio_nativeIsSongFinished(JNIEnv *, jobject) {
    return engine().isSongFinished() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_rebound_audio_NativeAudio_nativeIsSongStarted(JNIEnv *, jobject) {
    return engine().isSongStarted() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_dev_rebound_audio_NativeAudio_nativeStreamSampleRate(JNIEnv *, jobject) {
    return engine().streamSampleRate();
}

JNIEXPORT jint JNICALL
Java_dev_rebound_audio_NativeAudio_nativeFramesPerBurst(JNIEnv *, jobject) {
    return engine().framesPerBurst();
}

JNIEXPORT jdouble JNICALL
Java_dev_rebound_audio_NativeAudio_nativeOutputLatencyMs(JNIEnv *, jobject) {
    return engine().outputLatencyMs();
}

} // extern "C"
