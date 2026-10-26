// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class MusicPauseTest {
    /** A fake Kiosk Satellite: answers at once, or holds the answer to the read when asked to. */
    private static final class FakeHost implements MusicPause.Host {
        final List<String> log = new ArrayList<>();
        Boolean playing = true;  // null: an older Kiosk Satellite without the field
        boolean holdRead;
        MusicPause.Result heldRead;

        @Override public void command(String name, Map<String, Object> arguments, MusicPause.Result result) {
            if (name.equals("getSendspinPlayer")) {
                log.add("read");
                Map<String, Object> player = new HashMap<>();
                player.put("connected", true);
                if (playing != null) player.put("playing", playing);
                if (holdRead) {
                    heldRead = (ok, data) -> result.done(true, player);
                    return;
                }
                result.done(true, player);
            } else {
                String command = String.valueOf(arguments.get("command"));
                log.add(command);
                if (command.equals("pause")) playing = false;
                if (command.equals("play")) playing = true;
                result.done(true, null);
            }
        }

        String take() { String all = String.join(", ", log); log.clear(); return all; }
    }

    public static void main(String[] args) {
        // Playing: paused for the call, resumed afterwards.
        FakeHost host = new FakeHost();
        MusicPause pause = new MusicPause(host);
        pause.setActive(true);
        check(host.take(), "read, pause");
        pause.setActive(true);  // the ring, then Speaker: paused once
        check(host.take(), "");
        pause.setActive(false);
        check(host.take(), "play");
        pause.setActive(false);
        check(host.take(), "");

        // Not playing: left alone, and not resumed.
        host = new FakeHost();
        host.playing = false;
        pause = new MusicPause(host);
        pause.setActive(true);
        pause.setActive(false);
        check(host.take(), "read");

        // An older Kiosk Satellite without `playing`: left alone.
        host = new FakeHost();
        host.playing = null;
        pause = new MusicPause(host);
        pause.setActive(true);
        pause.setActive(false);
        check(host.take(), "read");

        // The call ends while the read is on its way: nothing paused.
        host = new FakeHost();
        host.holdRead = true;
        pause = new MusicPause(host);
        pause.setActive(true);
        pause.setActive(false);
        host.heldRead.done(true, null);
        check(host.take(), "read");

        // A next call after a resumed one pauses again.
        host = new FakeHost();
        pause = new MusicPause(host);
        pause.setActive(true);
        pause.setActive(false);
        pause.setActive(true);
        check(host.take(), "read, pause, play, read, pause");

        System.out.println("MusicPauseTest passed");
    }

    private static void check(Object actual, Object expected) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " but got " + actual);
    }
}
