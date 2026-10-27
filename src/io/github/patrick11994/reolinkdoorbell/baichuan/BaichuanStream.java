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
    private static final Pattern ENCRYPT_LEN = Pattern.compile("<encryptLen>(\\d+)</encryptLen>");

    private final String host, username, password;
    private final int port;
    private final boolean subStream;
    private final Listener listener;
    private final int timeoutMillis;
    private final FrameHub hub = new FrameHub();
    private volatile boolean running;
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
        if (running) return;
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
            while (running) {
                BcMessage m = BcMessage.read(s.in);
                if (m.command != CMD_PREVIEW || m.messageId != id) continue;
                if (m.statusOrEncryption != 200) throw new IOException("the doorbell answered the preview with " + m.statusOrEncryption);
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
