// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell;

/** How loud the door plays when the plugin has to set it on its own playback instead of Android's call volume. */
final class Loudness {
    /** The range a 0-100% slider spans, like Android's own volume steps: 100% plays as is, 1% at -40 dB. */
    static final float RANGE_DB = 40f;
    /** The door's sound while push to talk is held, so the microphone hears little of it: -14 dB. */
    static final float DUCK = 0.2f;

    private Loudness() {}

    /** A track volume (0 to 1) for a slider percentage, on a decibel scale, so each step sounds alike. */
    static float gain(int percent) {
        if (percent <= 0) return 0f;
        if (percent >= 100) return 1f;
        return (float) Math.pow(10, (percent / 100.0 - 1) * RANGE_DB / 20);
    }
}
