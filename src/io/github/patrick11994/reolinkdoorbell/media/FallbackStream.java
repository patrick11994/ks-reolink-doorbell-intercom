// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell.media;

/**
 * Streams over the primary (Baichuan, a fresh keyframe sooner and every frame a little earlier), and switches to the
 * fallback (RTSP) when the primary fails before its first keyframe. Sinks attached meanwhile move along. A stream that
 * ends after it has shown a picture is reported as usual, the same as RTSP.
 */
public final class FallbackStream implements MediaStream {
    public interface Factory {
        MediaStream create();
    }

    private final MediaStream primary;
    private final Factory fallbackFactory;
    private MediaStream fallback;
    private VideoSink video;
    private AudioSink audio;
    private boolean stopped;

    public FallbackStream(MediaStream primary, Factory fallbackFactory) {
        this.primary = primary;
        this.fallbackFactory = fallbackFactory;
    }

    @Override public void start() {
        primary.start();
    }

    /**
     * The primary failed: carry on over the fallback if it never showed a picture. Returns whether it switched; call it
     * from any thread but the primary's own (stopping waits for that thread).
     */
    public boolean primaryFailed() {
        MediaStream f;
        VideoSink v;
        AudioSink a;
        synchronized (this) {
            if (stopped || fallback != null || primary.firstKeyframeMillis() >= 0) return false;
            fallback = f = fallbackFactory.create();
            v = video;
            a = audio;
        }
        primary.stop();
        if (v != null) f.attachVideo(v);
        if (a != null) f.attachAudio(a);
        f.start();
        return true;
    }

    public synchronized boolean usingFallback() {
        return fallback != null;
    }

    @Override public void stop() {
        MediaStream f;
        synchronized (this) {
            stopped = true;
            f = fallback;
        }
        primary.stop();
        if (f != null) f.stop();
    }

    @Override public void attachVideo(VideoSink sink) {
        MediaStream current;
        synchronized (this) {
            video = sink;
            current = fallback != null ? fallback : primary;
        }
        current.attachVideo(sink);
    }

    @Override public void attachAudio(AudioSink sink) {
        MediaStream current;
        synchronized (this) {
            audio = sink;
            current = fallback != null ? fallback : primary;
        }
        current.attachAudio(sink);
    }

    @Override public long firstKeyframeMillis() {
        MediaStream current;
        synchronized (this) {
            current = fallback != null ? fallback : primary;
        }
        return current.firstKeyframeMillis();
    }
}
