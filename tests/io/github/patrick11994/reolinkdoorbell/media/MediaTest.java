// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell.media;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class MediaTest {
    /** The SDP the Reolink Video Doorbell PoE (fw v3.0.0.6460) answers with the ONVIF backchannel Require header. */
    static final String DOORBELL_SDP = "v=0\r\no=- 1792928993004663 1 IN IP4 192.168.1.50\r\ns=Session streamed by \"preview\"\r\ni=reolink rtsp stream\r\nt=0 0\r\n"
        + "a=tool:Reolink Streaming Media 2024.12.11\r\na=type:broadcast\r\na=control:*\r\na=range:npt=now-\r\n"
        + "m=video 0 RTP/AVP 96\r\nc=IN IP4 0.0.0.0\r\nb=AS:8192\r\na=rtpmap:96 H264/90000\r\n"
        + "a=fmtp:96 packetization-mode=1;profile-level-id=640033;sprop-parameter-sets=Z2QAM6wVFKAoAPGQ,aO48sA==\r\na=recvonly\r\na=control:track1\r\n"
        + "m=audio 0 RTP/AVP 97\r\nc=IN IP4 0.0.0.0\r\nb=AS:8192\r\na=rtpmap:97 MPEG4-GENERIC/16000\r\n"
        + "a=fmtp:97 streamtype=5;profile-level-id=1;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3;config=1408\r\na=recvonly\r\na=control:track2\r\n"
        + "m=audio 0 RTP/AVP 0\r\na=control:track3\r\na=rtpmap:0 PCMU/8000\r\na=sendonly\r\n";

    public static void main(String[] args) {
        sdp();
        spsSizes();
        h264SingleStapAndFragments();
        h264KeepsParameterSetsWithKeyframe();
        h264DropsBrokenFragment();
        aacFrames();
        hubReplaysFromKeyframe();
        h264DropsRunawayFrame();
        rtspRefusesHugeAnswers();
        System.out.println("MediaTest passed");
    }

    static void sdp() {
        Sdp sdp = Sdp.parse(DOORBELL_SDP);
        check(sdp.tracks.size(), 3);
        Sdp.Track video = sdp.receive("video");
        check(video.codec, "H264");
        check(video.clock, 90000);
        check(video.control, "track1");
        check(video.parameterSets().size(), 2);
        check(video.parameterSets().get(0)[0] & 0x1F, 7);
        check(video.parameterSets().get(1)[0] & 0x1F, 8);
        Sdp.Track audio = sdp.receive("audio");
        check(audio.codec, "MPEG4-GENERIC");
        check(audio.clock, 16000);
        check(Arrays.toString(audio.audioConfig()), Arrays.toString(new byte[] {0x14, 0x08}));
        check(audio.fmtpInt("sizelength", 0), 13);
        Sdp.Track back = sdp.backchannel();
        check(back.control, "track3");
        check(back.codec, "PCMU");
        check(back.clock, 8000);
    }

    /** SPS from the doorbell's main and sub streams; ffprobe reports 2560x1920 and 640x480. */
    static void spsSizes() {
        SpsInfo main = SpsInfo.parse(java.util.Base64.getDecoder().decode("Z2QAM6wVFKAoAPGQ"));
        check(main.width + "x" + main.height, "2560x1920");
        SpsInfo sub = SpsInfo.parse(java.util.Base64.getDecoder().decode("Z2QAM6wVFKCgPZA="));
        check(sub.width + "x" + sub.height, "640x480");
        check(SpsInfo.parse(new byte[] {0x67}) == null, true);
    }

    static void h264SingleStapAndFragments() {
        List<String> units = new ArrayList<>();
        H264Depacketizer d = new H264Depacketizer((au, ts, key) -> units.add(ts + ":" + key + ":" + hex(au)));
        // A STAP-A with SPS + PPS, then an IDR in three FU-A fragments, marker on the last.
        byte[] stap = concat(new byte[] {24}, new byte[] {0, 2, 0x67, 0x11}, new byte[] {0, 2, 0x68, 0x22});
        d.onRtp(rtp(1, 1000, false, stap), 0, 12 + stap.length);
        d.onRtp(rtp(2, 1000, false, new byte[] {0x7C, (byte) 0x85, 1, 2}), 0, 16);
        d.onRtp(rtp(3, 1000, false, new byte[] {0x7C, 0x05, 3}), 0, 15);
        d.onRtp(rtp(4, 1000, true, new byte[] {0x7C, 0x45, 4}), 0, 15);
        // A P-frame as a single NAL unit.
        d.onRtp(rtp(5, 4600, true, new byte[] {0x41, 9}), 0, 14);
        check(units.size(), 2);
        check(units.get(0), "1000:true:" + "00000001" + "6711" + "00000001" + "6822" + "00000001" + "6501020304");
        check(units.get(1), "4600:false:00000001" + "4109");
    }

    static void h264KeepsParameterSetsWithKeyframe() {
        List<String> units = new ArrayList<>();
        H264Depacketizer d = new H264Depacketizer((au, ts, key) -> units.add(key + ":" + hex(au)));
        d.onRtp(rtp(1, 1000, true, new byte[] {0x67, 0x11}), 0, 14);  // SPS with the marker set, as the doorbell does
        d.onRtp(rtp(2, 1000, true, new byte[] {0x68, 0x22}), 0, 14);
        d.onRtp(rtp(3, 1000, true, new byte[] {0x65, 0x33}), 0, 14);
        check(units.size(), 1);
        check(units.get(0), "true:00000001671100000001682200000001" + "6533");
    }

    static void h264DropsBrokenFragment() {
        List<String> units = new ArrayList<>();
        H264Depacketizer d = new H264Depacketizer((au, ts, key) -> units.add(hex(au)));
        d.onRtp(rtp(1, 1000, false, new byte[] {0x7C, (byte) 0x85, 1}), 0, 15);
        d.onRtp(rtp(3, 1000, true, new byte[] {0x7C, 0x45, 3}), 0, 15);  // sequence 2 lost
        d.onRtp(rtp(4, 4600, true, new byte[] {0x41, 9}), 0, 14);
        check(units.size(), 1);
        check(units.get(0), "000000014109");
    }

    static void aacFrames() {
        List<String> frames = new ArrayList<>();
        AacDepacketizer d = new AacDepacketizer((frame, ts) -> frames.add(ts + ":" + hex(frame)), 13, 3);
        // AU-headers-length 32 bits: two 16-bit headers, sizes 3 and 2 (size << 3, index 0).
        byte[] payload = {0, 32, 0, 3 << 3, 0, 2 << 3, 1, 2, 3, 4, 5};
        d.onRtp(rtp(1, 5000, true, payload), 0, 12 + payload.length);
        check(frames.size(), 2);
        check(frames.get(0), "5000:010203");
        check(frames.get(1), "6024:0405");
    }

    /** A sink attaching late gets the cached frames from the last keyframe, then live ones, each once. */
    static void hubReplaysFromKeyframe() {
        StreamHub hub = new StreamHub("unused", 554, "/", "u", "p", state -> {});
        java.lang.reflect.Method frame;
        try {
            java.lang.reflect.Field running = StreamHub.class.getDeclaredField("running");
            running.setAccessible(true);
            running.set(hub, true);
            frame = StreamHub.class.getDeclaredMethod("onAccessUnit", byte[].class, long.class, boolean.class);
            frame.setAccessible(true);
            frame.invoke(hub, new byte[] {1}, 0L, false);  // before any keyframe: undecodable, dropped
            frame.invoke(hub, new byte[] {2}, 9000L, true);
            frame.invoke(hub, new byte[] {3}, 12600L, false);
            frame.invoke(hub, new byte[] {4}, 16200L, true);  // a new keyframe resets the cache
            frame.invoke(hub, new byte[] {5}, 19800L, false);
            List<String> seen = new ArrayList<>();
            hub.attachVideo((au, micros, key) -> seen.add(au[0] + "@" + micros + (key ? "K" : "")));
            frame.invoke(hub, new byte[] {6}, 23400L, false);
            check(seen.toString(), "[4@80000K, 5@120000, 6@160000]");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** A stream that never ends a frame must not grow memory without bound: the frame is dropped at the cap. */
    static void h264DropsRunawayFrame() {
        List<Integer> sizes = new ArrayList<>();
        H264Depacketizer d = new H264Depacketizer((au, ts, key) -> sizes.add(au.length));
        byte[] chunk = new byte[60_000];
        chunk[0] = 0x41;  // slice NAL, but every packet without the marker and with the same timestamp
        int packets = H264Depacketizer.MAX_UNIT / chunk.length + 10;
        for (int i = 0; i < packets; i++) d.onRtp(rtp(i, 1000, false, chunk), 0, 12 + chunk.length);
        d.onRtp(rtp(packets, 1000, true, new byte[] {0x41, 1}), 0, 14);
        check(sizes.size(), 1);
        check(sizes.get(0) <= H264Depacketizer.MAX_UNIT, true);
        d.onRtp(rtp(packets + 1, 4600, true, new byte[] {0x41, 2}), 0, 14);  // the next normal frame still comes through
        check(sizes.get(sizes.size() - 1), 6);
    }

    /** An RTSP answer may not announce a giant body or send an endless header line. */
    static void rtspRefusesHugeAnswers() {
        String[] answers = {
            "RTSP/1.0 200 OK\r\nCSeq: 1\r\nContent-Length: 10485760\r\n\r\n",
            "RTSP/1.0 200 OK\r\nCSeq: 1\r\nX-Junk: " + new String(new char[RtspSession.MAX_LINE + 10]).replace('\0', 'a') + "\r\n\r\n",
        };
        for (String answer : answers) {
            try (java.net.ServerSocket server = new java.net.ServerSocket(0)) {
                Thread fake = new Thread(() -> {
                    try (java.net.Socket socket = server.accept()) {
                        socket.getInputStream().read(new byte[4096]);
                        socket.getOutputStream().write(answer.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                        Thread.sleep(500);
                    } catch (Exception closed) {
                        // The client gave up, as it should.
                    }
                });
                fake.start();
                RtspSession session = new RtspSession("127.0.0.1", server.getLocalPort(), "/x", "u", "p");
                String outcome;
                try {
                    session.describe(java.util.Collections.<String, String>emptyMap());
                    outcome = "accepted";
                } catch (java.io.IOException refused) {
                    outcome = refused.getMessage().contains("too") ? "refused" : "failed: " + refused.getMessage();
                } finally {
                    session.close();
                }
                check(outcome, "refused");
                fake.join(2000);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    }

    static byte[] rtp(int sequence, long timestamp, boolean marker, byte[] payload) {
        byte[] packet = new byte[12 + payload.length];
        packet[0] = (byte) 0x80;
        packet[1] = (byte) ((marker ? 0x80 : 0) | 96);
        packet[2] = (byte) (sequence >> 8);
        packet[3] = (byte) sequence;
        packet[4] = (byte) (timestamp >> 24);
        packet[5] = (byte) (timestamp >> 16);
        packet[6] = (byte) (timestamp >> 8);
        packet[7] = (byte) timestamp;
        System.arraycopy(payload, 0, packet, 12, payload.length);
        return packet;
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part, 0, part.length);
        return out.toByteArray();
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x & 0xff));
        return s.toString();
    }

    private static void check(Object actual, Object expected) {
        if (!expected.equals(actual)) throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
    }
}
