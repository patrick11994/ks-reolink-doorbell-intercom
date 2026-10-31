// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell;

import android.content.Context;
import android.view.Surface;
import android.view.View;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;
import io.github.patrick11994.reolinkdoorbell.RingController.State;
import io.github.patrick11994.reolinkdoorbell.baichuan.AlarmEvents;
import io.github.patrick11994.reolinkdoorbell.baichuan.BaichuanClient;
import io.github.patrick11994.reolinkdoorbell.baichuan.BaichuanStream;
import io.github.patrick11994.reolinkdoorbell.media.FallbackStream;
import io.github.patrick11994.reolinkdoorbell.media.MediaStream;
import io.github.patrick11994.reolinkdoorbell.media.StreamHub;
import io.github.patrick11994.reolinkdoorbell.baichuan.BaichuanTalk;
import io.github.patrick11994.reolinkdoorbell.talk.Backchannel;
import io.github.patrick11994.reolinkdoorbell.talk.FallbackTalk;
import io.github.patrick11994.reolinkdoorbell.talk.TalkChannel;

/**
 * Reolink doorbell for Kiosk Satellite: holds a Baichuan session to the doorbell, and on a button press wakes the
 * panel, rings and opens the full-screen viewer. Everything else is in RingController, DoorbellView and the clients.
 */
public final class ReolinkPlugin implements KioskPlugin, RingController.Actions {
    static final String OVERLAY = "doorbell";
    private static final String ENTITY_EVENT = "ks.ha.entity.";

    private volatile PluginHost host;
    private volatile boolean stopped;  // a ring that raced stop() must not start a stream after stop released it
    private volatile Settings settings;
    private volatile Texts texts = Texts.of("Automatic");
    private volatile RingController controller;
    private volatile DoorbellView view;
    private volatile BaichuanClient events;  // written under this
    private ScheduledExecutorService ticker;
    private volatile WakeWordGuard wakeWord;
    private volatile MusicPause music;
    // The call's media, all guarded by this.
    private MediaStream stream;
    private VideoRenderer videoRenderer;
    private Object videoOwner;  // the view whose surface the decoder draws on
    private Surface videoSurface;  // that surface: released after its decoder, never before
    // Each stream (a call's, or a reconnect's) and each stopMedia count this up, so a reconnect that is due for an
    // older stream, or for a call that has ended, does nothing.
    private int streamGeneration;
    private int reconnects;  // in a row, since a stream last delivered
    private int decoderRestarts;  // in this call
    private int talkGeneration;  // the same for talk: each channel opened, and each one dropped on purpose
    private int talkRetries;  // in a row, since talk last opened
    private static final int MAX_DECODER_RESTARTS = 2;
    private AudioRenderer audioRenderer;
    private TalkChannel talk;
    private BaichuanTalk baichuanTalk;  // the Baichuan part of talk, for its encoder load
    // ONVIF: talk opens at the ring and stays; Baichuan only while the microphone is on. Set from talk's own thread
    // when Automatic falls back, so volatile.
    private volatile boolean talkFromRing;
    // Automatic talk found that this doorbell refuses Baichuan talk: ONVIF straight away, until the settings change,
    // instead of losing 1-2.5 s on each talk start.
    private volatile boolean baichuanTalkRefused;
    private MicCapture mic;
    private CallVolume callVolume;
    // The slider's level: updateSettings stores it for later sessions but does not call configure, so settings keeps
    // the level from the start. Everything reads this instead.
    private volatile int liveCallVolume;
    private CallMode callMode;
    private long ringAt;
    // Push to talk: the call's ducking, and when the door comes back up after the button lets go (guarded by this).
    private boolean talking;
    private ScheduledFuture<?> unduck;
    // Taken when the viewer opens: a Talk mode changed mid-call must not split the view from the microphone.
    private volatile boolean callPushToTalk;
    /** How long the door stays down after push to talk lets go: our own voice comes back from the door's microphone
     * about 1.4 s after we spoke over Baichuan (1 s to the door, 0.4 s back), 2.4 s over ONVIF (2 s to the door), and
     * should not play at full volume. */
    private static final long DUCK_TAIL_MILLIS = 1500, ONVIF_DUCK_TAIL_MILLIS = 2700;
    private volatile long countdownStart, countdownMillis;  // shown by the viewer, also one created later
    // The cards' Home Assistant entities: followed only while the viewer is open.
    private final Set<String> followed = new HashSet<>();  // guarded by itself
    private final Map<String, Map<?, ?>> snapshots = new ConcurrentHashMap<>();
    private volatile boolean viewerOpen;

    @Override public void start(PluginHost host, Map<String, Object> values) {
        stopped = false;
        this.host = host;
        Texts.setAssets(host::openAsset);  // the language files under assets/i18n
        settings = new Settings(values);
        baichuanTalkRefused = false;
        texts = Texts.of(settings.language);
        liveCallVolume = settings.callVolume;
        // Monotonic: a clock set after boot (no RTC battery, NTP) must not end a ring at once or stretch a call.
        controller = new RingController(this, () -> System.nanoTime() / 1_000_000, settings.autoCloseMillis);
        controller.setMaxCallMillis(settings.maxCallMillis);
        wakeWord = new WakeWordGuard(new WakeWordGuard.Host() {
            @Override public void command(String name, Map<String, Object> arguments, WakeWordGuard.Result result) {
                PluginHost h = ReolinkPlugin.this.host;
                if (h == null) {
                    result.done(false, null);
                    return;
                }
                try {
                    h.executeCommand(name, arguments, (ok, data, error) -> result.done(ok, data));
                } catch (RuntimeException unavailable) {
                    result.done(false, null);  // an older Kiosk Satellite, or the session ended
                }
            }

            @Override public void remember(boolean mutedByUs) {
                PluginHost h = ReolinkPlugin.this.host;
                if (h == null) return;
                try {
                    h.updateSettings(Collections.<String, Object>singletonMap("wakeWordRestore", mutedByUs));
                } catch (RuntimeException | LinkageError older) {
                    // Without it, only a crash mid-call could leave the wake word muted.
                }
            }
        });
        wakeWord.restore(settings.wakeWordRestore);
        music = new MusicPause(new MusicPause.Host() {
            @Override public void command(String name, Map<String, Object> arguments, MusicPause.Result result) {
                PluginHost h = ReolinkPlugin.this.host;
                if (h == null) {
                    result.done(false, null);
                    return;
                }
                try {
                    h.executeCommand(name, arguments, (ok, data, error) -> result.done(ok, data));
                } catch (RuntimeException unavailable) {
                    result.done(false, null);  // an older Kiosk Satellite, or the session ended
                }
            }

            @Override public void remember(boolean pausedByUs) {
                PluginHost h = ReolinkPlugin.this.host;
                if (h == null) return;
                try {
                    h.updateSettings(Collections.<String, Object>singletonMap("musicRestore", pausedByUs));
                } catch (RuntimeException | LinkageError older) {
                    // Without it, only a stop mid-call could leave the music paused.
                }
            }
        });
        music.restore(settings.musicRestore);
        ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "reolink-ticker");
            thread.setDaemon(true);
            return thread;
        });
        ticker.scheduleWithFixedDelay(() -> {
            RingController c = controller;
            if (c != null) c.tick();
        }, 1, 1, TimeUnit.SECONDS);
        publishText("last_ring", texts.lastRingReading(), texts.noneYet());
        connect();
    }

    @Override public void configure(Map<String, Object> values) {
        Settings previous = settings;
        settings = new Settings(values);
        baichuanTalkRefused = false;
        texts = Texts.of(settings.language);
        liveCallVolume = settings.callVolume;
        RingController c = controller;
        if (c != null) {
            c.setAutoCloseMillis(settings.autoCloseMillis);
            c.setMaxCallMillis(settings.maxCallMillis);
        }
        if (previous == null || !previous.sameConnection(settings)) connect();
        if (viewerOpen) {
            followCards(settings.cards);
            DoorbellView v = view;
            List<Cards.Card> cards = settings.cards;
            if (v != null) v.post(() -> v.setCards(cards));
        }
    }

    @Override public void execute(String command, Map<String, Object> arguments) {
        RingController c = controller;
        if (c == null) return;
        switch (command) {
            case "show": c.open(); break;
            case "testRing": c.onVisitor(false); c.onVisitor(true); break;
            case "dismiss": c.hangUp(); break;
            default: throw new IllegalArgumentException("Unknown command " + command);
        }
    }

    @Override public void onEvent(String event, Map<String, Object> payload) {
        if ("settings.button".equals(event) && "check".equals(payload.get("id"))) {
            check(String.valueOf(payload.get("run")));
            return;
        }
        if (event.startsWith(ENTITY_EVENT)) {
            String entityId = event.substring(ENTITY_EVENT.length());
            synchronized (followed) {
                if (!followed.contains(entityId)) return;  // late, for a card no longer shown: it would show next call
                snapshots.put(entityId, payload);
            }
            DoorbellView v = view;
            if (v != null) v.post(() -> v.entityChanged(entityId, payload));
            return;
        }
        if ("overlay.closed".equals(event) && OVERLAY.equals(payload.get("key"))) {
            RingController c = controller;
            if (c != null) c.hangUp();  // Back closed the viewer
        }
    }

    @Override public void stop() {
        // The host is revoked before stop: release resources without calling it.
        stopped = true;
        host = null;
        BaichuanClient client = events;
        events = null;
        if (client != null) client.stop();
        ScheduledExecutorService t = ticker;
        ticker = null;
        if (t != null) t.shutdownNow();
        controller = null;
        view = null;
        viewerOpen = false;
        synchronized (followed) {
            followed.clear();  // the session's subscriptions end with it
        }
        snapshots.clear();
        stopMedia();
    }

    private synchronized void connect() {
        BaichuanClient previous = events;
        events = null;
        if (previous != null) previous.stop();
        Settings s = settings;
        if (!s.configured()) {
            status(texts.enterSettings(), true);
            publishText("connection", texts.connectionReading(), texts.notConfigured());
            return;
        }
        BaichuanClient[] self = new BaichuanClient[1];  // a replaced client can take up to its timeout to stop: ignore it
        BaichuanClient client = new BaichuanClient(s.host, 9000, s.username, s.password, new BaichuanClient.Listener() {
            private String logged = "";  // each change once: a doorbell that is away fails every few seconds

            @Override public void onConnection(boolean connected, String detail) {
                if (events != self[0]) return;
                Texts t = texts;
                boolean connecting = "connecting".equals(detail);
                // Retrying after a drop: keep saying what is wrong, rather than flipping to "connecting" every few seconds.
                if (connecting && !logged.isEmpty() && !logged.equals("connected")) return;
                if (!connecting && !detail.equals(logged)) log("events: " + detail);
                if (!connecting) logged = detail;
                String shown = connected ? t.connected() : connecting ? t.connectingState() : ConnectionCheck.problem(detail, t);
                publishText("connection", t.connectionReading(), shown);
                status(connected ? t.connectedTo(s.name(t)) : t.doorbellProblem(shown), !connected && !connecting);
            }

            @Override public void onAlarm(AlarmEvents.Event event) {
                RingController c = controller;
                if (c == null || event.channel != 0 || events != self[0]) return;
                if (event.visitor()) publishText("last_ring", texts.lastRingReading(), new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date()));
                c.onVisitor(event.visitor());
            }
        });
        self[0] = client;
        events = client;
        client.start();
    }

    // RingController.Actions

    @Override public void openViewer(State state, boolean rang) {
        PluginHost h = host;
        if (h == null) return;
        if (settings.wakeScreen) {
            command(h, "stopScreensaver");
            command(h, "screenOn");
        }
        final Texts t = texts;
        final String name = settings.name(t);
        log("viewer: open (" + (rang ? "ring" : "by hand") + ")");
        stopMedia();  // a fresh call: nothing from an earlier one may linger
        callPushToTalk = settings.pushToTalk;
        startStream();
        viewerOpen = true;
        followCards(settings.cards);
        h.showOverlay(OVERLAY, OverlaySpec.fullScreen().onTop(true).closeOnBack(true), new OverlayFactory() {
            @Override public View create(Context context, KsTheme theme) {
                RingController c = controller;
                State current = c == null ? state : c.state();
                synchronized (ReolinkPlugin.this) {
                    if (callVolume == null) callVolume = new CallVolume(context);
                    if (callMode == null) callMode = new CallMode(context);
                }
                DoorbellView created = new DoorbellView(context, theme, t, name, current, liveCallVolume, new DoorbellView.Callback() {
                    @Override public void hangUp() { RingController c = controller; if (c != null) c.hangUp(); }
                    @Override public void speaker() { RingController c = controller; if (c != null) c.speaker(); }
                    @Override public void answer() { RingController c = controller; if (c != null) c.answer(); }
                    @Override public void toggleSpeaker() { RingController c = controller; if (c != null) c.toggleSpeaker(); }
                    @Override public void toggleMic() { RingController c = controller; if (c != null) c.toggleMic(); }
                    @Override public void touched() { RingController c = controller; if (c != null) c.touched(); }
                    @Override public void surfaceReady(Surface surface) { attachVideo(this, surface); }
                    @Override public void surfaceGone() { detachVideo(this); }
                    @Override public void volumeChanged(int percent, boolean done) { setCallVolume(percent, done); }
                    @Override public void cardPressed(Cards.Card card) { runCard(card); }
                    @Override public void holdToTalk(boolean pressed) { ReolinkPlugin.this.holdToTalk(pressed); }
                });
                view = created;  // first, so a state arriving during the replay below reaches this view, not the old one
                for (Map.Entry<String, Map<?, ?>> known : snapshots.entrySet()) created.entityChanged(known.getKey(), known.getValue());
                created.setCards(settings.cards);
                created.setPushToTalk(callPushToTalk);
                if (c != null) created.update(c.state(), c.speakerOn(), c.micOn());
                created.setCountdown(countdownStart, countdownMillis);
                return created;
            }

            @Override public void onThemeChanged(View changed, KsTheme theme) {
                if (changed instanceof DoorbellView) ((DoorbellView) changed).applyTheme(theme);
            }

            @Override public void onDestroy(View destroyed) {
                if (view == destroyed) view = null;
            }
        });
    }

    @Override public void updateViewer(State state, boolean speakerOn, boolean micOn) {
        DoorbellView v = view;
        if (v != null) v.post(() -> v.update(state, speakerOn, micOn));
    }

    @Override public void countdown(long startedAt, long durationMillis) {
        countdownStart = startedAt;
        countdownMillis = durationMillis;
        DoorbellView v = view;
        if (v != null) v.post(() -> v.setCountdown(startedAt, durationMillis));
    }

    @Override public void closeViewer() {
        log("viewer: close");
        MusicPause m = music;
        if (m != null) m.setActive(false);
        PluginHost h = host;
        if (h != null) h.hideOverlay(OVERLAY);
        viewerOpen = false;
        followCards(Collections.<Cards.Card>emptyList());
        stopMedia();
    }

    // The cards

    /** Follows exactly the cards' entities; KS sends each one's current state first, then every change. */
    private void followCards(List<Cards.Card> cards) {
        PluginHost h = host;
        Set<String> wanted = new HashSet<>();
        for (Cards.Card card : cards) if (card.hasEntity()) wanted.add(card.entityId);
        synchronized (followed) {
            for (String entityId : new HashSet<>(followed)) {
                if (wanted.contains(entityId)) continue;
                followed.remove(entityId);
                snapshots.remove(entityId);
                try {
                    if (h != null) h.unsubscribe("ha.entity." + entityId);
                } catch (RuntimeException gone) {
                    // The session ended; its subscriptions went with it.
                }
            }
            for (String entityId : wanted) {
                if (followed.contains(entityId) || h == null) continue;
                try {
                    h.subscribe("ha.entity." + entityId);
                    followed.add(entityId);
                } catch (RuntimeException refused) {
                    log("card: cannot follow " + entityId + ": " + refused.getMessage());
                }
            }
        }
    }

    /** Runs the action the user chose for the card, and shows on the card how it went. */
    private void runCard(Cards.Card card) {
        PluginHost h = host;
        DoorbellView v = view;
        if (h == null || !card.action) return;
        Texts t = texts;
        if (v != null) v.cardFeedback(card, DoorbellView.RUNNING);
        try {
            h.runAction(card.actionPath(), (ok, data, error) -> {
                log("card: " + (card.label.isEmpty() ? card.actionPath() : card.label) + (ok ? " ran" : " failed: " + error));
                DoorbellView now = view;
                // With a state to follow, the card keeps showing it is running until the new state arrives.
                if (ok && card.hasEntity()) return;
                String shown = ok ? t.cardDone() : t.cardFailed();
                if (now != null) now.post(() -> now.cardFeedback(card, shown));
            });
        } catch (UnsupportedOperationException older) {
            log("card: this Kiosk Satellite version cannot run actions");
            if (v != null) v.cardFeedback(card, t.cardsNeedNewerKs());
        } catch (RuntimeException refused) {
            log("card: " + refused.getMessage());
            if (v != null) v.cardFeedback(card, t.cardFailed());
        }
    }

    @Override public synchronized void setAudio(boolean speakerOn, boolean micOn) {
        WakeWordGuard guard = wakeWord;
        if (guard != null) guard.setActive(speakerOn || micOn);
        MusicPause m = music;
        if (m != null && (speakerOn || micOn) && !settings.musicPause.equals("Off")) m.setActive(true);
        log("audio: speaker " + speakerOn + ", microphone " + micOn + (stream == null ? ", no stream" : ""));
        // Before the track and the recorder open, so both start in call mode; left again only when the call ends.
        if ((speakerOn || micOn) && settings.callMode && callMode != null) {
            String entered = callMode.enter();
            if (entered != null) log("audio: " + entered);
        }
        if (speakerOn && audioRenderer == null && stream != null) {
            audioRenderer = new AudioRenderer(message -> {
                showNotice(texts.doorbellSoundStopped());
                log(message);
            });
            applyCallVolume(liveCallVolume, true);
            if (talking) audioRenderer.setDuck(Loudness.DUCK);
            stream.attachAudio(audioRenderer);
        } else if (!speakerOn && audioRenderer != null) {
            if (stream != null) stream.attachAudio(null);
            releaseLater(audioRenderer::release);
            audioRenderer = null;
        }
        if (micOn && mic == null && stream != null) {
            if (talk == null) {
                talk = openTalk(settings);
                talk.start();
            }
            if (callPushToTalk) log("microphone: push to talk");
            mic = openMic();
        } else if (!micOn && mic != null) {
            releaseLater(mic::release);
            mic = null;
            if (talk != null && talkFromRing) {
                talk.clear();
            } else if (talk != null) {
                // Gives the doorbell's speaker back (TalkReset), for its own sounds and a Quick Reply.
                logTalk();
                releaseLater(talk::stop);
                talk = null;
                baichuanTalk = null;
                talkGeneration++;  // dropped on purpose: nothing to reopen
            }
        }
    }

    /** The microphone, into the current talk channel; muted in push to talk until the button is held. */
    private MicCapture openMic() {
        return new MicCapture(talk, settings.microphone, settings.micRate, callPushToTalk && !talking, state -> {
            log("microphone: " + state);
            boolean live = state.startsWith("live");
            voiceGoesOut(live);
            if (!live) showNotice(texts.noMicrophone());
        });
    }

    /** The settings page's Check button: runs off the plugin worker and reports through the setup run. */
    private void check(String run) {
        PluginHost h = host;
        if (h == null) return;
        Settings s = settings;
        Texts t = texts;
        Thread thread = new Thread(() -> {
            try {
                h.setupResult(run, "running", t.checking());
                ConnectionCheck.Result result = ConnectionCheck.run(s.host, 9000, 554, s.streamPath, s.talkPath, s.username, s.password, t);
                h.setupResult(run, result.ok ? "success" : "error", result.message);
            } catch (RuntimeException | LinkageError gone) {
                // The run or the session ended meanwhile.
            }
        }, "reolink-check");
        thread.setDaemon(true);
        thread.start();
    }

    private static void releaseLater(Runnable release) {
        Thread thread = new Thread(release, "reolink-cleanup");
        thread.setDaemon(true);
        thread.start();
    }

    // The call's media

    /** Starts the doorbell stream at the ring, before the viewer exists, so the first picture is ready sooner. */
    private synchronized void startStream() {
        Settings s = settings;
        if (stream != null || !s.configured() || stopped) return;
        ringAt = System.nanoTime() / 1_000_000;
        log("media: start stream " + s.streamPath + " over " + (s.videoPath.equals("RTSP") ? "RTSP" : "Baichuan") + " (Video over: " + s.videoPath + ")");
        publishText("first_frame", texts.firstPictureReading(), texts.waiting());
        reconnects = 0;
        decoderRestarts = 0;
        stream = openStream(s, ++streamGeneration);
        stream.start();
        // ONVIF talk opens now and streams silence, so the doorbell's 2 s playout buffer is full by the time anyone
        // presses Answer (it drops audio while that buffer fills). Baichuan talk sets up in about 0.2 s, so it waits
        // for the microphone: an open talk session holds the doorbell's speaker, which would cut the doorbell's own
        // ring tone and refuse a Quick Reply.
        talkFromRing = s.talkPath.equals("ONVIF") || (s.talkPath.equals("Automatic") && baichuanTalkRefused);
        if (talkFromRing) {
            talk = openTalk(s);
            talk.start();
        }
    }

    /**
     * The video path from the Video over setting: Baichuan (the Reolink app's way, a fresh keyframe on request), RTSP,
     * or Automatic: Baichuan, and RTSP if Baichuan fails before the first picture.
     */
    private MediaStream openStream(Settings s, int generation) {
        if (s.videoPath.equals("RTSP")) return rtspStream(s, generation);
        final FallbackStream[] fallback = new FallbackStream[1];
        BaichuanStream baichuan = new BaichuanStream(s.host, 9000, s.username, s.password, s.streamPath.endsWith("_sub"), state -> {
            if (!state.equals("connecting") && !state.equals("streaming")) log("video: " + state + " (Baichuan)");
            if (state.equals("streaming")) streamDelivers(generation);
            FallbackStream f = fallback[0];
            if (state.startsWith("stream failed") && f != null && !f.usingFallback() && !state.contains("H.265")) {
                // Reported on the Baichuan stream's own thread; switching stops that stream and waits for it.
                releaseLater(() -> {
                    if (f.primaryFailed()) log("video: falling back to RTSP");
                    else streamLost(generation);
                });
            } else if (state.startsWith("stream failed") || state.startsWith("stream ended")) {
                showMessage(videoProblem(state));
                if (!state.contains("H.265")) streamLost(generation);  // trying again does not change the codec
            }
        });
        if (s.videoPath.equals("Baichuan")) return baichuan;
        fallback[0] = new FallbackStream(baichuan, () -> rtspStream(s, generation));
        return fallback[0];
    }

    private MediaStream rtspStream(Settings s, int generation) {
        return new StreamHub(s.host, 554, s.streamPath, s.username, s.password, state -> {
            if (!state.equals("connecting") && !state.equals("streaming")) log("video: " + state);
            if (state.equals("streaming")) streamDelivers(generation);
            if (state.startsWith("stream failed") || state.startsWith("stream ended")) {
                showMessage(videoProblem(state));
                if (!state.contains("H.265")) streamLost(generation);  // trying again does not change the codec
            }
        });
    }

    private synchronized void streamDelivers(int generation) {
        if (generation == streamGeneration) reconnects = 0;
    }

    /**
     * The call's stream ended or failed (the doorbell rebooted, a network hiccup): while the viewer is open, open it
     * again, sooner the first time, at most every 5 s. The viewer's decoder and sound carry on with the new stream, and
     * newestIFrame brings a fresh picture about 0.3 s after it connects.
     */
    private synchronized void streamLost(int generation) {
        if (generation != streamGeneration || stopped || !viewerOpen) return;
        BaichuanClient e = events;
        if (e != null) e.checkNow();  // the doorbell may be gone altogether: let the ring session find out now too
        ScheduledExecutorService t = ticker;
        if (t == null) return;
        long delay = Math.min(5_000, 1_000L << Math.min(reconnects, 3));
        reconnects++;
        log("media: reopening the stream in " + delay / 1000 + " s");
        t.schedule(() -> reopenStream(generation), delay, TimeUnit.MILLISECONDS);
    }

    private synchronized void reopenStream(int generation) {
        if (generation != streamGeneration || stopped || !viewerOpen || stream == null) return;
        final MediaStream old = stream;
        stream = openStream(settings, ++streamGeneration);
        if (videoRenderer != null) stream.attachVideo(videoRenderer);
        if (audioRenderer != null) stream.attachAudio(audioRenderer);
        stream.start();
        releaseLater(old::stop);  // it may still say goodbye to the doorbell: off this thread
    }

    /** What the viewer says about a stream state; the protocol's own words only go to the log. */
    private String videoProblem(String state) {
        if (state.contains("H.265")) return texts.videoH265();
        return state.startsWith("stream ended") ? texts.videoStopped() : texts.noVideo();
    }

    /**
     * The talk path from the Talk setting: Baichuan (about 1 s to the door, 16 kHz), the ONVIF backchannel (about
     * 2 s, 8 kHz, for doorbells that do not talk over Baichuan), or Automatic: Baichuan, and ONVIF if it is refused,
     * from then on straight away.
     */
    private TalkChannel openTalk(Settings s) {
        baichuanTalk = null;
        final int generation = ++talkGeneration;
        if (s.talkPath.equals("ONVIF") || (s.talkPath.equals("Automatic") && baichuanTalkRefused)) return onvifTalk(s, generation);
        final FallbackTalk[] fallback = new FallbackTalk[1];
        BaichuanTalk baichuan = new BaichuanTalk(s.host, 9000, s.username, s.password, state -> {
            if (!state.equals("connecting") && !state.equals("open")) log("talk: " + state);
            else if (state.equals("open")) log("talk: open over Baichuan");
            if (state.equals("open")) talkOpened(generation);
            if (state.startsWith("talk refused") && fallback[0] != null) {
                // 422 even after a reset: someone else is talking, which passes. Anything else is the doorbell's way.
                boolean lasting = !state.contains("answered 422");
                log("talk: falling back to the ONVIF backchannel" + (lasting ? ", from now on" : " for this call"));
                if (lasting) {
                    baichuanTalkRefused = true;
                    talkFromRing = true;  // ONVIF talk now stays open for the call, as with the ONVIF setting
                }
                fallback[0].primaryRefused();
            } else if (state.startsWith("talk refused")) {
                voiceGoesOut(false);  // the doorbell will refuse again: not retried
                showNotice(texts.voiceNotThrough());
            } else if (state.startsWith("talk failed") || state.startsWith("talk ended")) {
                talkLost(generation);
            }
        });
        baichuanTalk = baichuan;
        if (s.talkPath.equals("Baichuan")) return baichuan;
        fallback[0] = new FallbackTalk(baichuan, () -> onvifTalk(s, generation));
        return fallback[0];
    }

    private TalkChannel onvifTalk(Settings s, int generation) {
        return new Backchannel(s.host, 554, s.streamPath, s.username, s.password, state -> {
            if (!state.equals("connecting") && !state.equals("open")) log("talk: " + state);
            else if (state.equals("open")) log("talk: open over ONVIF");
            if (state.equals("open")) talkOpened(generation);
            if (state.startsWith("talk failed") || state.startsWith("talk ended")) talkLost(generation);
        });
    }

    private synchronized void talkOpened(int generation) {
        if (generation == talkGeneration) talkRetries = 0;
        voiceGoesOut(true);
    }

    /**
     * Talk failed or dropped while it is still wanted (the microphone is on, or ONVIF talk that stays open for the
     * call): say so, and open it again the same way as the stream, with the microphone moved over to the new channel.
     */
    private synchronized void talkLost(int generation) {
        voiceGoesOut(false);
        showNotice(texts.voiceNotThrough());
        if (generation != talkGeneration || stopped || !viewerOpen || talk == null || (mic == null && !talkFromRing)) return;
        ScheduledExecutorService t = ticker;
        if (t == null) return;
        long delay = Math.min(5_000, 1_000L << Math.min(talkRetries, 3));
        talkRetries++;
        log("talk: reopening in " + delay / 1000 + " s");
        t.schedule(() -> reopenTalk(generation), delay, TimeUnit.MILLISECONDS);
    }

    private synchronized void reopenTalk(int generation) {
        if (generation != talkGeneration || stopped || !viewerOpen || talk == null || (mic == null && !talkFromRing)) return;
        final TalkChannel old = talk;
        logTalk();
        talk = openTalk(settings);
        talk.start();
        releaseLater(old::stop);
        if (mic != null) {
            final MicCapture oldMic = mic;
            releaseLater(oldMic::release);
            mic = openMic();
        }
    }

    /**
     * KS may build a new viewer before it removes the old one, so each viewer only ever detaches its own decoder:
     * owner is the viewer's callback.
     */
    private synchronized void attachVideo(Object owner, Surface surface) {
        log("viewer: surface ready" + (stream == null ? ", no stream" : ""));
        // The old viewer's decoder: released off this (main) thread, which waiting for it would hold up, together
        // with the new viewer's first picture and every tap. Its surface lives until its own viewer goes.
        final VideoRenderer old = videoRenderer;
        final Surface oldSurface = videoSurface;
        videoOwner = owner;
        videoSurface = surface;
        videoRenderer = newRenderer(surface, false);
        if (stream != null) stream.attachVideo(videoRenderer);
        if (old != null || oldSurface != null) releaseLater(() -> release(old, oldSurface));
    }

    /** A decoder for the viewer's surface; a replacement for one that failed does not report a first picture again. */
    private VideoRenderer newRenderer(Surface surface, boolean replacement) {
        final VideoRenderer[] self = new VideoRenderer[1];
        self[0] = new VideoRenderer(surface, new VideoRenderer.Listener() {
            @Override public void onVideoSize(int width, int height) {
                DoorbellView v = view;
                if (v != null) v.post(() -> v.setVideoSize(width, height));
            }

            @Override public void onVideoStats(long firstFrameMillis, double decodeMillis) {
                if (firstFrameMillis >= 0 && !replacement) {
                    long sinceRing = System.nanoTime() / 1_000_000 - ringAt;
                    publishText("first_frame", texts.firstPictureReading(), texts.afterRing(sinceRing));
                }
            }

            @Override public void onVideoError(String message) {
                // null: no keyframe yet. Otherwise the decoder failed, which the log explains.
                showMessage(message == null ? texts.noPictureYet() : texts.videoNotShown());
                log(message == null ? texts.noPictureYet() : message);
                if (message != null) decoderFailed(self[0]);
            }

            @Override public void onVideoLog(String message) {
                log(message);
            }
        });
        return self[0];
    }

    /**
     * The decoder stopped with an error mid-call (a transient codec fault, a stream glitch it could not take): a new
     * one on the same surface, from the stream's last keyframe, at most twice a call. The new one can only connect to
     * the surface once the failed one has let go of it, so it is made after that release, off this thread.
     */
    private synchronized void decoderFailed(VideoRenderer failed) {
        if (failed == null || failed != videoRenderer || videoSurface == null || decoderRestarts >= MAX_DECODER_RESTARTS) return;
        decoderRestarts++;
        final Surface surface = videoSurface;
        log("video decoder: starting a new one");
        releaseLater(() -> {
            failed.release();
            synchronized (ReolinkPlugin.this) {
                if (videoRenderer != failed || videoSurface != surface) return;  // the viewer or the call went meanwhile
                videoRenderer = newRenderer(surface, true);
                if (stream != null) stream.attachVideo(videoRenderer);
            }
        });
    }

    /** A decoder, then the surface it drew on: the other way round the decoder writes into a surface that is gone. */
    private static void release(VideoRenderer renderer, Surface surface) {
        if (renderer != null) renderer.release();
        if (surface != null) surface.release();
    }

    private synchronized void detachVideo(Object owner) {
        log("viewer: surface gone" + (owner != videoOwner ? " (an older viewer)" : ""));
        if (owner != videoOwner) return;  // an old viewer going away after a new one took over
        videoOwner = null;
        if (stream != null) stream.attachVideo(null);
        release(videoRenderer, videoSurface);  // now: the surface's texture is freed when this callback returns
        videoRenderer = null;
        videoSurface = null;
    }

    /**
     * Forgets the call's media at once and releases it on a background thread: Hang up runs on the main thread, where
     * Android refuses network traffic (the RTSP TEARDOWN) and where waiting for a decoder to stop would stall the UI.
     */
    private synchronized void stopMedia() {
        streamGeneration++;  // a reconnect still due belongs to this call: it must not reopen anything
        talkGeneration++;
        talking = false;
        if (unduck != null) unduck.cancel(false);
        unduck = null;
        if (stream == null && videoRenderer == null && audioRenderer == null && talk == null && mic == null) {
            if (callVolume != null) callVolume.restore();
            if (callMode != null) callMode.leave();
            return;
        }
        log("media: stop (stream " + (stream != null) + ", video " + (videoRenderer != null) + ", audio " + (audioRenderer != null)
            + ", talk " + (talk != null) + ", microphone " + (mic != null) + ")");
        logTalk();
        final MediaStream oldStream = stream;
        final VideoRenderer oldVideo = videoRenderer;
        final Surface oldSurface = videoSurface;
        final AudioRenderer oldAudio = audioRenderer;
        final TalkChannel oldTalk = talk;
        final MicCapture oldMic = mic;
        talk = null;
        baichuanTalk = null;
        mic = null;
        stream = null;
        videoRenderer = null;
        videoSurface = null;
        videoOwner = null;
        audioRenderer = null;
        if (callVolume != null) callVolume.restore();
        if (callMode != null) callMode.leave();  // here, not on the cleanup thread, so it cannot undo a next call's
        Thread cleanup = new Thread(() -> {
            // The decoder first: it needs no network, and a quick next ring may need it back (some panels have few).
            release(oldVideo, oldSurface);
            try {
                if (oldStream != null) oldStream.stop();
            } catch (RuntimeException ignored) {
                // Going away anyway.
            }
            if (oldMic != null) oldMic.release();
            if (oldTalk != null) oldTalk.stop();
            if (oldAudio != null) oldAudio.release();
        }, "reolink-cleanup");
        cleanup.setDaemon(true);
        cleanup.start();
    }

    private void logTalk() {
        BaichuanTalk bt = baichuanTalk;
        if (bt != null && bt.blocksSent() > 0) log(String.format(Locale.ROOT, "talk: %d Baichuan blocks sent, encoder load %.1f%%", bt.blocksSent(), 100 * bt.encodeLoad()));
    }

    private void setCallVolume(int percent, boolean done) {
        liveCallVolume = percent;
        applyCallVolume(percent, done);
        if (!done) return;
        PluginHost h = host;
        if (h == null) return;
        try {
            h.updateSettings(Collections.<String, Object>singletonMap("callVolume", (double) percent));
        } catch (RuntimeException | LinkageError older) {
            // Older Kiosk Satellite: the level holds for this call only.
        }
    }

    /**
     * Android's call volume where it can be changed; where it cannot (a fixed-volume device, such as the Echo Show
     * image, or one that keeps its own level), the door's own playback is turned down instead.
     */
    private synchronized void applyCallVolume(int percent, boolean report) {
        CallVolume volume = callVolume;
        String refused = volume == null ? "unavailable" : volume.apply(percent);
        if (audioRenderer != null) audioRenderer.setGain(refused == null ? 1f : Loudness.remaining(percent, volume == null ? 100 : volume.reachedPercent()));
        if (report) {
            log(refused == null ? "audio: call volume " + percent + "%"
                : "audio: call volume " + percent + "%, played by the plugin: Android's call volume " + refused);
        }
    }

    /**
     * Push to talk: the door hears you only while the button is held, and you hear the door turned down meanwhile.
     * Held while it rings, it answers first; held in a viewer opened by hand, it turns the microphone on first. That
     * runs outside this lock: the controller calls back into it.
     */
    private void holdToTalk(boolean pressed) {
        RingController c = controller;
        if (pressed && c != null && !c.micOn()) {
            synchronized (this) {
                talking = true;  // so the microphone that opens starts unmuted, and the door ducked
            }
            c.talk();
        }
        pushToTalk(pressed);
    }

    private synchronized void pushToTalk(boolean pressed) {
        talking = pressed;
        if (mic != null) mic.setMuted(!pressed);
        if (unduck != null) unduck.cancel(false);
        unduck = null;
        if (pressed) {
            if (audioRenderer != null) audioRenderer.setDuck(Loudness.DUCK);
            return;
        }
        ScheduledExecutorService t = ticker;
        if (t == null) return;
        unduck = t.schedule(() -> {
            synchronized (ReolinkPlugin.this) {
                if (!talking && audioRenderer != null) audioRenderer.setDuck(1f);
            }
        }, talkOverOnvif() ? ONVIF_DUCK_TAIL_MILLIS : DUCK_TAIL_MILLIS, TimeUnit.MILLISECONDS);
    }

    /** Whether the voice goes over the ONVIF backchannel, by setting or because Automatic fell back. */
    private synchronized boolean talkOverOnvif() {
        TalkChannel t = talk;
        return t instanceof Backchannel || (t instanceof FallbackTalk && ((FallbackTalk) t).usingFallback());
    }

    /** Why there is no picture, or why it stopped: over the picture area. */
    private void showMessage(String message) {
        DoorbellView v = view;
        if (v != null) v.post(() -> v.setMessage(message));
    }

    private void voiceGoesOut(boolean works) {
        DoorbellView v = view;
        if (v != null) v.post(() -> v.setVoiceGoesOut(works));
    }

    /** A problem with sound or talking: a short notice that leaves the picture free. */
    private void showNotice(String message) {
        DoorbellView v = view;
        if (v != null) v.post(() -> v.showNotice(message));
    }

    @Override public void ring() {
        MusicPause m = music;
        if (m != null && settings.musicPause.equals("At the ring")) m.setActive(true);
        if (settings.chime) Chime.play(settings.chimeVolume, settings.chimeStream);
    }

    @Override public void ringTrigger() {
        PluginHost h = host;
        if (h == null) return;
        try {
            h.fireTrigger("doorbellPressed");
        } catch (RuntimeException | LinkageError older) {
            // Gesture triggers need a newer Kiosk Satellite; the ring itself still works.
        }
    }

    private static void command(PluginHost h, String name) {
        try {
            h.executeCommand(name, Collections.<String, Object>emptyMap(), (ok, data, error) -> {});
        } catch (RuntimeException ignored) {
            // A screen that cannot be woken still shows the viewer once it is on.
        }
    }

    private void publishText(String key, String name, String value) {
        PluginHost h = host;
        if (h == null) return;
        try {
            h.publishTextSensor(key, name, value.length() > 512 ? value.substring(0, 512) : value);
        } catch (RuntimeException ignored) {
            // Readings are informational.
        }
    }

    private void status(String message, boolean error) {
        PluginHost h = host;
        if (h != null) h.status(message, error);
    }

    private void log(String message) {
        PluginHost h = host;
        if (h != null) h.log(message);
    }

    /** The validated settings as plain fields. */
    static final class Settings {
        final String host, username, password, streamPath, language, microphone, micRate, musicPause, chimeStream, talkPath, videoPath;
        private final String name;
        final boolean chime, wakeScreen, wakeWordRestore, musicRestore, callMode, pushToTalk;
        final long autoCloseMillis, maxCallMillis;
        final int callVolume, chimeVolume;
        final List<Cards.Card> cards;

        Settings(Map<String, Object> values) {
            host = string(values, "host");
            username = string(values, "username");
            password = string(values, "password");
            name = string(values, "name");
            language = string(values, "language");
            String mic = string(values, "microphone");
            microphone = mic.isEmpty() ? "Automatic" : mic;
            String rate = string(values, "micRate");
            micRate = rate.isEmpty() ? "Automatic" : rate;
            String video = string(values, "videoSource");
            videoPath = video.startsWith("Baichuan") ? "Baichuan" : video.startsWith("RTSP") ? "RTSP" : "Automatic";
            String path = string(values, "talk");
            talkPath = path.startsWith("Baichuan") ? "Baichuan" : path.startsWith("ONVIF") ? "ONVIF" : "Automatic";
            pushToTalk = string(values, "talkMode").equals("Push to talk");
            chime = !Boolean.FALSE.equals(values.get("chime"));
            Object chimeLevel = values.get("chimeVolume");
            chimeVolume = chimeLevel instanceof Number ? Math.max(0, Math.min(100, ((Number) chimeLevel).intValue())) : 40;
            String sound = string(values, "chimeStream");
            chimeStream = java.util.Arrays.asList(Chime.STREAMS).contains(sound) ? sound : "Notifications";
            String pause = string(values, "musicPause");
            musicPause = pause.equals("At the ring") || pause.equals("At Speaker or Answer") ? pause : "Off";
            wakeScreen = !Boolean.FALSE.equals(values.get("wakeScreen"));
            autoCloseMillis = autoClose(string(values, "autoClose"));
            maxCallMillis = maxCall(string(values, "maxCall"));
            wakeWordRestore = Boolean.TRUE.equals(values.get("wakeWordRestore"));
            musicRestore = Boolean.TRUE.equals(values.get("musicRestore"));
            callMode = !Boolean.FALSE.equals(values.get("callMode"));
            streamPath = string(values, "stream").startsWith("Sub") ? "/h264Preview_01_sub" : "/h264Preview_01_main";
            Object volume = values.get("callVolume");
            callVolume = volume instanceof Number ? Math.max(0, Math.min(100, ((Number) volume).intValue())) : 70;
            cards = Cards.parse(values.get("cards"));
        }

        /** The doorbell's name for the viewer, or the translated default. */
        String name(Texts texts) {
            return name.isEmpty() ? texts.defaultName() : name;
        }

        boolean configured() {
            return !host.isEmpty() && !username.isEmpty() && !password.isEmpty();
        }

        boolean sameConnection(Settings other) {
            return host.equals(other.host) && username.equals(other.username) && password.equals(other.password);
        }

        private static long autoClose(String choice) {
            switch (choice) {
                case "Off": return 0;
                case "30 seconds": return 30_000;
                case "2 minutes": return 120_000;
                case "5 minutes": return 300_000;
                default: return 60_000;
            }
        }

        private static long maxCall(String choice) {
            switch (choice) {
                case "Off": return 0;
                case "5 minutes": return 300_000;
                case "30 minutes": return 1_800_000;
                default: return 600_000;
            }
        }

        private static String string(Map<String, Object> values, String key) {
            Object value = values.get(key);
            return value instanceof String ? ((String) value).trim() : "";
        }
    }
}
