// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell.talk;

/**
 * Talks over the primary channel (Baichuan, faster and clearer), and switches to the fallback (the ONVIF backchannel)
 * if the doorbell refuses the primary. The microphone keeps writing at the primary's rate; audio for a slower
 * fallback is downsampled here, so a switch in the middle of a call needs nothing from the microphone side.
 */
public final class FallbackTalk implements TalkChannel {
    public interface Factory {
        TalkChannel create();
    }

    private final TalkChannel primary;
    private final Factory fallbackFactory;
    private TalkChannel fallback;
    private Downsampler down;
    private short[] downOut = new short[0];
    private boolean stopped;

    public FallbackTalk(TalkChannel primary, Factory fallbackFactory) {
        this.primary = primary;
        this.fallbackFactory = fallbackFactory;
    }

    @Override public void start() {
        primary.start();
    }

    /** The primary refused to talk: carry on over the fallback. Its own session ends by itself. */
    public void primaryRefused() {
        TalkChannel f;
        synchronized (this) {
            if (stopped || fallback != null) return;
            f = fallbackFactory.create();
            if (f.rate() != primary.rate()) down = new Downsampler(primary.rate(), f.rate());
            fallback = f;
        }
        f.start();
    }

    /** Whether the fallback took over. */
    public synchronized boolean usingFallback() {
        return fallback != null;
    }

    @Override public void stop() {
        TalkChannel f;
        synchronized (this) {
            stopped = true;
            f = fallback;
        }
        primary.stop();
        if (f != null) f.stop();
    }

    @Override public void write(short[] samples, int count) {
        TalkChannel f;
        int n = count;
        short[] out = samples;
        synchronized (this) {
            f = fallback;
            if (f != null && down != null) {
                if (downOut.length < count) downOut = new short[count];
                n = down.process(samples, count, downOut, 0);
                out = downOut;
                f.write(out, n);  // inside the lock: downOut is reused
                return;
            }
        }
        (f != null ? f : primary).write(out, n);
    }

    @Override public void clear() {
        TalkChannel f;
        synchronized (this) {
            f = fallback;
        }
        (f != null ? f : primary).clear();
    }

    @Override public int rate() {
        return primary.rate();
    }
}
