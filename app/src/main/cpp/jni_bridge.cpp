// JNI bridge between Kotlin (com.giastudio.app.audio.NativeCore) and the
// C++ NativeEngine. Methods are registered explicitly in JNI_OnLoad so the
// Kotlin side only ever sees the stable method names below.

#include <jni.h>

#include "engine/NativeEngine.h"

namespace {

jboolean nativeStart(JNIEnv* /*env*/, jobject /*thiz*/, jint sampleRate) {
    return gia::NativeEngine::instance().start(sampleRate) ? JNI_TRUE : JNI_FALSE;
}

void nativeStop(JNIEnv* /*env*/, jobject /*thiz*/) {
    gia::NativeEngine::instance().stop();
}

void nativePlayNote(JNIEnv* /*env*/, jobject /*thiz*/, jint midiNote, jint velocity) {
    gia::NativeEngine::instance().noteOn(midiNote, velocity);
}

void nativeNoteOff(JNIEnv* /*env*/, jobject /*thiz*/, jint midiNote) {
    gia::NativeEngine::instance().noteOff(midiNote);
}

jboolean nativeActive(JNIEnv* /*env*/, jobject /*thiz*/) {
    return gia::NativeEngine::instance().active() ? JNI_TRUE : JNI_FALSE;
}

jint nativePluginCount(JNIEnv* /*env*/, jobject /*thiz*/) {
    return gia::NativeEngine::instance().pluginCount();
}

jstring nativePluginName(JNIEnv* env, jobject /*thiz*/, jint index) {
    const char* name = gia::NativeEngine::instance().pluginName(index);
    return env->NewStringUTF(name != nullptr ? name : "");
}

jstring nativeVersion(JNIEnv* env, jobject /*thiz*/) {
    return env->NewStringUTF("0.3.0-core-v1");
}

const JNINativeMethod kMethods[] = {
    {"nativeStart", "(I)Z", reinterpret_cast<void*>(nativeStart)},
    {"nativeStop", "()V", reinterpret_cast<void*>(nativeStop)},
    {"nativePlayNote", "(II)V", reinterpret_cast<void*>(nativePlayNote)},
    {"nativeNoteOff", "(I)V", reinterpret_cast<void*>(nativeNoteOff)},
    {"nativeActive", "()Z", reinterpret_cast<void*>(nativeActive)},
    {"nativePluginCount", "()I", reinterpret_cast<void*>(nativePluginCount)},
    {"nativePluginName", "(I)Ljava/lang/String;", reinterpret_cast<void*>(nativePluginName)},
    {"nativeVersion", "()Ljava/lang/String;", reinterpret_cast<void*>(nativeVersion)},
};

}  // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass cls = env->FindClass("com/giastudio/app/audio/NativeCore");
    if (cls == nullptr) {
        return JNI_ERR;
    }
    if (env->RegisterNatives(cls, kMethods, sizeof(kMethods) / sizeof(kMethods[0])) != JNI_OK) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}