// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import io.github.patrick11994.reolinkdoorbell.media.AvcConfig;
import io.github.patrick11994.reolinkdoorbell.media.HevcConfig;
import io.github.patrick11994.reolinkdoorbell.media.MediaStream;
import io.github.patrick11994.reolinkdoorbell.media.SpsInfo;

/**
 * Hardware H.264 decoding onto a Surface, with little delay: frames go to the decoder as they arrive, with an SPS that
 * rules out reordering so the decoder holds none back (SpsInfo.lowDelay), and each decoded
 * picture is handed to the display with the time to show it, at the doorbell's own pace (VideoPacer). A picture that
 * misses its time is shown at once, or dropped when a newer one is ready too. A backlog is dropped up to the next
 * keyframe: when the queue is full, and once the picture shows, when its oldest frame waited longer than
 * MAX_BEHIND_MILLIS. A decoder slower than the stream would otherwise let the picture fall seconds behind the sound,
 * without any error. When it ends, the call's video is summed up in the log.
 */
final class VideoRenderer implements MediaStream.VideoSink {
    interface Listener {
        void onVideoSize(int width, int height);
        /** First frame on screen, and the decoder's average delay from input to output. */
        void onVideoStats(long firstFrameMillis, double decodeMillis);
        void onVideoError(String message);
        /** The decoder's setup, for the log: which decoder, the stream's profile, level and size. */
        void onVideoLog(String message);
    }

    private static final class Frame {
        final byte[] data;
        final long micros;
        final boolean keyframe;
        final long arrived = System.nanoTime() / 1_000_000;

        Frame(byte[] data, long micros, boolean keyframe) {
            this.data = data;
            this.micros = micros;
            this.keyframe = keyframe;
        }
    }

    private final Surface surface;
    private final Listener listener;
    private static final int MAX_BEHIND_MILLIS = 400;
    // Large enough for the keyframe group the stream replays when the renderer is attached, before the decoder runs.
    private final ArrayBlockingQueue<Frame> queue = new ArrayBlockingQueue<>(60);
    private final long created = System.nanoTime() / 1_000_000;
    private volatile boolean running = true;
    private volatile boolean waitForKeyframe;
    private volatile boolean showing;  // after the first picture: from then on the queue is bounded by age as well
    private volatile int dropped;  // backlogs dropped, for the log; counted on the stream's thread
    private volatile long received, lastArrived = -1, largestGap;  // frames from the stream and the longest wait between two, for the log
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
        Frame frame = new Frame(annexB, micros, keyframe);
        received++;
        if (lastArrived >= 0) largestGap = Math.max(largestGap, frame.arrived - lastArrived);
        lastArrived = frame.arrived;
        Frame oldest = showing ? queue.peek() : null;
        if (oldest != null && frame.arrived - oldest.arrived > MAX_BEHIND_MILLIS) {
            dropBacklog(frame, "waited " + (frame.arrived - oldest.arrived) + " ms");
        } else if (!queue.offer(frame)) {
            dropBacklog(frame, "filled the queue");
        }
    }

    /**
     * For the Check button: whether the decoder a call creates can show a picture of this size, or null when it does
     * not say. A decoder that cannot fails at configure, as the Echo Show 8's does with the main stream.
     */
    static Boolean canPlay(String mime, int width, int height) {
        MediaCodec codec = null;
        try {
            codec = MediaCodec.createDecoderByType(mime);
            MediaCodecInfo.VideoCapabilities video = codec.getCodecInfo().getCapabilitiesForType(mime).getVideoCapabilities();
            return video == null ? null : video.isSizeSupported(width, height);
        } catch (java.io.IOException | RuntimeException unknown) {
            return null;
        } finally {
            if (codec != null) codec.release();
        }
    }

    /**
     * For the Check button, when the decoder does not say: sets up the decoder a call creates, the same way, and decodes
     * one keyframe into memory instead of onto the screen. True when a picture came out, false when the decoder refused
     * it, null when the test itself gave no clear answer.
     */
    static Boolean decodes(byte[] keyframe) {
        MediaFormat format;
        byte[] data = keyframe;
        String mime;
        if (HevcConfig.is(keyframe)) {
            HevcConfig config = HevcConfig.of(keyframe);
            if (config.size == null) return null;
            mime = MediaFormat.MIMETYPE_VIDEO_HEVC;
            format = MediaFormat.createVideoFormat(mime, config.size.width, config.size.height);
            if (config.complete()) format.setByteBuffer("csd-0", ByteBuffer.wrap(config.csd()));
        } else {
            AvcConfig config = AvcConfig.of(keyframe);
            if (config.size == null) return null;
            mime = MediaFormat.MIMETYPE_VIDEO_AVC;
            format = MediaFormat.createVideoFormat(mime, config.size.width, config.size.height);
            byte[] sps = config.sps == null ? null : SpsInfo.lowDelay(config.sps);
            if (config.complete()) {
                format.setByteBuffer("csd-0", ByteBuffer.wrap(AvcConfig.withStartCode(sps != null ? sps : config.sps)));
                format.setByteBuffer("csd-1", ByteBuffer.wrap(AvcConfig.withStartCode(config.pps)));
            }
            if (sps != null) data = AvcConfig.withSps(keyframe, sps);
        }
        MediaCodec codec = null;
        try {
            try {
                codec = MediaCodec.createDecoderByType(mime);
            } catch (java.io.IOException | RuntimeException none) {
                return null;  // no decoder to ask: a call says what goes wrong
            }
            try {
                codec.configure(format, null, null, 0);
                codec.start();
            } catch (RuntimeException refused) {
                return false;  // as a call's decoder fails on a picture too large for it
            }
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean queued = false, ended = false;
            long until = System.nanoTime() + 2_000_000_000L;
            while (System.nanoTime() < until) {
                if (!ended) {
                    int input = codec.dequeueInputBuffer(10_000);
                    if (input >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(input);
                        buffer.clear();
                        if (!queued && data.length <= buffer.remaining()) {
                            buffer.put(data);
                            codec.queueInputBuffer(input, 0, data.length, 0, 0);
                            queued = true;
                        } else {
                            // The end of the stream makes the decoder let go of what it holds.
                            codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            ended = true;
                            if (!queued) return null;  // the picture does not fit an input buffer: not this test's question
                        }
                    }
                }
                int output = codec.dequeueOutputBuffer(info, 10_000);
                if (output >= 0) {
                    boolean picture = info.size > 0 || (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0;
                    codec.releaseOutputBuffer(output, false);
                    if (picture) return true;
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return null;  // ended without a picture
                }
            }
            return null;
        } catch (RuntimeException failed) {
            return false;  // the decoder failed on the picture
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (RuntimeException ignored) {}
                codec.release();
            }
        }
    }

    /** The decoder fell behind: drop the backlog and restart at the next keyframe instead of lagging. */
    private void dropBacklog(Frame frame, String why) {
        int frames = queue.size();
        queue.clear();
        waitForKeyframe = !frame.keyframe;
        if (frame.keyframe) queue.offer(frame);
        if (dropped++ % 10 == 0) listener.onVideoLog("video decoder: behind, " + frames + " frames " + why + ", dropped up to the next keyframe (" + dropped + " times this call)");
    }

    /** Median, 90th percentile and maximum of whole milliseconds, in 5 ms steps up to 2 s, for the log. */
    private static final class Spread {
        private final int[] counts = new int[401];
        private int total;

        void add(long millis) {
            counts[(int) Math.max(0, Math.min(400, millis / 5))]++;
            total++;
        }

        private long at(double share) {
            int seen = 0;
            for (int i = 0; i < counts.length; i++) {
                seen += counts[i];
                if (seen > share * total) return i * 5L;
            }
            return 2000;
        }

        @Override public String toString() {
            if (total == 0) return "-";
            int highest = counts.length - 1;
            while (counts[highest] == 0) highest--;
            return at(0.5) + "/" + at(0.9) + "/" + highest * 5;
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
        boolean[] queuedKey = new boolean[256];
        Spread keyDecode = new Spread(), frameDecode = new Spread(), behind = new Spread();  // for the log
        double decodeAverage = 0;
        boolean shown = false;
        VideoPacer pacer = new VideoPacer();
        long superseded = 0;  // late pictures not shown because a newer one was ready
        String stage = "create";  // where a decoder error happened, for the log
        try {
            Frame first = null;
            boolean warned = false;
            while (running && first == null) {
                Frame frame = queue.poll(200, TimeUnit.MILLISECONDS);
                if (frame != null && frame.keyframe) first = frame;
                if (first == null && !warned && System.nanoTime() / 1_000_000 - created > 6000) {
                    warned = true;
                    listener.onVideoError(null);  // no keyframe yet: the plugin shows its own message
                }
            }
            if (first == null) return;
            // H.264, or H.265 as the Video Doorbell WiFi Gen 2 sends its main stream (#17).
            boolean hevc = HevcConfig.is(first.data);
            String mime = hevc ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
            AvcConfig config = hevc ? null : AvcConfig.of(first.data);
            HevcConfig hevcConfig = hevc ? HevcConfig.of(first.data) : null;
            SpsInfo size = hevc ? hevcConfig.size : config.size;
            boolean complete = hevc ? hevcConfig.complete() : config.complete();
            MediaFormat format = MediaFormat.createVideoFormat(mime, size == null ? 1920 : size.width, size == null ? 1080 : size.height);
            // The doorbell's SPS has no VUI, so a decoder has to allow for reordering and may hold back pictures: the
            // panel's held each keyframe 450 ms. Its frames come in filming order, so the SPS can say there is none.
            // Every keyframe's SPS is replaced the same way. Only for H.264: an H.265 SPS always says how much it reorders.
            byte[] spsIn = hevc ? null : config.sps, spsOut = spsIn == null ? null : SpsInfo.lowDelay(spsIn);
            // The parameter sets up front as well as in the stream: older decoders, such as MediaTek's, want them there.
            if (hevc && complete) {
                format.setByteBuffer("csd-0", ByteBuffer.wrap(hevcConfig.csd()));
            } else if (complete) {
                format.setByteBuffer("csd-0", ByteBuffer.wrap(AvcConfig.withStartCode(spsOut != null ? spsOut : config.sps)));
                format.setByteBuffer("csd-1", ByteBuffer.wrap(AvcConfig.withStartCode(config.pps)));
            }
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);  // realtime
            codec = MediaCodec.createDecoderByType(mime);
            // Low latency only where the decoder offers it: some refuse a format that asks for what they lack.
            boolean lowLatency = false;
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    lowLatency = codec.getCodecInfo().getCapabilitiesForType(mime)
                        .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency);
                } catch (RuntimeException unknown) {
                    // Treated as not offered.
                }
                if (lowLatency) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            }
            listener.onVideoLog("video decoder: " + codec.getName() + (Build.VERSION.SDK_INT >= 29 && !codec.getCodecInfo().isHardwareAccelerated() ? " (software)" : "")
                + ", " + (hevc ? hevcConfig.describe() : config.describe()) + ", parameter sets " + (complete ? "up front" : "missing from the keyframe")
                + (spsOut != null ? ", no reordering added to the SPS" : "")
                + ", low latency " + (lowLatency ? "on" : "not offered"));
            stage = "configure";
            codec.configure(format, surface, null, 0);
            stage = "start";
            codec.start();
            stage = "decode";
            if (size != null) listener.onVideoSize(size.width, size.height);
            boolean warnedTooLarge = false;
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
                        byte[] data = pending.data;
                        if (pending.keyframe && spsOut != null) {
                            byte[] sps = AvcConfig.of(data).sps;
                            if (sps != null && !java.util.Arrays.equals(sps, spsIn)) {
                                spsIn = sps;  // the doorbell changed its stream: the SPS anew
                                spsOut = SpsInfo.lowDelay(sps);
                            }
                            if (sps != null && spsOut != null) data = AvcConfig.withSps(data, spsOut);
                        }
                        if (data.length <= buffer.remaining()) {
                            buffer.put(data);
                            codec.queueInputBuffer(input, 0, data.length, pending.micros, 0);
                            queuedAt[slot % 256] = System.nanoTime();
                            queuedMicros[slot % 256] = pending.micros;
                            queuedKey[slot % 256] = pending.keyframe;
                            slot++;
                        } else {
                            if (!warnedTooLarge) {
                                warnedTooLarge = true;
                                listener.onVideoLog("video decoder: a frame of " + data.length + " bytes does not fit its " + buffer.remaining() + "-byte input buffer, dropped");
                            }
                            codec.queueInputBuffer(input, 0, 0, pending.micros, 0);
                        }
                        pending = null;
                    }
                }
                // Hand everything decoded to the display, each picture with its time; of late ones only the newest.
                long now = System.nanoTime();
                int late = -1, released = 0;
                long releasedMicros = 0;
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
                    long at = pacer.displayAt(info.presentationTimeUs, now / 1000) * 1000;
                    behind.add(pacer.behindMillis());
                    for (int i = slot - 1; i >= Math.max(0, slot - 256); i--) {
                        if (queuedMicros[i % 256] == info.presentationTimeUs) {
                            (queuedKey[i % 256] ? keyDecode : frameDecode).add((now - queuedAt[i % 256]) / 1_000_000);
                            break;
                        }
                    }
                    if (late >= 0) {
                        boolean newerLate = at <= now;
                        codec.releaseOutputBuffer(late, !newerLate);
                        if (newerLate) superseded++;
                        late = -1;
                    }
                    if (at <= now) {
                        late = output;
                    } else {
                        codec.releaseOutputBuffer(output, at);
                    }
                    released++;
                    releasedMicros = info.presentationTimeUs;
                }
                if (late >= 0) codec.releaseOutputBuffer(late, true);
                if (released > 0) {
                    for (int i = Math.max(0, slot - 256); i < slot; i++) {
                        if (queuedMicros[i % 256] == releasedMicros) {
                            double millis = (System.nanoTime() - queuedAt[i % 256]) / 1e6;
                            decodeAverage = decodeAverage == 0 ? millis : decodeAverage * 0.9 + millis * 0.1;
                            break;
                        }
                    }
                    if (!shown) {
                        shown = true;
                        showing = true;
                        listener.onVideoStats(System.nanoTime() / 1_000_000 - created, decodeAverage);
                    } else if (slot % 50 == 0) {
                        listener.onVideoStats(-1, decodeAverage);
                    }
                }
            }
        } catch (InterruptedException stopped) {
            // Released.
        } catch (MediaCodec.CodecException error) {
            if (!running) return;
            listener.onVideoLog("video decoder: failed at " + stage + ", error code " + error.getErrorCode()
                + (error.isTransient() ? ", transient" : "") + (error.isRecoverable() ? ", recoverable" : "") + ", " + error.getDiagnosticInfo());
            listener.onVideoError("video decoder: " + error);
        } catch (Exception error) {
            if (running) listener.onVideoError("video decoder: " + error + " at " + stage);
        } finally {
            if (shown) {
                listener.onVideoLog("video: " + received + " frames, " + pacer.onTime() + " shown at their pace, " + pacer.late() + " late"
                    + (superseded > 0 ? " (" + superseded + " of them skipped for a newer one)" : "") + ", longest wait between frames " + largestGap
                    + " ms, cushion " + pacer.cushionMillis() + " ms, decoder " + Math.round(decodeAverage) + " ms on average"
                    + (dropped > 0 ? ", backlog dropped " + dropped + " times" : ""));
                listener.onVideoLog("video: decoder time for keyframes " + keyDecode + ", other frames " + frameDecode
                    + "; ready later than the earliest by " + behind + " (median, 90%, max in ms)");
            }
            if (codec != null) {
                try { codec.stop(); } catch (RuntimeException ignored) {}
                codec.release();
            }
        }
    }
}
