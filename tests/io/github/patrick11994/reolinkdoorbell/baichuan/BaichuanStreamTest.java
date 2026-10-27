// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell.baichuan;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class BaichuanStreamTest {
    public static void main(String[] args) throws Exception {
        parsesFramesAcrossMessages();
        adtsBecomesTheRtspConfig();
        skipsJunkAndCapsFrames();
        streamsFromAFakeDoorbell();
        System.out.println("BaichuanStreamTest passed");
    }

    /** Info, a keyframe split over three feeds, a P-frame and audio: each comes out once, whole. */
    static void parsesFramesAcrossMessages() {
        List<String> out = new ArrayList<>();
        BcMediaParser p = new BcMediaParser(listener(out));
        byte[] stream = concat(info(2560, 1920, 20), video("00dc", annexB(5, 1000), 1_000_000), video("01dc", annexB(1, 13), 1_050_000), aac(adts(40)));
        p.feed(stream, 0, 20);
        p.feed(stream, 20, 600);
        p.feed(stream, 620, stream.length - 620);
        check(out.toString(), "[info 2560x1920@20, video key 1004 bytes @1000000, video 17 bytes @1050000, aac 40 bytes config 1408 @16000]");
        check(p.skipped(), 0L);
    }

    /** The doorbell's ADTS header (ff f9 60 40) says AAC-LC, 16 kHz, mono: the same 1408 RTSP announces. */
    static void adtsBecomesTheRtspConfig() {
        List<String> out = new ArrayList<>();
        BcMediaParser p = new BcMediaParser(listener(out));
        byte[] frame = aac(adts(100));
        p.feed(frame, 0, frame.length);
        check(out.toString(), "[aac 100 bytes config 1408 @16000]");
    }

    /** Bytes that are no frame are skipped up to the next magic; a frame claiming more than MAX_FRAME is not trusted. */
    static void skipsJunkAndCapsFrames() {
        List<String> out = new ArrayList<>();
        BcMediaParser p = new BcMediaParser(listener(out));
        byte[] huge = video("00dc", new byte[8], 0);
        ByteBuffer.wrap(huge).order(ByteOrder.LITTLE_ENDIAN).putInt(8, BcMediaParser.MAX_FRAME + 1);
        byte[] stream = concat(new byte[] {1, 2, 3, 4, 5}, huge, video("01dc", annexB(1, 5), 7));
        p.feed(stream, 0, stream.length);
        check(out.toString(), "[video 9 bytes @7]");
        check(p.skipped() > 0, true);
    }

    /** The stream's session: Preview with newestIFrame, then frames whose first encryptLen bytes are encrypted. */
    static void streamsFromAFakeDoorbell() throws Exception {
        List<String> log = new CopyOnWriteArrayList<>();
        List<String> seen = new CopyOnWriteArrayList<>();
        try (ServerSocket server = new ServerSocket(0)) {
            Thread doorbell = new Thread(() -> fakePreviewDoorbell(server, log));
            doorbell.start();
            BaichuanStream stream = new BaichuanStream("127.0.0.1", server.getLocalPort(), BaichuanClientTest.USER, BaichuanClientTest.PASSWORD, false, s -> log.add("state " + s), 2000);
            stream.attachVideo((au, micros, key) -> seen.add("video " + au.length + (key ? " key" : "") + " @" + micros));
            stream.attachAudio(new io.github.patrick11994.reolinkdoorbell.media.MediaStream.AudioSink() {
                @Override public void onAudioFormat(byte[] config, int rate) { seen.add("format " + String.format("%02x%02x", config[0], config[1]) + " @" + rate); }
                @Override public void onAudio(byte[] frame, long micros) { seen.add("audio " + frame.length + " @" + micros); }
            });
            stream.start();
            Thread.sleep(500);
            check(stream.firstKeyframeMillis() >= 0, true);
            stream.stop();
            doorbell.join(3000);
            check(log.contains("preview mainStream newestIFrame"), true);
            check(log.contains("state streaming"), true);
            check(seen.toString(), "[video 1004 key @0, video 17 @50000, format 1408 @16000, audio 40 @0, audio 40 @64000]");
            check(log.get(log.size() - 1), "preview stop");
        }
    }

    static void fakePreviewDoorbell(ServerSocket server, List<String> log) {
        try (Socket socket = server.accept()) {
            socket.setSoTimeout(3000);
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            OutputStream out = socket.getOutputStream();
            byte[] key = BaichuanClientTest.fakeLogin(in, out, BaichuanClientTest.PASSWORD, log);
            if (key == null) return;
            while (true) {
                BcMessage m = BcMessage.read(in);
                String xml = new String(BcCrypto.aes(key, m.body, false), StandardCharsets.UTF_8);
                if (m.command == BaichuanStream.CMD_PREVIEW) {
                    log.add("preview " + tag(xml, "streamType") + " " + tag(xml, "policy"));
                    // As the doorbell does it: a plain info header, then a keyframe spread over two messages whose first
                    // part is encrypted (encryptLen), then a P-frame and two audio frames in one encrypted message.
                    byte[] key1 = video("00dc", annexB(5, 1000), 3_000_000);
                    send(out, m.messageId, key, info(2560, 1920, 20), -1);
                    send(out, m.messageId, key, java.util.Arrays.copyOf(key1, 512), 512);
                    send(out, m.messageId, key, java.util.Arrays.copyOfRange(key1, 512, key1.length), -1);
                    byte[] rest = concat(video("01dc", annexB(1, 13), 3_050_000), aac(adts(40)), aac(adts(40)));
                    send(out, m.messageId, key, rest, rest.length);
                } else if (m.command == BaichuanStream.CMD_PREVIEW_STOP) {
                    log.add("preview stop");
                }
                out.flush();
            }
        } catch (Exception closed) {
            // The stream stopped.
        }
    }

    /** One cmd 3 answer: an extension with encryptLen (or none, -1), and the payload with its first part encrypted. */
    static void send(OutputStream out, int id, byte[] key, byte[] payload, int encryptLen) throws Exception {
        String ext = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<Extension version=\"1.1\">\n" + (encryptLen >= 0 ? "<encryptLen>" + encryptLen + "</encryptLen>\n" : "")
            + "<binaryData>1</binaryData>\n</Extension>\n";
        byte[] e = BcCrypto.aes(key, ext.getBytes(StandardCharsets.UTF_8), true);
        byte[] body = payload.clone();
        if (encryptLen > 0) System.arraycopy(BcCrypto.aes(key, java.util.Arrays.copyOf(payload, encryptLen), true), 0, body, 0, encryptLen);
        ByteArrayOutputStream m = new ByteArrayOutputStream();
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(BcMessage.MAGIC).putInt(BaichuanStream.CMD_PREVIEW).putInt(e.length + body.length).putInt(id).putShort((short) 200).putShort((short) BcMessage.CLASS_ZERO).putInt(e.length);
        m.write(h.array());
        m.write(e);
        m.write(body);
        out.write(m.toByteArray());
    }

    static BcMediaParser.Listener listener(List<String> out) {
        return new BcMediaParser.Listener() {
            @Override public void onInfo(int w, int h, int fps) { out.add("info " + w + "x" + h + "@" + fps); }
            @Override public void onVideo(byte[] a, long micros, boolean key, boolean h265) { out.add("video " + (key ? "key " : "") + a.length + " bytes @" + micros); }
            @Override public void onAac(byte[] raw, byte[] config, int rate) { out.add("aac " + raw.length + " bytes config " + String.format("%02x%02x", config[0], config[1]) + " @" + rate); }
        };
    }

    static byte[] info(int w, int h, int fps) {
        ByteBuffer b = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        b.put("1002".getBytes(StandardCharsets.US_ASCII)).putInt(32).putInt(w).putInt(h).put((byte) 0).put((byte) fps);
        return b.array();
    }

    /** A BcMedia video frame with an 8-byte extra header, padded to 8. */
    static byte[] video(String magic, byte[] annexB, long micros) {
        int pad = (8 - annexB.length % 8) % 8;
        ByteBuffer b = ByteBuffer.allocate(24 + 8 + annexB.length + pad).order(ByteOrder.LITTLE_ENDIAN);
        b.put(magic.getBytes(StandardCharsets.US_ASCII)).put("H264".getBytes(StandardCharsets.US_ASCII)).putInt(annexB.length).putInt(8).putInt((int) micros).putInt(0);
        b.putLong(0x1122334455667788L);
        b.put(annexB);
        return b.array();
    }

    /** A BcMedia audio frame around an ADTS frame, padded to 8. */
    static byte[] aac(byte[] adts) {
        int pad = (8 - adts.length % 8) % 8;
        ByteBuffer b = ByteBuffer.allocate(8 + adts.length + pad).order(ByteOrder.LITTLE_ENDIAN);
        b.put("05wb".getBytes(StandardCharsets.US_ASCII)).putShort((short) adts.length).putShort((short) adts.length).put(adts);
        return b.array();
    }

    /** An ADTS frame as the doorbell sends it: AAC-LC, 16 kHz, mono, no CRC, then rawLength bytes. */
    static byte[] adts(int rawLength) {
        int total = 7 + rawLength;
        byte[] a = new byte[total];
        a[0] = (byte) 0xFF;
        a[1] = (byte) 0xF9;
        a[2] = 0x60;
        a[3] = (byte) (0x40 | (total >> 11) & 3);
        a[4] = (byte) (total >> 3);
        a[5] = (byte) ((total & 7) << 5 | 0x1F);
        a[6] = (byte) 0xFC;
        return a;
    }

    static byte[] annexB(int nalType, int length) {
        byte[] a = new byte[length + 4];
        a[3] = 1;
        a[4] = (byte) (0x60 | nalType);
        return a;
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    private static String tag(String xml, String name) {
        int a = xml.indexOf("<" + name + ">"), b = xml.indexOf("</" + name + ">");
        return a < 0 || b < 0 ? "?" : xml.substring(a + name.length() + 2, b);
    }

    private static void check(Object actual, Object expected) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " but got " + actual);
    }
}
