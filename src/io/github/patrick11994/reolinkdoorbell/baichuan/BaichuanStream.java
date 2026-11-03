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
    /**
     * A battery doorbell behind a Reolink Home Hub answers the preview at once but sends its first picture only once it
     * is awake, about 12 s later (#9): a doorbell that answered by FIRST_KEYFRAME_MILLIS gets this long instead. One
     * that did not answer at all still fails at FIRST_KEYFRAME_MILLIS, so Automatic falls back to RTSP as quickly as
     * before. Learned from DevFlorian's patch.
     */
    static final int WAKING_KEYFRAME_MILLIS = 15_000;
    private static final Pattern ENCRYPT_LEN = Pattern.compile("<encryptLen>(\\d+)</encryptLen>");

    private final String host, username, password;
    private final int port;
    private final boolean subStream;
    private final Listener listener;
    private final int timeoutMillis;
    private final FrameHub hub = new FrameHub();
    private final int channel;
    private volatile boolean running;
    private boolean stopped;  // guarded by this
    private BaichuanClient.Login session;
    private Thread thread;
    private long videoBase = -1;
    private long audioFrames;
    private boolean audioAnnounced;

    public BaichuanStream(String host, int port, String username, String password, boolean subStream, Listener listener) {
        this(host, port, username, password, 0, subStream, listener, 5_000);
    }

    /** channel: 0 for a doorbell reached directly; behind an NVR, its channel there, counted from 0. */
    public BaichuanStream(String host, int port, String username, String password, int channel, boolean subStream, Listener listener) {
        this(host, port, username, password, channel, subStream, listener, 5_000);
    }

    BaichuanStream(String host, int port, String username, String password, boolean subStream, Listener listener, int timeoutMillis) {
        this(host, port, username, password, 0, subStream, listener, timeoutMillis);
    }

    BaichuanStream(String host, int port, String username, String password, int channel, boolean subStream, Listener listener, int timeoutMillis) {
        this.channel = channel;
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
            sendStop(s, channel, subStream ? 1 : 0);  // the stop thread may talk to the network: plugins stop media off the main thread
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
    private static void sendStop(BaichuanClient.Login s, int channel, int stream) {
        String stop = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<body>\n<Preview version=\"1.1\">\n<channelId>" + channel + "</channelId>\n<handle>0</handle>\n</Preview>\n</body>\n";
        synchronized (s.out) {
            if (s.socket.isClosed() || s.stopSent) return;
            s.stopSent = true;
            try {
                s.out.write(BcMessage.encodeModern(CMD_PREVIEW_STOP, s.streamId(channel, stream, 0), new byte[0], BcCrypto.aes(s.aesKey(), stop.getBytes(StandardCharsets.UTF_8), true)));
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
        private final int request;
        private final BcMediaParser parser;
        private int answers, continuations;
        private long videoBytes;
        private int others;
        private final StringBuilder kinds = new StringBuilder();

        /** The doorbell answered the preview itself, even if only with the stream's info. */
        boolean answered() {
            return answers > 0;
        }

        Waited(int request, BcMediaParser parser) {
            this.request = request;
            this.parser = parser;
        }

        void answer(int bytes, boolean continuation) {
            answers++;
            if (continuation) continuations++;
            videoBytes += bytes;
        }

        void other(BcMessage m) {
            if (others++ < 5) kinds.append(kinds.length() == 0 ? "" : ", ").append("cmd ").append(m.command).append(" id ")
                .append(Integer.toHexString(m.messageId)).append(" status ").append(m.statusOrEncryption).append(" ").append(m.payload.length).append(" bytes");
        }

        @Override public String toString() {
            return answers + " answers to the preview " + idBytes(request) + " (" + continuations + " without an extension) with " + videoBytes + " bytes: "
                + parser.summary() + "; "
                + others + " other messages" + (kinds.length() == 0 ? "" : ": " + kinds + (others > 5 ? ", …" : ""));
        }

        /** The id as its four header bytes: channel, stream, counter, handle. */
        private static String idBytes(int id) {
            return String.format(java.util.Locale.ROOT, "[%d %d %d %d]", id & 0xFF, id >>> 8 & 0xFF, id >>> 16 & 0xFF, id >>> 24);
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
            String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<body>\n<Preview version=\"1.1\">\n<channelId>" + channel + "</channelId>\n<handle>0</handle>\n"
                + "<streamType>" + (subStream ? "subStream" : "mainStream") + "</streamType>\n<policy>newestIFrame</policy>\n</Preview>\n</body>\n";
            int id = s.streamId(channel, subStream ? 1 : 0, 0);
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
            Waited waited = new Waited(id, parser);
            boolean inPacket = false;  // a binaryData message came: messages without an extension carry more of it  // what came in before the first picture, for the log when none does
            long started = System.nanoTime() / 1_000_000;
            long limit = FIRST_KEYFRAME_MILLIS;  // WAKING_KEYFRAME_MILLIS once the doorbell answered without a picture
            boolean waiting = true;
            while (running) {
                // Until the first keyframe, the read may only wait as long as the keyframe deadline leaves: a doorbell
                // that answers other messages, or nothing at all, must not hold the fallback back.
                if (waiting && hub.firstKeyframeMillis() >= 0) {
                    waiting = false;
                    s.socket.setSoTimeout(timeoutMillis);
                } else if (waiting) {
                    long left = started + limit - System.nanoTime() / 1_000_000;
                    if (left <= 0 && limit == FIRST_KEYFRAME_MILLIS && waited.answered()) {
                        limit = WAKING_KEYFRAME_MILLIS;
                        left = started + limit - System.nanoTime() / 1_000_000;
                        listener.onStreamState("waiting up to " + WAKING_KEYFRAME_MILLIS / 1000 + " s for the doorbell to wake up (" + waited + ")");
                    }
                    if (left <= 0) throw new IOException("no picture within " + limit + " ms (" + waited + ")");
                    s.socket.setSoTimeout((int) left);
                }
                BcMessage m;
                try {
                    m = BcMessage.read(s.in);
                } catch (java.net.SocketTimeoutException quiet) {
                    if (waiting) continue;  // the deadline above decides: wait longer for a waking doorbell, or give up
                    throw quiet;
                }
                if (m.command != CMD_PREVIEW || m.messageId != id) {
                    if (waiting) waited.other(m);
                    continue;
                }
                // A media packet starts in a message whose extension says binaryData, and may go on in messages without
                // an extension: plain media from start to end. A doorbell reached directly gives every message an
                // extension; an NVR does not, and dropping those cut every frame short (#6). Learned from reostream's
                // protocol notes (AGPL-3.0); no code copied.
                boolean continuation = !m.extended && inPacket;
                if (!continuation && m.statusOrEncryption != 200) throw new IOException("the doorbell answered the preview with " + m.statusOrEncryption);
                byte[] p;
                String ext;
                if (continuation) {
                    p = m.body;
                    ext = "";
                } else {
                    if (m.payload.length == 0) {
                        if (waiting) waited.answer(0, false);
                        continue;
                    }
                    p = m.payload;
                    ext = m.body.length == 0 ? "" : new String(BcCrypto.aes(s.aesKey(), m.body, false), StandardCharsets.UTF_8);
                    if (ext.contains("<binaryData>")) inPacket = true;
                }
                if (waiting) waited.answer(p.length, continuation);
                if (p.length == 0) continue;
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
                sendStop(s, channel, subStream ? 1 : 0);
                try {
                    s.close();
                } catch (IOException ignored) {
                    // Closed anyway.
                }
            }
        }
    }
}
