// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell.baichuan;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.patrick11994.reolinkdoorbell.talk.ImaAdpcm;

/**
 * Spike, run by hand against a real doorbell (REOLINK_HOST/USER/PASSWORD): talking over Baichuan instead of the
 * ONVIF backchannel. Logs in, prints the doorbell's TalkAbility, sends a TalkConfig for its first ADPCM config, then
 * streams ADPCM in real time: silence with beeps of 700, 1000, 1300, 1600 and 1900 Hz, 4 s apart, each logged as
 * "beep <pitch> Hz sent at <epoch ms>" for mac_mic_meter.py. An optional 16 kHz mono 16-bit WAV is played after the
 * beeps, to judge the sound. Ends with TalkReset. Protocol learned from neolink (AGPL-3.0); no code copied.
 *
 *     java -cp <classes> io.github.patrick11994.reolinkdoorbell.baichuan.LiveBaichuanTalk [speech.wav] [--encrypt-audio] [--ability] [--no-beeps]
 *
 * --ability stops after printing the TalkAbility: no sound at the door. --no-beeps plays only the WAV.
 */
public final class LiveBaichuanTalk {
    static final int CMD_TALK_ABILITY = 10, CMD_TALK_RESET = 11, CMD_TALK_CONFIG = 201, CMD_TALK = 202;
    static final int CHANNEL = 0;
    static final int MEDIA_ADPCM = 0x62773130, MEDIA_ADPCM_DATA = 0x0100;
    private static final Pattern NONCE = Pattern.compile("<nonce>([^<]+)</nonce>");

    private final DataInputStream in;
    private final OutputStream out;
    private final String password;
    private byte[] aesKey;
    private int counter;
    private final long started = System.currentTimeMillis();

    private LiveBaichuanTalk(Socket socket, String password) throws IOException {
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = socket.getOutputStream();
        this.password = password;
    }

    public static void main(String[] args) throws Exception {
        String host = System.getenv("REOLINK_HOST"), user = System.getenv("REOLINK_USER"), pass = System.getenv("REOLINK_PASSWORD");
        if (host == null || user == null || pass == null) throw new IllegalStateException("set REOLINK_HOST, REOLINK_USER and REOLINK_PASSWORD");
        String wav = null;
        boolean encryptAudio = false, abilityOnly = false, noBeeps = false;
        for (String a : args) {
            if (a.equals("--encrypt-audio")) encryptAudio = true;
            else if (a.equals("--ability")) abilityOnly = true;
            else if (a.equals("--no-beeps")) noBeeps = true;
            else wav = a;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, 9000), 5000);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(5000);
            LiveBaichuanTalk talk = new LiveBaichuanTalk(socket, pass);
            talk.login(user);
            talk.run(wav, encryptAudio, abilityOnly, noBeeps);
        }
    }

    private void login(String user) throws IOException {
        int nonceId = nextId(250);
        out.write(BcMessage.encodeNonceRequest(nonceId));
        out.flush();
        String reply = new String(BcCrypto.xor(await(1, nonceId).body, 250), StandardCharsets.UTF_8);
        Matcher nonce = NONCE.matcher(reply);
        if (!nonce.find()) throw new IOException("no nonce");
        aesKey = BcCrypto.aesKey(nonce.group(1), password);
        String login = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<body>\n<LoginUser version=\"1.1\">\n"
            + "<userName>" + BcCrypto.md5Modern(user + nonce.group(1)) + "</userName>\n"
            + "<password>" + BcCrypto.md5Modern(password + nonce.group(1)) + "</password>\n"
            + "<userVer>1</userVer>\n</LoginUser>\n<LoginNet version=\"1.1\">\n<type>LAN</type>\n<udpPort>0</udpPort>\n</LoginNet>\n</body>\n";
        int loginId = nextId(250);
        out.write(BcMessage.encodeModern(1, loginId, new byte[0], BcCrypto.xor(login.getBytes(StandardCharsets.UTF_8), 250)));
        out.flush();
        BcMessage answer = await(1, loginId);
        log("login status " + answer.statusOrEncryption);
        if (answer.statusOrEncryption != 200) throw new IOException("login refused");
    }

    private void run(String wav, boolean encryptAudio, boolean abilityOnly, boolean noBeeps) throws Exception {
        // 1. What the doorbell can take.
        BcMessage ability = request(CMD_TALK_ABILITY, extension(false), new byte[0]);
        String abilityXml = decrypt(ability.body, ability);
        log("TalkAbility status " + ability.statusOrEncryption + ":\n" + abilityXml);
        String config = first(abilityXml, "audioConfig");
        if (config == null) throw new IOException("no audioConfig in TalkAbility");
        if (abilityOnly) return;
        int rate = Integer.parseInt(first(config, "sampleRate"));
        int lengthPerEncoder = Integer.parseInt(first(config, "lengthPerEncoder"));
        String type = first(config, "audioType");
        if (!"adpcm".equals(type)) throw new IOException("audio type " + type + ", not adpcm");
        String duplex = first(abilityXml, "duplex");
        String mode = first(abilityXml, "audioStreamMode");

        // 2. Ask for that format; 422 means someone else talks (or a crashed session): reset and retry once.
        String talkConfig = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<body>\n<TalkConfig version=\"1.1\">\n"
            + "<channelId>" + CHANNEL + "</channelId>\n<duplex>" + (duplex == null ? "FDX" : duplex) + "</duplex>\n"
            + "<audioStreamMode>" + (mode == null ? "followVideoStream" : mode) + "</audioStreamMode>\n"
            + "<audioConfig>\n<audioType>adpcm</audioType>\n<sampleRate>" + rate + "</sampleRate>\n<samplePrecision>16</samplePrecision>\n"
            + "<lengthPerEncoder>" + lengthPerEncoder + "</lengthPerEncoder>\n<soundTrack>mono</soundTrack>\n</audioConfig>\n</TalkConfig>\n</body>\n";
        long configSent = System.currentTimeMillis();
        BcMessage configured = request(CMD_TALK_CONFIG, extension(false), BcCrypto.aes(aesKey, talkConfig.getBytes(StandardCharsets.UTF_8), true));
        if (configured.statusOrEncryption == 422) {
            log("TalkConfig 422: resetting and retrying");
            request(CMD_TALK_RESET, extension(false), new byte[0]);
            configured = request(CMD_TALK_CONFIG, extension(false), BcCrypto.aes(aesKey, talkConfig.getBytes(StandardCharsets.UTF_8), true));
        }
        log("TalkConfig status " + configured.statusOrEncryption + " after " + (System.currentTimeMillis() - configSent) + " ms (rate " + rate + ", lengthPerEncoder " + lengthPerEncoder + ")");
        if (configured.statusOrEncryption != 200) throw new IOException("TalkConfig refused");

        // 3. The audio: silence with five beeps, then the optional speech.
        int codeBytes = lengthPerEncoder / 2;
        int perBlock = ImaAdpcm.samplesPerBlock(codeBytes);
        short[] beeps = new short[noBeeps ? rate : rate * 22];
        int[] pitches = noBeeps ? new int[0] : new int[] {700, 1000, 1300, 1600, 1900};
        int[] beepAt = new int[pitches.length];
        for (int p = 0; p < pitches.length; p++) {
            beepAt[p] = rate * (2 + 4 * p);
            for (int i = 0; i < rate * 3 / 10; i++) {
                double fade = Math.min(1, Math.min(i, rate * 3 / 10 - i) / (rate * 0.01));
                beeps[beepAt[p] + i] = (short) (16000 * fade * Math.sin(2 * Math.PI * pitches[p] * i / rate));
            }
        }
        short[] speech = wav == null ? new short[0] : readWav(wav, rate);
        short[] all = new short[beeps.length + speech.length + rate];
        System.arraycopy(beeps, 0, all, 0, beeps.length);
        System.arraycopy(speech, 0, all, beeps.length, speech.length);

        Thread replies = new Thread(this::readReplies, "talk-replies");
        replies.setDaemon(true);
        replies.start();

        ImaAdpcm encoder = new ImaAdpcm();
        int talkId = nextId(CHANNEL);
        byte[] ext = extension(true);
        long t0 = System.nanoTime();
        int nextBeep = 0;
        long blocks = 0;
        for (int offset = 0; offset + perBlock <= all.length; offset += perBlock) {
            while (nextBeep < beepAt.length && beepAt[nextBeep] < offset + perBlock) {
                // The beep's first sample leaves with this block; report when it is due to play from our side.
                long due = System.currentTimeMillis() + Math.max(0, (beepAt[nextBeep] - offset) * 1000L / rate);
                System.out.println("beep " + pitches[nextBeep] + " Hz sent at " + due);
                nextBeep++;
            }
            byte[] block = encoder.encodeBlock(all, offset, codeBytes);
            byte[] media = mediaPacket(block);
            byte[] body = encryptAudio ? BcCrypto.aes(aesKey, media, true) : media;
            synchronized (out) {
                out.write(BcMessage.encodeModern(CMD_TALK, talkId, ext, body));
                out.flush();
            }
            blocks++;
            // Real time: the next block leaves when this one is due to have played.
            long dueNanos = t0 + blocks * perBlock * 1_000_000_000L / rate;
            long wait = dueNanos - System.nanoTime();
            if (wait > 0) Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
        }
        log("sent " + blocks + " blocks of " + perBlock + " samples (" + (perBlock * 1000 / rate) + " ms each)");
        Thread.sleep(2500);  // let the doorbell play out its buffer before the reset cuts it off
        synchronized (out) {
            out.write(BcMessage.encodeModern(CMD_TALK_RESET, nextId(CHANNEL), extension(false), new byte[0]));
            out.flush();
        }
        Thread.sleep(500);
        log("done");
    }

    /** One BcMedia ADPCM packet: magic, the size twice, the data magic, half the code bytes, then the block, padded to 8. */
    static byte[] mediaPacket(byte[] block) {
        int pad = (8 - block.length % 8) % 8;
        ByteBuffer b = ByteBuffer.allocate(12 + block.length + pad).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(MEDIA_ADPCM);
        b.putShort((short) (block.length + 4));
        b.putShort((short) (block.length + 4));
        b.putShort((short) MEDIA_ADPCM_DATA);
        b.putShort((short) ((block.length - 4) / 2));
        b.put(block);
        return b.array();
    }

    private byte[] extension(boolean binary) {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<Extension version=\"1.1\">\n"
            + (binary ? "<binaryData>1</binaryData>\n" : "") + "<channelId>" + CHANNEL + "</channelId>\n</Extension>\n";
        return BcCrypto.aes(aesKey, xml.getBytes(StandardCharsets.UTF_8), true);
    }

    private BcMessage request(int command, byte[] ext, byte[] body) throws IOException {
        int id = nextId(CHANNEL);
        out.write(BcMessage.encodeModern(command, id, ext, body));
        out.flush();
        BcMessage answer = await(command, id);
        log("cmd " + command + " -> status " + answer.statusOrEncryption);
        return answer;
    }

    private void readReplies() {
        int shown = 0;
        try {
            while (true) {
                BcMessage m = BcMessage.read(in);
                if (m.command == CMD_TALK && shown < 5 || m.command != CMD_TALK) {
                    log("<- cmd " + m.command + " status " + m.statusOrEncryption + " body " + m.body.length + " payload " + m.payload.length);
                    if (m.command == CMD_TALK) shown++;
                }
            }
        } catch (IOException ended) {
            log("replies: " + ended.getMessage());
        }
    }

    private BcMessage await(int command, int id) throws IOException {
        while (true) {
            BcMessage m = BcMessage.read(in);
            if (m.command == command && m.messageId == id) return m;
            log("(skipped cmd " + m.command + ")");
        }
    }

    private String decrypt(byte[] body, BcMessage m) {
        if (body.length == 0) return "";
        return new String(BcCrypto.aes(aesKey, body, false), StandardCharsets.UTF_8);
    }

    private static String first(String xml, String tag) {
        Matcher m = Pattern.compile("<" + tag + ">(.*?)</" + tag + ">", Pattern.DOTALL).matcher(xml);
        return m.find() ? m.group(1).trim() : null;
    }

    private static short[] readWav(String path, int rate) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(Paths.get(path))).order(ByteOrder.LITTLE_ENDIAN);
        b.position(12);
        int channels = 0, sampleRate = 0, bits = 0;
        while (b.remaining() >= 8) {
            String id = new String(new byte[] {b.get(), b.get(), b.get(), b.get()}, StandardCharsets.US_ASCII);
            int size = b.getInt();
            if (id.equals("fmt ")) {
                b.getShort();
                channels = b.getShort();
                sampleRate = b.getInt();
                b.getInt();
                b.getShort();
                bits = b.getShort();
                b.position(b.position() + size - 16);
            } else if (id.equals("data")) {
                if (channels != 1 || sampleRate != rate || bits != 16) throw new IOException("need " + rate + " Hz mono 16-bit, got " + sampleRate + " Hz " + channels + " ch " + bits + " bit");
                short[] pcm = new short[size / 2];
                b.asShortBuffer().get(pcm);
                return pcm;
            } else {
                b.position(b.position() + size + (size & 1));
            }
        }
        throw new IOException("no data chunk in " + path);
    }

    private int nextId(int channel) {
        counter = (counter + 1) & 0xFFFFFF;
        return (channel & 0xFF) | (counter << 8);
    }

    private void log(String message) {
        System.out.println((System.currentTimeMillis() - started) + " ms " + message);
    }
}
