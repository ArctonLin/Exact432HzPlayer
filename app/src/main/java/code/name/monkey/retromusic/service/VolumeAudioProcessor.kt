package code.name.monkey.retromusic.service

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

class VolumeAudioProcessor : BaseAudioProcessor() {
    var volume: Float = 1.0f

    @Throws(AudioProcessor.UnhandledAudioFormatException::class)
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) {
            return
        }

        val buffer = replaceOutputBuffer(size)
        val limit = inputBuffer.limit()

        // Apply volume gain to each 16-bit PCM sample
        while (inputBuffer.position() < limit) {
            val sample = inputBuffer.short
            var scaled = (sample * volume).toInt()
            
            // Clamp to avoid integer overflow / clipping distortion wrapping
            scaled = max(Short.MIN_VALUE.toInt(), min(Short.MAX_VALUE.toInt(), scaled))
            
            buffer.putShort(scaled.toShort())
        }

        inputBuffer.position(limit)
        buffer.flip()
    }
}
