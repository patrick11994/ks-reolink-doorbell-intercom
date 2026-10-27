// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell.talk;

/**
 * IMA ADPCM in the DVI-4 block layout Reolink's Baichuan talk takes: each block starts with a 4-byte state (the first
 * sample as little-endian int16, the step index, a zero byte), then one 4-bit code per further sample, low nibble
 * first. A block of n code bytes carries 2n + 1 samples. Keeps its state between blocks, like a stream encoder.
 */
public final class ImaAdpcm {
    private static final int[] STEPS = {
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97,
        107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
        876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871,
        5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623,
        27086, 29794, 32767,
    };
    private static final int[] INDEX_CHANGE = {-1, -1, -1, -1, 2, 4, 6, 8};

    private int index;

    /** Samples one block of codeBytes code bytes needs. */
    public static int samplesPerBlock(int codeBytes) {
        return 2 * codeBytes + 1;
    }

    /** Encodes samplesPerBlock(codeBytes) samples from pcm[offset] into one block of 4 + codeBytes bytes. */
    public byte[] encodeBlock(short[] pcm, int offset, int codeBytes) {
        byte[] block = new byte[4 + codeBytes];
        int predicted = pcm[offset];
        block[0] = (byte) predicted;
        block[1] = (byte) (predicted >> 8);
        block[2] = (byte) index;
        for (int i = 0; i < 2 * codeBytes; i++) {
            int code = encode(pcm[offset + 1 + i], predicted);
            predicted = decodeStep(code, predicted);
            index = nextIndex(code, index);
            if ((i & 1) == 0) block[4 + i / 2] = (byte) code;
            else block[4 + i / 2] |= (byte) (code << 4);
        }
        return block;
    }

    /** Decodes one block, for tests: the samples it carries. */
    public static short[] decodeBlock(byte[] block) {
        int predicted = (short) ((block[0] & 0xFF) | (block[1] << 8));
        int index = block[2] & 0xFF;
        int codeBytes = block.length - 4;
        short[] out = new short[samplesPerBlock(codeBytes)];
        out[0] = (short) predicted;
        for (int i = 0; i < 2 * codeBytes; i++) {
            int b = block[4 + i / 2] & 0xFF;
            int code = (i & 1) == 0 ? b & 0x0F : b >> 4;
            predicted = decodeStep(code, predicted, index);
            index = nextIndex(code, index);
            out[1 + i] = (short) predicted;
        }
        return out;
    }

    private int encode(int sample, int predicted) {
        int step = STEPS[index];
        int diff = sample - predicted;
        int code = 0;
        if (diff < 0) {
            code = 8;
            diff = -diff;
        }
        if (diff >= step) { code |= 4; diff -= step; }
        step >>= 1;
        if (diff >= step) { code |= 2; diff -= step; }
        step >>= 1;
        if (diff >= step) code |= 1;
        return code;
    }

    private int decodeStep(int code, int predicted) {
        return decodeStep(code, predicted, index);
    }

    private static int decodeStep(int code, int predicted, int index) {
        int step = STEPS[index];
        int diff = step >> 3;
        if ((code & 4) != 0) diff += step;
        if ((code & 2) != 0) diff += step >> 1;
        if ((code & 1) != 0) diff += step >> 2;
        predicted += (code & 8) != 0 ? -diff : diff;
        return Math.max(-32768, Math.min(32767, predicted));
    }

    private static int nextIndex(int code, int index) {
        return Math.max(0, Math.min(STEPS.length - 1, index + INDEX_CHANGE[code & 7]));
    }
}
