package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonMagenta
import com.example.ui.theme.NeonRed
import kotlin.math.sin

@Composable
fun WaveformVisualizer(
    waveform: FloatArray,
    inputDb: Float,
    outputDb: Float,
    isGateOpen: Boolean,
    isProcessing: Boolean,
    latencyMs: Int,
    sampleRate: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, if (isProcessing) NeonCyan.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(20.dp))
            .testTag("waveform_visualizer_container"),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 6.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header stats & status indicators
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Live Status badge
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val statusDotColor by animateColorAsState(
                        targetValue = if (isProcessing) NeonGreen else Color.Gray,
                        animationSpec = tween(300),
                        label = "status_dot"
                    )
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(statusDotColor)
                            .shadow(if (isProcessing) 8.dp else 0.dp, CircleShape, spotColor = NeonGreen)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isProcessing) "REAL-TIME DSP ACTIVE" else "DSP STANDBY",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        color = if (isProcessing) NeonCyan else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Latency & Sample Rate pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = "Latency",
                        tint = NeonAmber,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${latencyMs}ms | ${sampleRate / 1000}kHz",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Oscilloscope Waveform Canvas
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF070A0F))
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 4.dp)
                ) {
                    val width = size.width
                    val height = size.height
                    val centerY = height / 2f

                    // Draw grid lines
                    val gridColor = Color(0xFF162032)
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, centerY),
                        end = Offset(width, centerY),
                        strokeWidth = 1f
                    )
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, centerY - height * 0.3f),
                        end = Offset(width, centerY - height * 0.3f),
                        strokeWidth = 1f
                    )
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, centerY + height * 0.3f),
                        end = Offset(width, centerY + height * 0.3f),
                        strokeWidth = 1f
                    )

                    // Draw vertical time marker divisions
                    for (k in 1..7) {
                        val x = width * (k / 8f)
                        drawLine(
                            color = gridColor.copy(alpha = 0.5f),
                            start = Offset(x, 0f),
                            end = Offset(x, height),
                            strokeWidth = 1f
                        )
                    }

                    val pointsCount = waveform.size
                    if (pointsCount > 1) {
                        val path = Path()
                        val glowPath = Path()

                        val stepX = width / (pointsCount - 1).toFloat()

                        for (i in 0 until pointsCount) {
                            val amplitude = if (isProcessing) waveform[i] else 0f
                            // Scale amplitude with slight compression
                            val y = centerY - (amplitude * (height * 0.42f)).coerceIn(-height * 0.45f, height * 0.45f)
                            val x = i * stepX

                            if (i == 0) {
                                path.moveTo(x, y)
                                glowPath.moveTo(x, y)
                            } else {
                                path.lineTo(x, y)
                                glowPath.lineTo(x, y)
                            }
                        }

                        // Draw outer neon glow
                        drawPath(
                            path = glowPath,
                            brush = Brush.horizontalGradient(
                                listOf(NeonCyan.copy(alpha = 0.3f), NeonMagenta.copy(alpha = 0.3f))
                            ),
                            style = Stroke(
                                width = 6.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )

                        // Draw crisp neon wave line
                        drawPath(
                            path = path,
                            brush = Brush.horizontalGradient(
                                listOf(NeonCyan, NeonMagenta, NeonAmber)
                            ),
                            style = Stroke(
                                width = 2.5.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                    }
                }

                // Gate status badge
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF0F172A).copy(alpha = 0.85f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (isGateOpen && isProcessing) NeonGreen else Color.Gray)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isGateOpen && isProcessing) "GATE OPEN" else "GATE CLOSED",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        ),
                        color = if (isGateOpen && isProcessing) NeonGreen else Color.Gray
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // VU Meter bars for Input & Output Levels
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Input Meter
                LevelMeterBar(
                    label = "MIC IN",
                    levelDb = inputDb,
                    isLive = isProcessing,
                    modifier = Modifier.weight(1f)
                )

                // Output Meter
                LevelMeterBar(
                    label = "DSP OUT",
                    levelDb = outputDb,
                    isLive = isProcessing,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun LevelMeterBar(
    label: String,
    levelDb: Float,
    isLive: Boolean,
    modifier: Modifier = Modifier
) {
    val normalized = if (isLive) {
        ((levelDb + 60f) / 60f).coerceIn(0f, 1f)
    } else 0f

    val animatedFraction by animateFloatAsState(
        targetValue = normalized,
        animationSpec = tween(durationMillis = 80, easing = FastOutSlowInEasing),
        label = "level_meter_anim"
    )

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (isLive) "${levelDb.toInt()} dB" else "-∞ dB",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                color = if (levelDb > -6f) NeonRed else MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF0F172A))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animatedFraction)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        brush = Brush.horizontalGradient(
                            listOf(
                                NeonGreen,
                                NeonCyan,
                                NeonAmber,
                                NeonRed
                            )
                        )
                    )
            )
        }
    }
}
