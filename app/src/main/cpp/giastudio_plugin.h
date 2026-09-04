// GIA Studio — native plugin API v1.
//
// This is the contract every native audio plugin must satisfy: a plain C
// struct of function pointers plus a small registry of built-in plugins.
// It is intentionally CLAP-like in spirit (descriptor + host calls into the
// plugin) but minimal: v1 is output-only, stereo-interleaved, sample-rate
// agnostic. Later revisions will add parameter automation, sidechains and
// external .so plugin loading (AAP/CLAP/VST3 bridging).
//
// Threading contract:
//   * create/destroy/setParam are called from the host (any thread).
//   * process/noteOn/noteOff are called from the real-time audio thread and
//     must not allocate, lock, or block.
//
// Audio format (v1): 2 channels (stereo), interleaved floats in [-1, 1].
// A plugin writes numFrames * 2 floats to output. input may be null for
// synthesizers.

#pragma once

#include <cstdint>

namespace gia {
namespace plugin {

struct Plugin {
    const char* id;       // stable identifier, e.g. "gia.test-synth"
    const char* name;     // human name, e.g. "Test Synth"
    const char* vendor;   // "GIA Studio"
    int numParams;        // count of settable parameters

    // Create a plugin instance for the given sample rate, or null on failure.
    void* (*create)(double sampleRate);

    // Destroy an instance returned by create.
    void (*destroy)(void* state);

    // Set parameter index in [0, numParams). value in [0, 1].
    void (*setParam)(void* state, int index, float value);

    // Read parameter index in [0, numParams). Returns value in [0, 1].
    float (*getParam)(void* state, int index);

    // Trigger a note (real-time thread). velocity in [0, 1].
    void (*noteOn)(void* state, int midiNote, float velocity);

    // Release a note (real-time thread).
    void (*noteOff)(void* state, int midiNote);

    // Render numFrames of stereo-interleaved audio into output.
    // input may be null (synthesizer). Real-time thread.
    void (*process)(void* state, const float* input, float* output, int numFrames);
};

// Built-in plugin registry (populated by the engine's plugin sources).
// Index in [0, getBuiltinPluginCount()).
const Plugin* getBuiltinPlugin(int index);
int getBuiltinPluginCount();

}  // namespace plugin
}  // namespace gia