package code.name.monkey.retromusic.service

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Analyzes the entire audio file to find its absolute peak amplitude.
 * Returns a value between 0.0f and 1.0f representing the peak.
 */
suspend fun analyzeVolumePeak(context: Context, uri: Uri): Float {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(context, uri, null)
    } catch (e: Exception) {
        try { extractor.release() } catch (_: Throwable) {}
        return -1f // Failed to read
    }

    val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
        extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
    } ?: run {
        extractor.release()
        return -1f
    }

    extractor.selectTrack(trackIndex)
    val format: MediaFormat = extractor.getTrackFormat(trackIndex)
    val mime = format.getString(MediaFormat.KEY_MIME) ?: run {
        extractor.release()
        return -1f
    }

    val decoder = MediaCodec.createDecoderByType(mime)
    var maxPeak = 0.0f

    try {
        decoder.configure(format, null, null, 0)
        decoder.start()

        val bufferInfo = MediaCodec.BufferInfo()
        var sawInputEOS = false
        var sawOutputEOS = false

        while (!sawOutputEOS) {
            if (!sawInputEOS) {
                val inIndex = decoder.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val inputBuf = decoder.getInputBuffer(inIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuf, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEOS = true
                    } else {
                        val pts = extractor.sampleTime
                        decoder.queueInputBuffer(inIndex, 0, sampleSize, pts, 0)
                        extractor.advance()
                    }
                }
            }

            val outIndex = decoder.dequeueOutputBuffer(bufferInfo, 10_000)
            if (outIndex >= 0) {
                val outBuf: ByteBuffer = decoder.getOutputBuffer(outIndex)!!
                outBuf.order(ByteOrder.LITTLE_ENDIAN)
                val outBytes = ByteArray(bufferInfo.size)
                outBuf.get(outBytes)
                outBuf.clear()

                // Assume 16-bit PCM output from decoder
                val shortBuf = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val tmp = ShortArray(shortBuf.remaining())
                shortBuf.get(tmp)

                for (sample in tmp) {
                    // Convert 16-bit signed integer to 0.0f - 1.0f range
                    val normalizedSample = abs(sample.toFloat() / 32768f)
                    if (normalizedSample > maxPeak) {
                        maxPeak = normalizedSample
                    }
                }

                decoder.releaseOutputBuffer(outIndex, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    sawOutputEOS = true
                }
            }
        }
        return maxPeak
    } catch (ex: Exception) {
        return -1f
    } finally {
        try { decoder.stop() } catch (_: Throwable) {}
        try { decoder.release() } catch (_: Throwable) {}
        try { extractor.release() } catch (_: Throwable) {}
    }
}
