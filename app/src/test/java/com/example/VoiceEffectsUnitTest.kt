package com.example

import com.example.audio.AudioEffectParams
import com.example.audio.VoiceEffectsPipeline
import com.example.model.PresetsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class VoiceEffectsUnitTest {

    @Test
    fun `test presets are loaded correctly`() {
        val presets = PresetsManager.builtInPresets
        assertTrue("Presets list should not be empty", presets.isNotEmpty())
        val chipmunk = presets.find { it.id == "chipmunk" }
        assertNotNull(chipmunk)
        assertEquals(8.0f, chipmunk?.params?.pitchSemitones ?: 0f, 0.01f)
    }

    @Test
    fun `test voice effects pipeline processing stability`() {
        val sampleRate = 44100
        val pipeline = VoiceEffectsPipeline(sampleRate)
        val count = 512
        val samples = FloatArray(count) { i ->
            (sin(2.0 * Math.PI * 440.0 * i / sampleRate) * 0.5).toFloat()
        }

        val params = AudioEffectParams(
            pitchSemitones = 5.0f,
            formantShift = 2.0f,
            echoDelayMs = 100f,
            echoWetMix = 0.5f,
            robotEnabled = true,
            robotFrequencyHz = 200f,
            gateThresholdDb = -45f,
            masterGain = 1.0f
        )

        pipeline.process(samples, count, params)

        // Verify output is valid non-NaN finite audio
        for (i in 0 until count) {
            assertTrue("Sample $i is NaN or Infinite: ${samples[i]}", samples[i].isFinite())
            assertTrue("Sample $i clipped excessively: ${samples[i]}", samples[i] in -1.5f..1.5f)
        }
    }

    @Test
    fun `test noise gate suppresses silence`() {
        val sampleRate = 44100
        val pipeline = VoiceEffectsPipeline(sampleRate)
        val count = 512
        // Very low amplitude noise (-80dB)
        val samples = FloatArray(count) { 0.00005f }

        val params = AudioEffectParams(
            gateThresholdDb = -40f // Gate at -40dB
        )

        pipeline.process(samples, count, params)
        assertTrue("Noise gate should attenuate low level input", samples.last() < 0.00005f)
    }
}
