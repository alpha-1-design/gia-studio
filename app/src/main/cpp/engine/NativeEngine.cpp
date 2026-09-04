#include "NativeEngine.h"

#include <android/log.h>

#include <cmath>
#include <cstring>

#include "../plugins/TestSynth.h"

#define GIA_LOG(...) __android_log_print(ANDROID_LOG_INFO, "GIA-Core", __VA_ARGS__)

namespace gia {

NativeEngine& NativeEngine::instance() {
    static NativeEngine engine;
    return engine;
}

bool NativeEngine::start(int sampleRate) {
    if (running_.load()) return true;
    std::lock_guard<std::mutex> lock(mutex_);

    oboe::AudioStreamBuilder builder;
    // Oboe's fluent setters return AudioStreamBuilder* — hence the arrow chain.
    builder.setDirection(oboe::Direction::Output)
        ->setAudioApi(oboe::AudioApi::AAudio)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(2)
        ->setSampleRate(sampleRate > 0 ? sampleRate : 48000)
        ->setCallback(this);

    oboe::Result result = builder.openStream(&stream_);
    if (result != oboe::Result::OK) {
        GIA_LOG("openStream failed: %s", oboe::convertToText(result));
        stream_ = nullptr;
        return false;
    }

    gia::plugin::registerTestSynth();
    const int n = gia::plugin::getBuiltinPluginCount();
    int loaded = 0;
    for (int i = 0; i < n && i < kMaxPlugins; i++) {
        const gia::plugin::Plugin* p = gia::plugin::getBuiltinPlugin(i);
        if (p == nullptr || p->create == nullptr) continue;
        void* st = p->create(stream_->getSampleRate());
        if (st != nullptr) {
            plugins_[loaded] = p;
            pluginStates_[loaded] = st;
            loaded++;
        }
    }
    pluginCount_ = loaded;

    result = stream_->requestStart();
    if (result != oboe::Result::OK) {
        GIA_LOG("requestStart failed: %s", oboe::convertToText(result));
        unloadPlugins();
        stream_->close();
        stream_ = nullptr;
        return false;
    }

    // Low-latency recipe: shrink the buffer toward 2 bursts.
    const int32_t burst = stream_->getFramesPerBurst();
    if (burst > 0) {
        stream_->setBufferSizeInFrames(burst * 2);
    }

    running_ = true;
    GIA_LOG("native engine started: %d Hz, %d plugin(s)", stream_->getSampleRate(), loaded);
    return true;
}

void NativeEngine::stop() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (stream_ != nullptr) {
        stream_->requestStop();
        stream_->close();
        stream_ = nullptr;
    }
    unloadPlugins();
    running_ = false;
}

void NativeEngine::unloadPlugins() {
    for (int i = 0; i < pluginCount_; i++) {
        if (pluginStates_[i] != nullptr && plugins_[i] != nullptr &&
            plugins_[i]->destroy != nullptr) {
            plugins_[i]->destroy(pluginStates_[i]);
        }
        plugins_[i] = nullptr;
        pluginStates_[i] = nullptr;
    }
    pluginCount_ = 0;
}

void NativeEngine::noteOn(int midiNote, int velocity) {
    if (!running_.load()) return;
    if (midiNote < 0 || midiNote > 127) return;
    std::lock_guard<std::mutex> lock(mutex_);
    const float vel = (velocity < 0 ? 0 : (velocity > 127 ? 127 : velocity)) / 127.0f;
    for (int i = 0; i < pluginCount_; i++) {
        if (plugins_[i] != nullptr && pluginStates_[i] != nullptr &&
            plugins_[i]->noteOn != nullptr) {
            plugins_[i]->noteOn(pluginStates_[i], midiNote, vel);
        }
    }
}

void NativeEngine::noteOff(int midiNote) {
    if (!running_.load()) return;
    if (midiNote < 0 || midiNote > 127) return;
    std::lock_guard<std::mutex> lock(mutex_);
    for (int i = 0; i < pluginCount_; i++) {
        if (plugins_[i] != nullptr && pluginStates_[i] != nullptr &&
            plugins_[i]->noteOff != nullptr) {
            plugins_[i]->noteOff(pluginStates_[i], midiNote);
        }
    }
}

const char* NativeEngine::pluginName(int index) const {
    if (index < 0 || index >= pluginCount_) return nullptr;
    if (plugins_[index] == nullptr) return nullptr;
    return plugins_[index]->name;
}

oboe::DataCallbackResult NativeEngine::onAudioReady(oboe::AudioStream* stream,
                                                    void* audioData,
                                                    int32_t numFrames) {
    auto* out = static_cast<float*>(audioData);
    const int32_t channels = stream->getChannelCount();
    std::memset(out, 0,
                sizeof(float) * static_cast<size_t>(channels) * static_cast<size_t>(numFrames));

    std::lock_guard<std::mutex> lock(mutex_);
    if (pluginCount_ == 0) return oboe::DataCallbackResult::Continue;

    const size_t needed = static_cast<size_t>(numFrames) * 2;
    if (scratch_.size() < needed) scratch_.resize(needed);
    float* scratch = scratch_.data();

    for (int i = 0; i < pluginCount_; i++) {
        const gia::plugin::Plugin* p = plugins_[i];
        void* st = pluginStates_[i];
        if (p == nullptr || st == nullptr || p->process == nullptr) continue;
        std::memset(scratch, 0, sizeof(float) * needed);
        p->process(st, nullptr, scratch, numFrames);
        if (channels == 2) {
            for (int f = 0; f < numFrames; f++) {
                out[f * 2] += scratch[f * 2];
                out[f * 2 + 1] += scratch[f * 2 + 1];
            }
        } else {
            for (int f = 0; f < numFrames; f++) {
                out[f] += (scratch[f * 2] + scratch[f * 2 + 1]) * 0.5f;
            }
        }
    }

    // Master gain + soft clip (s / (1 + |s|)) so summed plugins can't clip.
    for (int i = 0; i < channels * numFrames; i++) {
        const float s = out[i] * 0.85f;
        out[i] = s / (1.0f + std::fabs(s));
    }
    return oboe::DataCallbackResult::Continue;
}

void NativeEngine::onErrorAfterClose(oboe::AudioStream* /*stream*/, oboe::Result error) {
    GIA_LOG("stream closed after error: %s", oboe::convertToText(error));
    running_ = false;
    stream_ = nullptr;
}

}  // namespace gia