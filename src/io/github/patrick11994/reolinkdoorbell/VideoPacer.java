// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell;

/**
 * When to show each decoded picture: at the doorbell's own pace, from its capture timestamps, instead of the moment the
 * decoder lets go of it. The doorbell films at an even rate, but its pictures reach the screen unevenly: its keyframe,
 * twenty times a normal frame, comes about 45 ms late each second and the next frame right behind it (measured with
 * LiveVideoPace), and the decoder adds its own unevenness. Shown as they come, the picture runs slow and fast in turn.
 *
 * A picture's lateness is the time it is ready minus its capture time. The anchor is the lowest lateness lately seen,
 * and a picture is shown at its capture time plus the anchor plus a cushion that covers the unevenness. A picture that
 * misses that time is shown at once. The anchor follows the lowest lateness of each window by at most SLEW_MICROS per
 * picture, a change in pace too small to see, so clock drift between doorbell and panel does not build up; it jumps
 * down at once in the first window, past the keyframe group a stream replays on attach, and later for a picture far
 * earlier than expected. The cushion grows by STEP_MILLIS when pictures keep missing their time, and shrinks back after a
 * period in which it was not needed, never below START_MILLIS.
 */
final class VideoPacer {
    static final int START_MILLIS = 80;  // covered every frame measured over Baichuan; the panel's decoder adds to it
    static final int MAX_MILLIS = 200;
    static final int STEP_MILLIS = 20;
    static final int LATE_TO_GROW = 3;  // pictures missing their time within one period
    static final int PERIOD_MILLIS = 10_000;
    static final int WINDOW_MILLIS = 2000;
    static final long SLEW_MICROS = 1000;  // 2% of a 50 ms frame

    private long anchor = Long.MIN_VALUE, target;
    private boolean settled;  // after the first window
    private long windowStart = -1, windowLowest = Long.MAX_VALUE;
    private long periodStart = -1, periodHighest;
    private int periodLate;
    private int cushionMillis = START_MILLIS;
    private long onTime, late, behind;

    /** The time to show a picture captured at captureMicros, ready at nowMicros on the same clock as the result. */
    long displayAt(long captureMicros, long nowMicros) {
        long lateness = nowMicros - captureMicros;
        long cushion = cushionMillis * 1000L;
        if (anchor == Long.MIN_VALUE || lateness < anchor - (settled ? cushion : 0)) {
            // The first window, or far earlier than expected: jump ahead. In the first window the replayed keyframe group
            // and the live pictures after it come in turn, and the picture has only just appeared.
            anchor = target = lateness;
            if (settled) restartWindow(nowMicros);
        }
        if (windowStart < 0) windowStart = nowMicros;
        windowLowest = Math.min(windowLowest, lateness);
        if (nowMicros - windowStart >= WINDOW_MILLIS * 1000L) {
            target = windowLowest;
            settled = true;
            restartWindow(nowMicros);
        }
        anchor += Math.max(-SLEW_MICROS, Math.min(SLEW_MICROS, target - anchor));
        if (periodStart < 0) periodStart = nowMicros;
        behind = lateness - anchor;
        periodHighest = Math.max(periodHighest, behind);
        boolean missed = lateness > anchor + cushion;
        if (missed) {
            late++;
            if (++periodLate >= LATE_TO_GROW && cushionMillis < MAX_MILLIS) {
                cushionMillis = Math.min(MAX_MILLIS, cushionMillis + STEP_MILLIS);
                restartPeriod(nowMicros);
            }
        } else {
            onTime++;
        }
        if (nowMicros - periodStart >= PERIOD_MILLIS * 1000L) {
            if (periodLate == 0 && periodHighest < (cushionMillis - 2 * STEP_MILLIS) * 1000L && cushionMillis > START_MILLIS) cushionMillis -= STEP_MILLIS;
            restartPeriod(nowMicros);
        }
        return missed ? nowMicros : captureMicros + anchor + cushion;
    }

    /** How much later than the lowest lateness the last picture was ready, in milliseconds: what the cushion has to cover. */
    long behindMillis() {
        return behind / 1000;
    }

    int cushionMillis() {
        return cushionMillis;
    }

    long onTime() {
        return onTime;
    }

    long late() {
        return late;
    }

    private void restartWindow(long nowMicros) {
        windowStart = nowMicros;
        windowLowest = Long.MAX_VALUE;
    }

    private void restartPeriod(long nowMicros) {
        periodStart = nowMicros;
        periodHighest = 0;
        periodLate = 0;
    }
}
