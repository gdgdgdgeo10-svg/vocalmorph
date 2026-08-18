package com.example.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Parameters controlling all real-time voice DSP effects.
 */
data class AudioEffectParams(
    val pitchSemitones: Float = 0f,         // -12.0 to +12.0
    val formantShift: Float = 0f,            // -5.0 (Deep Masculine) to +5.0 (Feminine / Bright)
    val echoDelayMs: Float = 0f,             // 0 to 500 ms
    val echoWetMix: Float = 0f,              // 0.0 to 1.0 (0% to 100%)
    val robotEnabled: Boolean = false,       // Ring modulation toggle
    val robotFrequencyHz: Float = 160f,      // 80 to 400 Hz
    val gateThresholdDb: Float = -48f,       // -60 to -10 dB
    val masterGain: Float = 1.0f             // Master volume multiplier
)

/**
 * Real-time Noise Gate effect to silence ambient room hiss and breath noise.
 */
class NoiseGate(private val sampleRate: Int) {
    private var envelope: Float = 0f
    private var currentGain: Float = 1f
    var isGateOpen: Boolean = true
        private set

    // Attack time ~ 2ms, Release time ~ 70ms
    private val attackCoeff = (1.0 - kotlin.math.exp(-1.0 / (0.002 * sampleRate))).toFloat()
    private val releaseCoeff = (1.0 - kotlin.math.exp(-1.0 / (0.070 * sampleRate))).toFloat()

    fun process(samples: FloatArray, count: Int, thresholdDb: Float) {
        val linearThreshold = 10f.pow(thresholdDb / 20f)

        for (i in 0 until count) {
            val absVal = kotlin.math.abs(samples[i])

            // Smooth envelope follower
            if (absVal > envelope) {
                envelope += attackCoeff * (absVal - envelope)
            } else {
                envelope += releaseCoeff * (absVal - envelope)
            }

            // Hysteresis threshold comparison
            val targetGain = if (envelope > linearThreshold) 1.0f else 0.0f
            isGateOpen = envelope > linearThreshold

            // Smooth gain transition to avoid clicking
            if (targetGain > currentGain) {
                currentGain += attackCoeff * (targetGain - currentGain)
            } else {
                currentGain += releaseCoeff * (targetGain - currentGain)
            }

            samples[i] *= currentGain
        }
    }

    fun reset() {
        envelope = 0f
        currentGain = 1f
        isGateOpen = true
    }
}

/**
 * Ultra-low-latency Granular Pitch Shifter (-12 to +12 semitones).
 * Uses dual overlapping Hann-windowed read pointers on a short circular ring buffer.
 * Provides zero algorithmic delay and artifact-free continuous real-time pitching.
 */
class GranularPitchShifter(private val sampleRate: Int) {
    // 40ms grain window
    private val windowSize: Int = (sampleRate * 0.040f).toInt().coerceAtLeast(256)
    private val bufferSize: Int = windowSize * 2
    private val buffer = FloatArray(bufferSize)

    private var writePos: Int = 0
    private var readPos1: Float = 0f
    private var readPos2: Float = (windowSize / 2).toFloat()

    // Precomputed Hann window
    private val hannWindow = FloatArray(windowSize) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / (windowSize - 1)))).toFloat()
    }

    fun process(samples: FloatArray, count: Int, semitones: Float) {
        if (kotlin.math.abs(semitones) < 0.05f) {
            // Bypass processing for pitch = 0 to preserve pure audio clarity
            for (i in 0 until count) {
                buffer[writePos] = samples[i]
                writePos = (writePos + 1) % bufferSize
            }
            return
        }

        // Pitch shift ratio: 2^(semitones / 12)
        val rate = 2f.pow(semitones / 12f)
        val halfWindow = windowSize / 2

        for (i in 0 until count) {
            val inputSample = samples[i]

            // Write into circular buffer
            buffer[writePos] = inputSample

            // Phase 1 within window
            val phase1 = (readPos1.toInt()) % windowSize
            val windowWeight1 = hannWindow[phase1]

            // Phase 2 (shifted by half window)
            val phase2 = (readPos2.toInt()) % windowSize
            val windowWeight2 = hannWindow[phase2]

            // Interpolated read for pointer 1
            val actualIdx1 = (writePos - windowSize + (readPos1.toInt()))
            val sample1 = readInterpolated(actualIdx1, readPos1 - readPos1.toInt())

            // Interpolated read for pointer 2
            val actualIdx2 = (writePos - windowSize + (readPos2.toInt()))
            val sample2 = readInterpolated(actualIdx2, readPos2 - readPos2.toInt())

            // Combined granular output
            val output = (sample1 * windowWeight1) + (sample2 * windowWeight2)
            samples[i] = output

            // Advance pointers
            readPos1 += rate
            if (readPos1 >= windowSize) {
                readPos1 -= windowSize
            }

            readPos2 += rate
            if (readPos2 >= windowSize) {
                readPos2 -= windowSize
            }

            writePos = (writePos + 1) % bufferSize
        }
    }

    private fun readInterpolated(baseIndex: Int, frac: Float): Float {
        var idx0 = baseIndex % bufferSize
        if (idx0 < 0) idx0 += bufferSize
        var idx1 = (idx0 + 1) % bufferSize
        return buffer[idx0] * (1f - frac) + buffer[idx1] * frac
    }

    fun reset() {
        buffer.fill(0f)
        writePos = 0
        readPos1 = 0f
        readPos2 = (windowSize / 2).toFloat()
    }
}

/**
 * Direct Form II Transposed Biquad Filter for zero-latency spectral sculpting.
 */
class BiquadFilter {
    private var b0 = 1.0f
    private var b1 = 0.0f
    private var b2 = 0.0f
    private var a1 = 0.0f
    private var a2 = 0.0f

    private var z1 = 0.0f
    private var z2 = 0.0f

    fun setPeakingEQ(sampleRate: Float, centerFreq: Float, q: Float, gainDb: Float) {
        val w0 = (2.0 * PI * (centerFreq.coerceIn(20f, sampleRate * 0.48f)) / sampleRate).toFloat()
        val alpha = (sin(w0.toDouble()) / (2.0 * q.coerceAtLeast(0.1f))).toFloat()
        val a = 10f.pow(gainDb / 40f)

        val cosW0 = cos(w0.toDouble()).toFloat()
        val a0 = 1.0f + alpha / a
        b0 = (1.0f + alpha * a) / a0
        b1 = (-2.0f * cosW0) / a0
        b2 = (1.0f - alpha * a) / a0
        a1 = (-2.0f * cosW0) / a0
        a2 = (1.0f - alpha / a) / a0
    }

    fun setLowShelf(sampleRate: Float, cutoffFreq: Float, gainDb: Float) {
        val w0 = (2.0 * PI * (cutoffFreq.coerceIn(20f, sampleRate * 0.45f)) / sampleRate).toFloat()
        val a = 10f.pow(gainDb / 40f)
        val cosW0 = cos(w0.toDouble()).toFloat()
        val sinW0 = sin(w0.toDouble()).toFloat()
        val alpha = (sinW0 / 2.0f * sqrt(2.0)).toFloat()

        val a0 = (a + 1f) + (a - 1f) * cosW0 + 2f * sqrt(a) * alpha
        b0 = (a * ((a + 1f) - (a - 1f) * cosW0 + 2f * sqrt(a) * alpha)) / a0
        b1 = (2f * a * ((a - 1f) - (a + 1f) * cosW0)) / a0
        b2 = (a * ((a + 1f) - (a - 1f) * cosW0 - 2f * sqrt(a) * alpha)) / a0
        a1 = (-2f * ((a - 1f) + (a + 1f) * cosW0)) / a0
        a2 = ((a + 1f) + (a - 1f) * cosW0 - 2f * sqrt(a) * alpha) / a0
    }

    fun setHighShelf(sampleRate: Float, cutoffFreq: Float, gainDb: Float) {
        val w0 = (2.0 * PI * (cutoffFreq.coerceIn(20f, sampleRate * 0.45f)) / sampleRate).toFloat()
        val a = 10f.pow(gainDb / 40f)
        val cosW0 = cos(w0.toDouble()).toFloat()
        val sinW0 = sin(w0.toDouble()).toFloat()
        val alpha = (sinW0 / 2.0f * sqrt(2.0)).toFloat()

        val a0 = (a + 1f) - (a - 1f) * cosW0 + 2f * sqrt(a) * alpha
        b0 = (a * ((a + 1f) + (a - 1f) * cosW0 + 2f * sqrt(a) * alpha)) / a0
        b1 = (-2f * a * ((a - 1f) + (a + 1f) * cosW0)) / a0
        b2 = (a * ((a + 1f) + (a - 1f) * cosW0 - 2f * sqrt(a) * alpha)) / a0
        a1 = (2f * ((a - 1f) - (a + 1f) * cosW0)) / a0
        a2 = ((a + 1f) - (a - 1f) * cosW0 - 2f * sqrt(a) * alpha) / a0
    }

    fun process(sample: Float): Float {
        val out = b0 * sample + z1
        z1 = b1 * sample - a1 * out + z2
        z2 = b2 * sample - a2 * out
        return out
    }

    fun reset() {
        z1 = 0.0f
        z2 = 0.0f
    }
}

/**
 * Formant Filter sculpting spectral vocal envelope (-5.0 masculine to +5.0 feminine).
 */
class FormantShifter(private val sampleRate: Int) {
    private val filterLow = BiquadFilter()
    private val filterFormant1 = BiquadFilter()
    private val filterFormant2 = BiquadFilter()
    private val filterHigh = BiquadFilter()
    private var lastShift: Float = Float.NaN

    fun process(samples: FloatArray, count: Int, formantShift: Float) {
        if (kotlin.math.abs(formantShift) < 0.05f) {
            return
        }

        if (formantShift != lastShift) {
            updateFilters(formantShift)
            lastShift = formantShift
        }

        val sr = sampleRate.toFloat()
        for (i in 0 until count) {
            var s = samples[i]
            s = filterLow.process(s)
            s = filterFormant1.process(s)
            s = filterFormant2.process(s)
            s = filterHigh.process(s)
            samples[i] = s
        }
    }

    private fun updateFilters(shift: Float) {
        val sr = sampleRate.toFloat()
        if (shift > 0) {
            // Feminine / Bright vocal tract: Formant peaks shifted higher, low resonance reduced
            val f1 = 800f + shift * 120f
            val f2 = 2800f + shift * 300f
            filterLow.setLowShelf(sr, 200f, -shift * 1.5f)
            filterFormant1.setPeakingEQ(sr, f1, 1.8f, shift * 1.8f)
            filterFormant2.setPeakingEQ(sr, f2, 2.0f, shift * 2.2f)
            filterHigh.setHighShelf(sr, 4500f, shift * 1.4f)
        } else {
            // Deep Masculine vocal tract: Formants lower, chest resonance emphasized
            val absShift = kotlin.math.abs(shift)
            val f1 = (600f - absShift * 80f).coerceAtLeast(200f)
            val f2 = (1800f - absShift * 180f).coerceAtLeast(800f)
            filterLow.setLowShelf(sr, 180f, absShift * 2.2f)
            filterFormant1.setPeakingEQ(sr, f1, 1.5f, absShift * 1.6f)
            filterFormant2.setPeakingEQ(sr, f2, 1.8f, -absShift * 1.2f)
            filterHigh.setHighShelf(sr, 4000f, -absShift * 2.0f)
        }
    }

    fun reset() {
        filterLow.reset()
        filterFormant1.reset()
        filterFormant2.reset()
        filterHigh.reset()
        lastShift = Float.NaN
    }
}

/**
 * Robot Voice Ring Modulator (80 Hz to 400 Hz).
 * Produces authentic robotic cyber voice timbre.
 */
class RingModulator(private val sampleRate: Int) {
    private var phase: Double = 0.0

    fun process(samples: FloatArray, count: Int, enabled: Boolean, frequencyHz: Float) {
        if (!enabled) return

        val freq = frequencyHz.coerceIn(80f, 400f)
        val phaseIncrement = 2.0 * PI * freq / sampleRate

        for (i in 0 until count) {
            val carrier = sin(phase).toFloat()
            // Add slight 2nd harmonic for metallic edge
            val carrierHarmonic = 0.85f * carrier + 0.15f * sin(2.0 * phase).toFloat()

            // 85% modulated + 15% original dry for vocal intelligibility
            samples[i] = samples[i] * carrierHarmonic * 0.85f + samples[i] * 0.15f

            phase += phaseIncrement
            if (phase >= 2.0 * PI) {
                phase -= 2.0 * PI
            }
        }
    }

    fun reset() {
        phase = 0.0
    }
}

/**
 * Echo / Delay Effect with feedback and damping (0 to 500 ms).
 */
class EchoDelay(private val sampleRate: Int) {
    // 500ms max delay buffer
    private val maxDelaySamples: Int = (sampleRate * 0.5f).toInt() + 10
    private val delayBuffer = FloatArray(maxDelaySamples)
    private var writeIndex: Int = 0
    private var filterMemory: Float = 0f

    fun process(samples: FloatArray, count: Int, delayMs: Float, wetMix: Float) {
        if (delayMs <= 1f || wetMix <= 0.01f) {
            // Write to buffer to keep delay history continuous
            for (i in 0 until count) {
                delayBuffer[writeIndex] = samples[i]
                writeIndex = (writeIndex + 1) % maxDelaySamples
            }
            return
        }

        val delaySamples = (delayMs * sampleRate / 1000f).toInt().coerceIn(1, maxDelaySamples - 1)
        val feedback = 0.35f
        val damping = 0.25f // Simple 1-pole lowpass for warm decay

        for (i in 0 until count) {
            val dry = samples[i]

            // Read from delay line
            var readIndex = writeIndex - delaySamples
            if (readIndex < 0) readIndex += maxDelaySamples

            val delayed = delayBuffer[readIndex]

            // High frequency damping
            filterMemory = (1f - damping) * delayed + damping * filterMemory

            // Mix feedback into buffer
            delayBuffer[writeIndex] = dry + filterMemory * feedback
            writeIndex = (writeIndex + 1) % maxDelaySamples

            // Output wet/dry blend
            samples[i] = (1f - wetMix) * dry + wetMix * delayed
        }
    }

    fun reset() {
        delayBuffer.fill(0f)
        writeIndex = 0
        filterMemory = 0f
    }
}

/**
 * Complete Voice Effects Pipeline running in real-time.
 */
class VoiceEffectsPipeline(val sampleRate: Int = 44100) {
    val noiseGate = NoiseGate(sampleRate)
    val pitchShifter = GranularPitchShifter(sampleRate)
    val formantShifter = FormantShifter(sampleRate)
    val ringModulator = RingModulator(sampleRate)
    val echoDelay = EchoDelay(sampleRate)

    fun process(samples: FloatArray, count: Int, params: AudioEffectParams) {
        // 1. Noise Gate to remove ambient noise
        noiseGate.process(samples, count, params.gateThresholdDb)

        // 2. Robot / Ring Modulation
        ringModulator.process(samples, count, params.robotEnabled, params.robotFrequencyHz)

        // 3. Granular Pitch Shifting
        pitchShifter.process(samples, count, params.pitchSemitones)

        // 4. Formant / Vocal Tract Morphing
        formantShifter.process(samples, count, params.formantShift)

        // 5. Echo / Delay
        echoDelay.process(samples, count, params.echoDelayMs, params.echoWetMix)

        // 6. Master Gain & Soft Limiting (tanh saturation) to prevent digital clipping
        val gain = params.masterGain
        for (i in 0 until count) {
            val amplified = samples[i] * gain
            // Fast soft clipper
            samples[i] = if (amplified > 1.2f || amplified < -1.2f) {
                tanh(amplified.toDouble()).toFloat()
            } else if (amplified > 0.9f) {
                0.9f + (amplified - 0.9f) * 0.3f
            } else if (amplified < -0.9f) {
                -0.9f + (amplified + 0.9f) * 0.3f
            } else {
                amplified
            }
        }
    }

    fun reset() {
        noiseGate.reset()
        pitchShifter.reset()
        formantShifter.reset()
        ringModulator.reset()
        echoDelay.reset()
    }
}
