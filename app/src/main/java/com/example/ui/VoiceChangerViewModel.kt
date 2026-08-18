package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioEffectParams
import com.example.audio.AudioEngine
import com.example.audio.AudioProcessor
import com.example.model.PresetsManager
import com.example.model.VoicePreset
import com.example.service.VoiceChangerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VoiceChangerUiState(
    val params: AudioEffectParams = AudioEffectParams(),
    val isLiveProcessing: Boolean = false,
    val isTestRecording: Boolean = false,
    val isTestLoopPlaying: Boolean = false,
    val hasRecordedSample: Boolean = false,
    val isHeadsetConnected: Boolean = false,
    val isBluetoothHeadsetConnected: Boolean = false,
    val isBluetoothScoActive: Boolean = false,
    val preferBluetoothMic: Boolean = true,
    val audioRouteDescription: String = "Built-in Microphone",
    val showHeadsetWarning: Boolean = true,
    val waveform: FloatArray = FloatArray(64),
    val inputDb: Float = -60f,
    val outputDb: Float = -60f,
    val isGateOpen: Boolean = true,
    val estimatedLatencyMs: Int = 22,
    val selectedPresetId: String = "normal",
    val customPresets: List<VoicePreset> = emptyList(),
    val allPresets: List<VoicePreset> = PresetsManager.builtInPresets,
    val isSavePresetDialogVisible: Boolean = false,
    val errorMessage: String? = null
)

class VoiceChangerViewModel(application: Application) : AndroidViewModel(application) {

    val audioProcessor: AudioProcessor = AudioEngine.getInstance(application)

    private val _uiState = MutableStateFlow(VoiceChangerUiState())
    val uiState: StateFlow<VoiceChangerUiState> = _uiState.asStateFlow()

    init {
        loadPresets()
        checkHeadset()

        // Observe audio processor telemetry
        viewModelScope.launch {
            audioProcessor.isLiveProcessing.collect { live ->
                _uiState.update { it.copy(isLiveProcessing = live) }
            }
        }
        viewModelScope.launch {
            audioProcessor.waveformState.collect { wave ->
                _uiState.update { it.copy(waveform = wave) }
            }
        }
        viewModelScope.launch {
            audioProcessor.inputLevelDb.collect { inDb ->
                _uiState.update { it.copy(inputDb = inDb) }
            }
        }
        viewModelScope.launch {
            audioProcessor.outputLevelDb.collect { outDb ->
                _uiState.update { it.copy(outputDb = outDb) }
            }
        }
        viewModelScope.launch {
            audioProcessor.isGateActive.collect { open ->
                _uiState.update { it.copy(isGateOpen = open) }
            }
        }
        viewModelScope.launch {
            audioProcessor.estimatedLatencyMs.collect { lat ->
                _uiState.update { it.copy(estimatedLatencyMs = lat) }
            }
        }
        viewModelScope.launch {
            audioProcessor.isTestRecording.collect { rec ->
                _uiState.update { it.copy(isTestRecording = rec) }
            }
        }
        viewModelScope.launch {
            audioProcessor.isTestLoopPlaying.collect { loop ->
                _uiState.update { it.copy(isTestLoopPlaying = loop) }
            }
        }
        viewModelScope.launch {
            audioProcessor.isBluetoothHeadsetConnected.collect { btConnected ->
                _uiState.update { it.copy(isBluetoothHeadsetConnected = btConnected) }
                checkHeadset()
            }
        }
        viewModelScope.launch {
            audioProcessor.isBluetoothScoActive.collect { scoActive ->
                _uiState.update { it.copy(isBluetoothScoActive = scoActive) }
            }
        }
        viewModelScope.launch {
            audioProcessor.preferBluetoothMic.collect { preferBt ->
                _uiState.update { it.copy(preferBluetoothMic = preferBt) }
            }
        }
        viewModelScope.launch {
            audioProcessor.audioRouteDescription.collect { route ->
                _uiState.update { it.copy(audioRouteDescription = route) }
            }
        }
    }

    fun checkHeadset() {
        val isPlugged = audioProcessor.isHeadsetConnected()
        audioProcessor.refreshAudioDeviceRoutes()
        _uiState.update {
            it.copy(
                isHeadsetConnected = isPlugged,
                showHeadsetWarning = !isPlugged && it.showHeadsetWarning
            )
        }
    }

    fun setPreferBluetoothMic(prefer: Boolean) {
        audioProcessor.setPreferBluetoothMic(prefer)
    }

    fun dismissHeadsetWarning() {
        _uiState.update { it.copy(showHeadsetWarning = false) }
    }

    private fun loadPresets() {
        val custom = PresetsManager.loadCustomPresets(getApplication())
        val combined = PresetsManager.builtInPresets + custom
        _uiState.update {
            it.copy(
                customPresets = custom,
                allPresets = combined
            )
        }
    }

    private fun syncParams(newParams: AudioEffectParams) {
        audioProcessor.effectParams = newParams
        _uiState.update { it.copy(params = newParams) }
    }

    fun toggleLiveProcessing(): Boolean {
        return if (_uiState.value.isLiveProcessing) {
            VoiceChangerService.stopService(getApplication())
            audioProcessor.stopRealtimeProcessing()
            true
        } else {
            VoiceChangerService.startService(getApplication())
            true
        }
    }

    fun setPitch(semitones: Float) {
        val newParams = _uiState.value.params.copy(pitchSemitones = semitones)
        syncParams(newParams)
        checkCustomPresetActive()
    }

    fun setFormant(shift: Float) {
        val newParams = _uiState.value.params.copy(formantShift = shift)
        syncParams(newParams)
        checkCustomPresetActive()
    }

    fun setEchoDelay(delayMs: Float) {
        val newParams = _uiState.value.params.copy(echoDelayMs = delayMs)
        syncParams(newParams)
        checkCustomPresetActive()
    }

    fun setEchoWetMix(mix: Float) {
        val newParams = _uiState.value.params.copy(echoWetMix = mix)
        syncParams(newParams)
        checkCustomPresetActive()
    }

    fun setRobotEnabled(enabled: Boolean) {
        val newParams = _uiState.value.params.copy(robotEnabled = enabled)
        syncParams(newParams)
        checkCustomPresetActive()
    }

    fun setRobotFrequency(freqHz: Float) {
        val newParams = _uiState.value.params.copy(robotFrequencyHz = freqHz)
        syncParams(newParams)
        checkCustomPresetActive()
    }

    fun setGateThreshold(thresholdDb: Float) {
        val newParams = _uiState.value.params.copy(gateThresholdDb = thresholdDb)
        syncParams(newParams)
        checkCustomPresetActive()
    }

    fun setMasterGain(gain: Float) {
        val newParams = _uiState.value.params.copy(masterGain = gain)
        syncParams(newParams)
    }

    fun selectPreset(preset: VoicePreset) {
        syncParams(preset.params)
        _uiState.update { it.copy(selectedPresetId = preset.id) }
    }

    private fun checkCustomPresetActive() {
        val cur = _uiState.value.params
        val matched = _uiState.value.allPresets.find { it.params == cur }
        _uiState.update { it.copy(selectedPresetId = matched?.id ?: "custom") }
    }

    fun resetToDefault() {
        val normalPreset = PresetsManager.builtInPresets.first()
        selectPreset(normalPreset)
    }

    fun showSavePresetDialog(show: Boolean) {
        _uiState.update { it.copy(isSavePresetDialogVisible = show) }
    }

    fun saveCurrentPreset(name: String, emoji: String, description: String) {
        val id = "custom_" + System.currentTimeMillis()
        val newPreset = VoicePreset(
            id = id,
            name = name.ifBlank { "Custom Preset" },
            description = description.ifBlank { "User created preset" },
            emoji = emoji.ifBlank { "✨" },
            params = _uiState.value.params,
            isCustom = true
        )
        PresetsManager.saveCustomPreset(getApplication(), newPreset)
        loadPresets()
        _uiState.update {
            it.copy(
                selectedPresetId = id,
                isSavePresetDialogVisible = false
            )
        }
    }

    fun deleteCustomPreset(presetId: String) {
        PresetsManager.deleteCustomPreset(getApplication(), presetId)
        loadPresets()
        if (_uiState.value.selectedPresetId == presetId) {
            selectPreset(PresetsManager.builtInPresets.first())
        }
    }

    // Voice test recording and looping
    fun startTestRecording() {
        audioProcessor.startTestRecording { success ->
            _uiState.update { it.copy(hasRecordedSample = success) }
            if (success) {
                audioProcessor.startTestLoop()
            }
        }
    }

    fun stopTestRecording() {
        audioProcessor.stopTestRecording()
    }

    fun toggleTestLoop() {
        if (_uiState.value.isTestLoopPlaying) {
            audioProcessor.stopTestLoop()
        } else {
            audioProcessor.startTestLoop()
        }
    }

    fun clearErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
