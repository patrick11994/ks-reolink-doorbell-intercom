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
        synchronized (this) {
            if (stopped || fallback != null || primary.firstKeyframeMillis() >= 0) return false;
            fallback = f = fallbackFactory.create();
        }
        primary.stop();  // waits for the primary's thread: not under the lock
        // Meanwhile the call may have ended, or a new viewer attached its sinks: take what is current now. Attaching
        // under the lock keeps a sink attached meanwhile from being overwritten by an older one.
        synchronized (this) {
            if (stopped) return true;  // stop() stopped the fallback too, and a stopped stream does not start again
            if (video != null) f.attachVideo(video);
            if (audio != null) f.attachAudio(audio);
            f.start();
        }
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

    @Override public synchronized void attachVideo(VideoSink sink) {
        video = sink;
        (fallback != null ? fallback : primary).attachVideo(sink);
    }

    @Override public synchronized void attachAudio(AudioSink sink) {
        audio = sink;
        (fallback != null ? fallback : primary).attachAudio(sink);
    }

    @Override public long firstKeyframeMillis() {
        MediaStream current;
        synchronized (this) {
            current = fallback != null ? fallback : primary;
        }
        return current.firstKeyframeMillis();
    }
}
