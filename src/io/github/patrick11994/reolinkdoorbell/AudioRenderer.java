// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import io.github.patrick11994.reolinkdoorbell.media.StreamHub;

/**
 * The doorbell's microphone on the panel speaker: AAC decoded and played as voice-call audio, which on this panel
 * gets the vendor's echo suppression (media audio does not). A small jitter buffer smooths the doorbell's 64 ms
 * packets: playback starts with START_MILLIS of silence as a cushion, and audio is only skipped when more than
 * MAX_MILLIS piles up, so the delay stays bounded without gaps from ordinary network jitter.
 */
final class AudioRenderer implements StreamHub.AudioSink {
    interface Listener {
        void onAudioError(String message);
    }

    private static final int START_MILLIS = 120;  // the cushion of silence playback starts with
    private static final int MAX_MILLIS = 300;  // more than this waiting: skip audio to catch up
    private static final int CAPACITY_MILLIS = MAX_MILLIS;

    private final Listener listener;
    private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(16);
    private volatile boolean running = true;
    private volatile byte[] config;
    private volatile int sampleRate;
    private final Thread thread;

    AudioRenderer(Listener listener) {
        this.listener = listener;
        thread = new Thread(this::run, "reolink-audio");
        thread.setDaemon(true);
        thread.start();
    }

    @Override public void onAudioFormat(byte[] config, int sampleRate) {
        this.config = config;
        this.sampleRate = sampleRate;
    }

    @Override public void onAudio(byte[] aacFrame, long micros) {
        if (!running) return;
        if (!queue.offer(aacFrame)) {
            queue.clear();  // behind: skip ahead rather than delay
            queue.offer(aacFrame);
        }
    }

    void release() {
        running = false;
        thread.interrupt();
        try {
            thread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        MediaCodec codec = null;
        AudioTrack track = null;
        try {
            while (running && sampleRate == 0) Thread.sleep(20);
            if (!running) return;
            int rate = sampleRate;
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1);
            format.setInteger(MediaFormat.KEY_IS_ADTS, 0);
            if (config != null && config.length > 0) format.setByteBuffer("csd-0", ByteBuffer.wrap(config));
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            codec.configure(format, null, null, 0);
            codec.start();
            int minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(Math.max(minimum, rate * 2 * CAPACITY_MILLIS / 1000))
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
            // Android 12+ only starts a streaming track once its buffer is full; ours never fills that far on purpose.
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                try {
                    track.setStartThresholdInFrames(Math.min(rate * START_MILLIS / 1000, track.getBufferSizeInFrames()));
                } catch (RuntimeException refused) {
                    // Keeps the default threshold.
                }
            }
            // Start at once with START_MILLIS of silence as the jitter cushion, so playback never waits on the network.
            track.play();
            byte[] cushion = new byte[rate * 2 * START_MILLIS / 1000];
            track.write(cushion, 0, cushion.length);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            byte[] pcm = new byte[8192];
            long frames = 0, written = cushion.length / 2;  // samples handed to the track
            while (running) {
                byte[] frame = queue.poll(20, TimeUnit.MILLISECONDS);
                if (frame != null) {
                    int input = codec.dequeueInputBuffer(10_000);
                    if (input >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(input);
                        buffer.clear();
                        buffer.put(frame);
                        codec.queueInputBuffer(input, 0, frame.length, frames++ * 1024L * 1_000_000L / rate, 0);
                    }
                }
                for (int output = codec.dequeueOutputBuffer(info, 0); output >= 0 || output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED; output = codec.dequeueOutputBuffer(info, 0)) {
                    if (output < 0) continue;
                    ByteBuffer out = codec.getOutputBuffer(output);
                    int size = Math.min(info.size, pcm.length);
                    out.position(info.offset);
                    out.get(pcm, 0, size);
                    codec.releaseOutputBuffer(output, false);
                    long buffered = written - (track.getPlaybackHeadPosition() & 0xFFFFFFFFL);
                    if (buffered * 1000 / rate > MAX_MILLIS) continue;  // too far behind: skip this piece
                    if (buffered <= 0) {
                        // Ran dry: rebuild the cushion so the next network hiccup does not cut the sound again.
                        track.write(cushion, 0, cushion.length);
                        written += cushion.length / 2;
                    }
                    track.write(pcm, 0, size);
                    written += size / 2;
                }
            }
        } catch (InterruptedException stopped) {
            // Released.
        } catch (Exception error) {
            if (running) listener.onAudioError("audio: " + error);
        } finally {
            if (track != null) {
                try { track.stop(); } catch (RuntimeException ignored) {}
                track.release();
            }
            if (codec != null) {
                try { codec.stop(); } catch (RuntimeException ignored) {}
                codec.release();
            }
        }
    }
}
