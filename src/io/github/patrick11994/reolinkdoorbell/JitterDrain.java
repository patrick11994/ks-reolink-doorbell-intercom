// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell;

/**
 * Brings the door audio's jitter buffer back to its cushion. Audio comes in and plays out at the same rate, so the
 * level a network burst leaves behind would otherwise stay for the whole call. When the buffer has not once dipped to
 * the cushion plus one piece for a whole window, the network evidently does not need that much, and one piece is
 * skipped: a quiet one if one comes soon, so the visitor's words are not cut.
 */
final class JitterDrain {
    static final int WINDOW_MILLIS = 1000;
    static final int QUIET_WAIT_MILLIS = 1000;  // excess found: how long a quiet piece may take before a loud one goes
    static final int QUIET_PEAK = 1000;  // a 16-bit peak below this is near silence, about -30 dBFS

    private final long cushion;
    private long windowStart = -1, windowLowest = Long.MAX_VALUE, excessSince = -1;

    /** cushion: the buffer level to keep, in frames. */
    JitterDrain(long cushion) {
        this.cushion = cushion;
    }

    /** Whether to skip the next decoded piece of pieceFrames instead of playing it, with buffered frames still waiting. */
    boolean skip(long buffered, long pieceFrames, boolean quiet, long nowMillis) {
        long enough = cushion + pieceFrames;
        if (excessSince >= 0) {
            if (buffered <= enough) {
                excessSince = -1;  // came down by itself
                restart(nowMillis);
            } else if (quiet || nowMillis - excessSince >= QUIET_WAIT_MILLIS) {
                excessSince = -1;
                restart(nowMillis);
                return true;
            }
            return false;
        }
        if (windowStart < 0) windowStart = nowMillis;
        windowLowest = Math.min(windowLowest, buffered);
        if (nowMillis - windowStart >= WINDOW_MILLIS) {
            if (windowLowest > enough) excessSince = nowMillis;
            restart(nowMillis);
        }
        return false;
    }

    private void restart(long nowMillis) {
        windowStart = nowMillis;
        windowLowest = Long.MAX_VALUE;
    }

    /** Whether 16-bit little-endian PCM stays below QUIET_PEAK. */
    static boolean quiet(byte[] pcm, int size) {
        for (int i = 0; i + 1 < size; i += 2) {
            int sample = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            if (sample >= QUIET_PEAK || sample <= -QUIET_PEAK) return false;
        }
        return true;
    }
}
