// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell.baichuan;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import io.github.patrick11994.reolinkdoorbell.talk.ImaAdpcm;

public final class BaichuanTalkTest {
    public static final String ABILITY = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<body>\n<TalkAbility version=\"1.1\">\n<duplexList>\n<duplex>FDX</duplex>\n"
        + "</duplexList>\n<audioStreamModeList>\n<audioStreamMode>followVideoStream</audioStreamMode>\n<audioStreamMode>mixAudioStream</audioStreamMode>\n"
        + "</audioStreamModeList>\n<audioConfigList>\n<audioConfig>\n<priority>0</priority>\n<audioType>adpcm</audioType>\n<sampleRate>16000</sampleRate>\n"
        + "<samplePrecision>16</samplePrecision>\n<lengthPerEncoder>1024</lengthPerEncoder>\n<soundTrack>mono</soundTrack>\n</audioConfig>\n</audioConfigList>\n"
        + "</TalkAbility>\n</body>\n";  // as the Reolink Video Doorbell PoE answers

    public static void main(String[] args) throws Exception {
        talksLikeTheApp();
        resetsAndRetriesWhenBusy();
        refusedWithoutAdpcm();
        silentDoorbellEndsTalk();
        talksOnAChannel();
        System.out.println("BaichuanTalkTest passed");
    }

    /** Behind an NVR, every talk request names the doorbell's channel: the fake checks each one, and the probe too. */
    static void talksOnAChannel() throws Exception {
        List<String> log = new CopyOnWriteArrayList<>();
        List<byte[]> bodies = new CopyOnWriteArrayList<>();
        List<String> states = new CopyOnWriteArrayList<>();
        try (ServerSocket server = new ServerSocket(0)) {
            Thread nvr = new Thread(() -> fakeTalkDoorbell(server, ABILITY, 0, log, bodies, true, 4));
            nvr.start();
            BaichuanTalk talk = new BaichuanTalk("127.0.0.1", server.getLocalPort(), BaichuanClientTest.USER, BaichuanClientTest.PASSWORD, 4, states::add, 2000);
            talk.start();
            io.github.patrick11994.reolinkdoorbell.Await.until(() -> bodies.size() >= 2, 5000, "talk blocks on channel 5");
            talk.stop();
            nvr.join(3000);
            check(log.contains("talk reset"), true);
        }
        try (ServerSocket server = new ServerSocket(0)) {
            Thread nvr = new Thread(() -> fakeTalkDoorbell(server, ABILITY, 0, new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>(), true, 4));
            nvr.setDaemon(true);
            nvr.start();
            check(BaichuanTalk.probe("127.0.0.1", server.getLocalPort(), BaichuanClientTest.USER, BaichuanClientTest.PASSWORD, 4) != null, true);
        }
    }

    /** TalkAbility, TalkConfig, then 528-byte blocks in the app's layout carrying our audio, and a reset at the end. */
    static void talksLikeTheApp() throws Exception {
        List<String> log = new CopyOnWriteArrayList<>();
        List<byte[]> bodies = new CopyOnWriteArrayList<>();
        List<String> states = new CopyOnWriteArrayList<>();
        try (ServerSocket server = new ServerSocket(0)) {
            Thread doorbell = new Thread(() -> fakeTalkDoorbell(server, ABILITY, 0, log, bodies));
            doorbell.start();
            BaichuanTalk talk = new BaichuanTalk("127.0.0.1", server.getLocalPort(), BaichuanClientTest.USER, BaichuanClientTest.PASSWORD, states::add, 2000);
            talk.start();
            short[] tone = new short[16000];
            for (int i = 0; i < tone.length; i++) tone[i] = (short) (8000 * Math.sin(2 * Math.PI * 1000 * i / 16000));
            io.github.patrick11994.reolinkdoorbell.Await.until(() -> states.contains("open"), 5000, "talk open");
            talk.write(tone, 3200);  // 200 ms, the most the queue keeps
            io.github.patrick11994.reolinkdoorbell.Await.until(() -> bodies.size() >= 8, 5000, "eight blocks");
            talk.stop();
            doorbell.join(3000);
            check(states.contains("open"), true);
            check(log.contains("talk config adpcm 16000 1024 FDX followVideoStream"), true);
            check(log.get(log.size() - 1), "talk reset");
            check(bodies.size() >= 6, true);  // one per 64 ms
            double loudest = 0;
            for (byte[] body : bodies) {
                check(body.length, 528);
                ByteBuffer b = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
                check(b.getInt(), BaichuanTalk.MEDIA_ADPCM);
                check((int) b.getShort(), 520);
                check((int) b.getShort(), 520);
                check((int) b.getShort(), 0x0100);
                check((int) b.getShort(), 2);
                byte[] block = java.util.Arrays.copyOfRange(body, 12, 528);
                short[] pcm = ImaAdpcm.decodeBlock(ImaAdpcm.highNibbleFirst(block));  // back to WAV order, then decode
                double sum = 0;
                for (short v : pcm) sum += v * (double) v;
                loudest = Math.max(loudest, Math.sqrt(sum / pcm.length));
            }
            check(loudest > 5000, true);  // the 8000-peak tone (rms 5657) came through in at least one block
            check(talk.blocksSent() >= bodies.size(), true);
        }
    }

    /** 422 (someone else talks): the app's answer is a TalkReset and the same TalkConfig again. */
    static void resetsAndRetriesWhenBusy() throws Exception {
        List<String> log = new CopyOnWriteArrayList<>();
        List<String> states = new CopyOnWriteArrayList<>();
        try (ServerSocket server = new ServerSocket(0)) {
            Thread doorbell = new Thread(() -> fakeTalkDoorbell(server, ABILITY, 1, log, new CopyOnWriteArrayList<>()));
            doorbell.start();
            BaichuanTalk talk = new BaichuanTalk("127.0.0.1", server.getLocalPort(), BaichuanClientTest.USER, BaichuanClientTest.PASSWORD, states::add, 2000);
            talk.start();
            io.github.patrick11994.reolinkdoorbell.Await.until(() -> log.stream().filter(l -> l.startsWith("talk config")).count() >= 2, 5000, "TalkConfig again after the reset");
            talk.stop();
            doorbell.join(3000);
            check(states.contains("open"), true);
            int first = log.indexOf("talk config adpcm 16000 1024 FDX followVideoStream");
            check(first >= 0, true);
            check(log.get(first + 1), "talk reset");
            check(log.get(first + 2), "talk config adpcm 16000 1024 FDX followVideoStream");
        }
    }

    /** A doorbell without ADPCM talk: refused, so Automatic can switch to ONVIF; no TalkConfig is sent. */
    static void refusedWithoutAdpcm() throws Exception {
        List<String> log = new CopyOnWriteArrayList<>();
        List<String> states = new CopyOnWriteArrayList<>();
        String noTalk = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<body>\n<TalkAbility version=\"1.1\">\n</TalkAbility>\n</body>\n";
        try (ServerSocket server = new ServerSocket(0)) {
            Thread doorbell = new Thread(() -> fakeTalkDoorbell(server, noTalk, 0, log, new CopyOnWriteArrayList<>()));
            doorbell.start();
            BaichuanTalk talk = new BaichuanTalk("127.0.0.1", server.getLocalPort(), BaichuanClientTest.USER, BaichuanClientTest.PASSWORD, states::add, 2000);
            talk.start();
            io.github.patrick11994.reolinkdoorbell.Await.until(() -> states.stream().anyMatch(st -> st.startsWith("talk refused")), 5000, "the refusal");
            talk.stop();
            doorbell.join(3000);
            check(states.stream().anyMatch(s -> s.startsWith("talk refused")), true);
            check(log.stream().anyMatch(s -> s.startsWith("talk config")), false);
        }
    }

    /** A doorbell that stops answering the blocks: talk ends within the timeout, reported once, not when it comes back. */
    static void silentDoorbellEndsTalk() throws Exception {
        List<String> states = new CopyOnWriteArrayList<>();
        try (ServerSocket server = new ServerSocket(0)) {
            Thread doorbell = new Thread(() -> fakeTalkDoorbell(server, ABILITY, 0, new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>(), false));
            doorbell.start();
            BaichuanTalk talk = new BaichuanTalk("127.0.0.1", server.getLocalPort(), BaichuanClientTest.USER, BaichuanClientTest.PASSWORD, states::add, 1000);
            talk.start();
            long took;
            try {
                io.github.patrick11994.reolinkdoorbell.Await.until(() -> states.contains("open"), 5000, "talk open");
                long opened = System.nanoTime();
                io.github.patrick11994.reolinkdoorbell.Await.until(() -> states.stream().anyMatch(s -> s.startsWith("talk ended")), 5000, "talk ended");
                took = (System.nanoTime() - opened) / 1_000_000;
                Thread.sleep(300);  // the sender notices the closed connection too: that must not be a second report
            } finally {
                talk.stop();  // a failure must not leave talk sending, which keeps the test running
            }
            doorbell.join(3000);
            check(states.stream().filter(s -> s.startsWith("talk ended") || s.startsWith("talk failed")).count(), 1L);
            check(states.contains("talk ended: the doorbell stopped answering"), true);
            check(took < 2500, true);  // the 1 s timeout, not the doorbell's return
        }
    }

    /** A doorbell's talk session: answers TalkAbility, TalkConfig (busyFirst times 422 first), and every block. */
    public static void fakeTalkDoorbell(ServerSocket server, String ability, int busyFirst, List<String> log, List<byte[]> bodies) {
        fakeTalkDoorbell(server, ability, busyFirst, log, bodies, true);
    }

    /** answerBlocks false: the doorbell goes quiet once talk is set up, as one that lost power does. */
    static void fakeTalkDoorbell(ServerSocket server, String ability, int busyFirst, List<String> log, List<byte[]> bodies, boolean answerBlocks) {
        fakeTalkDoorbell(server, ability, busyFirst, log, bodies, answerBlocks, 0);
    }

    /** channel: the channel every request must name, in its extension, its header and TalkConfig (an NVR's camera). */
    static void fakeTalkDoorbell(ServerSocket server, String ability, int busyFirst, List<String> log, List<byte[]> bodies, boolean answerBlocks, int channel) {
        try (Socket socket = server.accept()) {
            socket.setSoTimeout(3000);
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            OutputStream out = socket.getOutputStream();
            byte[] key = BaichuanClientTest.fakeLogin(in, out, BaichuanClientTest.PASSWORD, log);
            if (key == null) return;
            int busy = busyFirst;
            while (true) {
                BcMessage m = BcMessage.read(in);
                String ext = new String(BcCrypto.aes(key, m.body, false), StandardCharsets.UTF_8);
                check(ext.contains("<channelId>" + channel + "</channelId>"), true);
                check(m.messageId & 0xFF, channel);
                switch (m.command) {
                    case BaichuanTalk.CMD_TALK_ABILITY:
                        out.write(BaichuanClientTest.reply24(m.command, m.messageId, 200, BcCrypto.aes(key, ability.getBytes(StandardCharsets.UTF_8), true)));
                        break;
                    case BaichuanTalk.CMD_TALK_CONFIG: {
                        String xml = new String(BcCrypto.aes(key, m.payload, false), StandardCharsets.UTF_8);
                        check(tag(xml, "channelId"), String.valueOf(channel));
                        log.add("talk config " + tag(xml, "audioType") + " " + tag(xml, "sampleRate") + " " + tag(xml, "lengthPerEncoder") + " "
                            + tag(xml, "duplex") + " " + tag(xml, "audioStreamMode"));
                        out.write(BaichuanClientTest.reply24(m.command, m.messageId, busy-- > 0 ? 422 : 200, new byte[0]));
                        break;
                    }
                    case BaichuanTalk.CMD_TALK:
                        check(ext.contains("<binaryData>1</binaryData>"), true);
                        bodies.add(m.payload);  // binary, not encrypted
                        if (answerBlocks) out.write(BaichuanClientTest.reply24(m.command, m.messageId, 200, new byte[0]));
                        break;
                    case BaichuanTalk.CMD_TALK_RESET:
                        log.add("talk reset");
                        out.write(BaichuanClientTest.reply24(m.command, m.messageId, 200, new byte[0]));
                        break;
                    default:
                        log.add("unexpected cmd " + m.command);
                }
                out.flush();
            }
        } catch (Exception closed) {
            // The talk session ended.
        }
    }

    private static String tag(String xml, String name) {
        int a = xml.indexOf("<" + name + ">"), b = xml.indexOf("</" + name + ">");
        return a < 0 || b < 0 ? "?" : xml.substring(a + name.length() + 2, b);
    }

    private static void check(Object actual, Object expected) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " but got " + actual);
    }
}
