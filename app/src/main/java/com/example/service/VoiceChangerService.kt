package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.audio.AudioEngine
import com.example.audio.AudioProcessor

/**
 * Foreground Service that keeps the Real-Time Audio DSP processing engine active
 * when the app is in the background, locked, or user switches to other applications.
 */
class VoiceChangerService : Service() {

    companion object {
        private const val TAG = "VoiceChangerService"
        const val CHANNEL_ID = "vocalmorph_live_dsp_channel"
        const val NOTIFICATION_ID = 9001

        const val ACTION_START = "com.example.action.START_VOICE_DSP"
        const val ACTION_STOP = "com.example.action.STOP_VOICE_DSP"

        fun startService(context: Context) {
            val intent = Intent(context, VoiceChangerService::class.java).apply {
                action = ACTION_START
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start foreground service", e)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, VoiceChangerService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop service", e)
            }
        }
    }

    private lateinit var audioProcessor: AudioProcessor

    override fun onCreate() {
        super.onCreate()
        audioProcessor = AudioEngine.getInstance(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAudioAndService()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                startForegroundDsp()
            }
        }
        return START_STICKY
    }

    private fun startForegroundDsp() {
        val notification = buildForegroundNotification()
        val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }

        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                foregroundServiceType
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error calling startForeground", e)
        }

        try {
            val started = audioProcessor.startRealtimeProcessing()
            if (!started) {
                Log.e(TAG, "Failed to start real-time audio processing")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during startRealtimeProcessing", e)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopAudioAndService() {
        try {
            audioProcessor.stopRealtimeProcessing()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping audio processor", e)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildForegroundNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, VoiceChangerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VocalMorph DSP Active")
            .setContentText("Real-time voice changer running in background")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop DSP",
                stopPendingIntent
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VocalMorph Voice DSP Engine",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows persistent status while real-time voice DSP is running"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            audioProcessor.stopRealtimeProcessing()
        } catch (e: Exception) {
            Log.w(TAG, "Error in service onDestroy", e)
        }
        super.onDestroy()
    }
}
