// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell;

import static io.github.patrick11994.reolinkdoorbell.ScreenRestore.ALWAYS;
import static io.github.patrick11994.reolinkdoorbell.ScreenRestore.NEVER;
import static io.github.patrick11994.reolinkdoorbell.ScreenRestore.WHEN_NOT_ANSWERED;

/** Restore the screen (#21): back to the screen off or the screensaver once the call screen closes, as the setting says. */
public final class ScreenRestoreTest {
    public static void main(String[] args) {
        // Nothing known yet: nothing to restore.
        ScreenRestore restore = new ScreenRestore();
        restore.ring();
        check(restore.close(ALWAYS), null);

        // Screen off: off again; the wake's own event in between does not change what the ring found.
        restore.screen(false);
        restore.ring();
        restore.screen(true);
        check(restore.close(WHEN_NOT_ANSWERED), "screenOff");

        // The screensaver: started again. The dashboard: left as it is.
        restore.screensaver(true);
        restore.ring();
        check(restore.close(WHEN_NOT_ANSWERED), "startScreensaver");
        restore.screensaver(false);
        restore.ring();
        check(restore.close(ALWAYS), null);

        // Screen off wins over a screensaver that was still marked active under it.
        restore.screen(false);
        restore.screensaver(true);
        restore.ring();
        check(restore.close(ALWAYS), "screenOff");

        // Answered: only Always restores; Never never does.
        restore.ring();
        restore.answered();
        check(restore.close(WHEN_NOT_ANSWERED), null);
        restore.ring();
        restore.answered();
        check(restore.close(ALWAYS), "screenOff");
        restore.ring();
        check(restore.close(NEVER), null);

        // A new ring forgets the last call's answer; a close without a ring (opened by hand) restores nothing.
        restore.ring();
        check(restore.close(WHEN_NOT_ANSWERED), "screenOff");
        check(restore.close(ALWAYS), null);

        // An unknown setting value acts as the default.
        restore.ring();
        restore.answered();
        check(restore.close("Something else"), null);

        // A read answer that arrives after an event is older: the event stands.
        restore = new ScreenRestore();
        restore.screen(false);
        restore.readScreen(true);
        restore.screensaver(false);
        restore.readScreensaver(true);
        restore.ring();
        check(restore.before(), ScreenRestore.Before.OFF);
        restore = new ScreenRestore();
        restore.readScreen(true);
        restore.readScreensaver(true);
        restore.ring();
        check(restore.before(), ScreenRestore.Before.SCREENSAVER);
        System.out.println("ScreenRestoreTest passed");
    }

    private static void check(Object actual, Object expected) {
        if (expected == null ? actual != null : !expected.equals(actual)) throw new AssertionError("expected " + expected + " but got " + actual);
    }
}
