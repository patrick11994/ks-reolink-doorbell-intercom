// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell;

import android.content.Context;
import android.view.Surface;
import android.view.View;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
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
import io.github.patrick11994.reolinkdoorbell.media.StreamHub;
import io.github.patrick11994.reolinkdoorbell.talk.Backchannel;

/**
 * Reolink doorbell for Kiosk Satellite: holds a Baichuan session to the doorbell, and on a button press wakes the
 * panel, rings and opens the full-screen viewer. Everything else is in RingController, DoorbellView and the clients.
 */
public final class ReolinkPlugin implements KioskPlugin, RingController.Actions {
    static final String OVERLAY = "doorbell";

    private volatile PluginHost host;
    private volatile Settings settings;
    private volatile Texts texts = Texts.of("Automatic");
    private volatile RingController controller;
    private volatile DoorbellView view;
    private BaichuanClient events;
    private ScheduledExecutorService ticker;
    private volatile WakeWordGuard wakeWord;
    // The call's media, all guarded by this.
    private StreamHub stream;
    private VideoRenderer videoRenderer;
    private Object videoOwner;  // the view whose surface the decoder draws on
    private AudioRenderer audioRenderer;
    private Backchannel backchannel;
    private MicCapture mic;
    private CallVolume callVolume;
    private CallMode callMode;
    private long ringAt;
    private volatile long countdownStart, countdownMillis;  // shown by the viewer, also one created later

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
                });
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
        PluginHost h = host;
        if (h != null) h.hideOverlay(OVERLAY);
        stopMedia();
    }

    @Override public synchronized void setAudio(boolean speakerOn, boolean micOn) {
        WakeWordGuard guard = wakeWord;
        if (guard != null) guard.setActive(speakerOn || micOn);
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
        if (micOn && mic == null && backchannel != null) {
            mic = new MicCapture(backchannel, settings.microphone, settings.micRate, state -> {
                log("microphone: " + state);
                if (!state.startsWith("live")) showMessage(texts.noMicrophone());
            });
        } else if (!micOn && mic != null) {
            releaseLater(mic::release);
            mic = null;
            if (backchannel != null) backchannel.clear();
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
        log("media: start stream " + s.streamPath);
        publishText("first_frame", texts.firstPictureReading(), texts.waiting());
        stream = new StreamHub(s.host, 554, s.streamPath, s.username, s.password, state -> {
            if (!state.equals("connecting") && !state.equals("streaming")) log("video: " + state);
            if (state.startsWith("stream failed") || state.startsWith("stream ended")) showMessage(texts.noVideo(state.substring(state.indexOf(' ') + 1)));
        });
        stream.start();
        // Talking: open the backchannel now and stream silence, so the doorbell's ~2 s buffer is full by the time
        // anyone presses Answer (it drops audio while that buffer fills).
        backchannel = new Backchannel(s.host, 554, s.streamPath, s.username, s.password, state -> {
            if (!state.equals("connecting") && !state.equals("open")) log("talk: " + state);
            if (state.startsWith("talk failed")) showMessage(state);
        });
        backchannel.start();
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
        if (stream == null && videoRenderer == null && audioRenderer == null && backchannel == null && mic == null) {
            if (callVolume != null) callVolume.restore();
            if (callMode != null) callMode.leave();
            return;
        }
        log("media: stop (stream " + (stream != null) + ", video " + (videoRenderer != null) + ", audio " + (audioRenderer != null)
            + ", talk " + (backchannel != null) + ", microphone " + (mic != null) + ")");
        final StreamHub oldStream = stream;
        final VideoRenderer oldVideo = videoRenderer;
        final AudioRenderer oldAudio = audioRenderer;
        final Backchannel oldTalk = backchannel;
        final MicCapture oldMic = mic;
        backchannel = null;
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
        if (settings.chime) Chime.play();
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
        final String host, username, password, streamPath, language, microphone, micRate;
        private final String name;
        final boolean chime, wakeScreen, wakeWordRestore, callMode;
        final long autoCloseMillis, maxCallMillis;
        final int callVolume;

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
            chime = !Boolean.FALSE.equals(values.get("chime"));
            wakeScreen = !Boolean.FALSE.equals(values.get("wakeScreen"));
            autoCloseMillis = autoClose(string(values, "autoClose"));
            maxCallMillis = maxCall(string(values, "maxCall"));
            wakeWordRestore = Boolean.TRUE.equals(values.get("wakeWordRestore"));
            callMode = !Boolean.FALSE.equals(values.get("callMode"));
            streamPath = string(values, "stream").startsWith("Sub") ? "/h264Preview_01_sub" : "/h264Preview_01_main";
            Object volume = values.get("callVolume");
            callVolume = volume instanceof Number ? Math.max(0, Math.min(100, ((Number) volume).intValue())) : 70;
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
