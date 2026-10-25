// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import io.github.patrick11994.reolinkdoorbell.media.SpsInfo;
import io.github.patrick11994.reolinkdoorbell.media.StreamHub;

/**
 * Hardware H.264 decoding onto a Surface, tuned for delay rather than smoothness: frames go to the decoder as they
 * arrive, only the newest decoded frame is shown, and a backlog is dropped up to the next keyframe.
 */
final class VideoRenderer implements StreamHub.VideoSink {
    interface Listener {
        void onVideoSize(int width, int height);
        /** First frame on screen, and the decoder's average delay from input to output. */
        void onVideoStats(long firstFrameMillis, double decodeMillis);
        void onVideoError(String message);
    }

    private static final class Frame {
        final byte[] data;
        final long micros;
        final boolean keyframe;

        Frame(byte[] data, long micros, boolean keyframe) {
            this.data = data;
            this.micros = micros;
            this.keyframe = keyframe;
        }
    }

    private final Surface surface;
    private final Listener listener;
    private final ArrayBlockingQueue<Frame> queue = new ArrayBlockingQueue<>(60);
    private final long created = System.currentTimeMillis();
    private volatile boolean running = true;
    private volatile boolean waitForKeyframe;
    private final Thread thread;

    VideoRenderer(Surface surface, Listener listener) {
        this.surface = surface;
        this.listener = listener;
        thread = new Thread(this::run, "reolink-video");
        thread.setDaemon(true);
        thread.start();
    }

    @Override public void onVideo(byte[] annexB, long micros, boolean keyframe) {
        if (!running) return;
        if (waitForKeyframe && !keyframe) return;
        waitForKeyframe = false;
        if (!queue.offer(new Frame(annexB, micros, keyframe))) {
            // The decoder fell behind: drop the backlog and restart at the next keyframe instead of lagging.
            queue.clear();
            waitForKeyframe = !keyframe;
            if (keyframe) queue.offer(new Frame(annexB, micros, true));
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
        long[] queuedAt = new long[256];
        long[] queuedMicros = new long[256];
        double decodeAverage = 0;
        boolean shown = false;
        try {
            Frame first = null;
            boolean warned = false;
            while (running && first == null) {
                Frame frame = queue.poll(200, TimeUnit.MILLISECONDS);
                if (frame != null && frame.keyframe) first = frame;
                if (first == null && !warned && System.currentTimeMillis() - created > 6000) {
                    warned = true;
                    listener.onVideoError(null);  // no keyframe yet: the plugin shows its own message
                }
            }
            if (first == null) return;
            SpsInfo size = size(first.data);
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size == null ? 1920 : size.width, size == null ? 1080 : size.height);
            if (Build.VERSION.SDK_INT >= 30) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);  // realtime
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            codec.configure(format, surface, null, 0);
            codec.start();
            if (size != null) listener.onVideoSize(size.width, size.height);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            Frame pending = first;
            int slot = 0;
            while (running) {
                if (pending == null) pending = queue.poll(5, TimeUnit.MILLISECONDS);
                if (pending != null) {
                    int input = codec.dequeueInputBuffer(5_000);
                    if (input >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(input);
                        buffer.clear();
                        if (pending.data.length <= buffer.remaining()) {
                            buffer.put(pending.data);
                            codec.queueInputBuffer(input, 0, pending.data.length, pending.micros, 0);
                            queuedAt[slot % 256] = System.nanoTime();
                            queuedMicros[slot % 256] = pending.micros;
                            slot++;
                        } else {
                            codec.queueInputBuffer(input, 0, 0, pending.micros, 0);
                        }
                        pending = null;
                    }
                }
                // Drain everything decoded; show only the newest picture.
                int newest = -1;
                long newestMicros = 0;
                while (true) {
                    int output = codec.dequeueOutputBuffer(info, 0);
                    if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat out = codec.getOutputFormat();
                        int width = out.getInteger(MediaFormat.KEY_WIDTH), height = out.getInteger(MediaFormat.KEY_HEIGHT);
                        if (out.containsKey("crop-right") && out.containsKey("crop-left")) width = out.getInteger("crop-right") - out.getInteger("crop-left") + 1;
                        if (out.containsKey("crop-bottom") && out.containsKey("crop-top")) height = out.getInteger("crop-bottom") - out.getInteger("crop-top") + 1;
                        listener.onVideoSize(width, height);
                        continue;
                    }
                    if (output < 0) break;
                    if (newest >= 0) codec.releaseOutputBuffer(newest, false);
                    newest = output;
                    newestMicros = info.presentationTimeUs;
                }
                if (newest >= 0) {
                    codec.releaseOutputBuffer(newest, true);
                    for (int i = Math.max(0, slot - 256); i < slot; i++) {
                        if (queuedMicros[i % 256] == newestMicros) {
                            double millis = (System.nanoTime() - queuedAt[i % 256]) / 1e6;
                            decodeAverage = decodeAverage == 0 ? millis : decodeAverage * 0.9 + millis * 0.1;
                            break;
                        }
                    }
                    if (!shown) {
                        shown = true;
                        listener.onVideoStats(System.currentTimeMillis() - created, decodeAverage);
                    } else if (slot % 50 == 0) {
                        listener.onVideoStats(-1, decodeAverage);
                    }
                }
            }
        } catch (InterruptedException stopped) {
            // Released.
        } catch (Exception error) {
            if (running) listener.onVideoError("video decoder: " + error);
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (RuntimeException ignored) {}
                codec.release();
            }
        }
    }

    /** The picture size from the SPS at the start of a keyframe. */
    private static SpsInfo size(byte[] annexB) {
        for (int i = 0; i + 4 < annexB.length; i++) {
            if (annexB[i] == 0 && annexB[i + 1] == 0 && annexB[i + 2] == 0 && annexB[i + 3] == 1 && (annexB[i + 4] & 0x1F) == 7) {
                int end = i + 4;
                while (end + 3 < annexB.length && !(annexB[end] == 0 && annexB[end + 1] == 0 && annexB[end + 2] == 0 && annexB[end + 3] == 1)) end++;
                if (end + 3 >= annexB.length) end = annexB.length;
                byte[] sps = new byte[end - i - 4];
                System.arraycopy(annexB, i + 4, sps, 0, sps.length);
                return SpsInfo.parse(sps);
            }
        }
        return null;
    }
}
