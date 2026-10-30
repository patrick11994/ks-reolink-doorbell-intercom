// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell;

public final class LoudnessTest {
    public static void main(String[] args) {
        check(Loudness.gain(100), 1f);
        check(Loudness.gain(150), 1f);
        check(Loudness.gain(0), 0f);
        check(Loudness.gain(-5), 0f);
        // A decibel scale: 50% is 20 dB down (a tenth), 70% 12 dB, 1% almost 40 dB.
        near(Loudness.gain(50), 0.1f);
        near(Loudness.gain(70), (float) Math.pow(10, -12 / 20.0));
        near(Loudness.gain(1), (float) Math.pow(10, -39.6 / 20.0));
        // Each step louder than the last.
        for (int p = 1; p <= 100; p++) {
            if (!(Loudness.gain(p) > Loudness.gain(p - 1))) throw new AssertionError("not rising at " + p);
        }
        // Android set part of the level: the plugin turns down only the rest.
        near(Loudness.remaining(50, 100), Loudness.gain(50));  // fixed volume: all of it
        near(Loudness.remaining(50, 70), Loudness.gain(50) / Loudness.gain(70));
        near(Loudness.remaining(50, 30), 1f);  // Android went lower than wanted: never boosted
        near(Loudness.remaining(50, 0), 1f);
        // Ducking: well down, but the visitor stays audible.
        near(Loudness.DUCK, 0.2f);
        System.out.println("LoudnessTest passed");
    }

    private static void near(float actual, float expected) {
        if (Math.abs(actual - expected) > 1e-4f) throw new AssertionError("expected " + expected + " but got " + actual);
    }

    private static void check(Object actual, Object expected) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " but got " + actual);
    }
}
