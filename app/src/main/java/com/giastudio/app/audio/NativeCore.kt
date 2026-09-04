package com.giastudio.app.audio

/**
 * Kotlin front door to the C++ audio core (Oboe + plugin engine).
 *
 * The native library is only present in builds compiled with -PwithNative.
 * Every call is guarded: if the .so failed to load, the app keeps working
 * on the pure-Kotlin engine and this object simply reports unavailable.
 */
object NativeCore {

    @Volatile
    var available: Boolean = false
        private set

    @Volatile
    private var loadAttempted = false

    private val lock = Any()

    fun ensureLoaded() {
        if (loadAttempted) return
        synchronized(lock) {
            if (loadAttempted) return
            loadAttempted = true
            available = try {
                System.loadLibrary("giastudio")
                val v = nativeVersion()
                v.isNotEmpty()
            } catch (t: Throwable) {
                false
            }
        }
    }

    /** Starts the engine at the given sample rate. Returns false if native is unavailable. */
    fun start(sampleRate: Int): Boolean {
        ensureLoaded()
        if (!available) return false
        return try {
            nativeStart(sampleRate)
        } catch (t: Throwable) {
            available = false
            false
        }
    }

    fun stop() {
        if (!available) return
        try {
            nativeStop()
        } catch (t: Throwable) {
            available = false
        }
    }

    fun active(): Boolean {
        if (!available) return false
        return try {
            nativeActive()
        } catch (t: Throwable) {
            available = false
            false
        }
    }

    /** Plays a note through the native engine (velocity 1..127). No-op when unavailable. */
    fun playNote(midiNote: Int, velocity: Int = 100) {
        if (!available) return
        try {
            nativePlayNote(midiNote.coerceIn(0, 127), velocity.coerceIn(1, 127))
        } catch (t: Throwable) {
            available = false
        }
    }

    fun noteOff(midiNote: Int) {
        if (!available) return
        try {
            nativeNoteOff(midiNote.coerceIn(0, 127))
        } catch (t: Throwable) {
            available = false
        }
    }

    fun pluginCount(): Int {
        if (!available) return 0
        return try {
            nativePluginCount()
        } catch (t: Throwable) {
            available = false
            0
        }
    }

    fun pluginName(index: Int): String {
        if (!available) return ""
        return try {
            nativePluginName(index)
        } catch (t: Throwable) {
            available = false
            ""
        }
    }

    // --- native methods (registered in jni_bridge.cpp, JNI_OnLoad) ---

    private external fun nativeStart(sampleRate: Int): Boolean
    private external fun nativeStop()
    private external fun nativePlayNote(midiNote: Int, velocity: Int)
    private external fun nativeNoteOff(midiNote: Int)
    private external fun nativeActive(): Boolean
    private external fun nativePluginCount(): Int
    private external fun nativePluginName(index: Int): String
    private external fun nativeVersion(): String
}