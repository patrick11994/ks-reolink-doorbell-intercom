// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell.talk;

/**
 * Microphone audio waiting to be sent. Playing starts once a small cushion has built up, never holds more than a cap
 * (the oldest audio goes first), and runs dry into silence rather than sending fragments.
 */
public final class TalkQueue {
    private final int startSamples, maxSamples;
    private final short[] ring;
    private int ringStart, ringCount;
    private boolean flowing;  // microphone audio is being played out (after its start cushion)

    /** startSamples must queue before audio flows; maxSamples is the most ever kept. */
    public TalkQueue(int startSamples, int maxSamples) {
        this.startSamples = startSamples;
        this.maxSamples = maxSamples;
        ring = new short[maxSamples * 2];
    }

    public synchronized void write(short[] samples, int count) {
        for (int i = 0; i < count; i++) {
            if (ringCount == ring.length) {
                ringStart = (ringStart + 1) % ring.length;
                ringCount--;
            }
            ring[(ringStart + ringCount) % ring.length] = samples[i];
            ringCount++;
        }
        while (ringCount > maxSamples) {
            ringStart = (ringStart + 1) % ring.length;
            ringCount--;
        }
    }

    public synchronized void clear() {
        ringStart = 0;
        ringCount = 0;
        flowing = false;
    }

    /** Fills out[0, count) with the next microphone audio when there is enough, else with silence. */
    public synchronized void next(short[] out, int count) {
        if (!flowing && ringCount >= startSamples) flowing = true;
        if (flowing && ringCount >= count) {
            for (int i = 0; i < count; i++) out[i] = ring[(ringStart + i) % ring.length];
            ringStart = (ringStart + count) % ring.length;
            ringCount -= count;
            return;
        }
        if (flowing) flowing = false;  // ran dry: wait for a new cushion instead of sending fragments
        java.util.Arrays.fill(out, 0, count, (short) 0);
    }
}
