// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell.talk;

/** G.711 encoders, as the reference Sun g711.c linear2ulaw / linear2alaw (bit-exact with Python's audioop). */
public final class G711 {
    private static final int[] ULAW_SEGMENTS = {0x3F, 0x7F, 0xFF, 0x1FF, 0x3FF, 0x7FF, 0xFFF, 0x1FFF};
    private static final int[] ALAW_SEGMENTS = {0x1F, 0x3F, 0x7F, 0xFF, 0x1FF, 0x3FF, 0x7FF, 0xFFF};

    private G711() {}

    public static byte ulaw(int sample) {
        int value = sample >> 2;
        int mask;
        if (value < 0) {
            value = -value;
            mask = 0x7F;
        } else {
            mask = 0xFF;
        }
        value = Math.min(value, 8159) + 33;
        int segment = segment(value, ULAW_SEGMENTS);
        if (segment >= 8) return (byte) (0x7F ^ mask);
        return (byte) (((segment << 4) | ((value >> (segment + 1)) & 0x0F)) ^ mask);
    }

    public static byte alaw(int sample) {
        int value = sample >> 3;
        int mask;
        if (value >= 0) {
            mask = 0xD5;
        } else {
            mask = 0x55;
            value = -value - 1;
        }
        int segment = segment(value, ALAW_SEGMENTS);
        if (segment >= 8) return (byte) (0x7F ^ mask);
        return (byte) (((segment << 4) | ((value >> (segment < 2 ? 1 : segment)) & 0x0F)) ^ mask);
    }

    private static int segment(int value, int[] ends) {
        for (int i = 0; i < ends.length; i++) if (value <= ends[i]) return i;
        return ends.length;
    }
}
