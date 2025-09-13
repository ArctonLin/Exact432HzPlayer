package code.name.monkey.retromusic.service

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jtransforms.fft.FloatFFT_1D
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

fun floatRange(start: Float, end: Float, step: Float) = generateSequence(start) { previous ->
    val next = previous + step
    if (next <= end) next else null
}

/*
fun analyzeTone(context: Context, uri: Uri): Float{
    // Reference 440Hz note partials (same as your toneFreq)
    val toneFreq = floatArrayOf(
        16.35f, 17.32f, 18.35f, 19.45f, 20.6f, 21.83f, 23.12f, 24.5f,
        25.96f, 27.5f, 29.14f, 30.87f, 32.7f, 34.65f, 36.71f, 38.89f,
        41.2f, 43.65f, 46.25f, 49f, 51.91f, 55f, 58.27f, 61.74f,
        65.41f, 69.3f, 73.42f, 77.78f, 82.41f, 87.31f, 92.5f, 98f,
        103.83f, 110f, 116.54f, 123.47f, 130.81f, 138.59f, 146.83f, 155.56f,
        164.81f, 174.61f, 185f, 196f, 207.65f, 220f, 233.08f, 246.94f,
        261.63f, 277.18f, 293.66f, 311.13f, 329.63f, 349.23f, 369.99f, 392f,
        415.3f, 440f, 466.16f, 493.88f, 523.25f, 554.37f, 587.33f, 622.25f,
        659.25f, 698.46f, 739.99f, 783.99f, 830.61f, 880f, 932.33f, 987.77f,
        1046.5f, 1108.73f, 1174.66f, 1244.51f, 1318.51f, 1396.91f, 1479.98f,
        1567.98f, 1661.22f, 1760f, 1864.66f, 1975.53f, 2093f, 2217.46f,
        2349.32f, 2489.02f, 2637.02f, 2793.83f, 2959.96f, 3135.96f, 3322.44f,
        3520f, 3729.31f, 3951.07f, 4186.01f, 4434.92f, 4698.63f, 4978.03f,
        5274.04f, 5587.65f, 5919.91f, 6271.93f, 6644.88f, 7040f, 7458.62f, 7902.13f
    )

    // --- Setup extractor/decoder ---
    val extractor = MediaExtractor()
    extractor.setDataSource(context, uri, null)
    val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
        extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
    } ?: run {
        extractor.release()
        return 440f
    }
    extractor.selectTrack(trackIndex)
    val format: MediaFormat = extractor.getTrackFormat(trackIndex)
    val mime = format.getString(MediaFormat.KEY_MIME) ?: run {
        extractor.release()
        return 440f
    }
    val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
    val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1

    val decoder = MediaCodec.createDecoderByType(mime)
    try {
        decoder.configure(format, null, null, 0)
        decoder.start()

        val bufferInfo = BufferInfo()
        var sawInputEOS = false
        var sawOutputEOS = false

        // Desired FFT size (power of two). Larger -> better frequency resolution but more memory/CPU.
        val fftSize = 480000 // (10s@48k). Adjust if OOM on low-memory devices.
        val targetSamples = fftSize
        val samples = FloatArray(targetSamples)
        var samplesCollected = 0

        while (!sawOutputEOS && samplesCollected < targetSamples) {
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
                // Convert byte[] -> short[] (16-bit PCM), then to mono floats.
                val shortBuf = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val tmp = ShortArray(shortBuf.remaining())
                shortBuf.get(tmp)

                if (channelCount <= 1) {
                    var i = 0
                    while (i < tmp.size && samplesCollected < targetSamples) {
                        samples[samplesCollected++] = tmp[i].toFloat()
                        i++
                    }
                } else {
                    // interleaved channels -> average to mono
                    var i = 0
                    while (i + channelCount - 1 < tmp.size && samplesCollected < targetSamples) {
                        var sum = 0
                        for (ch in 0 until channelCount) {
                            sum += tmp[i + ch].toInt()
                        }
                        samples[samplesCollected++] = (sum.toFloat() / channelCount.toFloat())
                        i += channelCount
                    }
                }

                decoder.releaseOutputBuffer(outIndex, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    sawOutputEOS = true
                }
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // ignored
            } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // no output yet
            }
        }

        // If we didn't fill enough samples, pad with zeros (already zeroed by FloatArray)
        val actualSamples = samplesCollected.takeIf { it > 0 } ?: return 440f

        // Use only fftSize samples (or highest power of two less or equal to collected)
        val usedFftSize = Integer.highestOneBit(minOf(actualSamples, fftSize))
        if (usedFftSize < 2048) {
            // not enough data
            return 440f
        }

        val fftInput = FloatArray(usedFftSize)
        // copy first usedFftSize samples
        System.arraycopy(samples, 0, fftInput, 0, usedFftSize)

        // apply a Hann window to reduce spectral leakage (helps detection)
        val n = usedFftSize
        for (i in 0 until n) {
            val w = 0.5f * (1f - kotlin.math.cos(2.0 * Math.PI * i / (n - 1)).toFloat())
            fftInput[i] *= w
        }

        // FFT
        val fft = FloatFFT_1D(n.toLong())
        fft.realForward(fftInput) // in-place packed real FFT

        // Build magnitude spectrum (bins 0..n/2)
        val half = n / 2
        val magnitude = FloatArray(half + 1)
        // k = 0
        magnitude[0] = abs(fftInput[0]) / n
        // Nyquist if n even -> stored in fftInput[1]
        magnitude[half] = abs(fftInput[1]) / n
        // other bins k=1..half-1 use packed [2*k] = Re(k), [2*k+1] = Im(k)
        for (k in 1 until half) {
            val re = fftInput[2 * k]
            val im = fftInput[2 * k + 1]
            magnitude[k] = sqrt(re * re + im * im) / n
        }

        val freqStep = sampleRate.toFloat() / n.toFloat()

        // search 424.0 .. 448.0 step 0.1
        var maxSum = 0f
        var bestFreq = 440f
        var f = 424.0f
        while (f <= 448.0f + 1e-6f) {
            var sum = 0f
            for (t in toneFreq) {
                val target = t * f / 440f
                val idx = (target / freqStep).roundToInt()
                if (idx in magnitude.indices) sum += magnitude[idx]
            }
            if (sum > maxSum) {
                maxSum = sum
                bestFreq = f
            }
            f += 0.1f
        }

        // cleanup
        decoder.stop()
        decoder.release()
        extractor.release()

        return bestFreq
    } catch (ex: Exception) {
        try { decoder.release() } catch (_: Throwable) {}
        try { extractor.release() } catch (_: Throwable) {}
        return 440f
    }
}
*/

fun analyzeTone(context: Context, uri: Uri): Float {
    // Reference 440Hz note partials
    val toneFreq = floatArrayOf(
        16.35f,17.32f,18.35f,19.45f,20.6f,21.83f,23.12f,24.5f,
        25.96f,27.5f,29.14f,30.87f,32.7f,34.65f,36.71f,38.89f,
        41.2f,43.65f,46.25f,49f,51.91f,55f,58.27f,61.74f,
        65.41f,69.3f,73.42f,77.78f,82.41f,87.31f,92.5f,98f,
        103.83f,110f,116.54f,123.47f,130.81f,138.59f,146.83f,155.56f,
        164.81f,174.61f,185f,196f,207.65f,220f,233.08f,246.94f,
        261.63f,277.18f,293.66f,311.13f,329.63f,349.23f,369.99f,392f,
        415.3f,440f,466.16f,493.88f,523.25f,554.37f,587.33f,622.25f,
        659.25f,698.46f,739.99f,783.99f,830.61f,880f,932.33f,987.77f,
        1046.5f,1108.73f,1174.66f,1244.51f,1318.51f,1396.91f,1479.98f,
        1567.98f,1661.22f,1760f,1864.66f,1975.53f,2093f,2217.46f,
        2349.32f,2489.02f,2637.02f,2793.83f,2959.96f,3135.96f,3322.44f,
        3520f,3729.31f,3951.07f,4186.01f,4434.92f,4698.63f,4978.03f,
        5274.04f,5587.65f,5919.91f,6271.93f,6644.88f,7040f,7458.62f,7902.13f
    )

    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(context, uri, null)
    } catch (e: Exception) {
        try { extractor.release() } catch (_: Throwable) {}
        return 440f
    }

    val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
        extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
    } ?: run {
        extractor.release()
        return 440f
    }
    extractor.selectTrack(trackIndex)
    val format: MediaFormat = extractor.getTrackFormat(trackIndex)
    val mime = format.getString(MediaFormat.KEY_MIME) ?: run {
        extractor.release()
        return 440f
    }
    val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
    val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
    val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
    val durationSec = durationUs / 1_000_000.0

    // Helper for one-window FFT analysis (1s or more if fallback)
    fun analyzeWindow(startUs: Long?, maxSamples: Int): Float {
        val decoder = MediaCodec.createDecoderByType(mime)
        try {
            decoder.configure(format, null, null, 0)
            decoder.start()
            if (startUs != null) {
                // seek extractor so subsequent readSampleData begins from here
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false
            val samples = FloatArray(maxSamples)
            var samplesCollected = 0

            while (!sawOutputEOS && samplesCollected < maxSamples) {
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
                    val outBuf = decoder.getOutputBuffer(outIndex)!!
                    outBuf.order(ByteOrder.LITTLE_ENDIAN)
                    val outBytes = ByteArray(bufferInfo.size)
                    outBuf.get(outBytes)
                    outBuf.clear()
                    val shortBuf = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val tmp = ShortArray(shortBuf.remaining())
                    shortBuf.get(tmp)

                    if (channelCount <= 1) {
                        var i = 0
                        while (i < tmp.size && samplesCollected < maxSamples) {
                            samples[samplesCollected++] = tmp[i].toFloat()
                            i++
                        }
                    } else {
                        var i = 0
                        while (i + channelCount - 1 < tmp.size && samplesCollected < maxSamples) {
                            var sum = 0
                            for (ch in 0 until channelCount) {
                                sum += tmp[i + ch].toInt()
                            }
                            samples[samplesCollected++] = (sum.toFloat() / channelCount.toFloat())
                            i += channelCount
                        }
                    }

                    decoder.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEOS = true
                    }
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // give decoder a moment
                }
            }

            val actualSamples = samplesCollected.takeIf { it > 0 } ?: return 440f
            val usedFftSize = Integer.highestOneBit(minOf(actualSamples, maxSamples))
            if (usedFftSize < 2048) return 440f

            val fftInput = FloatArray(usedFftSize)
            System.arraycopy(samples, 0, fftInput, 0, usedFftSize)

            // Hann window
            val n = usedFftSize
            for (i in 0 until n) {
                val w = 0.5f * (1f - kotlin.math.cos(2.0 * Math.PI * i / (n - 1)).toFloat())
                fftInput[i] *= w
            }

            val fft = FloatFFT_1D(n.toLong())
            fft.realForward(fftInput)

            val half = n / 2
            val magnitude = FloatArray(half + 1)
            magnitude[0] = abs(fftInput[0]) / n
            magnitude[half] = abs(fftInput[1]) / n
            for (k in 1 until half) {
                val re = fftInput[2 * k]
                val im = fftInput[2 * k + 1]
                magnitude[k] = sqrt(re * re + im * im) / n
            }

            val freqStep = sampleRate.toFloat() / n.toFloat()

            var maxSum = 0f
            var bestFreq = 440f
            var f = 424.0f
            while (f <= 448.0f + 1e-6f) {
                var sum = 0f
                for (t in toneFreq) {
                    val target = t * f / 440f
                    val idx = (target / freqStep).roundToInt()
                    if (idx in magnitude.indices) sum += magnitude[idx]
                }
                if (sum > maxSum) {
                    maxSum = sum
                    bestFreq = f
                }
                f += 0.1f
            }

            return bestFreq
        } finally {
            try { decoder.stop() } catch (_: Throwable) {}
            try { decoder.release() } catch (_: Throwable) {}
        }
    }

    // --- main logic ---
    return try {
        if (durationSec < 5.0) {
            // Original: decode long buffer (10s or until EOS)
            analyzeWindow(null, 480_000)
        } else {
            // 5 point sampling, each 1 second
            val windowSamples = sampleRate * 1
            // Fractional positions (16.6%, 33.3%, 50%, 66.6%, 83.2%)
            val positions = listOf(0.166, 0.333, 0.5, 0.666, 0.832)

            val freqs = positions.map { p ->
                // clamp start to ensure at least 1s remains
                val rawStartUs = (durationUs * p).toLong()
                val maxStartUs = maxOf(0L, durationUs - 1_000_000L)
                val startUs = minOf(rawStartUs, maxStartUs)
                analyzeWindow(startUs, windowSamples)
            }
            freqs.average().toFloat()
        }
    } catch (ex: Exception) {
        440f
    } finally {
        try { extractor.release() } catch (_: Throwable) {}
    }
}
