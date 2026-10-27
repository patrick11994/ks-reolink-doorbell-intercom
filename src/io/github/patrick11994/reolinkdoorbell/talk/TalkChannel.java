// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell.talk;

/**
 * A way to talk to the door: opened at the ring, it streams silence until the microphone feeds it, so the doorbell's
 * playout buffer is already filled when someone starts talking.
 */
public interface TalkChannel {
    void start();

    /** Stops sending and closes the session; safe from any thread except the main one (it may wait briefly). */
    void stop();

    /** Microphone audio at rate(). The oldest audio is dropped rather than letting the delay grow. */
    void write(short[] samples, int count);

    /** The microphone stopped (muted or ended): forget what was queued and go back to silence. */
    void clear();

    /** The sample rate write() takes. */
    int rate();
}
