// TestSynth — the first GIA native plugin.
//
// A small polyphonic subtractive synth (sawtooth + one-pole low-pass + ADSR)
// written to prove the whole native pipeline: C++ plugin -> host engine ->
// Oboe -> speaker. It is deliberately simple but real: 8 voices, velocity,
// and two tweakable parameters.
//
// The built-in plugin registry lives here too for now — when external plugin
// loading lands, the registry moves to its own module.

#include "TestSynth.h"
#include "../giastudio_plugin.h"

#include <cmath>

namespace gia {
namespace plugin {

namespace {

constexpr int kMaxVoices = 8;
constexpr int kNumParams = 2;
constexpr int kMaxBuiltins = 16;

struct Voice {
    bool active = false;
    bool released = false;
    double phase = 0.0;
    float lp = 0.0f;      // one-pole low-pass state
    float env = 0.0f;
    float vel = 0.0f;
    int note = 0;
    int age = 0;
};

struct TestSynth {
    double sampleRate = 48000.0;
    float cutoff = 0.7f;  // param 0
    float decay = 0.35f;  // param 1
    Voice voices[kMaxVoices];
    int voiceCursor = 0;
    int ageCounter = 0;
};

float midiToFreq(int note) {
    return 440.0f * std::pow(2.0f, (note - 69) / 12.0f);
}

void* create(double sampleRate) {
    auto* s = new TestSynth();
    s->sampleRate = sampleRate > 0.0 ? sampleRate : 48000.0;
    return s;
}

void destroy(void* state) {
    delete static_cast<TestSynth*>(state);
}

void setParam(void* state, int index, float value) {
    auto* s = static_cast<TestSynth*>(state);
    if (index == 0) {
        s->cutoff = value < 0.0f ? 0.0f : (value > 1.0f ? 1.0f : value);
    } else if (index == 1) {
        s->decay = value < 0.0f ? 0.0f : (value > 1.0f ? 1.0f : value);
    }
}

float getParam(void* state, int index) {
    auto* s = static_cast<TestSynth*>(state);
    return index == 0 ? s->cutoff : (index == 1 ? s->decay : 0.0f);
}

void noteOn(void* state, int midiNote, float velocity) {
    auto* s = static_cast<TestSynth*>(state);
    Voice* target = nullptr;
    for (int i = 0; i < kMaxVoices; i++) {
        Voice& v = s->voices[(s->voiceCursor + i) % kMaxVoices];
        if (!v.active) {
            target = &v;
            s->voiceCursor = (s->voiceCursor + i + 1) % kMaxVoices;
            break;
        }
    }
    if (target == nullptr) {
        // Steal the oldest active voice.
        Voice* oldest = &s->voices[0];
        for (int i = 1; i < kMaxVoices; i++) {
            if (s->voices[i].age < oldest->age) oldest = &s->voices[i];
        }
        target = oldest;
    }
    target->active = true;
    target->released = false;
    target->phase = 0.0;
    target->lp = 0.0f;
    target->env = 0.0f;
    target->vel = velocity < 0.0f ? 0.0f : (velocity > 1.0f ? 1.0f : velocity);
    target->note = midiNote;
}

void noteOff(void* state, int midiNote) {
    auto* s = static_cast<TestSynth*>(state);
    for (int i = 0; i < kMaxVoices; i++) {
        Voice& v = s->voices[i];
        if (v.active && !v.released && v.note == midiNote) v.released = true;
    }
}

void process(void* state, const float* input, float* output, int numFrames) {
    (void)input;
    auto* s = static_cast<TestSynth*>(state);
    const double sr = s->sampleRate;

    const float cutoffHz = 60.0f + 19000.0f * s->cutoff * s->cutoff;  // 60..~19k
    const float lpCoef = cutoffHz / (cutoffHz + static_cast<float>(sr));
    const float decaySec = 0.08f + 0.9f * s->decay;                   // 0.08..0.98 s
    const float aCoef = 1.0f - std::exp(-1.0f / (0.004f * static_cast<float>(sr)));
    const float dCoef = 1.0f - std::exp(-1.0f / (decaySec * static_cast<float>(sr)));
    const float rCoef = std::exp(-1.0f / (0.06f * static_cast<float>(sr)));
    constexpr float kSustain = 0.7f;

    for (int f = 0; f < numFrames; f++) {
        float mix = 0.0f;
        for (int i = 0; i < kMaxVoices; i++) {
            Voice& v = s->voices[i];
            if (!v.active) continue;
            v.age = ++s->ageCounter;

            v.phase += midiToFreq(v.note) / sr;
            if (v.phase >= 1.0) v.phase -= 1.0;
            const float saw = 2.0f * static_cast<float>(v.phase - std::floor(v.phase + 0.5));

            if (v.released) {
                v.env *= rCoef;
                if (v.env < 0.001f) {
                    v.active = false;
                    continue;
                }
            } else if (v.env < 1.0f) {
                v.env += (1.0f - v.env) * aCoef;
                if (v.env >= 0.99f) v.env = 1.0f;
            } else {
                v.env += (kSustain - v.env) * dCoef;
            }

            v.lp += (saw - v.lp) * lpCoef;
            mix += v.lp * v.env * v.vel;
        }
        const float sample = mix * 0.35f;
        output[f * 2] = sample;
        output[f * 2 + 1] = sample;
    }
}

const Plugin kTestSynth = {
    /* id          = */ "gia.test-synth",
    /* name        = */ "Test Synth",
    /* vendor      = */ "GIA Studio",
    /* numParams   = */ kNumParams,
    /* create      = */ create,
    /* destroy     = */ destroy,
    /* setParam    = */ setParam,
    /* getParam    = */ getParam,
    /* noteOn      = */ noteOn,
    /* noteOff     = */ noteOff,
    /* process     = */ process,
};

const Plugin* g_builtins[kMaxBuiltins] = {};
int g_builtinCount = 0;

}  // namespace

void registerTestSynth() {
    for (int i = 0; i < g_builtinCount; i++) {
        if (g_builtins[i] == &kTestSynth) return;  // already registered
    }
    if (g_builtinCount < kMaxBuiltins) {
        g_builtins[g_builtinCount++] = &kTestSynth;
    }
}

const Plugin* getBuiltinPlugin(int index) {
    if (index < 0 || index >= g_builtinCount) return nullptr;
    return g_builtins[index];
}

int getBuiltinPluginCount() {
    return g_builtinCount;
}

}  // namespace plugin
}  // namespace gia