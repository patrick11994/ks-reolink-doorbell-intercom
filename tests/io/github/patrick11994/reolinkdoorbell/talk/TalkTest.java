// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell.talk;

import java.io.DataInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class TalkTest {
    public static void main(String[] args) throws Exception {
        g711MatchesAudioop();
        downsamplerFilters();
        cushionAndCap();
        backchannelAgainstFakeDoorbell();
        System.out.println("TalkTest passed");
    }

    /** SHA-256 of Python audioop.lin2ulaw / lin2alaw over every 16-bit sample, -32768 to 32767. */
    static void g711MatchesAudioop() throws Exception {
        byte[] ulaw = new byte[65536], alaw = new byte[65536];
        for (int s = -32768; s < 32768; s++) {
            ulaw[s + 32768] = G711.ulaw(s);
            alaw[s + 32768] = G711.alaw(s);
        }
        check(sha256(ulaw), "81d633c9e6972a18c74a58720b96cb8ca0bdd096d4060b646dd708c3b846019a");
        check(sha256(alaw), "38488f6fd710f4686360edc4d38639f96c491595ef93f8eb8d62d5e07ca6ce7b");
    }

    /** 48 or 16 kHz to 8 kHz: 1 kHz speech passes almost unchanged, 6 kHz (would alias to 2 kHz) is strongly cut. 8 kHz passes through. */
    static void downsamplerFilters() {
        for (int rate : new int[] {48000, 16000}) {
            check(peakAfter(rate, 1000) > 9000, true);
            check(peakAfter(rate, 6000) < 1500, true);
        }
        check(peakAfter(8000, 1000) > 9900, true);
        try {
            new Downsampler(44100, 8000);
            throw new AssertionError("44.1 kHz is not a multiple of 8 kHz");
        } catch (IllegalArgumentException expected) {
            // Good.
        }
    }

    private static int peakAfter(int rate, int frequency) {
        Downsampler d = new Downsampler(rate, 8000);
        short[] in = new short[rate], out = new short[8000];
        for (int i = 0; i < in.length; i++) in[i] = (short) (10000 * Math.sin(2 * Math.PI * frequency * i / rate));
        int n = d.process(in, in.length, out, 0);
        check(n, 8000);
        int peak = 0;
        for (int i = 1000; i < n; i++) peak = Math.max(peak, Math.abs(out[i]));
        return peak;
    }

    static void cushionAndCap() {
        Backchannel b = new Backchannel("unused", 554, "/", "u", "p", state -> {});
        short[] packet = new short[Backchannel.PACKET_SAMPLES];
        b.write(filled(160, (short) 100), 160);  // 20 ms: less than the 40 ms cushion
        b.nextPacket(packet);
        check(packet[0], (short) 0);
        b.write(filled(160, (short) 200), 160);  // now 40 ms: playing starts, oldest first
        b.nextPacket(packet);
        check(packet[0], (short) 100);
        b.nextPacket(packet);
        check(packet[0], (short) 200);
        b.nextPacket(packet);  // dry: silence, and a new cushion is needed
        check(packet[0], (short) 0);
        b.write(filled(8000, (short) 7), 8000);  // a full second queued: only the newest 200 ms are kept
        int played = 0;
        for (int i = 0; i < 20; i++) {
            b.nextPacket(packet);
            if (packet[0] == 7) played++;
        }
        check(played, 10);
        b.write(filled(800, (short) 9), 800);
        b.clear();
        b.nextPacket(packet);
        check(packet[0], (short) 0);
    }

    /** The real doorbell's SDP; the fake answers like it and records the RTP it receives. */
    static void backchannelAgainstFakeDoorbell() throws Exception {
        String sdp = "v=0\r\ns=Session streamed by \"preview\"\r\nt=0 0\r\na=control:*\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\na=recvonly\r\na=control:track1\r\n"
            + "m=audio 0 RTP/AVP 97\r\na=rtpmap:97 MPEG4-GENERIC/16000\r\na=recvonly\r\na=control:track2\r\n"
            + "m=audio 0 RTP/AVP 0\r\na=control:track3\r\na=rtpmap:0 PCMU/8000\r\na=sendonly\r\n";
        List<String> requests = new CopyOnWriteArrayList<>();
        List<byte[]> packets = new CopyOnWriteArrayList<>();
        try (ServerSocket server = new ServerSocket(0)) {
            Thread doorbell = new Thread(() -> fakeRtsp(server, sdp, requests, packets));
            doorbell.start();
            List<String> states = new CopyOnWriteArrayList<>();
            Backchannel b = new Backchannel("127.0.0.1", server.getLocalPort(), "/h264Preview_01_main", "user", "pass", states::add);
            b.start();
            Thread.sleep(300);
            b.write(filled(800, (short) 4000), 800);  // 100 ms of "speech"
            Thread.sleep(500);
            b.stop();
            doorbell.join(2000);
            check(states.contains("open"), true);
            check(requests.get(0).startsWith("OPTIONS"), true);
            check(requests.stream().filter(r -> r.startsWith("DESCRIBE") || r.startsWith("SETUP") || r.startsWith("PLAY")).allMatch(r -> r.contains("Require: www.onvif.org/ver20/backchannel")), true);
            check(requests.stream().anyMatch(r -> r.startsWith("SETUP") && r.contains("/track3") && r.contains("interleaved=0-1")), true);
            check(packets.size() >= 30 && packets.size() <= 50, true);  // 50 a second for about 0.8 s
            byte[] first = packets.get(0);
            check(first.length, 12 + 160);
            check(first[1] & 0x7F, 0);  // PCMU
            check((first[1] & 0x80) != 0, true);  // marker on the first
            check(packets.stream().anyMatch(p -> (p[12] & 0xFF) != 0xFF), true);  // some packets carry the speech
            check(packets.get(1)[12] & 0xFF, 0xFF);  // early packets are silence (u-law 0 is 0xFF)
            int s0 = ((first[2] & 0xFF) << 8) | (first[3] & 0xFF), s1 = ((packets.get(1)[2] & 0xFF) << 8) | (packets.get(1)[3] & 0xFF);
            check(s1, (s0 + 1) & 0xFFFF);
        }
    }

    private static void fakeRtsp(ServerSocket server, String sdp, List<String> requests, List<byte[]> packets) {
        try (Socket socket = server.accept()) {
            InputStream raw = socket.getInputStream();
            DataInputStream in = new DataInputStream(raw);
            OutputStream out = socket.getOutputStream();
            while (true) {
                int first = in.read();
                if (first < 0) return;
                if (first == '$') {
                    in.readUnsignedByte();
                    byte[] packet = new byte[in.readUnsignedShort()];
                    in.readFully(packet);
                    packets.add(packet);
                    continue;
                }
                StringBuilder request = new StringBuilder().append((char) first);
                while (!request.toString().endsWith("\r\n\r\n")) request.append((char) in.read());
                String text = request.toString();
                requests.add(text);
                String cseq = text.replaceAll("(?s).*CSeq: (\\d+).*", "$1");
                String method = text.substring(0, text.indexOf(' '));
                String extra = "";
                String body = "";
                if (method.equals("DESCRIBE")) {
                    body = sdp;
                    extra = "Content-Type: application/sdp\r\nContent-Length: " + body.length() + "\r\n";
                } else if (method.equals("SETUP")) {
                    extra = "Session: 12AB;timeout=30\r\nTransport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n";
                }
                out.write(("RTSP/1.0 200 OK\r\nCSeq: " + cseq + "\r\n" + extra + "\r\n" + body).getBytes(StandardCharsets.UTF_8));
                if (method.equals("TEARDOWN")) return;
            }
        } catch (Exception closed) {
            // The client went away.
        }
    }

    private static short[] filled(int count, short value) {
        short[] samples = new short[count];
        java.util.Arrays.fill(samples, value);
        return samples;
    }

    private static String sha256(byte[] data) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) hex.append(String.format("%02x", b & 0xFF));
        return hex.toString();
    }

    private static void check(Object actual, Object expected) {
        if (!expected.equals(actual)) throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
    }

}
