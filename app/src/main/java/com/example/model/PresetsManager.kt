package com.example.model

import android.content.Context
import android.content.SharedPreferences
import com.example.audio.AudioEffectParams
import org.json.JSONArray
import org.json.JSONObject

/**
 * Representation of a voice preset with metadata and icon styling.
 */
data class VoicePreset(
    val id: String,
    val name: String,
    val description: String,
    val emoji: String,
    val params: AudioEffectParams,
    val isCustom: Boolean = false
)

object PresetsManager {

    val builtInPresets: List<VoicePreset> = listOf(
        VoicePreset(
            id = "normal",
            name = "Normal",
            description = "Clean natural voice with noise suppression",
            emoji = "🎙️",
            params = AudioEffectParams(
                pitchSemitones = 0f,
                formantShift = 0f,
                echoDelayMs = 0f,
                echoWetMix = 0f,
                robotEnabled = false,
                robotFrequencyHz = 160f,
                gateThresholdDb = -48f,
                masterGain = 1.0f
            )
        ),
        VoicePreset(
            id = "chipmunk",
            name = "Chipmunk",
            description = "High pitched squeaky character voice",
            emoji = "🐿️",
            params = AudioEffectParams(
                pitchSemitones = 8.0f,
                formantShift = 4.0f,
                echoDelayMs = 0f,
                echoWetMix = 0f,
                robotEnabled = false,
                robotFrequencyHz = 160f,
                gateThresholdDb = -46f,
                masterGain = 1.05f
            )
        ),
        VoicePreset(
            id = "deep",
            name = "Deep Voice",
            description = "Low pitch with chest formant resonance",
            emoji = "🗿",
            params = AudioEffectParams(
                pitchSemitones = -7.0f,
                formantShift = -4.0f,
                echoDelayMs = 20f,
                echoWetMix = 0.15f,
                robotEnabled = false,
                robotFrequencyHz = 160f,
                gateThresholdDb = -42f,
                masterGain = 1.1f
            )
        ),
        VoicePreset(
            id = "robot",
            name = "Robot",
            description = "Sci-Fi android ring modulation",
            emoji = "🤖",
            params = AudioEffectParams(
                pitchSemitones = 0f,
                formantShift = 0f,
                echoDelayMs = 35f,
                echoWetMix = 0.25f,
                robotEnabled = true,
                robotFrequencyHz = 180f,
                gateThresholdDb = -38f,
                masterGain = 1.0f
            )
        ),
        VoicePreset(
            id = "megaphone",
            name = "Megaphone",
            description = "Walkie-talkie & radio communication effect",
            emoji = "📢",
            params = AudioEffectParams(
                pitchSemitones = 1.5f,
                formantShift = 2.5f,
                echoDelayMs = 40f,
                echoWetMix = 0.35f,
                robotEnabled = true,
                robotFrequencyHz = 320f,
                gateThresholdDb = -35f,
                masterGain = 1.15f
            )
        ),
        VoicePreset(
            id = "cave_echo",
            name = "Cave Echo",
            description = "Atmospheric cavern spatial reflection",
            emoji = "🏔️",
            params = AudioEffectParams(
                pitchSemitones = -2.0f,
                formantShift = -1.0f,
                echoDelayMs = 320f,
                echoWetMix = 0.60f,
                robotEnabled = false,
                robotFrequencyHz = 160f,
                gateThresholdDb = -44f,
                masterGain = 0.95f
            )
        ),
        VoicePreset(
            id = "alien",
            name = "Alien",
            description = "Extraterrestrial modulated frequency timbre",
            emoji = "👽",
            params = AudioEffectParams(
                pitchSemitones = 5.0f,
                formantShift = 3.0f,
                echoDelayMs = 75f,
                echoWetMix = 0.40f,
                robotEnabled = true,
                robotFrequencyHz = 92f,
                gateThresholdDb = -40f,
                masterGain = 1.05f
            )
        ),
        VoicePreset(
            id = "monster",
            name = "Titan Monster",
            description = "Sub-octave booming mythological beast",
            emoji = "👹",
            params = AudioEffectParams(
                pitchSemitones = -12.0f,
                formantShift = -5.0f,
                echoDelayMs = 60f,
                echoWetMix = 0.30f,
                robotEnabled = false,
                robotFrequencyHz = 160f,
                gateThresholdDb = -36f,
                masterGain = 1.2f
            )
        ),
        VoicePreset(
            id = "helium",
            name = "Helium Gas",
            description = "Ultra high pitched balloon voice",
            emoji = "🎈",
            params = AudioEffectParams(
                pitchSemitones = 12.0f,
                formantShift = 5.0f,
                echoDelayMs = 0f,
                echoWetMix = 0f,
                robotEnabled = false,
                robotFrequencyHz = 160f,
                gateThresholdDb = -45f,
                masterGain = 1.0f
            )
        )
    )

    private const val PREFS_NAME = "vocal_morph_presets"
    private const val KEY_CUSTOM_PRESETS = "custom_presets_json"

    fun loadCustomPresets(context: Context): List<VoicePreset> {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_CUSTOM_PRESETS, null) ?: return emptyList()
        val list = mutableListOf<VoicePreset>()

        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val paramsObj = obj.getJSONObject("params")
                val params = AudioEffectParams(
                    pitchSemitones = paramsObj.optDouble("pitchSemitones", 0.0).toFloat(),
                    formantShift = paramsObj.optDouble("formantShift", 0.0).toFloat(),
                    echoDelayMs = paramsObj.optDouble("echoDelayMs", 0.0).toFloat(),
                    echoWetMix = paramsObj.optDouble("echoWetMix", 0.0).toFloat(),
                    robotEnabled = paramsObj.optBoolean("robotEnabled", false),
                    robotFrequencyHz = paramsObj.optDouble("robotFrequencyHz", 160.0).toFloat(),
                    gateThresholdDb = paramsObj.optDouble("gateThresholdDb", -48.0).toFloat(),
                    masterGain = paramsObj.optDouble("masterGain", 1.0).toFloat()
                )

                list.add(
                    VoicePreset(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        description = obj.optString("description", "Custom preset"),
                        emoji = obj.optString("emoji", "✨"),
                        params = params,
                        isCustom = true
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun saveCustomPreset(context: Context, preset: VoicePreset) {
        val current = loadCustomPresets(context).toMutableList()
        current.removeAll { it.id == preset.id }
        current.add(0, preset.copy(isCustom = true))
        persistCustomPresets(context, current)
    }

    fun deleteCustomPreset(context: Context, presetId: String) {
        val current = loadCustomPresets(context).toMutableList()
        current.removeAll { it.id == presetId }
        persistCustomPresets(context, current)
    }

    private fun persistCustomPresets(context: Context, list: List<VoicePreset>) {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonArray = JSONArray()

        for (p in list) {
            val obj = JSONObject()
            obj.put("id", p.id)
            obj.put("name", p.name)
            obj.put("description", p.description)
            obj.put("emoji", p.emoji)

            val paramsObj = JSONObject()
            paramsObj.put("pitchSemitones", p.params.pitchSemitones.toDouble())
            paramsObj.put("formantShift", p.params.formantShift.toDouble())
            paramsObj.put("echoDelayMs", p.params.echoDelayMs.toDouble())
            paramsObj.put("echoWetMix", p.params.echoWetMix.toDouble())
            paramsObj.put("robotEnabled", p.params.robotEnabled)
            paramsObj.put("robotFrequencyHz", p.params.robotFrequencyHz.toDouble())
            paramsObj.put("gateThresholdDb", p.params.gateThresholdDb.toDouble())
            paramsObj.put("masterGain", p.params.masterGain.toDouble())

            obj.put("params", paramsObj)
            jsonArray.put(obj)
        }

        prefs.edit().putString(KEY_CUSTOM_PRESETS, jsonArray.toString()).apply()
    }
}
