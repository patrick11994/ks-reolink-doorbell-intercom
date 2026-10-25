// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell.talk;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Map;
import io.github.patrick11994.reolinkdoorbell.media.RtspSession;
import io.github.patrick11994.reolinkdoorbell.media.Sdp;

/**
 * Talks to the doorbell over the ONVIF RTSP audio backchannel (the method Scrypted uses for Reolink doorbells):
 * DESCRIBE with the backchannel Require header, SETUP the sendonly audio track, PLAY, then one G.711 RTP packet
 * every 20 ms. It streams silence from the moment it opens, so the doorbell's fixed ~2 s playout buffer is already
 * filled when someone starts talking; microphone audio replaces the silence while it is fed.
 */
public final class Backchannel {
    public interface Listener {
        void onBackchannelState(String state);
    }

    static final String REQUIRE = "www.onvif.org/ver20/backchannel";
    public static final int RATE = 8000;
    static final int PACKET_MILLIS = 20;
    static final int PACKET_SAMPLES = RATE * PACKET_MILLIS / 1000;
    private static final int START_SAMPLES = RATE * 40 / 1000;  // microphone audio waits for this much before it plays
    private static final int MAX_SAMPLES = RATE * 200 / 1000;  // and is never allowed to pile up beyond this

    private final String host, path, username, password;
    private final int port;
    private final Listener listener;
    private final short[] ring = new short[MAX_SAMPLES * 2];
    private int ringStart, ringCount;
    private boolean flowing;  // microphone audio is being played out (after its start cushion)
    private volatile boolean running;
    private volatile long packetsSent, rtcpReceived;
    private RtspSession session;
    private Thread thread;

    public Backchannel(String host, int port, String path, String username, String password, Listener listener) {
        this.host = host;
        this.port = port;
        this.path = path;
        this.username = username;
        this.password = password;
        this.listener = listener;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::run, "reolink-talk");
        thread.setDaemon(true);
        thread.start();
    }

    /** Stops sending and closes the session; safe from any thread except the main one (it may wait briefly). */
    public void stop() {
        Thread t;
        synchronized (this) {
            running = false;
            t = thread;
            thread = null;
        }
        if (t != null) {
            t.interrupt();
            try {
                t.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Microphone audio at 8 kHz. The oldest audio is dropped rather than letting the delay grow. */
    public synchronized void write(short[] samples, int count) {
        for (int i = 0; i < count; i++) {
            if (ringCount == ring.length) {
                ringStart = (ringStart + 1) % ring.length;
                ringCount--;
            }
            ring[(ringStart + ringCount) % ring.length] = samples[i];
            ringCount++;
        }
        while (ringCount > MAX_SAMPLES) {
            ringStart = (ringStart + 1) % ring.length;
            ringCount--;
        }
    }

    /** The microphone stopped (muted or ended): forget what was queued and go back to silence. */
    public synchronized void clear() {
        ringStart = 0;
        ringCount = 0;
        flowing = false;
    }

    public long packetsSent() {
        return packetsSent;
    }

    /** Receiver reports from the doorbell: proof it is taking our audio. */
    public long rtcpReceived() {
        return rtcpReceived;
    }

    /** The next 20 ms to send: microphone audio when there is enough, else silence. */
    synchronized void nextPacket(short[] out) {
        if (!flowing && ringCount >= START_SAMPLES) flowing = true;
        if (flowing && ringCount >= PACKET_SAMPLES) {
            for (int i = 0; i < PACKET_SAMPLES; i++) out[i] = ring[(ringStart + i) % ring.length];
            ringStart = (ringStart + PACKET_SAMPLES) % ring.length;
            ringCount -= PACKET_SAMPLES;
            return;
        }
        if (flowing) flowing = false;  // ran dry: wait for a new cushion instead of sending fragments
        java.util.Arrays.fill(out, (short) 0);
    }

    private void run() {
        RtspSession s = new RtspSession(host, port, path, username, password);
        Map<String, String> require = Collections.singletonMap("Require", REQUIRE);
        try {
            listener.onBackchannelState("connecting");
            Sdp sdp = s.describe(require);
            Sdp.Track track = sdp.backchannel();
            if (track == null) throw new IOException("the doorbell offers no ONVIF audio backchannel");
            boolean ulaw;
            if (track.codec.equals("PCMU") || (track.codec.isEmpty() && track.payloadTypes.contains(0))) ulaw = true;
            else if (track.codec.equals("PCMA") || (track.codec.isEmpty() && track.payloadTypes.contains(8))) ulaw = false;
            else throw new IOException("the backchannel wants " + track.codec + ", not G.711");
            int payloadType = track.payloadTypes.isEmpty() ? (ulaw ? 0 : 8) : track.payloadTypes.get(0);
            int channel = s.setup(track, 0, require);
            s.play(require);
            synchronized (this) {
                if (!running) return;
                session = s;
            }
            s.start(new RtspSession.Listener() {
                @Override public void onPacket(int ch, byte[] buffer, int offset, int length) {
                    if (ch == channel + 1) rtcpReceived++;
                }

                @Override public void onClosed(String reason) {
                    if (reason != null && running) listener.onBackchannelState("talk ended: " + reason);
                }
            }, "reolink-talk-read");
            listener.onBackchannelState("open");
            send(s, channel, payloadType, ulaw);
        } catch (InterruptedException stopped) {
            // Stopped.
        } catch (Exception error) {
            if (running) listener.onBackchannelState("talk failed: " + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
        } finally {
            synchronized (this) {
                session = null;
            }
            s.close();
        }
    }

    /** One packet per 20 ms against the wall clock; after a stall it skips ahead instead of bursting. */
    private void send(RtspSession s, int channel, int payloadType, boolean ulaw) throws IOException, InterruptedException {
        SecureRandom random = new SecureRandom();
        int sequence = random.nextInt(0x10000);
        long timestamp = random.nextInt() & 0xFFFFFFFFL;
        int ssrc = random.nextInt();
        short[] pcm = new short[PACKET_SAMPLES];
        byte[] packet = new byte[12 + PACKET_SAMPLES];
        long next = System.nanoTime();
        boolean first = true;
        while (running) {
            nextPacket(pcm);
            packet[0] = (byte) 0x80;
            packet[1] = (byte) ((first ? 0x80 : 0) | payloadType);
            packet[2] = (byte) (sequence >> 8);
            packet[3] = (byte) sequence;
            packet[4] = (byte) (timestamp >> 24);
            packet[5] = (byte) (timestamp >> 16);
            packet[6] = (byte) (timestamp >> 8);
            packet[7] = (byte) timestamp;
            packet[8] = (byte) (ssrc >> 24);
            packet[9] = (byte) (ssrc >> 16);
            packet[10] = (byte) (ssrc >> 8);
            packet[11] = (byte) ssrc;
            for (int i = 0; i < PACKET_SAMPLES; i++) packet[12 + i] = ulaw ? G711.ulaw(pcm[i]) : G711.alaw(pcm[i]);
            s.send(channel, packet, packet.length);
            packetsSent++;
            first = false;
            sequence = (sequence + 1) & 0xFFFF;
            timestamp = (timestamp + PACKET_SAMPLES) & 0xFFFFFFFFL;
            next += PACKET_MILLIS * 1_000_000L;
            long wait = next - System.nanoTime();
            if (wait > 0) Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
            else if (-wait > 100_000_000L) next = System.nanoTime();  // more than 100 ms behind: resynchronize
        }
    }
}
