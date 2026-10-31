// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell;

public final class JitterDrainTest {
    private static final long CUSHION = 1920, PIECE = 1024;  // 120 ms and one AAC frame at 16 kHz

    public static void main(String[] args) {
        // A buffer that stays 300 ms full is drained, one quiet piece at a time, down to the cushion.
        JitterDrain drain = new JitterDrain(CUSHION);
        long buffered = 4800;
        int skipped = 0;
        for (long now = 0; now < 20_000; now += 64) {
            if (drain.skip(buffered, PIECE, true, now)) {
                buffered -= PIECE;
                skipped++;
            }
        }
        check(skipped, 2);  // 4800 -> 3776 -> 2752; one more would leave less than the cushion plus a piece
        if (buffered < CUSHION) throw new AssertionError("drained below the cushion: " + buffered);

        // A level that dips to the cushion now and then is what the network needs: nothing is skipped.
        drain = new JitterDrain(CUSHION);
        for (long now = 0; now < 20_000; now += 64) {
            long level = now % 900 < 64 ? CUSHION : 4800;
            if (drain.skip(level, PIECE, true, now)) throw new AssertionError("skipped at " + now);
        }

        // A loud piece is skipped only when no quiet one came for a while.
        drain = new JitterDrain(CUSHION);
        long first = -1;
        for (long now = 0; now < 5_000 && first < 0; now += 64) {
            if (drain.skip(4800, PIECE, false, now)) first = now;
        }
        if (first < JitterDrain.WINDOW_MILLIS + JitterDrain.QUIET_WAIT_MILLIS) throw new AssertionError("loud piece skipped at " + first);

        // Once excess is found, the next quiet piece goes.
        drain = new JitterDrain(CUSHION);
        for (long now = 0; now < JitterDrain.WINDOW_MILLIS + 64; now += 64) drain.skip(4800, PIECE, false, now);
        check(drain.skip(4800, PIECE, true, JitterDrain.WINDOW_MILLIS + 128), true);

        // Quiet means every sample below the peak.
        check(JitterDrain.quiet(new byte[] {(byte) 0xE7, 0x03, 0x19, (byte) 0xFC}, 4), true);  // 999 and -999
        check(JitterDrain.quiet(new byte[] {0, 0, (byte) 0xE8, 0x03}, 4), false);  // 1000
        check(JitterDrain.quiet(new byte[] {0, 0, 0x18, (byte) 0xFC}, 4), false);  // -1000
        System.out.println("JitterDrainTest passed");
    }

    private static void check(Object actual, Object expected) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " but got " + actual);
    }
}
