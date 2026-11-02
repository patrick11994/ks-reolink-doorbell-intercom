// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell.baichuan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.patrick11994.reolinkdoorbell.media.FrameHub;
import io.github.patrick11994.reolinkdoorbell.media.MediaStream;

/**
 * The live stream the way the Reolink app asks for it: cmd 3 Preview with policy newestIFrame over its own Baichuan
 * session. Measured on a Video Doorbell PoE, the first keyframe comes 136-210 ms after asking (RTSP: 364-466 ms), and
 * every frame about 60 ms before its RTSP copy: newestIFrame makes the doorbell encode a fresh keyframe on request.
 * The doorbell encrypts the first encryptLen bytes of each message (its extension says how many) with the session
 * key; the rest of a large frame follows in plain continuation messages.
 */
public final class BaichuanStream implements MediaStream {
    static final int CMD_PREVIEW = 3, CMD_PREVIEW_STOP = 4;
    /** newestIFrame brings a keyframe in about 0.2 s: one that has not come by now will not, so Automatic falls back. */
    static final int FIRST_KEYFRAME_MILLIS = 2_000;
    private static final Pattern ENCRYPT_LEN = Pattern.compile("<encryptLen>(\\d+)</encryptLen>");

    private final String host, username, password;
    private final int port;
    private final boolean subStream;
    private final Listener listener;
    private final int timeoutMillis;
    private final FrameHub hub = new FrameHub();
    private volatile boolean running;
    private boolean stopped;  // guarded by this
    private BaichuanClient.Login session;
    private Thread thread;
    private long videoBase = -1;
    private long audioFrames;
    private boolean audioAnnounced;

    public BaichuanStream(String host, int port, String username, String password, boolean subStream, Listener listener) {
        this(host, port, username, password, subStream, listener, 5_000);
    }

    BaichuanStream(String host, int port, String username, String password, boolean subStream, Listener listener, int timeoutMillis) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.subStream = subStream;
        this.listener = listener;
        this.timeoutMillis = timeoutMillis;
    }

    @Override public synchronized void start() {
        if (running || stopped) return;  // one stream per object: a late start after stop() must not open it again
        running = true;
        hub.start();
        thread = new Thread(this::run, "reolink-stream");
        thread.setDaemon(true);
        thread.start();
    }

    @Override public void stop() {
        Thread t;
        BaichuanClient.Login s;
        synchronized (this) {
            running = false;
            stopped = true;
            t = thread;
            thread = null;
            s = session;
            session = null;
        }
        hub.stop();
        if (s != null) {
            sendStop(s);  // the stop thread may talk to the network: plugins stop media off the main thread
            try {
                s.close();  // ends the reader thread's blocking read
            } catch (IOException ignored) {
                // Closed anyway.
            }
        }
        if (t != null) {
            t.interrupt();
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override public long firstKeyframeMillis() {
        return hub.firstKeyframeMillis();
    }

    @Override public void attachVideo(VideoSink sink) {
        hub.attachVideo(sink);
    }

    @Override public void attachAudio(AudioSink sink) {
        hub.attachAudio(sink);
    }

    /** Tells the doorbell to stop the preview, once per session; a session already gone is fine. */
    private static void sendStop(BaichuanClient.Login s) {
        String stop = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<body>\n<Preview version=\"1.1\">\n<channelId>0</channelId>\n<handle>0</handle>\n</Preview>\n</body>\n";
        synchronized (s.out) {
            if (s.socket.isClosed() || s.stopSent) return;
            s.stopSent = true;
            try {
                s.out.write(BcMessage.encodeModern(CMD_PREVIEW_STOP, s.nextId(0), new byte[0], BcCrypto.aes(s.aesKey(), stop.getBytes(StandardCharsets.UTF_8), true)));
                s.out.flush();
            } catch (IOException | RuntimeException ignored) {
                // The session is gone anyway.
            }
        }
    }

    /**
     * What arrived between the preview request and the first picture. Through an NVR no picture came at all (#6):
     * this tells an answer that never came from video that came under another message id or without a keyframe.
     */
    private static final class Waited {
        private int answers;
        private long videoBytes;
        private int others;
        private final StringBuilder kinds = new StringBuilder();

        void answer(BcMessage m) {
            answers++;
            videoBytes += m.payload.length;
        }

        void other(BcMessage m) {
            if (others++ < 5) kinds.append(kinds.length() == 0 ? "" : ", ").append("cmd ").append(m.command).append(" id ")
                .append(Integer.toHexString(m.messageId)).append(" status ").append(m.statusOrEncryption).append(" ").append(m.payload.length).append(" bytes");
        }

        @Override public String toString() {
            return answers + " answers to the preview with " + videoBytes + " bytes of video, " + others + " other messages"
                + (kinds.length() == 0 ? "" : ": " + kinds + (others > 5 ? ", …" : ""));
        }
    }

    private void run() {
        BaichuanClient.Login s = null;
        try {
            listener.onStreamState("connecting");
            s = BaichuanClient.login(host, port, username, password, timeoutMillis);
            synchronized (this) {
                if (!running) {
                    s.close();
                    return;
                }
                session = s;
            }
            String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<body>\n<Preview version=\"1.1\">\n<channelId>0</channelId>\n<handle>0</handle>\n"
                + "<streamType>" + (subStream ? "subStream" : "mainStream") + "</streamType>\n<policy>newestIFrame</policy>\n</Preview>\n</body>\n";
            int id = s.nextId(0);
            synchronized (s.out) {
                s.out.write(BcMessage.encodeModern(CMD_PREVIEW, id, new byte[0], BcCrypto.aes(s.aesKey(), xml.getBytes(StandardCharsets.UTF_8), true)));
                s.out.flush();
            }
            BcMediaParser parser = new BcMediaParser(new BcMediaParser.Listener() {
                @Override public void onInfo(int width, int height, int fps) {}

                @Override public void onVideo(byte[] annexB, long micros, boolean keyframe, boolean h265) {
                    if (h265) throw new IllegalStateException("the doorbell streams H.265, which the viewer does not decode");
                    if (videoBase < 0) {
                        if (!keyframe) return;
                        videoBase = micros;
                    }
                    hub.video(annexB, (micros - videoBase) & 0xFFFFFFFFL, keyframe);
                }

                @Override public void onAac(byte[] raw, byte[] config, int sampleRate) {
                    if (!audioAnnounced) {
                        hub.audioFormat(config, sampleRate);
                        audioAnnounced = true;
                    }
                    hub.audio(raw, audioFrames++ * 1024L * 1_000_000L / sampleRate);  // one AAC frame is 1024 samples
                }
            });
            boolean streaming = false;
            Waited waited = new Waited();  // what came in before the first picture, for the log when none does
            long keyframeBy = System.nanoTime() / 1_000_000 + FIRST_KEYFRAME_MILLIS;
            boolean waiting = true;
            while (running) {
                // Until the first keyframe, the read may only wait as long as the keyframe deadline leaves: a doorbell
                // that answers other messages, or nothing at all, must not hold the fallback back.
                if (waiting && hub.firstKeyframeMillis() >= 0) {
                    waiting = false;
                    s.socket.setSoTimeout(timeoutMillis);
                } else if (waiting) {
                    long left = keyframeBy - System.nanoTime() / 1_000_000;
                    if (left <= 0) throw new IOException("no picture within " + FIRST_KEYFRAME_MILLIS + " ms (" + waited + ")");
                    s.socket.setSoTimeout((int) left);
                }
                BcMessage m;
                try {
                    m = BcMessage.read(s.in);
                } catch (java.net.SocketTimeoutException quiet) {
                    if (waiting) throw new IOException("no picture within " + FIRST_KEYFRAME_MILLIS + " ms (" + waited + ")");
                    throw quiet;
                }
                if (m.command != CMD_PREVIEW || m.messageId != id) {
                    if (waiting) waited.other(m);
                    continue;
                }
                if (m.statusOrEncryption != 200) throw new IOException("the doorbell answered the preview with " + m.statusOrEncryption);
                if (waiting) waited.answer(m);
                if (m.payload.length == 0) continue;
                byte[] p = m.payload;
                String ext = m.body.length == 0 ? "" : new String(BcCrypto.aes(s.aesKey(), m.body, false), StandardCharsets.UTF_8);
                Matcher e = ENCRYPT_LEN.matcher(ext);
                if (e.find()) {
                    int n = (int) Math.min(Long.parseLong(e.group(1)), p.length);
                    byte[] head = BcCrypto.aes(s.aesKey(), java.util.Arrays.copyOf(p, n), false);
                    System.arraycopy(head, 0, p, 0, n);  // p is this message's own buffer
                }
                parser.feed(p, 0, p.length);
                if (!streaming) {
                    streaming = true;
                    listener.onStreamState("streaming");
                }
            }
        } catch (Exception error) {
            if (running) listener.onStreamState((hub.firstKeyframeMillis() < 0 ? "stream failed: " : "stream ended: ")
                + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
        } finally {
            if (s != null) {
                sendStop(s);
                try {
                    s.close();
                } catch (IOException ignored) {
                    // Closed anyway.
                }
            }
        }
    }
}
