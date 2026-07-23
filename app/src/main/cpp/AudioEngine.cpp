#include "AudioEngine.h"

#include <android/log.h>
#include <ctime>
#include <cstring>
#include <algorithm>

#define LOG_TAG "ReboundAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

int64_t nowMonotonicNanos() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

} // namespace

AudioEngine::~AudioEngine() {
    stop();
}

void AudioEngine::setSong(std::vector<int16_t> pcm, int32_t channels, int32_t sampleRate) {
    std::lock_guard<std::mutex> guard(mLock);
    mSongPcm = std::move(pcm);
    mSongChannels = std::max(1, channels);
    mSongSampleRate = sampleRate;
    mSongFrames = static_cast<int64_t>(mSongPcm.size()) / mSongChannels;
    LOGI("song loaded: %lld frames, %d ch, %d Hz",
         static_cast<long long>(mSongFrames), mSongChannels, sampleRate);
}

void AudioEngine::setSfx(std::vector<int16_t> pcm, int32_t channels) {
    std::lock_guard<std::mutex> guard(mLock);
    mSfxPcm = std::move(pcm);
    mSfxChannels = std::max(1, channels);
    mSfxFrames = static_cast<int64_t>(mSfxPcm.size()) / mSfxChannels;
}

bool AudioEngine::start() {
    std::lock_guard<std::mutex> guard(mLock);
    if (mStream) return true;

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Stereo)
            ->setUsage(oboe::Usage::Game)
            ->setContentType(oboe::ContentType::Music)
            ->setSampleRate(mSongSampleRate)
            ->setDataCallback(this)
            ->setErrorCallback(this);

    oboe::Result result = builder.openStream(mStream);
    if (result != oboe::Result::OK) {
        LOGE("failed to open stream: %s", oboe::convertToText(result));
        mStream.reset();
        return false;
    }

    mStreamSampleRate = mStream->getSampleRate();
    mFramesPerBurst = mStream->getFramesPerBurst();

    // Two bursts is the standard starting point: small enough to keep latency
    // low, large enough that an occasional slow callback does not underrun.
    mStream->setBufferSizeInFrames(mFramesPerBurst * 2);

    if (mStreamSampleRate != mSongSampleRate) {
        // Kotlin resamples to the device rate before loading, so this only fires
        // if the device handed us something other than what it advertised.
        LOGE("stream rate %d does not match song rate %d; timing will drift",
             mStreamSampleRate, mSongSampleRate);
    }

    // A fresh stream restarts its own frame counter, so ours has to as well.
    // songPositionMs() subtracts the song's start frame -- taken from this
    // counter -- from the frame the *stream* reports as presented. Carrying the
    // previous stream's total over would subtract a large number from a small
    // one and report a position that never advances, which reads on screen as
    // the song freezing the instant the count-in ends.
    mStreamFrames.store(0);
    mSongStartFrame.store(-1);
    mSongPausedFrames.store(0);
    mSongSeekFrame.store(0);
    mSongFrame = 0;
    mSongPlaying.store(false);

    result = mStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("failed to start stream: %s", oboe::convertToText(result));
        mStream->close();
        mStream.reset();
        return false;
    }

    LOGI("stream open: %d Hz, burst %d, buffer %d, ch %d",
         mStreamSampleRate, mFramesPerBurst,
         mStream->getBufferSizeInFrames(), mStream->getChannelCount());
    return true;
}

void AudioEngine::stop() {
    std::shared_ptr<oboe::AudioStream> stream;
    {
        std::lock_guard<std::mutex> guard(mLock);
        stream = mStream;
        mStream.reset();
    }
    mSongPlaying.store(false);
    if (stream) {
        stream->requestStop();
        stream->close();
    }
}

void AudioEngine::playSong() {
    playSongFromMs(0.0);
}

void AudioEngine::playSongFromMs(double ms) {
    int64_t frame = static_cast<int64_t>(ms * mStreamSampleRate / 1000.0);
    if (frame < 0) frame = 0;
    if (frame > mSongFrames) frame = mSongFrames;

    mSongFrame = frame;
    mSongSeekFrame.store(frame);
    mSongStartFrame.store(-1);
    mSongPausedFrames.store(0);
    mSongPlaying.store(true);
}

void AudioEngine::pauseSong() {
    mSongPlaying.store(false);
}

void AudioEngine::resumeSong() {
    // Deliberately leaves mSongFrame and mSongStartFrame alone, so playback
    // picks up mid-song instead of restarting.
    mSongPlaying.store(true);
}

void AudioEngine::triggerSfx() {
    mPendingSfx.fetch_add(1, std::memory_order_relaxed);
}

bool AudioEngine::isSongFinished() const {
    if (!mSongPlaying.load()) return false;
    const int64_t start = mSongStartFrame.load();
    if (start < 0) return false;
    return (mStreamFrames.load() - start) >= mSongFrames;
}

double AudioEngine::outputLatencyMs() const {
    std::lock_guard<std::mutex> guard(mLock);
    if (!mStream) return 0.0;
    auto latency = mStream->calculateLatencyMillis();
    return latency ? latency.value() : 0.0;
}

double AudioEngine::songPositionMs() const {
    const int64_t songStart = mSongStartFrame.load();
    if (songStart < 0) return 0.0;

    std::shared_ptr<oboe::AudioStream> stream;
    {
        std::lock_guard<std::mutex> guard(mLock);
        stream = mStream;
    }
    if (!stream) return 0.0;

    double presentedFrames;

    // The accurate path: the stream tells us which frame was leaving the device
    // at a known instant, and we extrapolate forward to now.
    auto timestamp = stream->getTimestamp(CLOCK_MONOTONIC);
    if (timestamp) {
        const auto &frameTs = timestamp.value();
        const double elapsedSeconds =
                static_cast<double>(nowMonotonicNanos() - frameTs.timestamp) * 1e-9;
        presentedFrames = static_cast<double>(frameTs.position) +
                          elapsedSeconds * mStreamSampleRate;
    } else {
        // Not every device exposes timestamps. Approximating the presented frame
        // as "written minus one buffer" is coarser but keeps the game playable.
        presentedFrames = static_cast<double>(mStreamFrames.load()) -
                          static_cast<double>(stream->getBufferSizeInFrames());
    }

    // Time spent paused is not song time. The correction is counted at write
    // time while presentedFrames is a playback-time figure, so it is off by up
    // to one buffer across a pause -- far smaller than the seconds-long jump it
    // removes.
    const double paused = static_cast<double>(mSongPausedFrames.load());

    // Add back where playback was started from, so the position is absolute
    // within the song even after a seek.
    const double elapsedFrames =
            presentedFrames - static_cast<double>(songStart) - paused +
            static_cast<double>(mSongSeekFrame.load());

    return elapsedFrames / static_cast<double>(mStreamSampleRate) * 1000.0;
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream *stream,
                                                   void *audioData,
                                                   int32_t numFrames) {
    auto *out = static_cast<float *>(audioData);
    const int32_t outChannels = stream->getChannelCount();
    std::memset(out, 0, sizeof(float) * static_cast<size_t>(numFrames) * outChannels);

    const int64_t frameBase = mStreamFrames.load(std::memory_order_relaxed);

    // --- song ---------------------------------------------------------
    if (!mSongPlaying.load(std::memory_order_relaxed) &&
        mSongStartFrame.load(std::memory_order_relaxed) >= 0) {
        // Started but currently paused: the stream rolls on, the song does not,
        // so bank these frames for songPositionMs() to discount.
        mSongPausedFrames.fetch_add(numFrames, std::memory_order_relaxed);
    }

    if (mSongPlaying.load(std::memory_order_relaxed) && mSongFrames > 0) {
        if (mSongStartFrame.load(std::memory_order_relaxed) < 0) {
            // First callback carrying song data; this is the frame it starts on.
            mSongStartFrame.store(frameBase, std::memory_order_relaxed);
        }
        const int16_t *pcm = mSongPcm.data();
        const int32_t sc = mSongChannels;
        for (int32_t i = 0; i < numFrames; ++i) {
            const int64_t f = mSongFrame + i;
            if (f >= mSongFrames) break;
            for (int32_t c = 0; c < outChannels; ++c) {
                out[i * outChannels + c] +=
                        static_cast<float>(pcm[f * sc + (c % sc)]) * kInt16ToFloat * kSongGain;
            }
        }
        mSongFrame += numFrames;
    }

    // --- hit sounds ---------------------------------------------------
    int pending = mPendingSfx.exchange(0, std::memory_order_relaxed);
    while (pending-- > 0) {
        for (auto &voice : mVoices) {
            if (!voice.active) {
                voice.active = true;
                voice.position = 0;
                break;
            }
        }
    }

    if (mSfxFrames > 0) {
        const int16_t *sfx = mSfxPcm.data();
        const int32_t fc = mSfxChannels;
        for (auto &voice : mVoices) {
            if (!voice.active) continue;
            for (int32_t i = 0; i < numFrames; ++i) {
                const int64_t f = voice.position + i;
                if (f >= mSfxFrames) {
                    voice.active = false;
                    break;
                }
                for (int32_t c = 0; c < outChannels; ++c) {
                    out[i * outChannels + c] +=
                            static_cast<float>(sfx[f * fc + (c % fc)]) * kInt16ToFloat * kSfxGain;
                }
            }
            if (voice.active) {
                voice.position += numFrames;
                if (voice.position >= mSfxFrames) voice.active = false;
            }
        }
    }

    // Mixing several sources can overshoot; clip rather than wrap.
    const int32_t samples = numFrames * outChannels;
    for (int32_t i = 0; i < samples; ++i) {
        out[i] = std::clamp(out[i], -1.0f, 1.0f);
    }

    mStreamFrames.store(frameBase + numFrames, std::memory_order_relaxed);
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream * /*stream*/, oboe::Result error) {
    // Typically a headphone unplug or a device switch. Reopening is the caller's
    // job; surfacing it in the log is enough for the prototype.
    LOGE("stream closed with error: %s", oboe::convertToText(error));
}
