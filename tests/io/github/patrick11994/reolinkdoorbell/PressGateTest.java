// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell;

import static io.github.patrick11994.reolinkdoorbell.PressGate.Result.DROP;
import static io.github.patrick11994.reolinkdoorbell.PressGate.Result.PASS;
import static io.github.patrick11994.reolinkdoorbell.PressGate.Result.SILENT_PRESS;

public final class PressGateTest {
    public static void main(String[] args) {
        // Ringing on throughout: the controller sees every report, as before Ring on this device existed.
        PressGate gate = new PressGate();
        check(gate.report(true, true, 0), PASS);
        check(gate.report(true, true, 1_000), PASS);
        check(gate.report(false, true, 15_000), PASS);
        check(gate.report(true, true, 16_000), PASS);

        // Ringing off: one silent press per press, releases still reach the controller (they do nothing there).
        gate = new PressGate();
        check(gate.report(true, false, 0), SILENT_PRESS);
        check(gate.report(true, false, 1_000), DROP);
        check(gate.report(false, false, 15_000), PASS);
        check(gate.report(true, false, 16_000), SILENT_PRESS);

        // Switched on during a silenced press: not rung halfway, not counted twice; the next press rings.
        gate = new PressGate();
        check(gate.report(true, false, 0), SILENT_PRESS);
        check(gate.report(true, true, 2_000), DROP);
        check(gate.report(false, true, 15_000), PASS);
        check(gate.report(true, true, 16_000), PASS);

        // Switched off during a rung press: the controller keeps seeing that press, no second trigger.
        gate = new PressGate();
        check(gate.report(true, true, 0), PASS);
        check(gate.report(true, false, 2_000), PASS);
        check(gate.report(true, false, 10_000), PASS);
        check(gate.report(false, false, 15_000), PASS);

        // Held past 20 s, ringing off: counts again, as the controller would ring again.
        gate = new PressGate();
        check(gate.report(true, false, 0), SILENT_PRESS);
        check(gate.report(true, false, 21_000), SILENT_PRESS);

        // A silenced press whose release went missing, then ringing on: the next press after 20 s rings.
        gate = new PressGate();
        check(gate.report(true, false, 0), SILENT_PRESS);
        check(gate.report(true, true, 30_000), PASS);
        check(gate.report(true, true, 31_000), PASS);

        // The very first report may be at any clock value, even 0 or negative.
        gate = new PressGate();
        check(gate.report(true, false, -5), SILENT_PRESS);
        System.out.println("PressGateTest passed");
    }

    private static void check(Object actual, Object expected) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " but got " + actual);
    }
}
