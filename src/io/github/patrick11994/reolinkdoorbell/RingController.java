// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell;

/**
 * The doorbell call as a state machine, free of Android and the SDK so it can be tested on its own.
 * Only a button press (visitor) rings; motion and person detection never do. An untouched ring closes itself after
 * the auto-close time; Speaker or Answer end that countdown for the rest of the call. A viewer opened by hand has
 * no countdown.
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
            return;
        }
        actions.ring();
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
        actions.openViewer(state, false);
        actions.countdown(0, 0);
    }

    /** Ringing: listen only. */
    public synchronized void speaker() {
        if (state != State.RINGING) return;
        state = State.LISTENING;
        stopCountdown();  // someone is paying attention: no auto-close for the rest of the call
        speakerOn = true;
        micOn = false;
        actions.setAudio(true, false);
        actions.updateViewer(state, speakerOn, micOn);
    }

    /** Ringing or listening: talk, with the speaker on too. */
    public synchronized void answer() {
        if (state != State.RINGING && state != State.LISTENING) return;
        if (state == State.RINGING) stopCountdown();
        state = State.IN_CALL;
        speakerOn = true;
        micOn = true;
        actions.setAudio(true, true);
        actions.updateViewer(state, speakerOn, micOn);
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
        if (state == State.RINGING && counting) restartCountdown(clock.now());
    }

    /** Called about once a second: closes a viewer that rang untouched. Listening or a call never closes on its own. */
    public synchronized void tick() {
        if (state != State.RINGING || !counting || autoCloseMillis <= 0) return;
        if (clock.now() - lastActivity >= autoCloseMillis) hangUp();
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
