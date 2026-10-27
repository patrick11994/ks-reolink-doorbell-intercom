// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell.baichuan;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.patrick11994.reolinkdoorbell.talk.ImaAdpcm;
import io.github.patrick11994.reolinkdoorbell.talk.TalkChannel;
import io.github.patrick11994.reolinkdoorbell.talk.TalkQueue;

/**
 * Talks to the door the way the Reolink app does, over its own Baichuan session: about 1 s from the panel to the door
 * speaker, against about 2.1 s over the ONVIF backchannel, and 16 kHz instead of 8 kHz. TalkAbility (cmd 10) says
 * what the doorbell takes, TalkConfig (cmd 201) picks it, then one ADPCM block per cmd 202 in real time, silence
 * until the microphone feeds it, and TalkReset (cmd 11) at the end. The blocks follow a capture of the Reolink app:
 * high nibble first, media header field 2, no padding. Protocol first learned from neolink (AGPL-3.0); no code copied.
 */
public final class BaichuanTalk implements TalkChannel {
    public interface Listener {
        /** "connecting", "open", "talk refused: ..." (this doorbell does not talk over Baichuan), "talk failed: ...". */
        void onTalkState(String state);
    }

    static final int CMD_TALK_ABILITY = 10, CMD_TALK_RESET = 11, CMD_TALK_CONFIG = 201, CMD_TALK = 202;
    static final int MEDIA_ADPCM = 0x62773130, MEDIA_ADPCM_DATA = 0x0100;
    public static final int RATE = 16000;
    static final int SURVIVORS = 16;  // the ADPCM trellis: still above G.711 on speech, at half the cost of 32 paths
    private static final int MAX_BLOCK_CODES = 4096;  // a lengthPerEncoder beyond this is not a real doorbell
    private static final Pattern TAG = Pattern.compile("<(\\w+)>([^<]*)</\\1>");

    private final String host, username, password;
    private final int port;
    private final Listener listener;
    private final int timeoutMillis;
    private final TalkQueue queue = new TalkQueue(RATE * 40 / 1000 + 1025, RATE * 200 / 1000 + 1025);
    private volatile boolean running;
    private volatile long blocksSent;
    private volatile long encodeNanos;
    private volatile int blockSamples = 1025;
    private BaichuanClient.Login session;
    private Thread thread;

    public BaichuanTalk(String host, int port, String username, String password, Listener listener) {
        this(host, port, username, password, listener, 5_000);
    }

    BaichuanTalk(String host, int port, String username, String password, Listener listener, int timeoutMillis) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.listener = listener;
        this.timeoutMillis = timeoutMillis;
    }

    @Override public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::run, "reolink-talk");
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
        }
        if (t != null) {
            t.interrupt();
            try {
                t.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // Closed anyway.
            }
        }
    }

    @Override public void write(short[] samples, int count) {
        queue.write(samples, count);
    }

    @Override public void clear() {
        queue.clear();
    }

    @Override public int rate() {
        return RATE;
    }

    public long blocksSent() {
        return blocksSent;
    }

    /** The share of real time the encoder takes, 0 to 1: how hard the trellis works this device. */
    public double encodeLoad() {
        long blocks = blocksSent;
        return blocks == 0 ? 0 : encodeNanos / (blocks * blockSamples * 1e9 / RATE);
    }

    /** What TalkAbility offers, for the check: null when the doorbell does not talk ADPCM over Baichuan. */
    public static String probe(String host, int port, String username, String password) throws IOException {
        try (BaichuanClient.Login s = BaichuanClient.login(host, port, username, password, 5_000)) {
            Format f = Format.of(request(s, CMD_TALK_ABILITY, extension(s, false), new byte[0]));
            return f == null ? null : "adpcm " + f.rate + " Hz";
        }
    }

    private void run() {
        BaichuanClient.Login s = null;
        try {
            listener.onTalkState("connecting");
            s = BaichuanClient.login(host, port, username, password, timeoutMillis);
            synchronized (this) {
                if (!running) return;
                session = s;
            }
            Format format = Format.of(request(s, CMD_TALK_ABILITY, extension(s, false), new byte[0]));
            if (format == null) {
                listener.onTalkState("talk refused: the doorbell offers no ADPCM talk over Baichuan");
                return;
            }
            byte[] config = BcCrypto.aes(s.aesKey(), format.talkConfig().getBytes(StandardCharsets.UTF_8), true);
            BcMessage answer = request(s, CMD_TALK_CONFIG, extension(s, false), config);
            if (answer.statusOrEncryption == 422) {
                // Someone else is talking, or a session of ours died before its reset: the app resets and retries.
                send(s, CMD_TALK_RESET, extension(s, false), new byte[0]);
                answer = request(s, CMD_TALK_CONFIG, extension(s, false), config);
            }
            if (answer.statusOrEncryption != 200) {
                listener.onTalkState("talk refused: TalkConfig answered " + answer.statusOrEncryption);
                return;
            }
            s.socket.setSoTimeout(0);
            final BaichuanClient.Login live = s;
            Thread reader = new Thread(() -> drain(live), "reolink-talk-read");
            reader.setDaemon(true);
            reader.start();
            listener.onTalkState("open");
            stream(s, format);
        } catch (InterruptedException stopped) {
            // Stopped.
        } catch (BaichuanClient.LoginRefused refused) {
            if (running) listener.onTalkState("talk failed: " + refused.getMessage());
        } catch (Exception error) {
            if (running) listener.onTalkState("talk failed: " + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
        } finally {
            if (s != null) {
                try {
                    send(s, CMD_TALK_RESET, extension(s, false), new byte[0]);  // free the doorbell's speaker for others
                } catch (IOException | RuntimeException ignored) {
                    // The session is gone anyway.
                }
                try {
                    s.close();
                } catch (IOException ignored) {
                    // Closed anyway.
                }
            }
            synchronized (this) {
                session = null;
            }
        }
    }

    /** One block per block length against the wall clock; after a stall it skips ahead instead of bursting. */
    private void stream(BaichuanClient.Login s, Format format) throws IOException, InterruptedException {
        int codeBytes = format.lengthPerEncoder / 2;
        int perBlock = ImaAdpcm.samplesPerBlock(codeBytes);
        blockSamples = perBlock;
        long blockNanos = perBlock * 1_000_000_000L / format.rate;
        ImaAdpcm encoder = new ImaAdpcm(SURVIVORS);
        short[] pcm = new short[perBlock];
        byte[] ext = extension(s, true);
        int talkId = s.nextId(0);
        long next = System.nanoTime();
        while (running) {
            queue.next(pcm, perBlock);
            long started = System.nanoTime();
            byte[] block = ImaAdpcm.highNibbleFirst(encoder.encodeBlock(pcm, 0, codeBytes));
            encodeNanos += System.nanoTime() - started;
            send(s, talkId, CMD_TALK, ext, mediaPacket(block));
            blocksSent++;
            next += blockNanos;
            long wait = next - System.nanoTime();
            if (wait > 0) Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
            else if (-wait > 200_000_000L) next = System.nanoTime();  // more than 200 ms behind: resynchronize
        }
    }

    /** The doorbell answers every block, and may send its own audio on a full-duplex session: read and drop it. */
    private void drain(BaichuanClient.Login s) {
        try {
            while (running) BcMessage.read(s.in);
        } catch (IOException ended) {
            if (running) listener.onTalkState("talk ended: " + (ended.getMessage() == null ? "connection closed" : ended.getMessage()));
        }
    }

    /** One BcMedia ADPCM packet as the Reolink app writes it: magic, the size twice, 0x0100, 2, the block, no padding. */
    static byte[] mediaPacket(byte[] block) {
        ByteBuffer b = ByteBuffer.allocate(12 + block.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(MEDIA_ADPCM);
        b.putShort((short) (block.length + 4));
        b.putShort((short) (block.length + 4));
        b.putShort((short) MEDIA_ADPCM_DATA);
        b.putShort((short) 2);
        b.put(block);
        return b.array();
    }

    private static byte[] extension(BaichuanClient.Login s, boolean binary) {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<Extension version=\"1.1\">\n"
            + (binary ? "<binaryData>1</binaryData>\n" : "") + "<channelId>0</channelId>\n</Extension>\n";
        return BcCrypto.aes(s.aesKey(), xml.getBytes(StandardCharsets.UTF_8), true);
    }

    private static void send(BaichuanClient.Login s, int command, byte[] ext, byte[] body) throws IOException {
        send(s, s.nextId(0), command, ext, body);
    }

    private static void send(BaichuanClient.Login s, int id, int command, byte[] ext, byte[] body) throws IOException {
        byte[] message = BcMessage.encodeModern(command, id, ext, body);
        synchronized (s.out) {
            s.out.write(message);
            s.out.flush();
        }
    }

    /** Sends a request and waits for its answer, before the reader thread runs. */
    private static BcMessage request(BaichuanClient.Login s, int command, byte[] ext, byte[] body) throws IOException {
        int id = s.nextId(0);
        send(s, id, command, ext, body);
        while (true) {
            BcMessage m = BcMessage.read(s.in);
            if (m.command == command && m.messageId == id) {
                if (m.body.length == 0) return m;
                return new BcMessage(m.command, m.messageId, m.statusOrEncryption, m.messageClass, m.headerLength, BcCrypto.aes(s.aesKey(), m.body, false), m.payload);
            }
        }
    }

    /** The talk format the doorbell offers: its first ADPCM audioConfig. */
    static final class Format {
        final int rate, lengthPerEncoder;
        final String duplex, mode;

        private Format(int rate, int lengthPerEncoder, String duplex, String mode) {
            this.rate = rate;
            this.lengthPerEncoder = lengthPerEncoder;
            this.duplex = duplex;
            this.mode = mode;
        }

        /** From a TalkAbility answer (body already decrypted); null if it offers nothing this class can send. */
        static Format of(BcMessage ability) {
            if (ability.statusOrEncryption != 200) return null;
            String xml = new String(ability.body, StandardCharsets.UTF_8);
            if (xml.contains("<!DOCTYPE")) return null;
            int config = xml.indexOf("<audioConfig>");
            if (config < 0) return null;
            String audio = xml.substring(config, Math.max(config, xml.indexOf("</audioConfig>", config)));
            String type = value(audio, "audioType"), soundTrack = value(audio, "soundTrack");
            int rate = number(value(audio, "sampleRate")), length = number(value(audio, "lengthPerEncoder"));
            // The microphone path delivers 16 kHz mono; other rates would need resampling this class does not do.
            if (!"adpcm".equals(type) || rate != RATE || length < 2 || length > MAX_BLOCK_CODES || length % 2 != 0) return null;
            if (soundTrack != null && !soundTrack.equals("mono")) return null;
            String duplex = value(xml, "duplex"), mode = value(xml, "audioStreamMode");
            return new Format(rate, length, duplex == null ? "FDX" : duplex, mode == null ? "followVideoStream" : mode);
        }

        String talkConfig() {
            return "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<body>\n<TalkConfig version=\"1.1\">\n<channelId>0</channelId>\n"
                + "<duplex>" + duplex + "</duplex>\n<audioStreamMode>" + mode + "</audioStreamMode>\n<audioConfig>\n"
                + "<audioType>adpcm</audioType>\n<sampleRate>" + rate + "</sampleRate>\n<samplePrecision>16</samplePrecision>\n"
                + "<lengthPerEncoder>" + lengthPerEncoder + "</lengthPerEncoder>\n<soundTrack>mono</soundTrack>\n</audioConfig>\n"
                + "</TalkConfig>\n</body>\n";
        }

        private static String value(String xml, String tag) {
            Matcher m = TAG.matcher(xml);
            while (m.find()) if (m.group(1).equals(tag)) return m.group(2).trim();
            return null;
        }

        private static int number(String text) {
            try {
                return text == null ? -1 : Integer.parseInt(text);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
    }
}
