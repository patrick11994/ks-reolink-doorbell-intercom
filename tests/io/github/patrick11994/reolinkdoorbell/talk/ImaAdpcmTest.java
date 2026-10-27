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

        // Silence stays silence.
        short[] quiet = new short[perBlock];
        short[] back = ImaAdpcm.decodeBlock(new ImaAdpcm().encodeBlock(quiet, 0, codeBytes));
        int peak = 0;
        for (short s : back) peak = Math.max(peak, Math.abs(s));
        check(peak <= 8, true);

        System.out.println("ImaAdpcmTest passed (SNR " + Math.round(snr) + " dB)");
    }

    private static void check(Object actual, Object expected) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " but got " + actual);
    }
}
