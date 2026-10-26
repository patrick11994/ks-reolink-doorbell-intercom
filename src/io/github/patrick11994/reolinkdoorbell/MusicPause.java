// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell;

import java.util.Collections;
import java.util.Map;

/**
 * Pauses the kiosk's music (its Sendspin player, or the player it follows) during a ring or call, and resumes it
 * afterwards, but only if it was playing and this plugin paused it. Needs Kiosk Satellite 2026.10.41 or newer, which
 * reports `playing`: an older one leaves `playing` out, and then the music is left alone.
 */
final class MusicPause {
    /** The host commands this needs, as a seam for tests. */
    interface Host {
        void command(String name, Map<String, Object> arguments, Result result);
    }

    interface Result {
        void done(boolean ok, Object data);
    }

    private final Host host;
    private boolean wanted;  // the ring or call wants the music paused
    private boolean pausedByUs;
    private boolean busy;  // a read or a pause command is on its way

    MusicPause(Host host) {
        this.host = host;
    }

    /** A ring or call started (true) or the viewer closed (false). */
    synchronized void setActive(boolean active) {
        wanted = active;
        settle();
    }

    /** Brings the music to what the call wants, one command at a time. */
    private void settle() {
        if (busy) return;
        if (wanted && !pausedByUs) {
            busy = true;
            host.command("getSendspinPlayer", Collections.<String, Object>emptyMap(), (ok, data) -> {
                Map<?, ?> player = ok && data instanceof Map ? (Map<?, ?>) data : null;
                if (player == null || !Boolean.TRUE.equals(player.get("playing"))) {
                    finish(false);  // nothing playing, or an older Kiosk Satellite: leave it alone
                    return;
                }
                synchronized (MusicPause.this) {
                    if (!wanted) {
                        finish(false);  // the call ended while we asked
                        return;
                    }
                }
                host.command("sendspinControl", Collections.<String, Object>singletonMap("command", "pause"), (paused, ignored) -> finish(paused));
            });
        } else if (!wanted && pausedByUs) {
            busy = true;
            host.command("sendspinControl", Collections.<String, Object>singletonMap("command", "play"), (played, ignored) -> {
                synchronized (MusicPause.this) {
                    busy = false;
                    pausedByUs = false;  // resumed, or it failed: never try to resume twice
                    settle();
                }
            });
        }
    }

    private synchronized void finish(boolean paused) {
        busy = false;
        pausedByUs = paused;
        // A read that found nothing playing is not repeated for this call; a call that ended meanwhile resumes.
        if (paused && !wanted) settle();
    }
}
