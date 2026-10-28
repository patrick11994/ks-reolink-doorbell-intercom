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
    private volatile Settings settings;
    private volatile Texts texts = Texts.of("Automatic");
    private volatile RingController controller;
    private volatile DoorbellView view;
    private BaichuanClient events;
    private ScheduledExecutorService ticker;
    private volatile WakeWordGuard wakeWord;
    private volatile MusicPause music;
    // The call's media, all guarded by this.
    private MediaStream stream;
    private VideoRenderer videoRenderer;
    private Object videoOwner;  // the view whose surface the decoder draws on
    private AudioRenderer audioRenderer;
    private TalkChannel talk;
    private BaichuanTalk baichuanTalk;  // the Baichuan part of talk, for its encoder load
    private MicCapture mic;
    private CallVolume callVolume;
    private CallMode callMode;
    private long ringAt;
    private volatile long countdownStart, countdownMillis;  // shown by the viewer, also one created later
    // The cards' Home Assistant entities: followed only while the viewer is open.
    private final Set<String> followed = new HashSet<>();  // guarded by itself
    private final Map<String, Map<?, ?>> snapshots = new ConcurrentHashMap<>();
    private volatile boolean viewerOpen;

    @Override public void start(PluginHost host, Map<String, Object> values) {
        this.host = host;
        settings = new Settings(values);
        texts = Texts.of(settings.language);
        controller = new RingController(this, System::currentTimeMillis, settings.autoCloseMillis);
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
        music = new MusicPause((name, arguments, result) -> {
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
        });
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
        texts = Texts.of(settings.language);
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
            snapshots.put(entityId, payload);
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
        BaichuanClient client = new BaichuanClient(s.host, 9000, s.username, s.password, new BaichuanClient.Listener() {
            @Override public void onConnection(boolean connected, String detail) {
                Texts t = texts;
                boolean connecting = "connecting".equals(detail);
                publishText("connection", t.connectionReading(), connected ? t.connected() : connecting ? t.connectingState() : detail);
                status(connected ? t.connectedTo(s.name(t)) : t.doorbellProblem(connecting ? t.connectingState() : detail), !connected && !connecting);
            }

            @Override public void onAlarm(AlarmEvents.Event event) {
                RingController c = controller;
                if (c == null || event.channel != 0) return;
                if (event.visitor()) publishText("last_ring", texts.lastRingReading(), new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date()));
                c.onVisitor(event.visitor());
            }
        });
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
                DoorbellView created = new DoorbellView(context, theme, t, name, current, settings.callVolume, new DoorbellView.Callback() {
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
                });
                for (Map.Entry<String, Map<?, ?>> known : snapshots.entrySet()) created.entityChanged(known.getKey(), known.getValue());
                created.setCards(settings.cards);
                if (c != null) created.update(c.state(), c.speakerOn(), c.micOn());
                created.setCountdown(countdownStart, countdownMillis);
                view = created;
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
                showMessage(message);
                log(message);
            });
            if (callVolume != null) callVolume.apply(settings.callVolume);
            stream.attachAudio(audioRenderer);
        } else if (!speakerOn && audioRenderer != null) {
            if (stream != null) stream.attachAudio(null);
            releaseLater(audioRenderer::release);
            audioRenderer = null;
        }
        if (micOn && mic == null && talk != null) {
            mic = new MicCapture(talk, settings.microphone, settings.micRate, state -> {
                log("microphone: " + state);
                if (!state.startsWith("live")) showMessage(texts.noMicrophone());
            });
        } else if (!micOn && mic != null) {
            releaseLater(mic::release);
            mic = null;
            if (talk != null) talk.clear();
        }
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
                ConnectionCheck.Result result = ConnectionCheck.run(s.host, 9000, 554, s.username, s.password, t);
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
        if (stream != null || !s.configured()) return;
        ringAt = System.currentTimeMillis();
        log("media: start stream " + s.streamPath + " over " + (s.videoPath.equals("RTSP") ? "RTSP" : "Baichuan") + " (Video over: " + s.videoPath + ")");
        publishText("first_frame", texts.firstPictureReading(), texts.waiting());
        stream = openStream(s);
        stream.start();
        // Talking: open the talk channel now and stream silence, so the doorbell's playout buffer is full by the time
        // anyone presses Answer (it drops audio while that buffer fills).
        talk = openTalk(s);
        talk.start();
    }

    /**
     * The video path from the Video over setting: Baichuan (the Reolink app's way, a fresh keyframe on request), RTSP,
     * or Automatic: Baichuan, and RTSP if Baichuan fails before the first picture.
     */
    private MediaStream openStream(Settings s) {
        if (s.videoPath.equals("RTSP")) return rtspStream(s);
        final FallbackStream[] fallback = new FallbackStream[1];
        BaichuanStream baichuan = new BaichuanStream(s.host, 9000, s.username, s.password, s.streamPath.endsWith("_sub"), state -> {
            if (!state.equals("connecting") && !state.equals("streaming")) log("video: " + state + " (Baichuan)");
            FallbackStream f = fallback[0];
            if (state.startsWith("stream failed") && f != null) {
                // Reported on the Baichuan stream's own thread; switching stops that stream and waits for it.
                releaseLater(() -> {
                    if (f.primaryFailed()) log("video: falling back to RTSP");
                });
            } else if (state.startsWith("stream failed") || state.startsWith("stream ended")) {
                showMessage(texts.noVideo(state.substring(state.indexOf(' ') + 1)));
            }
        });
        if (s.videoPath.equals("Baichuan")) return baichuan;
        fallback[0] = new FallbackStream(baichuan, () -> rtspStream(s));
        return fallback[0];
    }

    private MediaStream rtspStream(Settings s) {
        return new StreamHub(s.host, 554, s.streamPath, s.username, s.password, state -> {
            if (!state.equals("connecting") && !state.equals("streaming")) log("video: " + state);
            if (state.startsWith("stream failed") || state.startsWith("stream ended")) showMessage(texts.noVideo(state.substring(state.indexOf(' ') + 1)));
        });
    }

    /**
     * The talk path from the Talk setting: Baichuan (about 1 s to the door, 16 kHz), the ONVIF backchannel (about
     * 2 s, 8 kHz, for doorbells that do not talk over Baichuan), or Automatic: Baichuan, and ONVIF if it is refused.
     */
    private TalkChannel openTalk(Settings s) {
        baichuanTalk = null;
        if (s.talkPath.equals("ONVIF")) return onvifTalk(s);
        final FallbackTalk[] fallback = new FallbackTalk[1];
        BaichuanTalk baichuan = new BaichuanTalk(s.host, 9000, s.username, s.password, state -> {
            if (!state.equals("connecting") && !state.equals("open")) log("talk: " + state);
            else if (state.equals("open")) log("talk: open over Baichuan");
            if (state.startsWith("talk refused") && fallback[0] != null) {
                log("talk: falling back to the ONVIF backchannel");
                fallback[0].primaryRefused();
            } else if (state.startsWith("talk refused") || state.startsWith("talk failed")) {
                showMessage(state);
            }
        });
        baichuanTalk = baichuan;
        if (s.talkPath.equals("Baichuan")) return baichuan;
        fallback[0] = new FallbackTalk(baichuan, () -> onvifTalk(s));
        return fallback[0];
    }

    private TalkChannel onvifTalk(Settings s) {
        return new Backchannel(s.host, 554, s.streamPath, s.username, s.password, state -> {
            if (!state.equals("connecting") && !state.equals("open")) log("talk: " + state);
            else if (state.equals("open")) log("talk: open over ONVIF");
            if (state.startsWith("talk failed")) showMessage(state);
        });
    }

    /**
     * KS may build a new viewer before it removes the old one, so each viewer only ever detaches its own decoder:
     * owner is the viewer's callback.
     */
    private synchronized void attachVideo(Object owner, Surface surface) {
        log("viewer: surface ready" + (stream == null ? ", no stream" : ""));
        if (videoRenderer != null) videoRenderer.release();
        videoOwner = owner;
        videoRenderer = new VideoRenderer(surface, new VideoRenderer.Listener() {
            @Override public void onVideoSize(int width, int height) {
                DoorbellView v = view;
                if (v != null) v.post(() -> v.setVideoSize(width, height));
            }

            @Override public void onVideoStats(long firstFrameMillis, double decodeMillis) {
                if (firstFrameMillis >= 0) {
                    long sinceRing = System.currentTimeMillis() - ringAt;
                    publishText("first_frame", texts.firstPictureReading(), texts.afterRing(sinceRing));
                }
            }

            @Override public void onVideoError(String message) {
                String shown = message == null ? texts.noPictureYet() : message;
                showMessage(shown);
                log(shown);
            }
        });
        if (stream != null) stream.attachVideo(videoRenderer);
    }

    private synchronized void detachVideo(Object owner) {
        log("viewer: surface gone" + (owner != videoOwner ? " (an older viewer)" : ""));
        if (owner != videoOwner) return;  // an old viewer going away after a new one took over
        videoOwner = null;
        if (stream != null) stream.attachVideo(null);
        if (videoRenderer != null) videoRenderer.release();
        videoRenderer = null;
    }

    /**
     * Forgets the call's media at once and releases it on a background thread: Hang up runs on the main thread, where
     * Android refuses network traffic (the RTSP TEARDOWN) and where waiting for a decoder to stop would stall the UI.
     */
    private synchronized void stopMedia() {
        if (stream == null && videoRenderer == null && audioRenderer == null && talk == null && mic == null) {
            if (callVolume != null) callVolume.restore();
            if (callMode != null) callMode.leave();
            return;
        }
        log("media: stop (stream " + (stream != null) + ", video " + (videoRenderer != null) + ", audio " + (audioRenderer != null)
            + ", talk " + (talk != null) + ", microphone " + (mic != null) + ")");
        BaichuanTalk bt = baichuanTalk;
        if (bt != null && bt.blocksSent() > 0) log(String.format(Locale.ROOT, "talk: %d Baichuan blocks sent, encoder load %.1f%%", bt.blocksSent(), 100 * bt.encodeLoad()));
        final MediaStream oldStream = stream;
        final VideoRenderer oldVideo = videoRenderer;
        final AudioRenderer oldAudio = audioRenderer;
        final TalkChannel oldTalk = talk;
        final MicCapture oldMic = mic;
        talk = null;
        baichuanTalk = null;
        mic = null;
        stream = null;
        videoRenderer = null;
        videoOwner = null;
        audioRenderer = null;
        if (callVolume != null) callVolume.restore();
        if (callMode != null) callMode.leave();  // here, not on the cleanup thread, so it cannot undo a next call's
        Thread cleanup = new Thread(() -> {
            try {
                if (oldStream != null) oldStream.stop();
            } catch (RuntimeException ignored) {
                // Going away anyway.
            }
            if (oldMic != null) oldMic.release();
            if (oldTalk != null) oldTalk.stop();
            if (oldVideo != null) oldVideo.release();
            if (oldAudio != null) oldAudio.release();
        }, "reolink-cleanup");
        cleanup.setDaemon(true);
        cleanup.start();
    }

    private void setCallVolume(int percent, boolean done) {
        CallVolume volume = callVolume;
        if (volume != null) volume.apply(percent);
        if (!done) return;
        PluginHost h = host;
        if (h == null) return;
        try {
            h.updateSettings(Collections.<String, Object>singletonMap("callVolume", (double) percent));
        } catch (RuntimeException | LinkageError older) {
            // Older Kiosk Satellite: the level holds for this call only.
        }
    }

    private void showMessage(String message) {
        DoorbellView v = view;
        if (v != null) v.post(() -> v.setMessage(message));
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
        final boolean chime, wakeScreen, wakeWordRestore, callMode;
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
