// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell;

import java.util.function.BooleanSupplier;

/** Waits for what a test needs instead of sleeping a guess: as fast as the machine allows, and a clear failure. */
public final class Await {
    private Await() {}

    public static void until(BooleanSupplier condition, long millis, String what) throws InterruptedException {
        long deadline = System.nanoTime() / 1_000_000 + millis;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() / 1_000_000 > deadline) throw new AssertionError("not within " + millis + " ms: " + what);
            Thread.sleep(5);
        }
    }
}
