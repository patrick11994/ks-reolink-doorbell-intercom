// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell;

/**
 * What the device was doing when a ring woke it, so the call screen leaves it that way again once it closes (#21):
 * the screen off, the screensaver, or the dashboard, which the call screen never leaves. The screen and screensaver
 * states come from Kiosk Satellite's events, so the ring reads them without waiting for an answer.
 */
final class ScreenRestore {
    /** The Restore the screen setting's options; the first is the default. */
    static final String WHEN_NOT_ANSWERED = "When not answered", ALWAYS = "Always", NEVER = "Never";
    static final String[] MODES = {WHEN_NOT_ANSWERED, ALWAYS, NEVER};

    enum Before { DASHBOARD, SCREENSAVER, OFF }

    private Boolean screenOn, screensaver;  // null until Kiosk Satellite tells
    private boolean screenEvent, screensaverEvent;  // an event came: a slower read answer is older
    private Before before;  // null outside a call that a ring woke the device for
    private boolean answered;

    synchronized void screen(boolean on) {
        screenOn = on;
        screenEvent = true;
    }

    synchronized void screensaver(boolean active) {
        screensaver = active;
        screensaverEvent = true;
    }

    /** The start-up reads: they only fill in what no event has told yet, as an event may have overtaken them. */
    synchronized void readScreen(boolean on) {
        if (!screenEvent) screenOn = on;
    }

    synchronized void readScreensaver(boolean active) {
        if (!screensaverEvent) screensaver = active;
    }

    /** A ring is about to wake the device: remember what it is doing now. Unknown: nothing to restore. */
    synchronized void ring() {
        answered = false;
        before = screenOn == null ? null : !screenOn ? Before.OFF : Boolean.TRUE.equals(screensaver) ? Before.SCREENSAVER : Before.DASHBOARD;
    }

    synchronized Before before() {
        return before;
    }

    /** Speaker or Answer: someone took the ring. */
    synchronized void answered() {
        answered = true;
    }

    /** The call screen closed: the command that puts the device back as the setting says, or null for none. */
    synchronized String close(String mode) {
        Before was = before;
        before = null;
        if (was == null || NEVER.equals(mode) || (!ALWAYS.equals(mode) && answered)) return null;
        return was == Before.OFF ? "screenOff" : was == Before.SCREENSAVER ? "startScreensaver" : null;
    }
}
