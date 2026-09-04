// NativeEngine — the GIA Studio C++ audio engine.
//
// Owns the Oboe output stream and hosts built-in plugins in a simple chain.
// This is the first real slice of the C++ core: it proves the plugin ABI,
// the real-time render loop and the JNI bridge end to end. Future work
// (external plugin loading, transport sync with the Kotlin session engine)
// builds on this structure.

#pragma once

#include <oboe/Oboe.h>

#include <atomic>
#include <cstdint>
#include <mutex>
#include <vector>

#include "../giastudio_plugin.h"

namespace gia {

class NativeEngine : public oboe::AudioStreamCallback {
public:
    static NativeEngine& instance();

    // Starts the Oboe stream and loads built-in plugins. Idempotent.
    bool start(int sampleRate);
    void stop();
    bool active() const { return running_.load(); }

    // Note events (velocity in 0..127). Routed to every plugin slot.
    void noteOn(int midiNote, int velocity);
    void noteOff(int midiNote);

    int pluginCount() const { return pluginCount_; }
    const char* pluginName(int index) const;

    // oboe::AudioStreamCallback
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream,
                                          void* audioData,
                                          int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    NativeEngine() = default;
    ~NativeEngine() = default;
    void unloadPlugins();

    static constexpr int kMaxPlugins = 8;
    oboe::AudioStream* stream_ = nullptr;
    std::atomic<bool> running_{false};
    // Guards plugin state. The audio thread takes it briefly per callback;
    // UI-thread note events take it too. Fine for v1, revisit for lock-free
    // transport when plugin count grows.
    std::mutex mutex_;
    const gia::plugin::Plugin* plugins_[kMaxPlugins] = {};
    void* pluginStates_[kMaxPlugins] = {};
    int pluginCount_ = 0;
    std::vector<float> scratch_;  // stereo scratch buffer for plugin output
};

}  // namespace gia