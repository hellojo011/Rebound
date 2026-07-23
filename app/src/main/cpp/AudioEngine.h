#pragma once

#include <oboe/Oboe.h>

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>

/**
 * Low-latency playback for one song plus a handful of one-shot hit sounds.
 *
 * The point of this class is the clock. A rhythm game cannot time input against
 * wall-clock or frame deltas, because what the player reacts to is the audio
 * they *hear*, which trails what the app has written by a device-dependent
 * buffer. songPositionMs() therefore reports the position of the frame currently
 * leaving the speaker, derived from the stream's own presentation timestamp.
 *
 * Both the song and the hit sound must already be at the stream's sample rate.
 * Resampling is done once at load time on the Kotlin side, so the audio callback
 * stays a straight copy-and-mix with no rate conversion in the hot path.
 */
class AudioEngine : public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback {
public:
    AudioEngine() = default;
    ~AudioEngine() override;

    /** Hands over the decoded song. Must be called before start(). */
    void setSong(std::vector<int16_t> pcm, int32_t channels, int32_t sampleRate);

    /** Hands over the hit sound, at the same sample rate as the song. */
    void setSfx(std::vector<int16_t> pcm, int32_t channels);

    bool start();
    void stop();

    /** Begins the song from the top. Safe to call once the stream is running. */
    void playSong();

    /**
     * Begins playback from an arbitrary point.
     *
     * The editor needs this: checking that a beat grid lines up means listening
     * at the chorus, not sitting through the intro every time.
     */
    void playSongFromMs(double ms);

    void pauseSong();

    /** Continues from where [pauseSong] stopped, rather than restarting. */
    void resumeSong();

    /** Fires a hit sound on the next audio callback. Cheap and callback-safe. */
    void triggerSfx();

    /**
     * Position of the audio currently being heard, in ms from the first sample.
     * Negative before playback begins. This is the value gameplay times against.
     */
    double songPositionMs() const;

    bool isSongFinished() const;

    /**
     * True once the song's first frame has actually been written.
     *
     * The count-in hands over to the audio clock on this rather than on the
     * reported position crossing zero: a position that is wrong for any reason
     * would otherwise leave the clock waiting for it forever.
     */
    bool isSongStarted() const { return mSongStartFrame.load() >= 0; }

    int32_t streamSampleRate() const { return mStreamSampleRate; }
    int32_t framesPerBurst() const { return mFramesPerBurst; }

    /** Reported round-trip output latency in ms, for diagnostics. */
    double outputLatencyMs() const;

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream,
                                          void *audioData,
                                          int32_t numFrames) override;

    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;

private:
    static constexpr int kMaxVoices = 8;
    static constexpr float kInt16ToFloat = 1.0f / 32768.0f;
    static constexpr float kSongGain = 0.85f;
    static constexpr float kSfxGain = 0.7f;

    struct Voice {
        int64_t position = 0;
        bool active = false;
    };

    std::shared_ptr<oboe::AudioStream> mStream;
    mutable std::mutex mLock;

    std::vector<int16_t> mSongPcm;
    int64_t mSongFrames = 0;
    int32_t mSongChannels = 2;
    int32_t mSongSampleRate = 48000;

    std::vector<int16_t> mSfxPcm;
    int64_t mSfxFrames = 0;
    int32_t mSfxChannels = 1;

    int32_t mStreamSampleRate = 48000;
    int32_t mFramesPerBurst = 192;

    // --- touched by the audio callback ---
    std::atomic<bool> mSongPlaying{false};
    std::atomic<int64_t> mStreamFrames{0};   // frames this stream has rendered
    std::atomic<int64_t> mSongStartFrame{-1}; // stream frame the song began on
    // Stream frames that elapsed while the song was paused. The stream keeps
    // running when the song does not, so without this the song clock would carry
    // on advancing through a pause and jump on resume.
    std::atomic<int64_t> mSongPausedFrames{0};
    // Where playback was started from, so reported position stays absolute
    // within the song rather than restarting at zero after a seek.
    std::atomic<int64_t> mSongSeekFrame{0};
    std::atomic<int> mPendingSfx{0};
    int64_t mSongFrame = 0;                   // callback-only
    Voice mVoices[kMaxVoices];                // callback-only
};
