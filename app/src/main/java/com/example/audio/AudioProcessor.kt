package com.example.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * High-performance, real-time, low-latency Audio DSP Engine with Bluetooth SCO Headset routing support.
 * Runs on a dedicated Thread with THREAD_PRIORITY_URGENT_AUDIO.
 */
class AudioProcessor(private val context: Context) {

    companion object {
        private const val TAG = "AudioProcessor"
        private const val DEFAULT_SAMPLE_RATE = 44100
        private const val FRAME_CHUNK_SIZE = 512 // ~11.6ms latency chunk at 44.1kHz
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    val sampleRate: Int = run {
        val propRate = audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        if (propRate != null && (propRate == 44100 || propRate == 48000)) propRate else DEFAULT_SAMPLE_RATE
    }

    private val pipeline = VoiceEffectsPipeline(sampleRate)

    @Volatile
    var effectParams: AudioEffectParams = AudioEffectParams()

    private val isRunning = AtomicBoolean(false)
    private var processingThread: Thread? = null

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    // Routing preference: whether to prefer Bluetooth Headset / SCO microphone when available
    private val _preferBluetoothMic = MutableStateFlow(true)
    val preferBluetoothMic: StateFlow<Boolean> = _preferBluetoothMic.asStateFlow()

    // Bluetooth & Audio Route state
    private val _isBluetoothHeadsetConnected = MutableStateFlow(false)
    val isBluetoothHeadsetConnected: StateFlow<Boolean> = _isBluetoothHeadsetConnected.asStateFlow()

    private val _isBluetoothScoActive = MutableStateFlow(false)
    val isBluetoothScoActive: StateFlow<Boolean> = _isBluetoothScoActive.asStateFlow()

    private val _audioRouteDescription = MutableStateFlow("Built-in Microphone")
    val audioRouteDescription: StateFlow<String> = _audioRouteDescription.asStateFlow()

    // Real-time telemetry exposed to UI
    private val _isLiveProcessing = MutableStateFlow(false)
    val isLiveProcessing: StateFlow<Boolean> = _isLiveProcessing.asStateFlow()

    private val _waveformState = MutableStateFlow(FloatArray(64))
    val waveformState: StateFlow<FloatArray> = _waveformState.asStateFlow()

    private val _inputLevelDb = MutableStateFlow(-60f)
    val inputLevelDb: StateFlow<Float> = _inputLevelDb.asStateFlow()

    private val _outputLevelDb = MutableStateFlow(-60f)
    val outputLevelDb: StateFlow<Float> = _outputLevelDb.asStateFlow()

    private val _isGateActive = MutableStateFlow(true)
    val isGateActive: StateFlow<Boolean> = _isGateActive.asStateFlow()

    private val _estimatedLatencyMs = MutableStateFlow(24)
    val estimatedLatencyMs: StateFlow<Int> = _estimatedLatencyMs.asStateFlow()

    // Test loop recorder state
    private val _isTestRecording = MutableStateFlow(false)
    val isTestRecording: StateFlow<Boolean> = _isTestRecording.asStateFlow()

    private val _isTestLoopPlaying = MutableStateFlow(false)
    val isTestLoopPlaying: StateFlow<Boolean> = _isTestLoopPlaying.asStateFlow()

    private val testRecordedSamples = mutableListOf<Float>()
    private var testLoopThread: Thread? = null
    private val isTestLoopRunning = AtomicBoolean(false)

    // AudioDeviceCallback to listen for Bluetooth and Headset connections
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            refreshAudioDeviceRoutes()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            refreshAudioDeviceRoutes()
        }
    }

    init {
        try {
            audioManager?.registerAudioDeviceCallback(audioDeviceCallback, null)
        } catch (e: Exception) {
            Log.w(TAG, "Could not register AudioDeviceCallback", e)
        }
        refreshAudioDeviceRoutes()
    }

    /**
     * Toggles preference for Bluetooth Headset microphone input.
     */
    fun setPreferBluetoothMic(prefer: Boolean) {
        _preferBluetoothMic.value = prefer
        refreshAudioDeviceRoutes()
    }

    /**
     * Refreshes active audio devices and identifies connected Bluetooth/Wired headsets.
     */
    fun refreshAudioDeviceRoutes() {
        val am = audioManager ?: return
        try {
            val inputDevices = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            val outputDevices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

            val hasBtInput = inputDevices.any { isBluetoothDevice(it) }
            val hasBtOutput = outputDevices.any { isBluetoothDevice(it) }
            val isBtAvailable = hasBtInput || hasBtOutput

            _isBluetoothHeadsetConnected.value = isBtAvailable

            val hasWiredHeadset = outputDevices.any {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }

            val routeName = when {
                isBtAvailable && _preferBluetoothMic.value -> {
                    val btDev = inputDevices.firstOrNull { isBluetoothDevice(it) }
                    val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && btDev?.productName?.isNotBlank() == true) {
                        btDev.productName.toString()
                    } else {
                        "Bluetooth Headset (SCO)"
                    }
                    "Bluetooth Mic ($name)"
                }
                hasWiredHeadset -> "Wired Headset"
                else -> "Device Built-in Mic"
            }
            _audioRouteDescription.value = routeName
        } catch (e: Exception) {
            Log.w(TAG, "Error checking audio devices", e)
        }
    }

    private fun isBluetoothDevice(device: AudioDeviceInfo): Boolean {
        return device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
               device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
               (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && (
                   device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                   device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                   device.type == AudioDeviceInfo.TYPE_HEARING_AID
               ))
    }

    /**
     * Checks if headphones/headset (wired, USB, or bluetooth) are connected.
     */
    fun isHeadsetConnected(): Boolean {
        return try {
            val devices = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: return false
            devices.any { device ->
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && (
                    device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_HEARING_AID
                ))
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Configures audio routing profiles for Bluetooth SCO communication if requested.
     */
    private fun configureBluetoothAudioRouting(enable: Boolean): AudioDeviceInfo? {
        val am = audioManager ?: return null
        if (!enable) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    am.clearCommunicationDevice()
                }
                if (am.isBluetoothScoOn) {
                    am.stopBluetoothSco()
                    am.isBluetoothScoOn = false
                }
                am.mode = AudioManager.MODE_NORMAL
            } catch (e: Exception) {
                Log.w(TAG, "Error tearing down Bluetooth SCO routing", e)
            }
            _isBluetoothScoActive.value = false
            return null
        }

        var selectedDevice: AudioDeviceInfo? = null
        try {
            if (_preferBluetoothMic.value && _isBluetoothHeadsetConnected.value) {
                am.mode = AudioManager.MODE_IN_COMMUNICATION

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val commDevices = am.availableCommunicationDevices
                    val btCommDevice = commDevices.firstOrNull { isBluetoothDevice(it) }
                    if (btCommDevice != null) {
                        val result = am.setCommunicationDevice(btCommDevice)
                        Log.i(TAG, "setCommunicationDevice: $result (${btCommDevice.productName})")
                        selectedDevice = btCommDevice
                    }
                }

                am.startBluetoothSco()
                am.isBluetoothScoOn = true
                _isBluetoothScoActive.value = true
                Log.i(TAG, "Bluetooth SCO audio routing enabled")
            } else {
                am.mode = AudioManager.MODE_NORMAL
                _isBluetoothScoActive.value = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error setting up Bluetooth SCO routing", e)
        }
        return selectedDevice
    }

    /**
     * Starts the real-time low-latency audio processing loop with appropriate routing profile.
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun startRealtimeProcessing(): Boolean {
        if (isRunning.get()) return true
        stopTestLoop()

        val shouldUseBluetoothRouting = _preferBluetoothMic.value && _isBluetoothHeadsetConnected.value
        val btDevice = configureBluetoothAudioRouting(shouldUseBluetoothRouting)

        val minInBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val minOutBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minInBuf <= 0 || minOutBuf <= 0) {
            Log.e(TAG, "Invalid buffer sizes: in=$minInBuf, out=$minOutBuf")
            configureBluetoothAudioRouting(false)
            return false
        }

        try {
            val audioSource = if (shouldUseBluetoothRouting) {
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            } else {
                MediaRecorder.AudioSource.MIC
            }

            val recordBufSize = max(minInBuf, FRAME_CHUNK_SIZE * 4)
            val audioRecordFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()

            audioRecord = AudioRecord.Builder()
                .setAudioSource(audioSource)
                .setAudioFormat(audioRecordFormat)
                .setBufferSizeInBytes(recordBufSize)
                .build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                audioRecord?.release()
                audioRecord = null
                configureBluetoothAudioRouting(false)
                return false
            }

            // Set preferred device for AudioRecord if available
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && btDevice != null) {
                try {
                    audioRecord?.setPreferredDevice(btDevice)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set preferred input device", e)
                }
            }

            // Low latency AudioTrack setup
            val audioUsage = if (shouldUseBluetoothRouting) {
                AudioAttributes.USAGE_VOICE_COMMUNICATION
            } else {
                AudioAttributes.USAGE_MEDIA
            }

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(audioUsage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setFlags(AudioAttributes.FLAG_LOW_LATENCY)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            val trackBufSize = max(minOutBuf, FRAME_CHUNK_SIZE * 4)
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(trackBufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack failed to initialize")
                audioRecord?.release()
                audioRecord = null
                audioTrack?.release()
                audioTrack = null
                configureBluetoothAudioRouting(false)
                return false
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && btDevice != null) {
                try {
                    audioTrack?.setPreferredDevice(btDevice)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set preferred output device", e)
                }
            }

            // Estimate latency: (In buffer chunks + Out buffer chunks) / sampleRate
            val calculatedLatency = ((FRAME_CHUNK_SIZE * 2.5f) / sampleRate * 1000).toInt()
            _estimatedLatencyMs.value = calculatedLatency.coerceIn(12, 48)

            pipeline.reset()
            isRunning.set(true)
            _isLiveProcessing.value = true

            audioRecord?.startRecording()
            audioTrack?.play()

            processingThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

                val shortIn = ShortArray(FRAME_CHUNK_SIZE)
                val floatBuffer = FloatArray(FRAME_CHUNK_SIZE)
                val shortOut = ShortArray(FRAME_CHUNK_SIZE)
                val visualizerPacket = FloatArray(64)
                var visualizerCounter = 0

                while (isRunning.get()) {
                    val record = audioRecord ?: break
                    val track = audioTrack ?: break

                    val readCount = record.read(shortIn, 0, FRAME_CHUNK_SIZE)
                    if (readCount <= 0) continue

                    // 1. Convert Short PCM to Float [-1.0, 1.0] and compute input RMS
                    var inSumSq = 0.0
                    for (i in 0 until readCount) {
                        val s = shortIn[i] / 32768.0f
                        floatBuffer[i] = s
                        inSumSq += (s * s)
                    }
                    val inRms = sqrt(inSumSq / readCount).toFloat()
                    val inDb = if (inRms > 0.00001f) 20f * log10(inRms) else -60f

                    // 2. Process DSP pipeline
                    val params = effectParams
                    pipeline.process(floatBuffer, readCount, params)

                    // 3. Compute output RMS and convert to Short PCM
                    var outSumSq = 0.0
                    for (i in 0 until readCount) {
                        val s = floatBuffer[i].coerceIn(-1.0f, 1.0f)
                        outSumSq += (s * s)
                        shortOut[i] = (s * 32767.0f).toInt().toShort()
                    }
                    val outRms = sqrt(outSumSq / readCount).toFloat()
                    val outDb = if (outRms > 0.00001f) 20f * log10(outRms) else -60f

                    // 4. Output to low-latency stream
                    track.write(shortOut, 0, readCount, AudioTrack.WRITE_BLOCKING)

                    // 5. Downsample for UI visualizer
                    visualizerCounter++
                    if (visualizerCounter >= 2) {
                        visualizerCounter = 0
                        val step = readCount / 64
                        for (j in 0 until 64) {
                            val idx = (j * step).coerceIn(0, readCount - 1)
                            visualizerPacket[j] = floatBuffer[idx]
                        }
                        _waveformState.value = visualizerPacket.clone()
                        _inputLevelDb.value = inDb.coerceIn(-60f, 0f)
                        _outputLevelDb.value = outDb.coerceIn(-60f, 0f)
                        _isGateActive.value = pipeline.noiseGate.isGateOpen
                    }
                }
            }, "LowLatencyVoiceAudioThread").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting audio engine", e)
            stopRealtimeProcessing()
            return false
        }
    }

    /**
     * Stops the real-time audio processing loop and releases resources.
     */
    @Synchronized
    fun stopRealtimeProcessing() {
        isRunning.set(false)
        _isLiveProcessing.value = false

        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord", e)
        }
        try {
            audioTrack?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioTrack", e)
        }

        try {
            processingThread?.join(300)
        } catch (e: Exception) {
            Log.w(TAG, "Error joining audio thread", e)
        }
        processingThread = null

        audioRecord?.release()
        audioRecord = null
        audioTrack?.release()
        audioTrack = null

        // Teardown Bluetooth SCO audio routing
        configureBluetoothAudioRouting(false)

        // Reset visualizer levels
        _waveformState.value = FloatArray(64)
        _inputLevelDb.value = -60f
        _outputLevelDb.value = -60f
    }

    /**
     * Records a short voice sample (up to 3 seconds) with appropriate audio routing.
     */
    @SuppressLint("MissingPermission")
    fun startTestRecording(onComplete: (Boolean) -> Unit) {
        if (isRunning.get()) stopRealtimeProcessing()
        stopTestLoop()

        _isTestRecording.value = true
        testRecordedSamples.clear()

        val shouldUseBluetoothRouting = _preferBluetoothMic.value && _isBluetoothHeadsetConnected.value
        val btDevice = configureBluetoothAudioRouting(shouldUseBluetoothRouting)

        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val minInBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            var recorder: AudioRecord? = null
            try {
                val audioSource = if (shouldUseBluetoothRouting) {
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION
                } else {
                    MediaRecorder.AudioSource.MIC
                }

                val recordBufSize = max(minInBuf, FRAME_CHUNK_SIZE * 4)
                val audioRecordFormat = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()

                recorder = AudioRecord.Builder()
                    .setAudioSource(audioSource)
                    .setAudioFormat(audioRecordFormat)
                    .setBufferSizeInBytes(recordBufSize)
                    .build()

                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    _isTestRecording.value = false
                    configureBluetoothAudioRouting(false)
                    onComplete(false)
                    return@Thread
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && btDevice != null) {
                    try {
                        recorder.setPreferredDevice(btDevice)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to set preferred input device for test record", e)
                    }
                }

                recorder.startRecording()
                val shortIn = ShortArray(FRAME_CHUNK_SIZE)
                val maxSamples = sampleRate * 3 // 3 seconds sample
                var totalRecorded = 0

                val visualizerPacket = FloatArray(64)

                while (_isTestRecording.value && totalRecorded < maxSamples) {
                    val read = recorder.read(shortIn, 0, FRAME_CHUNK_SIZE)
                    if (read <= 0) continue

                    for (i in 0 until read) {
                        val s = shortIn[i] / 32768.0f
                        testRecordedSamples.add(s)
                    }
                    totalRecorded += read

                    // Visual feedback
                    val step = read / 64
                    for (j in 0 until 64) {
                        val idx = (j * step).coerceIn(0, read - 1)
                        visualizerPacket[j] = shortIn[idx] / 32768.0f
                    }
                    _waveformState.value = visualizerPacket.clone()
                }

                recorder.stop()
                recorder.release()
                configureBluetoothAudioRouting(false)
                _isTestRecording.value = false
                onComplete(testRecordedSamples.isNotEmpty())
            } catch (e: Exception) {
                Log.e(TAG, "Test record error", e)
                recorder?.release()
                configureBluetoothAudioRouting(false)
                _isTestRecording.value = false
                onComplete(false)
            }
        }, "TestVoiceRecorderThread").start()
    }

    fun stopTestRecording() {
        _isTestRecording.value = false
    }

    /**
     * Plays the recorded test voice in a loop with live real-time DSP effects applied.
     */
    fun startTestLoop() {
        if (testRecordedSamples.isEmpty()) return
        if (isRunning.get()) stopRealtimeProcessing()
        stopTestLoop()

        isTestLoopRunning.set(true)
        _isTestLoopPlaying.value = true

        testLoopThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            val minOutBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setFlags(AudioAttributes.FLAG_LOW_LATENCY)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(max(minOutBuf, FRAME_CHUNK_SIZE * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack.play()
            val chunk = FloatArray(FRAME_CHUNK_SIZE)
            val shortOut = ShortArray(FRAME_CHUNK_SIZE)
            val visualizerPacket = FloatArray(64)
            val loopPipeline = VoiceEffectsPipeline(sampleRate)

            var sampleIdx = 0
            val recordedCount = testRecordedSamples.size

            while (isTestLoopRunning.get()) {
                val toRead = min(FRAME_CHUNK_SIZE, recordedCount - sampleIdx)
                for (i in 0 until toRead) {
                    chunk[i] = testRecordedSamples[sampleIdx + i]
                }
                sampleIdx += toRead
                if (sampleIdx >= recordedCount) {
                    sampleIdx = 0
                }

                // Process DSP
                loopPipeline.process(chunk, toRead, effectParams)

                // Output
                for (i in 0 until toRead) {
                    val s = chunk[i].coerceIn(-1.0f, 1.0f)
                    shortOut[i] = (s * 32767.0f).toInt().toShort()
                }
                audioTrack.write(shortOut, 0, toRead, AudioTrack.WRITE_BLOCKING)

                // Visualizer
                val step = max(1, toRead / 64)
                for (j in 0 until 64) {
                    val idx = (j * step).coerceIn(0, toRead - 1)
                    visualizerPacket[j] = chunk[idx]
                }
                _waveformState.value = visualizerPacket.clone()
                _isGateActive.value = loopPipeline.noiseGate.isGateOpen
            }

            audioTrack.stop()
            audioTrack.release()
            _isTestLoopPlaying.value = false
        }, "TestVoicePlaybackLoopThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stopTestLoop() {
        isTestLoopRunning.set(false)
        _isTestLoopPlaying.value = false
        try {
            testLoopThread?.join(300)
        } catch (e: Exception) {
            Log.w(TAG, "Error joining test loop thread", e)
        }
        testLoopThread = null
    }

    fun hasRecordedVoice(): Boolean = testRecordedSamples.isNotEmpty()

    fun release() {
        stopRealtimeProcessing()
        stopTestLoop()
        try {
            audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering callback", e)
        }
    }
}
