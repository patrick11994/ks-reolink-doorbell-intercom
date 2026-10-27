// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell.talk;

/**
 * IMA ADPCM blocks as Reolink's Baichuan talk takes them: each block starts with a 4-byte header (the block's first
 * sample as little-endian int16, the step index, a zero byte), then one 4-bit code per further sample. A block of n
 * code bytes carries 2n + 1 samples. encodeBlock writes the WAV nibble order (low nibble first); the doorbell wants
 * the high nibble first, see highNibbleFirst. Keeps the step index between blocks, like a stream encoder.
 *
 * With more than one survivor it searches a trellis: it keeps that many candidate code sequences per sample and
 * picks the one with the least squared error at the end of the block. Choosing each code from the current sample
 * alone (one survivor) leaves audible noise on speech; 16 survivors gain about 3 dB. Any IMA decoder plays both.
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

    private final int survivors;
    private int index;

    /** The plain encoder: each code chosen from the current sample. */
    public ImaAdpcm() {
        this(1);
    }

    /** survivors candidate paths per sample; 1 is the plain encoder. */
    public ImaAdpcm(int survivors) {
        if (survivors < 1 || survivors > 1024) throw new IllegalArgumentException("survivors " + survivors);
        this.survivors = survivors;
    }

    /** Samples one block of codeBytes code bytes needs. */
    public static int samplesPerBlock(int codeBytes) {
        return 2 * codeBytes + 1;
    }

    /** Encodes samplesPerBlock(codeBytes) samples from pcm[offset] into one block of 4 + codeBytes bytes. */
    public byte[] encodeBlock(short[] pcm, int offset, int codeBytes) {
        return encode(pcm, offset + 1, codeBytes, pcm[offset]);
    }

    /**
     * Turns a block from the WAV nibble order into the one Reolink doorbells decode: each code byte's earlier sample in
     * its high nibble. Sent low nibble first, the doorbell plays every pair of samples swapped, which rattles on fast
     * sounds. Found by capturing the Reolink app.
     */
    public static byte[] highNibbleFirst(byte[] block) {
        for (int i = 4; i < block.length; i++) block[i] = (byte) ((block[i] & 0x0F) << 4 | (block[i] & 0xF0) >>> 4);
        return block;
    }

    private byte[] encode(short[] pcm, int first, int codeBytes, int start) {
        // A trellis gains nothing on silence (the talk channel's idle state), so that costs no more than the plain encoder.
        boolean silent = start == 0;
        for (int i = 0; silent && i < 2 * codeBytes; i++) silent = pcm[first + i] == 0;
        return survivors == 1 || silent ? encodePlain(pcm, first, codeBytes, start) : encodeTrellis(pcm, first, codeBytes, start);
    }

    private byte[] encodePlain(short[] pcm, int first, int codeBytes, int start) {
        byte[] block = new byte[4 + codeBytes];
        int predicted = start;
        block[0] = (byte) predicted;
        block[1] = (byte) (predicted >> 8);
        block[2] = (byte) index;
        for (int i = 0; i < 2 * codeBytes; i++) {
            int code = encode(pcm[first + i], predicted);
            predicted = decodeStep(code, predicted);
            index = nextIndex(code, index);
            if ((i & 1) == 0) block[4 + i / 2] = (byte) code;
            else block[4 + i / 2] |= (byte) (code << 4);
        }
        return block;
    }

    private byte[] encodeTrellis(short[] pcm, int first, int codeBytes, int start) {
        int steps = 2 * codeBytes, k = survivors;
        ensureTrellis(steps, k);
        int[] predicted = tPredicted, indexes = tIndexes, nextPredicted = tNextPredicted, nextIndexes = tNextIndexes;
        long[] cost = tCost, nextCost = tNextCost;
        int alive = 1;
        predicted[0] = start;
        indexes[0] = index;
        cost[0] = 0;
        for (int step = 0; step < steps; step++) {
            int sample = pcm[first + step], n = 0;
            for (int p = 0; p < alive; p++) {
                // The plain encoder's code and its neighbours in size, as ffmpeg's trellis does; far codes never win.
                int best = encode(sample, predicted[p], indexes[p]);
                int sign = best & 8, size = best & 7;
                for (int m = Math.max(0, size - 1); m <= Math.min(7, size + 1); m++) n = candidate(n, sign | m, p, sample, predicted, indexes, cost);
                if (size == 0) n = candidate(n, (sign ^ 8), p, sample, predicted, indexes, cost);
            }
            // Keep the k cheapest, one per predicted sample, so the survivors stay different paths.
            long[] order = tOrder;
            for (int i = 0; i < n; i++) order[i] = tCandCost[i] << 12 | i;
            java.util.Arrays.sort(order, 0, n);
            int stamp = ++tStamp, kept = 0;
            for (int i = 0; i < n && kept < k; i++) {
                int c = (int) (order[i] & 0xFFF);
                int slot = (tCandPredicted[c] * 0x9E3779B1 >>> 24) & (tSeen.length - 1);
                boolean duplicate = false;
                while (tSeenStamp[slot] == stamp) {
                    if (tSeen[slot] == tCandPredicted[c]) { duplicate = true; break; }
                    slot = (slot + 1) & (tSeen.length - 1);
                }
                if (duplicate) continue;
                tSeenStamp[slot] = stamp;
                tSeen[slot] = tCandPredicted[c];
                nextPredicted[kept] = tCandPredicted[c];
                nextIndexes[kept] = tCandIndex[c];
                nextCost[kept] = tCandCost[c];
                tCodes[step * k + kept] = (byte) (tCandFrom[c] & 15);
                tParents[step * k + kept] = (short) (tCandFrom[c] >> 4);
                kept++;
            }
            alive = kept;
            int[] t = predicted; predicted = nextPredicted; nextPredicted = t;
            t = indexes; indexes = nextIndexes; nextIndexes = t;
            long[] u = cost; cost = nextCost; nextCost = u;
        }
        // The cheapest path, traced back.
        int best = 0;
        for (int p = 1; p < alive; p++) if (cost[p] < cost[best]) best = p;
        byte[] block = new byte[4 + codeBytes];
        block[0] = (byte) start;
        block[1] = (byte) (start >> 8);
        block[2] = (byte) index;
        index = indexes[best];
        for (int step = steps - 1, p = best; step >= 0; step--) {
            int code = tCodes[step * k + p];
            if ((step & 1) == 0) block[4 + step / 2] |= (byte) code;
            else block[4 + step / 2] |= (byte) (code << 4);
            p = tParents[step * k + p];
        }
        return block;
    }

    private int candidate(int n, int code, int p, int sample, int[] predicted, int[] indexes, long[] cost) {
        int value = decodeStep(code, predicted[p], indexes[p]);
        long error = sample - value;
        tCandPredicted[n] = value;
        tCandIndex[n] = nextIndex(code, indexes[p]);
        tCandCost[n] = cost[p] + error * error;
        tCandFrom[n] = p << 4 | code;
        return n + 1;
    }

    // The trellis' working arrays, kept between blocks so encoding allocates nothing per sample.
    private int[] tPredicted, tIndexes, tNextPredicted, tNextIndexes, tCandPredicted, tCandIndex, tCandFrom, tSeen, tSeenStamp;
    private long[] tCost, tNextCost, tCandCost, tOrder;
    private byte[] tCodes;
    private short[] tParents;
    private int tStamp;

    private void ensureTrellis(int steps, int k) {
        if (tCodes != null && tCodes.length == steps * k) return;
        int wide = k * 4;
        tPredicted = new int[k]; tIndexes = new int[k]; tNextPredicted = new int[k]; tNextIndexes = new int[k];
        tCost = new long[k]; tNextCost = new long[k];
        tCandPredicted = new int[wide]; tCandIndex = new int[wide]; tCandFrom = new int[wide];
        tCandCost = new long[wide]; tOrder = new long[wide];
        int table = Integer.highestOneBit(wide * 2) * 2;
        tSeen = new int[table]; tSeenStamp = new int[table];
        tCodes = new byte[steps * k];
        tParents = new short[steps * k];
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
        return encode(sample, predicted, index);
    }

    private static int encode(int sample, int predicted, int index) {
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
