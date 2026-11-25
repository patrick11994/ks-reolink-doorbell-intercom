// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell;

/**
 * The doorbell call as a state machine, free of Android and the SDK so it can be tested on its own.
 * Only a button press (visitor) rings; motion and person detection never do. An untouched ring closes itself after
 * the auto-close time; Speaker or Answer end that countdown for the rest of the call. With Start with the speaker on,
 * a ring opens the call listening, and the microphone ends the countdown instead. A viewer opened by hand has no
 * countdown.
 *
 * idle → ringing (button pressed) → listening (Speaker) → in call (Answer) → idle (Hang up / End call)
 * idle → in call with microphone and speaker off (opened by hand) → idle
 */
public final class RingController {
    public enum State { IDLE, RINGING, LISTENING, IN_CALL }

    /** What the controller asks the plugin to do. Called synchronously from the controller's methods. */
    public interface Actions {
        void openViewer(State state, boolean rang);
        void updateViewer(State state, boolean speakerOn, boolean micOn);
        void closeViewer();
        /** Start or stop the door audio (listening) and the microphone (talking). */
        void setAudio(boolean speakerOn, boolean micOn);
        void ring();
        void ringTrigger();
        /** Answer pressed, or Talk first held, after a ring; with Start with the speaker on, the microphone turned on. */
        void answeredTrigger();
        /** Someone took the ring: Speaker or Answer, or the microphone with Start with the speaker on. */
        void taken();
        /** A ring closed itself after the auto-close time, without anyone pressing Speaker or Answer. */
        void missedTrigger();
        /** The auto-close countdown (re)started at startedAt and runs for durationMillis; durationMillis 0 hides it. */
        void countdown(long startedAt, long durationMillis);
    }

    /** A new press counts once the doorbell has reported the previous one over (visitor false), or after this long. */
    static final long REPEAT_MILLIS = 20_000;

    private final Actions actions;
    private final Clock clock;
    private State state = State.IDLE;
    private boolean visitorHeld;
    private long lastRing = Long.MIN_VALUE / 2;
    private long lastActivity;
    private boolean counting;  // the auto-close countdown runs: only for a ring, never for a viewer opened by hand
    private boolean speakerOn;
    private boolean micOn;
    private long autoCloseMillis;
    private long maxCallMillis;
    private boolean speakerAtRing;  // Start with the speaker on: a ring opens the call, speaker on, microphone off
    private long callStarted;  // when listening, talking or watching by hand began

    public interface Clock {
        long now();
    }

    public RingController(Actions actions, Clock clock, long autoCloseMillis) {
        this.actions = actions;
        this.clock = clock;
        this.autoCloseMillis = autoCloseMillis;
    }

    public synchronized State state() {
        return state;
    }

    public synchronized boolean speakerOn() {
        return speakerOn;
    }

    public synchronized boolean micOn() {
        return micOn;
    }

    public synchronized void setAutoCloseMillis(long millis) {
        autoCloseMillis = millis;
    }

    /** The longest a call may last once it is answered or opened, so a forgotten call does not keep the room on the line. */
    public synchronized void setMaxCallMillis(long millis) {
        maxCallMillis = millis;
    }

    /** Start with the speaker on: a ring opens straight into the call, listening, until someone turns the microphone on. */
    public synchronized void setSpeakerAtRing(boolean on) {
        speakerAtRing = on;
    }

    /** The doorbell's visitor flag, from every push. Only the rising edge rings. */
    public synchronized void onVisitor(boolean visitor) {
        long now = clock.now();
        boolean rising = visitor && (!visitorHeld || now - lastRing > REPEAT_MILLIS);
        visitorHeld = visitor;
        if (!rising) return;
        lastRing = now;
        actions.ringTrigger();
        if (state == State.IN_CALL) {
            // Only watching (opened by hand, microphone and speaker off): still chime, so a visitor is not missed.
            if (!micOn && !speakerOn) actions.ring();
            if (counting) restartCountdown(now);  // rung straight into the call, not answered yet: the time starts over
            return;
        }
        actions.ring();
        if (state == State.IDLE && speakerAtRing) {
            // Straight into the call, listening: the time to answer still runs out as on the ring screen.
            state = State.IN_CALL;
            callStarted = now;
            speakerOn = true;
            micOn = false;
            actions.openViewer(state, true);
            actions.setAudio(true, false);
            restartCountdown(now);
            return;
        }
        if (state == State.IDLE) {
            state = State.RINGING;
            actions.openViewer(state, true);
        }
        if (state == State.RINGING) restartCountdown(now);  // a second press while ringing starts the time over
    }

    /**
     * Opened by hand (menu, gesture or Home Assistant button): nobody rang, so nothing to answer. It opens straight
     * into the call with the microphone and speaker off, and without a countdown: whoever opened it closes it.
     */
    public synchronized void open() {
        if (state != State.IDLE) return;
        state = State.IN_CALL;
        counting = false;
        speakerOn = false;
        micOn = false;
        callStarted = clock.now();
        actions.openViewer(state, false);
        actions.countdown(0, 0);
    }

    /** Ringing: listen only. */
    public synchronized void speaker() {
        if (state != State.RINGING) return;
        state = State.LISTENING;
        callStarted = clock.now();
        stopCountdown();  // someone is paying attention: no auto-close for the rest of the call
        speakerOn = true;
        micOn = false;
        actions.setAudio(true, false);
        actions.updateViewer(state, speakerOn, micOn);
        actions.taken();
    }

    /** Ringing or listening: talk, with the speaker on too. */
    public synchronized void answer() {
        if (state != State.RINGING && state != State.LISTENING) return;
        if (state == State.RINGING) {
            stopCountdown();
            callStarted = clock.now();
        }
        state = State.IN_CALL;
        speakerOn = true;
        micOn = true;
        actions.setAudio(true, true);
        actions.updateViewer(state, speakerOn, micOn);
        actions.answeredTrigger();
        actions.taken();
    }

    /**
     * Push to talk held: ringing or listening, it answers; in a call opened by hand, where the microphone is still
     * off, it turns the microphone on (the speaker stays as it is). The plugin mutes the microphone between holds.
     */
    public synchronized void talk() {
        if (state == State.RINGING || state == State.LISTENING) {
            answer();
            return;
        }
        if (state != State.IN_CALL || micOn) return;
        micOn = true;
        actions.setAudio(speakerOn, true);
        actions.updateViewer(state, speakerOn, micOn);
        answeredInCall();
    }

    /** In call: mute or unmute the door audio. */
    public synchronized void toggleSpeaker() {
        if (state != State.IN_CALL && state != State.LISTENING) return;
        speakerOn = !speakerOn;
        actions.setAudio(speakerOn, micOn);
        actions.updateViewer(state, speakerOn, micOn);
    }

    /** In call: mute or unmute the microphone. */
    public synchronized void toggleMic() {
        if (state != State.IN_CALL) return;
        micOn = !micOn;
        actions.setAudio(speakerOn, micOn);
        actions.updateViewer(state, speakerOn, micOn);
        if (micOn) answeredInCall();
    }

    /** The microphone on in a call a ring opened with the speaker on: that answers it, once. */
    private void answeredInCall() {
        if (!counting) return;
        stopCountdown();
        callStarted = clock.now();
        actions.answeredTrigger();
        actions.taken();
    }

    /** Hang up, End call, Back or the viewer closing on its own. */
    public synchronized void hangUp() {
        if (state == State.IDLE) return;
        state = State.IDLE;
        counting = false;
        boolean audio = speakerOn || micOn;
        speakerOn = false;
        micOn = false;
        if (audio) actions.setAudio(false, false);
        actions.closeViewer();
    }

    /** A touch on the viewer while it rings starts the auto-close time over. */
    public synchronized void touched() {
        if (counting) restartCountdown(clock.now());
    }

    /** Called about once a second: closes a viewer that rang untouched, and ends a call that reached its maximum length. */
    public synchronized void tick() {
        if (state == State.IDLE) return;
        // A ring nobody took yet, on the ring screen or straight in the call: closes after the auto-close time.
        if (counting && autoCloseMillis > 0 && clock.now() - lastActivity >= autoCloseMillis) {
            actions.missedTrigger();
            hangUp();
            return;
        }
        // The call time starts once someone answers; until then the auto-close time decides (Off: the call time).
        if (state == State.RINGING || (counting && autoCloseMillis > 0)) return;
        if (maxCallMillis > 0 && clock.now() - callStarted >= maxCallMillis) hangUp();
    }

    private void restartCountdown(long now) {
        counting = true;
        lastActivity = now;
        actions.countdown(now, autoCloseMillis > 0 ? autoCloseMillis : 0);
    }

    private void stopCountdown() {
        if (!counting) return;
        counting = false;
        actions.countdown(0, 0);
    }
}
