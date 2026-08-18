package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ui.VoiceChangerUiState
import com.example.ui.VoiceChangerViewModel
import com.example.ui.components.AudioRouteCard
import com.example.ui.components.EffectSliderCard
import com.example.ui.components.HeadphoneAdvisoryBanner
import com.example.ui.components.PresetSelector
import com.example.ui.components.SavePresetDialog
import com.example.ui.components.TestVoicePanel
import com.example.ui.components.WaveformVisualizer
import com.example.ui.theme.AppThemeMode
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonMagenta
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.ThemeManager
import com.example.ui.theme.VocalMorphTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val viewModel: VoiceChangerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.init(this)
        enableEdgeToEdge()

        setContent {
            val currentThemeMode by ThemeManager.themeMode.collectAsState()
            VocalMorphTheme(themeMode = currentThemeMode) {
                VoiceChangerApp(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkHeadset()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceChangerApp(
    viewModel: VoiceChangerViewModel
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val currentThemeMode by ThemeManager.themeMode.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var showInfoDialog by remember { mutableStateOf(false) }
    var showThemeMenu by remember { mutableStateOf(false) }

    // Multi-permission launcher for Microphone, Notifications (Android 13+), and Bluetooth Connect (Android 12+)
    val permissionsToRequest = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { permissionsMap ->
            val recordGranted = permissionsMap[Manifest.permission.RECORD_AUDIO] ?: false
            if (recordGranted) {
                viewModel.checkHeadset()
                viewModel.toggleLiveProcessing()
            } else {
                scope.launch {
                    snackbarHostState.showSnackbar("Microphone permission is required for real-time voice DSP.")
                }
            }
        }
    )

    fun handleToggleLive() {
        val hasRecordPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasRecordPermission) {
            viewModel.toggleLiveProcessing()
        } else {
            permissionLauncher.launch(permissionsToRequest)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.systemBars,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(NeonCyan, NeonMagenta)
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "VOCALMORPH",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.5.sp,
                                fontFamily = FontFamily.Monospace
                            ),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                actions = {
                    // Dark / Light Theme Toggle Menu
                    Box {
                        IconButton(
                            onClick = { showThemeMenu = true },
                            modifier = Modifier.testTag("theme_toggle_button")
                        ) {
                            val themeIcon = when (currentThemeMode) {
                                AppThemeMode.DARK -> Icons.Default.DarkMode
                                AppThemeMode.LIGHT -> Icons.Default.LightMode
                                AppThemeMode.SYSTEM -> Icons.Default.BrightnessAuto
                            }
                            Icon(
                                imageVector = themeIcon,
                                contentDescription = "Theme: ${currentThemeMode.title}",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        DropdownMenu(
                            expanded = showThemeMenu,
                            onDismissRequest = { showThemeMenu = false },
                            modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                        ) {
                            DropdownMenuItem(
                                text = { Text("Dark Theme (OLED / Low Light)") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.DarkMode,
                                        contentDescription = null,
                                        tint = if (currentThemeMode == AppThemeMode.DARK) NeonCyan else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                onClick = {
                                    ThemeManager.setThemeMode(context, AppThemeMode.DARK)
                                    showThemeMenu = false
                                },
                                modifier = Modifier.testTag("theme_dark_option")
                            )
                            DropdownMenuItem(
                                text = { Text("Light Theme") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.LightMode,
                                        contentDescription = null,
                                        tint = if (currentThemeMode == AppThemeMode.LIGHT) NeonAmber else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                onClick = {
                                    ThemeManager.setThemeMode(context, AppThemeMode.LIGHT)
                                    showThemeMenu = false
                                },
                                modifier = Modifier.testTag("theme_light_option")
                            )
                            DropdownMenuItem(
                                text = { Text("System Default") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.BrightnessAuto,
                                        contentDescription = null,
                                        tint = if (currentThemeMode == AppThemeMode.SYSTEM) NeonPurple else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                onClick = {
                                    ThemeManager.setThemeMode(context, AppThemeMode.SYSTEM)
                                    showThemeMenu = false
                                },
                                modifier = Modifier.testTag("theme_system_option")
                            )
                        }
                    }

                    // Reset params button
                    IconButton(
                        onClick = { viewModel.resetToDefault() },
                        modifier = Modifier.testTag("reset_defaults_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reset Effects",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Info button
                    IconButton(
                        onClick = { showInfoDialog = true },
                        modifier = Modifier.testTag("info_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "App Info",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Headphone Advisory Banner
            item {
                HeadphoneAdvisoryBanner(
                    visible = uiState.showHeadsetWarning && !uiState.isHeadsetConnected,
                    onDismiss = { viewModel.dismissHeadsetWarning() }
                )
            }

            // 2. Audio Routing Profile & Bluetooth SCO Card
            item {
                AudioRouteCard(
                    audioRouteDescription = uiState.audioRouteDescription,
                    isBluetoothConnected = uiState.isBluetoothHeadsetConnected,
                    isBluetoothScoActive = uiState.isBluetoothScoActive,
                    preferBluetoothMic = uiState.preferBluetoothMic,
                    onTogglePreferBluetoothMic = { viewModel.setPreferBluetoothMic(it) },
                    onRefreshRoutes = { viewModel.checkHeadset() },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // 3. Master ON/OFF Activation Hero Button
            item {
                MasterPowerButton(
                    isProcessing = uiState.isLiveProcessing,
                    onToggle = { handleToggleLive() }
                )
            }

            // 4. Live Oscilloscope & VU Meters
            item {
                WaveformVisualizer(
                    waveform = uiState.waveform,
                    inputDb = uiState.inputDb,
                    outputDb = uiState.outputDb,
                    isGateOpen = uiState.isGateOpen,
                    isProcessing = uiState.isLiveProcessing || uiState.isTestLoopPlaying,
                    latencyMs = uiState.estimatedLatencyMs,
                    sampleRate = viewModel.audioProcessor.sampleRate,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // 5. Quick Voice Test Loopback Panel
            item {
                TestVoicePanel(
                    isRecording = uiState.isTestRecording,
                    isPlayingLoop = uiState.isTestLoopPlaying,
                    hasRecordedSample = uiState.hasRecordedSample,
                    onStartRecord = {
                        val hasPermission = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasPermission) {
                            viewModel.startTestRecording()
                        } else {
                            permissionLauncher.launch(permissionsToRequest)
                        }
                    },
                    onStopRecord = { viewModel.stopTestRecording() },
                    onToggleLoopPlay = { viewModel.toggleTestLoop() },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // 6. Presets Horizontal Selector
            item {
                PresetSelector(
                    presets = uiState.allPresets,
                    selectedPresetId = uiState.selectedPresetId,
                    onSelectPreset = { viewModel.selectPreset(it) },
                    onSaveCustomClick = { viewModel.showSavePresetDialog(true) },
                    onDeleteCustomPreset = { viewModel.deleteCustomPreset(it) }
                )
            }

            // 7. Effect Controls Section Header
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Sensors,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "EFFECT PARAMETERS",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // 8. Pitch Shifter Card (-12 to +12 semitones)
            item {
                val pitchVal = uiState.params.pitchSemitones
                val formattedPitch = when {
                    pitchVal > 0.05f -> "+${"%.1f".format(pitchVal)} st"
                    pitchVal < -0.05f -> "${"%.1f".format(pitchVal)} st"
                    else -> "0.0 st (Original)"
                }
                EffectSliderCard(
                    title = "Pitch Shift",
                    subtitle = "Vocal frequency scaling (-12 to +12 semitones)",
                    icon = Icons.Default.GraphicEq,
                    accentColor = NeonCyan,
                    value = pitchVal,
                    valueRange = -12f..12f,
                    valueFormatted = formattedPitch,
                    onValueChange = { viewModel.setPitch(it) },
                    testTagPrefix = "pitch",
                    quickPresets = listOf(
                        "-12 st (Octave Down)" to -12f,
                        "-7 st" to -7f,
                        "0 st (Default)" to 0f,
                        "+7 st" to 7f,
                        "+12 st (Octave Up)" to 12f
                    ),
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // 9. Formant / Vocal Tract Shift Card (-5 to +5: Masculine <-> Feminine)
            item {
                val formantVal = uiState.params.formantShift
                val formattedFormant = when {
                    formantVal > 0.2f -> "+${"%.1f".format(formantVal)} (Feminine / Bright)"
                    formantVal < -0.2f -> "${"%.1f".format(formantVal)} (Masculine / Deep)"
                    else -> "0.0 (Neutral)"
                }
                EffectSliderCard(
                    title = "Formant / Vocal Shift",
                    subtitle = "Resonant vocal tract morphing without altering base pitch",
                    icon = Icons.Default.RecordVoiceOver,
                    accentColor = NeonMagenta,
                    value = formantVal,
                    valueRange = -5f..5f,
                    valueFormatted = formattedFormant,
                    onValueChange = { viewModel.setFormant(it) },
                    testTagPrefix = "formant",
                    quickPresets = listOf(
                        "Deep (-5)" to -5f,
                        "Warm (-2.5)" to -2.5f,
                        "Neutral (0)" to 0f,
                        "Bright (+2.5)" to 2.5f,
                        "Feminine (+5)" to 5f
                    ),
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // 10. Robot Effect Card (Ring Modulation 80 to 400 Hz)
            item {
                val robotEnabled = uiState.params.robotEnabled
                val robotFreq = uiState.params.robotFrequencyHz
                EffectSliderCard(
                    title = "Robot Modulator",
                    subtitle = "Sci-Fi ring modulation carrier wave (80–400 Hz)",
                    icon = Icons.Default.SmartToy,
                    accentColor = NeonGreen,
                    value = robotFreq,
                    valueRange = 80f..400f,
                    valueFormatted = "${robotFreq.roundToInt()} Hz",
                    onValueChange = { viewModel.setRobotFrequency(it) },
                    testTagPrefix = "robot",
                    hasToggle = true,
                    toggleState = robotEnabled,
                    onToggleChange = { viewModel.setRobotEnabled(it) },
                    quickPresets = listOf(
                        "Dalek (90Hz)" to 90f,
                        "Android (160Hz)" to 160f,
                        "Metallic (240Hz)" to 240f,
                        "Cyber (360Hz)" to 360f
                    ),
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // 11. Echo / Spatial Delay Card (0-500ms, Wet/Dry mix)
            item {
                val echoDelay = uiState.params.echoDelayMs
                val echoMix = uiState.params.echoWetMix
                val echoSubtitle = if (echoDelay > 1f && echoMix > 0.01f) {
                    "${echoDelay.roundToInt()}ms delay • ${(echoMix * 100).roundToInt()}% wet mix"
                } else {
                    "Spatial reflections and acoustic feedback delay"
                }

                EffectSliderCard(
                    title = "Echo Delay Time",
                    subtitle = echoSubtitle,
                    icon = Icons.Default.SurroundSound,
                    accentColor = NeonPurple,
                    value = echoDelay,
                    valueRange = 0f..500f,
                    valueFormatted = "${echoDelay.roundToInt()} ms",
                    onValueChange = { viewModel.setEchoDelay(it) },
                    testTagPrefix = "echo_delay",
                    quickPresets = listOf(
                        "Off (0ms)" to 0f,
                        "Slapback (40ms)" to 40f,
                        "Room (120ms)" to 120f,
                        "Cave (280ms)" to 280f,
                        "Canyon (450ms)" to 450f
                    ),
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // 12. Echo Wet/Dry Mix Card
            item {
                val echoMix = uiState.params.echoWetMix
                EffectSliderCard(
                    title = "Echo Wet / Dry Mix",
                    subtitle = "Balance between dry vocal and delayed reflection",
                    icon = Icons.Default.Waves,
                    accentColor = NeonPurple,
                    value = echoMix,
                    valueRange = 0f..1f,
                    valueFormatted = "${(echoMix * 100).roundToInt()}%",
                    onValueChange = { viewModel.setEchoWetMix(it) },
                    testTagPrefix = "echo_mix",
                    quickPresets = listOf(
                        "Dry (0%)" to 0f,
                        "Subtle (20%)" to 0.2f,
                        "Balanced (40%)" to 0.4f,
                        "Lush (65%)" to 0.65f,
                        "Full (100%)" to 1f
                    ),
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // 13. Noise Gate Card (-60dB to -10dB)
            item {
                val gateThreshold = uiState.params.gateThresholdDb
                EffectSliderCard(
                    title = "Noise Gate Threshold",
                    subtitle = "Silences ambient room hum and background breath noise",
                    icon = Icons.Default.Security,
                    accentColor = NeonAmber,
                    value = gateThreshold,
                    valueRange = -60f..-10f,
                    valueFormatted = "${gateThreshold.roundToInt()} dB",
                    onValueChange = { viewModel.setGateThreshold(it) },
                    testTagPrefix = "noise_gate",
                    quickPresets = listOf(
                        "Low (-54dB)" to -54f,
                        "Standard (-46dB)" to -46f,
                        "Aggressive (-38dB)" to -38f,
                        "Strict (-28dB)" to -28f
                    ),
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // 14. Master Volume Gain Card
            item {
                val gain = uiState.params.masterGain
                EffectSliderCard(
                    title = "Master Volume Gain",
                    subtitle = "Output signal multiplier with soft-knee limiter",
                    icon = Icons.Default.VolumeUp,
                    accentColor = NeonCyan,
                    value = gain,
                    valueRange = 0.5f..2.0f,
                    valueFormatted = "${"%.2f".format(gain)}x",
                    onValueChange = { viewModel.setMasterGain(it) },
                    testTagPrefix = "master_gain",
                    quickPresets = listOf(
                        "0.8x (-2dB)" to 0.8f,
                        "1.0x (Unity)" to 1.0f,
                        "1.3x (+2.5dB)" to 1.3f,
                        "1.8x (+5dB)" to 1.8f
                    ),
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
    }

    // Save Preset Dialog
    if (uiState.isSavePresetDialogVisible) {
        SavePresetDialog(
            onDismiss = { viewModel.showSavePresetDialog(false) },
            onSave = { name, emoji, desc ->
                viewModel.saveCurrentPreset(name, emoji, desc)
                scope.launch {
                    snackbarHostState.showSnackbar("Preset '$name' saved successfully!")
                }
            }
        )
    }

    // About / Info Dialog
    if (showInfoDialog) {
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = {
                Text(
                    text = "About VocalMorph DSP",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "VocalMorph is a real-time, low-latency audio DSP voice changer built with Kotlin and Jetpack Compose.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "• Dark Theme: High-contrast cyber palette optimized for low-light environments\n• Foreground Service: Continues voice modulation across apps & locked screen\n• Audio Routing: Bluetooth SCO Headset input, Wired Headset, and Built-in Mic\n• Latency: Under 30ms round-trip\n• Pitch Shifter: Granular Overlap-Add (-12st to +12st)\n• Formant Shifter: Biquad Direct Form II Transposed\n• Ring Modulator: 80Hz - 400Hz carrier wave\n• Echo: 0-500ms delay line with 1-pole damping\n• Noise Gate: Dynamic envelope follower",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showInfoDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Got it", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp)
        )
    }
}

@Composable
private fun MasterPowerButton(
    isProcessing: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_anim")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isProcessing) 1.05f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(22.dp))
            .clickable { onToggle() }
            .testTag("master_power_button"),
        shape = RoundedCornerShape(22.dp),
        color = if (isProcessing) Color(0xFF00363D) else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(
            1.5.dp,
            if (isProcessing) NeonCyan else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        ),
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Glowing Power Icon
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .scale(if (isProcessing) pulseScale else 1f)
                        .clip(CircleShape)
                        .background(
                            if (isProcessing) NeonCyan.copy(alpha = 0.25f)
                            else MaterialTheme.colorScheme.surface
                        )
                        .border(
                            2.dp,
                            if (isProcessing) NeonCyan else Color.Gray.copy(alpha = 0.4f),
                            CircleShape
                        )
                        .then(
                            if (isProcessing) Modifier.shadow(16.dp, CircleShape, spotColor = NeonCyan)
                            else Modifier
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isProcessing) Icons.Default.Mic else Icons.Default.MicOff,
                        contentDescription = "Power",
                        tint = if (isProcessing) NeonCyan else Color.Gray,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column {
                    Text(
                        text = if (isProcessing) "REAL-TIME DSP ACTIVE" else "TAP TO ACTIVATE",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        color = if (isProcessing) NeonCyan else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isProcessing) "Running in Foreground Service • Background Active" else "Low-latency pass-through is disabled",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isProcessing) NeonCyan.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Power icon pill
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (isProcessing) NeonCyan else MaterialTheme.colorScheme.surface)
                    .border(1.dp, if (isProcessing) NeonCyan else Color.Gray, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = null,
                    tint = if (isProcessing) Color.Black else Color.Gray,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
