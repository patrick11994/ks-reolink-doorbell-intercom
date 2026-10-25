// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class WakeWordGuardTest {
    /** A fake Kiosk Satellite: answers at once, like the plugin worker would later, and records what was asked. */
    private static final class FakeHost implements WakeWordGuard.Host {
        final List<String> log = new ArrayList<>();
        boolean available = true, muted;
        Boolean remembered;

        @Override public void command(String name, Map<String, Object> arguments, WakeWordGuard.Result result) {
            if (name.equals("getWakeWordState")) {
                log.add("read");
                Map<String, Object> state = new HashMap<>();
                state.put("available", available);
                state.put("muted", muted);
                result.done(true, state);
            } else {
                muted = Boolean.TRUE.equals(arguments.get("muted"));
                log.add(muted ? "mute" : "unmute");
                result.done(true, null);
            }
        }

        @Override public void remember(boolean mutedByUs) {
            remembered = mutedByUs;
        }

        String take() { String all = String.join(", ", log); log.clear(); return all; }
    }

    public static void main(String[] args) {
        FakeHost host = new FakeHost();
        WakeWordGuard guard = new WakeWordGuard(host);
        guard.setActive(true);
        check(host.take(), "read, mute");
        check(host.remembered, true);
        guard.setActive(true);  // already muted by us: nothing more
        check(host.take(), "");
        guard.setActive(false);
        check(host.take(), "unmute");
        check(host.remembered, false);

        // A wake word the user muted is left alone, also afterwards.
        host.muted = true;
        guard.setActive(true);
        guard.setActive(false);
        check(host.take(), "read");
        check(host.muted, true);

        // No wake word at all (Voice Satellite off): nothing is changed.
        host.muted = false;
        host.available = false;
        guard.setActive(true);
        guard.setActive(false);
        check(host.take(), "read");

        // A session cut off mid-call: the next start puts the wake word back.
        FakeHost next = new FakeHost();
        next.muted = true;
        new WakeWordGuard(next).restore(true);
        check(next.take(), "unmute");
        check(next.muted, false);
        check(next.remembered, false);
        new WakeWordGuard(next).restore(false);
        check(next.take(), "");
        System.out.println("WakeWordGuardTest passed");
    }

    private static void check(Object actual, Object expected) {
        if (!expected.equals(actual)) throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
    }
}
