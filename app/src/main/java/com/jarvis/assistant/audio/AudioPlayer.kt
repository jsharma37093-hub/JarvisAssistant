package com.jarvis.assistant.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AudioPlayer(
    private val onPlaybackStateChanged: (isPlaying: Boolean) -> Unit,
    private val onPlaybackAmplitude: (amplitude: Float) -> Unit
) {
    companion object {
        private const val TAG = "AudioPlayer"
        const val SAMPLE_RATE = 24000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        private const val BYTES_PER_MS = SAMPLE_RATE * 2 / 1000   // 48 bytes per ms

        // AudioTrack buffer: big enough to survive network jitter (was ~85 ms -> underruns = cut audio)
        private const val BUFFER_MS = 300

        // Collect this much audio before starting each reply, so the first words don't stutter
        private const val PREBUFFER_MS = 120
        private const val PREBUFFER_MAX_WAIT_MS = 250L

        // Only report "finished speaking" after this long with no new audio
        // (Gemini sends audio in bursts; the old 100 ms limit fired in the middle of sentences)
        private const val IDLE_AFTER_MS = 500L
    }

    private var audioTrack: AudioTrack? = null
    private val audioQueue = LinkedBlockingQueue<ByteArray>()
    private val isRunning = AtomicBoolean(false)
    private val isPlayingState = AtomicBoolean(false)
    private val resetRequested = AtomicBoolean(false)
    private var playbackThread: Thread? = null

    init {
        initAudioTrack()
    }

    private fun initAudioTrack() {
        val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBufferSize * 2, BUFFER_MS * BYTES_PER_MS)

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_CONFIG)
            .setEncoding(AUDIO_FORMAT)
            .build()

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            // LOW_LATENCY forces tiny internal buffers -> crackling/cuts on streamed speech
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
            .build()
    }

    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)

        try {
            audioTrack?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting AudioTrack playback: ${e.message}")
        }

        playbackThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            val pending = ByteArrayOutputStream()
            var prebuffering = true
            var prebufferStart = 0L
            var lastWriteAt = 0L

            while (isRunning.get()) {
                try {
                    if (resetRequested.getAndSet(false)) {
                        pending.reset()
                        prebuffering = true
                    }

                    val chunk = audioQueue.poll(20, TimeUnit.MILLISECONDS)
                    val now = SystemClock.elapsedRealtime()

                    if (chunk != null && chunk.isNotEmpty()) {
                        if (prebuffering) {
                            if (pending.size() == 0) prebufferStart = now
                            pending.write(chunk)
                            if (pending.size() >= PREBUFFER_MS * BYTES_PER_MS ||
                                now - prebufferStart >= PREBUFFER_MAX_WAIT_MS
                            ) {
                                writeToTrack(pending.toByteArray())
                                pending.reset()
                                prebuffering = false
                                lastWriteAt = SystemClock.elapsedRealtime()
                            }
                        } else {
                            writeToTrack(chunk)
                            lastWriteAt = SystemClock.elapsedRealtime()
                        }
                    } else if (prebuffering) {
                        // Reply was short / stream paused: don't hold audio back forever
                        if (pending.size() > 0 && now - prebufferStart >= PREBUFFER_MAX_WAIT_MS) {
                            writeToTrack(pending.toByteArray())
                            pending.reset()
                            prebuffering = false
                            lastWriteAt = SystemClock.elapsedRealtime()
                        }
                    } else if (now - lastWriteAt >= IDLE_AFTER_MS) {
                        // Nothing new for a while -> reply finished
                        prebuffering = true
                        markIdle()
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in playback worker: ${e.message}")
                }
            }
        }.apply {
            start()
        }
    }

    private fun writeToTrack(data: ByteArray) {
        if (data.isEmpty()) return
        if (!isPlayingState.getAndSet(true)) {
            onPlaybackStateChanged(true)
        }
        onPlaybackAmplitude(AudioUtils.calculateRmsNormalized(data, data.size))

        val written = audioTrack?.write(data, 0, data.size) ?: return
        if (written < 0) {
            Log.e(TAG, "AudioTrack.write error code: $written")
        }
    }

    private fun markIdle() {
        if (isPlayingState.getAndSet(false)) {
            onPlaybackStateChanged(false)
            onPlaybackAmplitude(0f)
        }
    }

    fun enqueueAudio(pcmData: ByteArray) {
        if (pcmData.isNotEmpty()) {
            audioQueue.offer(pcmData)
        }
    }

    /**
     * Instantly flushes audio playback upon interruption.
     */
    fun flush() {
        audioQueue.clear()
        resetRequested.set(true)
        try {
            audioTrack?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.pause()
                    it.flush()
                    it.play()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error flushing AudioTrack: ${e.message}")
        }
        markIdle()
    }

    fun isPlaying(): Boolean = isPlayingState.get()

    fun release() {
        isRunning.set(false)
        playbackThread?.interrupt()
        playbackThread = null
        audioQueue.clear()

        try {
            audioTrack?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioTrack: ${e.message}")
        } finally {
            audioTrack = null
        }
    }
}
