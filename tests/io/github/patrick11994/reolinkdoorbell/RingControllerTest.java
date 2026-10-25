// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell;

import java.util.ArrayList;
import java.util.List;
import io.github.patrick11994.reolinkdoorbell.RingController.State;

public final class RingControllerTest {
    private static final long START = 1_000_000;

    private static final class Recorder implements RingController.Actions {
        final List<String> calls = new ArrayList<>();
        @Override public void openViewer(State state, boolean rang) { calls.add("open " + state + (rang ? " rang" : "")); }
        @Override public void updateViewer(State state, boolean speakerOn, boolean micOn) { calls.add("update " + state + " speaker=" + speakerOn + " mic=" + micOn); }
        @Override public void closeViewer() { calls.add("close"); }
        @Override public void setAudio(boolean speakerOn, boolean micOn) { calls.add("audio speaker=" + speakerOn + " mic=" + micOn); }
        @Override public void ring() { calls.add("ring"); }
        @Override public void ringTrigger() { calls.add("trigger"); }
        @Override public void countdown(long startedAt, long durationMillis) {
            calls.add(durationMillis <= 0 ? "countdown off" : "countdown " + (startedAt - START) / 1000 + "s+" + durationMillis / 1000 + "s");
        }
        String take() { String all = String.join(", ", calls); calls.clear(); return all; }
    }

    private static long now;

    public static void main(String[] args) {
        onlyRisingEdgeRings();
        repeatPressRingsAgain();
        speakerThenAnswer();
        muteToggles();
        hangUpStopsAudio();
        autoCloseOnlyWhileRinging();
        touchRestartsCountdown();
        speakerStopsCountdown();
        manualOpenDoesNotRing();
        manualOpenNeverAutoCloses();
        pressDuringCallDoesNotRing();
        System.out.println("RingControllerTest passed");
    }

    private static RingController controller(Recorder recorder) {
        now = START;
        return new RingController(recorder, () -> now, 60_000);
    }

    static void onlyRisingEdgeRings() {
        Recorder r = new Recorder();
        RingController c = controller(r);
        c.onVisitor(false);
        check(r.take(), "");
        c.onVisitor(true);
        check(r.take(), "trigger, ring, open RINGING rang, countdown 0s+60s");
        now += 1000;
        c.onVisitor(true);  // the same press, still held
        check(r.take(), "");
        check(c.state(), State.RINGING);
    }

    static void repeatPressRingsAgain() {
        Recorder r = new Recorder();
        RingController c = controller(r);
        c.onVisitor(true);
        r.take();
        now += 15_000;
        c.onVisitor(false);  // the doorbell clears visitor after ~15 s
        now += 2_000;
        c.onVisitor(true);  // a second press rings again and starts the time over; the viewer stays open
        check(r.take(), "trigger, ring, countdown 17s+60s");
        now += 25_000;
        c.onVisitor(true);  // held past the repeat window without a clear: counts as a new press
        check(r.take(), "trigger, ring, countdown 42s+60s");
    }

    static void speakerThenAnswer() {
        Recorder r = new Recorder();
        RingController c = controller(r);
        c.onVisitor(true);
        r.take();
        c.speaker();
        check(r.take(), "countdown off, audio speaker=true mic=false, update LISTENING speaker=true mic=false");
        c.answer();
        check(r.take(), "audio speaker=true mic=true, update IN_CALL speaker=true mic=true");
        c.speaker();  // not available in a call
        check(r.take(), "");
    }

    static void muteToggles() {
        Recorder r = new Recorder();
        RingController c = controller(r);
        c.onVisitor(true);
        c.answer();
        r.take();
        c.toggleMic();
        check(r.take(), "audio speaker=true mic=false, update IN_CALL speaker=true mic=false");
        c.toggleSpeaker();
        check(r.take(), "audio speaker=false mic=false, update IN_CALL speaker=false mic=false");
        c.toggleMic();
        check(r.take(), "audio speaker=false mic=true, update IN_CALL speaker=false mic=true");
    }

    static void hangUpStopsAudio() {
        Recorder r = new Recorder();
        RingController c = controller(r);
        c.onVisitor(true);
        c.answer();
        r.take();
        c.hangUp();
        check(r.take(), "audio speaker=false mic=false, close");
        check(c.state(), State.IDLE);
        c.hangUp();
        check(r.take(), "");
        c.onVisitor(true);  // not a new press: still held since the first one
        check(r.take(), "");
    }

    static void autoCloseOnlyWhileRinging() {
        Recorder r = new Recorder();
        RingController c = controller(r);
        c.onVisitor(true);
        r.take();
        now += 59_000;
        c.tick();
        check(r.take(), "");
        now += 1_000;
        c.tick();
        check(r.take(), "close");

        c.onVisitor(false);
        c.onVisitor(true);
        c.answer();  // a call, even with the microphone muted, never closes on its own
        c.toggleMic();
        r.take();
        now += 10 * 60_000;
        c.tick();
        check(r.take(), "");
        c.hangUp();
        r.take();
    }

    static void touchRestartsCountdown() {
        Recorder r = new Recorder();
        RingController c = controller(r);
        c.onVisitor(true);
        r.take();
        now += 50_000;
        c.touched();
        check(r.take(), "countdown 50s+60s");
        now += 59_000;
        c.tick();
        check(r.take(), "");
        now += 1_000;
        c.tick();
        check(r.take(), "close");
        c.touched();  // a touch after closing does nothing
        check(r.take(), "");
    }

    static void speakerStopsCountdown() {
        Recorder r = new Recorder();
        RingController c = controller(r);
        c.onVisitor(true);
        c.speaker();
        r.take();
        c.touched();  // listening: touches no longer restart anything
        now += 10 * 60_000;
        c.tick();
        check(r.take(), "");
        check(c.state(), State.LISTENING);
        c.answer();  // from listening the countdown is already off
        check(r.take(), "audio speaker=true mic=true, update IN_CALL speaker=true mic=true");
    }

    static void manualOpenDoesNotRing() {
        Recorder r = new Recorder();
        RingController c = controller(r);
        c.open();
        check(r.take(), "open IN_CALL, countdown off");
        check(c.speakerOn() || c.micOn(), false);
    }

    /** Opened by hand: straight into the call with both off, no countdown, never closes on its own. */
    static void manualOpenNeverAutoCloses() {
        Recorder r = new Recorder();
        RingController c = controller(r);
        c.open();
        r.take();
        c.answer();  // nothing to answer
        c.speaker();
        check(r.take(), "");
        c.touched();
        now += 10 * 60_000;
        c.tick();
        check(r.take(), "");
        check(c.state(), State.IN_CALL);
        c.onVisitor(true);  // someone rings while it is only watched: chime, so the visitor is not missed
        check(r.take(), "trigger, ring");
        now += 10 * 60_000;
        c.tick();
        check(r.take(), "");
        c.toggleMic();
        check(r.take(), "audio speaker=false mic=true, update IN_CALL speaker=false mic=true");
    }

    static void pressDuringCallDoesNotRing() {
        Recorder r = new Recorder();
        RingController c = controller(r);
        c.onVisitor(true);
        c.answer();
        r.take();
        now += 30_000;
        c.onVisitor(true);  // a new press while talking: the trigger fires, but no chime over the call
        check(r.take(), "trigger");
        c.hangUp();
        c.open();
        c.toggleSpeaker();  // opened by hand and listening
        r.take();
        now += 30_000;
        c.onVisitor(true);
        check(r.take(), "trigger");
    }

    private static void check(Object actual, Object expected) {
        if (!expected.equals(actual)) throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
    }
}
