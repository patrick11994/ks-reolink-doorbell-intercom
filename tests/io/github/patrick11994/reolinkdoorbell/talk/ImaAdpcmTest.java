// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell.talk;

public final class ImaAdpcmTest {
    public static void main(String[] args) {
        // A block layout: 4-byte state, then the codes; 256 code bytes carry 513 samples.
        check(ImaAdpcm.samplesPerBlock(256), 513);

        // Speech-like audio (two tones, rising level) survives encode and decode with a good signal-to-noise ratio.
        int codeBytes = 256, perBlock = ImaAdpcm.samplesPerBlock(codeBytes), blocks = 20;
        short[] pcm = new short[perBlock * blocks];
        for (int i = 0; i < pcm.length; i++) {
            double level = 2000 + 18000.0 * i / pcm.length;
            pcm[i] = (short) (level * (0.7 * Math.sin(2 * Math.PI * 440 * i / 16000) + 0.3 * Math.sin(2 * Math.PI * 1900 * i / 16000)));
        }
        ImaAdpcm encoder = new ImaAdpcm();
        double signal = 0, noise = 0;
        for (int b = 0; b < blocks; b++) {
            byte[] block = encoder.encodeBlock(pcm, b * perBlock, codeBytes);
            check(block.length, 4 + codeBytes);
            check(block[3], (byte) 0);
            short[] decoded = ImaAdpcm.decodeBlock(block);
            check(decoded.length, perBlock);
            check(decoded[0], pcm[b * perBlock]);  // the first sample travels uncompressed in the header
            for (int i = 0; i < perBlock; i++) {
                double s = pcm[b * perBlock + i], d = decoded[i] - s;
                signal += s * s;
                noise += d * d;
            }
        }
        double snr = 10 * Math.log10(signal / noise);
        check(snr > 20, true);

        // The trellis search: decodable by the same decoder, and less noise on the same audio. Two steady tones leave
        // it little to gain (about 1.3 dB); on speech it gains 2.7 dB at 32 survivors.
        double plain = snr(new ImaAdpcm(), pcm, codeBytes, blocks), trellis = snr(new ImaAdpcm(32), pcm, codeBytes, blocks);
        check(trellis > plain + 1, true);

        // The doorbell's nibble order: swapping twice gives the block back, and the first code moves to the high nibble.
        byte[] wav = new ImaAdpcm().encodeBlock(pcm, 0, codeBytes);
        byte[] doorbell = ImaAdpcm.highNibbleFirst(wav.clone());
        check(doorbell[4], (byte) ((wav[4] & 0x0F) << 4 | (wav[4] & 0xF0) >>> 4));
        check(java.util.Arrays.equals(ImaAdpcm.highNibbleFirst(doorbell.clone()), wav), true);
        check(java.util.Arrays.equals(java.util.Arrays.copyOf(doorbell, 4), java.util.Arrays.copyOf(wav, 4)), true);  // the header stays

        // Silence takes the cheap path in a trellis encoder too, and comes out as silence.
        short[] hush = ImaAdpcm.decodeBlock(new ImaAdpcm(16).encodeBlock(new short[perBlock], 0, codeBytes));
        for (short v : hush) check(v, (short) 0);

        // Silence stays silence.
        short[] quiet = new short[perBlock];
        short[] back = ImaAdpcm.decodeBlock(new ImaAdpcm().encodeBlock(quiet, 0, codeBytes));
        int peak = 0;
        for (short s : back) peak = Math.max(peak, Math.abs(s));
        check(peak <= 8, true);

        System.out.println("ImaAdpcmTest passed (SNR " + Math.round(snr) + " dB, trellis " + Math.round(trellis) + " dB)");
    }

    private static double snr(ImaAdpcm encoder, short[] pcm, int codeBytes, int blocks) {
        int perBlock = ImaAdpcm.samplesPerBlock(codeBytes);
        double signal = 0, noise = 0;
        for (int b = 0; b < blocks; b++) {
            short[] decoded = ImaAdpcm.decodeBlock(encoder.encodeBlock(pcm, b * perBlock, codeBytes));
            for (int i = 0; i < perBlock; i++) {
                double v = pcm[b * perBlock + i], d = decoded[i] - v;
                signal += v * v;
                noise += d * d;
            }
        }
        return 10 * Math.log10(signal / noise);
    }

    private static void check(Object actual, Object expected) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " but got " + actual);
    }
}
