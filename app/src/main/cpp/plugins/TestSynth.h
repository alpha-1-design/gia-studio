// TestSynth — the first GIA native plugin.
//
// A small polyphonic subtractive synth (sawtooth + one-pole low-pass + ADSR)
// written to prove the whole native pipeline: C++ plugin -> host engine ->
// Oboe -> speaker. It is deliberately simple but real: 8 voices, velocity,
// and two tweakable parameters.

#pragma once

namespace gia {
namespace plugin {

// Registers the built-in TestSynth descriptor in the plugin registry.
// Called once at engine startup; safe to call multiple times.
void registerTestSynth();

}  // namespace plugin
}  // namespace gia