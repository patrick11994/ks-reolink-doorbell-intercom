// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell;

public final class VideoPacerTest {
    private static final long FRAME = 50_000, BASE = 30_000;  // 20 fps; 30 ms from capture to ready on a good day

    public static void main(String[] args) {
        // What LiveVideoPace measured: frames ready 40 and 60 ms apart in turn, and each second's keyframe 45 ms late
        // with the next frame right behind it. Shown at an even 50 ms pace, the cushion's delay apart from capture.
        VideoPacer pacer = new VideoPacer();
        long previous = -1;
        for (int i = 0; i < 400; i++) {
            long capture = i * FRAME;
            long jitter = i % 20 == 0 ? 45_000 : i % 20 == 1 ? 8_000 : i % 2 == 0 ? 10_000 : 0;
            long shown = pacer.displayAt(capture, capture + BASE + jitter);
            if (i > 40 && shown - previous != FRAME) throw new AssertionError("frame " + i + " shown " + (shown - previous) / 1000 + " ms after the one before");
            previous = shown;
        }
        check(pacer.late(), 0L);
        check(pacer.cushionMillis(), VideoPacer.START_MILLIS);
        check(previous - 399 * FRAME, BASE + VideoPacer.START_MILLIS * 1000L);  // the delay is the cushion, no more

        // The keyframe group a stream replays on attach arrives at once: the live frames after it are not held back
        // behind its lateness.
        pacer = new VideoPacer();
        for (int i = 0; i < 20; i++) pacer.displayAt(i * FRAME, 1_000_000 + i * 1000);
        long capture = 20 * FRAME, ready = 1_050_000;
        for (int i = 0; i < 40; i++, capture += FRAME, ready += FRAME) previous = pacer.displayAt(capture, ready);
        long delay = previous - (ready - FRAME);
        if (delay > VideoPacer.START_MILLIS * 1000L) throw new AssertionError("held back " + delay / 1000 + " ms after the replay");

        // A stall of half a second: the frame is shown at once, the ones after it at their pace again.
        pacer = new VideoPacer();
        for (int i = 0; i < 100; i++) pacer.displayAt(i * FRAME, i * FRAME + BASE);
        check(pacer.displayAt(100 * FRAME, 100 * FRAME + BASE + 500_000), 100 * FRAME + BASE + 500_000);
        check(pacer.displayAt(101 * FRAME, 101 * FRAME + BASE), 101 * FRAME + BASE + VideoPacer.START_MILLIS * 1000L);

        // Frames that keep missing the cushion grow it, up to its maximum; a calm stream brings it back down.
        pacer = new VideoPacer();
        int i = 0;
        for (; i < 2000; i++) pacer.displayAt(i * FRAME, i * FRAME + BASE + (i % 10 == 5 ? 300_000 : 0));
        check(pacer.cushionMillis(), VideoPacer.MAX_MILLIS);
        for (int end = i + 4000; i < end; i++) pacer.displayAt(i * FRAME, i * FRAME + BASE);
        check(pacer.cushionMillis(), VideoPacer.START_MILLIS);

        // Some late frames, but not enough to grow it.
        pacer = new VideoPacer();
        for (i = 0; i < 2000; i++) pacer.displayAt(i * FRAME, i * FRAME + BASE + (i % 400 == 7 ? 150_000 : 0));
        check(pacer.cushionMillis(), VideoPacer.START_MILLIS);
        check(pacer.late(), 5L);

        // Clocks that drift 1 ms a second apart, both ways: followed without a late frame or a growing delay.
        for (long drift : new long[] {50, -50}) {  // micros per frame
            pacer = new VideoPacer();
            for (i = 0; i < 2400; i++) previous = pacer.displayAt(i * FRAME, i * FRAME + BASE + i * drift);
            check(pacer.late(), 0L);
            long behind = previous - (2399 * FRAME + BASE + 2399 * drift);
            if (Math.abs(behind - VideoPacer.START_MILLIS * 1000L) > 2 * VideoPacer.SLEW_MICROS + 50 * 40) throw new AssertionError("drift " + drift + ": delay " + behind / 1000 + " ms");
        }
        System.out.println("VideoPacerTest passed");
    }

    private static void check(Object actual, Object expected) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " but got " + actual);
    }
}
