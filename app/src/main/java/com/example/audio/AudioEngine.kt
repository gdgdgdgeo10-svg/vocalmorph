package com.example.audio

import android.content.Context

/**
 * Singleton holder for the shared low-latency AudioProcessor engine.
 */
object AudioEngine {

    @Volatile
    private var processorInstance: AudioProcessor? = null

    fun getInstance(context: Context): AudioProcessor {
        return processorInstance ?: synchronized(this) {
            processorInstance ?: AudioProcessor(context.applicationContext).also {
                processorInstance = it
            }
        }
    }
}
