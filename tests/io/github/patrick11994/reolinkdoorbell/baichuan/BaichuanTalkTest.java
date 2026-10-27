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
    static final String ABILITY = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<body>\n<TalkAbility version=\"1.1\">\n<duplexList>\n<duplex>FDX</duplex>\n"
        + "</duplexList>\n<audioStreamModeList>\n<audioStreamMode>followVideoStream</audioStreamMode>\n<audioStreamMode>mixAudioStream</audioStreamMode>\n"
        + "</audioStreamModeList>\n<audioConfigList>\n<audioConfig>\n<priority>0</priority>\n<audioType>adpcm</audioType>\n<sampleRate>16000</sampleRate>\n"
        + "<samplePrecision>16</samplePrecision>\n<lengthPerEncoder>1024</lengthPerEncoder>\n<soundTrack>mono</soundTrack>\n</audioConfig>\n</audioConfigList>\n"
        + "</TalkAbility>\n</body>\n";  // as the Reolink Video Doorbell PoE answers

    public static void main(String[] args) throws Exception {
        talksLikeTheApp();
        resetsAndRetriesWhenBusy();
        refusedWithoutAdpcm();
        System.out.println("BaichuanTalkTest passed");
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
            Thread.sleep(150);
            talk.write(tone, 3200);  // 200 ms, the most the queue keeps
            Thread.sleep(600);
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
            Thread.sleep(400);
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
            Thread.sleep(400);
            talk.stop();
            doorbell.join(3000);
            check(states.stream().anyMatch(s -> s.startsWith("talk refused")), true);
            check(log.stream().anyMatch(s -> s.startsWith("talk config")), false);
        }
    }

    /** A doorbell's talk session: answers TalkAbility, TalkConfig (busyFirst times 422 first), and every block. */
    static void fakeTalkDoorbell(ServerSocket server, String ability, int busyFirst, List<String> log, List<byte[]> bodies) {
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
                check(ext.contains("<channelId>0</channelId>"), true);
                switch (m.command) {
                    case BaichuanTalk.CMD_TALK_ABILITY:
                        out.write(BaichuanClientTest.reply24(m.command, m.messageId, 200, BcCrypto.aes(key, ability.getBytes(StandardCharsets.UTF_8), true)));
                        break;
                    case BaichuanTalk.CMD_TALK_CONFIG: {
                        String xml = new String(BcCrypto.aes(key, m.payload, false), StandardCharsets.UTF_8);
                        log.add("talk config " + tag(xml, "audioType") + " " + tag(xml, "sampleRate") + " " + tag(xml, "lengthPerEncoder") + " "
                            + tag(xml, "duplex") + " " + tag(xml, "audioStreamMode"));
                        out.write(BaichuanClientTest.reply24(m.command, m.messageId, busy-- > 0 ? 422 : 200, new byte[0]));
                        break;
                    }
                    case BaichuanTalk.CMD_TALK:
                        check(ext.contains("<binaryData>1</binaryData>"), true);
                        bodies.add(m.payload);  // binary, not encrypted
                        out.write(BaichuanClientTest.reply24(m.command, m.messageId, 200, new byte[0]));
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
