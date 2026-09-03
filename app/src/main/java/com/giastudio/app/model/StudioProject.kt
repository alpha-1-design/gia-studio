package com.giastudio.app.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * GIA Studio session model.
 *
 * A session is a list of tracks. Each track can hold any number of audio
 * clips placed along a shared timeline. Clips reference WAV files inside the
 * app's private `recordings/` directory; the JSON session file stores the
 * arrangement (positions, gains, mixer + FX state), not the audio itself.
 */

const val MAX_TRACKS = 8
const val MAX_CLIPS_PER_TRACK = 32

/** Per-track insert FX chain. Everything is bypassed by default. */
data class FxState(
    var hpOn: Boolean = false,
    var hpHz: Int = 90,
    var lpOn: Boolean = false,
    var lpHz: Int = 12000,
    var driveOn: Boolean = false,
    var drive: Float = 0.35f,
    var delayOn: Boolean = false,
    var delayMs: Float = 320f,
    var delayFeedback: Float = 0.38f,
    var delayMix: Float = 0.28f,
    var reverbOn: Boolean = false,
    var reverbMix: Float = 0.22f,
)

/** One audio region on a track. `fileName` is relative to recordings dir. */
data class Clip(
    val id: String,
    var name: String,
    var fileName: String,
    var startSec: Double,
    var lengthSec: Double,
    var gain: Float = 1f,
    var fadeInSec: Double = 0.0,
    var fadeOutSec: Double = 0.0,
) {
    fun endSec(): Double = startSec + lengthSec
}

data class Track(
    val id: Int,
    var name: String,
    var color: Int = 0,
    var volume: Float = 0.85f,
    var pan: Float = 0f,
    var muted: Boolean = false,
    var soloed: Boolean = false,
    var armed: Boolean = false,
    var fx: FxState = FxState(),
    /** Gate mic input while recording (removes hiss between phrases). */
    var inputGate: Boolean = true,
    val clips: MutableList<Clip> = mutableListOf(),
) {
    /** End of the last clip on this track (seconds). */
    fun contentEndSec(): Double = clips.maxOfOrNull { it.endSec() } ?: 0.0
}

data class StudioProject(
    var name: String = "My First Session",
    var bpm: Int = 120,
    var masterVolume: Float = 0.9f,
    var masterLimiter: Boolean = true,
    val tracks: MutableList<Track> = defaultTracks(),
) {
    /** End of the longest clip in the session (seconds). */
    fun arrangementEndSec(): Double =
        tracks.maxOfOrNull { it.contentEndSec() } ?: 0.0

    fun nextTrackId(): Int = (tracks.maxOfOrNull { it.id } ?: 0) + 1
}

private val DEFAULT_TRACK_NAMES = listOf(
    "Vocal", "Beat", "Keys", "Bass", "Guitar", "FX", "Voice Memo", "Live"
)

fun defaultTracks(): MutableList<Track> =
    MutableList(DEFAULT_TRACK_NAMES.size) { i ->
        Track(
            id = i + 1,
            name = DEFAULT_TRACK_NAMES[i],
            color = i,
            volume = 0.85f,
            armed = i == 0,
        )
    }.toMutableList()

// ---------------------------------------------------------------------------
// JSON (org.json ships with the Android platform — no extra dependency)
// ---------------------------------------------------------------------------

fun FxState.toJson(): JSONObject = JSONObject().apply {
    put("hpOn", hpOn); put("hpHz", hpHz)
    put("lpOn", lpOn); put("lpHz", lpHz)
    put("driveOn", driveOn); put("drive", drive.toDouble())
    put("delayOn", delayOn); put("delayMs", delayMs.toDouble())
    put("delayFeedback", delayFeedback.toDouble()); put("delayMix", delayMix.toDouble())
    put("reverbOn", reverbOn); put("reverbMix", reverbMix.toDouble())
}

fun fxFromJson(o: JSONObject): FxState = FxState(
    hpOn = o.optBoolean("hpOn", false),
    hpHz = o.optInt("hpHz", 90),
    lpOn = o.optBoolean("lpOn", false),
    lpHz = o.optInt("lpHz", 12000),
    driveOn = o.optBoolean("driveOn", false),
    drive = o.optDouble("drive", 0.35).toFloat(),
    delayOn = o.optBoolean("delayOn", false),
    delayMs = o.optDouble("delayMs", 320.0).toFloat(),
    delayFeedback = o.optDouble("delayFeedback", 0.38).toFloat(),
    delayMix = o.optDouble("delayMix", 0.28).toFloat(),
    reverbOn = o.optBoolean("reverbOn", false),
    reverbMix = o.optDouble("reverbMix", 0.22).toFloat(),
)

fun Clip.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("fileName", fileName)
    put("startSec", startSec)
    put("lengthSec", lengthSec)
    put("gain", gain.toDouble())
    put("fadeInSec", fadeInSec)
    put("fadeOutSec", fadeOutSec)
}

fun clipFromJson(o: JSONObject): Clip = Clip(
    id = o.getString("id"),
    name = o.optString("name", "Clip"),
    fileName = o.getString("fileName"),
    startSec = o.optDouble("startSec", 0.0),
    lengthSec = o.optDouble("lengthSec", 0.0),
    gain = o.optDouble("gain", 1.0).toFloat(),
    fadeInSec = o.optDouble("fadeInSec", 0.0),
    fadeOutSec = o.optDouble("fadeOutSec", 0.0),
)

fun Track.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("color", color)
    put("volume", volume.toDouble())
    put("pan", pan.toDouble())
    put("muted", muted)
    put("soloed", soloed)
    put("armed", armed)
    put("inputGate", inputGate)
    put("fx", fx.toJson())
    val clipsJson = JSONArray()
    for (c in clips) clipsJson.put(c.toJson())
    put("clips", clipsJson)
}

fun trackFromJson(o: JSONObject): Track = Track(
    id = o.getInt("id"),
    name = o.optString("name", "Track"),
    color = o.optInt("color", 0),
    volume = o.optDouble("volume", 0.85).toFloat(),
    pan = o.optDouble("pan", 0.0).toFloat(),
    muted = o.optBoolean("muted", false),
    soloed = o.optBoolean("soloed", false),
    armed = o.optBoolean("armed", false),
    inputGate = o.optBoolean("inputGate", true),
    fx = fxFromJson(o.optJSONObject("fx") ?: JSONObject()),
    clips = run {
        val arr = o.optJSONArray("clips") ?: JSONArray()
        MutableList(arr.length()) { i -> clipFromJson(arr.getJSONObject(i)) }
    },
)

fun StudioProject.toJson(): JSONObject = JSONObject().apply {
    put("name", name)
    put("bpm", bpm)
    put("masterVolume", masterVolume.toDouble())
    put("masterLimiter", masterLimiter)
    val tracksJson = JSONArray()
    for (t in tracks) tracksJson.put(t.toJson())
    put("tracks", tracksJson)
}

fun projectFromJson(o: JSONObject): StudioProject = StudioProject(
    name = o.optString("name", "My First Session"),
    bpm = o.optInt("bpm", 120),
    masterVolume = o.optDouble("masterVolume", 0.9).toFloat(),
    masterLimiter = o.optBoolean("masterLimiter", true),
    tracks = run {
        val arr = o.optJSONArray("tracks") ?: JSONArray()
        MutableList(arr.length()) { i -> trackFromJson(arr.getJSONObject(i)) }
    }.ifEmpty { defaultTracks() },
)
