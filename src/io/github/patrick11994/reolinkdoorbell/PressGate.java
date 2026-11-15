// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell;

/**
 * Ring on this device (#19): which of the doorbell's visitor reports reach the ring controller. Each press that counts
 * is rung or silenced by the setting as it is then, and stays so until the doorbell lets go of it, so switching
 * ringing on or off in the middle neither rings halfway nor counts the press twice. As in the controller, a press
 * held past REPEAT_MILLIS, or whose release went missing, counts again. Not thread-safe: the caller guards it.
 */
final class PressGate {
    /** What to do with one visitor report. */
    enum Result { PASS, SILENT_PRESS, DROP }

    private boolean visitorBefore, silenced;
    private long pressAt = Long.MIN_VALUE / 2;  // when the last press counted, rung or silenced

    /** visitor: the doorbell's state; ring: the setting now; now: monotonic milliseconds. */
    Result report(boolean visitor, boolean ring, long now) {
        boolean press = visitor && (!visitorBefore || now - pressAt > RingController.REPEAT_MILLIS);
        visitorBefore = visitor;
        if (!visitor) silenced = false;
        if (press) {
            pressAt = now;
            silenced = !ring;
            if (silenced) return Result.SILENT_PRESS;
        }
        return silenced ? Result.DROP : Result.PASS;
    }
}
