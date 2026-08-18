package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.audio.AudioProcessor
import com.example.ui.VoiceChangerViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("VocalMorph", appName)
    }

    @Test
    fun `test AudioProcessor initializes and handles bluetooth preference`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val processor = AudioProcessor(context)
        assertNotNull(processor)
        assertTrue(processor.sampleRate > 0)
        assertTrue(processor.preferBluetoothMic.value)

        processor.setPreferBluetoothMic(false)
        assertEquals(false, processor.preferBluetoothMic.value)

        processor.setPreferBluetoothMic(true)
        assertEquals(true, processor.preferBluetoothMic.value)

        processor.release()
    }
}
