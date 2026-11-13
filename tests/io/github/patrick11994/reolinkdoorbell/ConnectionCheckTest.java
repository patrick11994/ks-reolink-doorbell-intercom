// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell;

import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import io.github.patrick11994.reolinkdoorbell.baichuan.BaichuanClientTest;
import io.github.patrick11994.reolinkdoorbell.baichuan.BaichuanTalkTest;
import io.github.patrick11994.reolinkdoorbell.talk.TalkTest;

public final class ConnectionCheckTest {
    public static void main(String[] args) throws Exception {
        Texts english = Texts.of("English"), dutch = Texts.of("Nederlands");
        check(ConnectionCheck.run("", 9000, 554, MAIN, "Automatic", "Automatic", "u", "p", PICTURE, PLAYS, LOG, english).message, english.checkNotConfigured());
        check(dutch.answer(), "Opnemen");

        int closed;
        try (ServerSocket probe = new ServerSocket(0)) {
            closed = probe.getLocalPort();
        }
        ConnectionCheck.Result unreachable = ConnectionCheck.run("127.0.0.1", closed, closed, MAIN, "Automatic", "Automatic", "u", "p", PICTURE, PLAYS, LOG, english);
        check(unreachable.ok, false);
        check(unreachable.message, english.checkUnreachable("127.0.0.1"));

        // A doorbell without Baichuan, such as the Video Doorbell WiFi Gen 2 (#14): Automatic checks its button presses
        // over ONVIF instead, and says so when that login is refused. Set to Baichuan, the closed port is the answer.
        try (ServerSocket onvif = new ServerSocket(0)) {
            List<String> lines = new CopyOnWriteArrayList<>();
            Thread doorbell = new Thread(() -> {
                for (int i = 0; i < 2; i++) {
                    try (java.net.Socket socket = onvif.accept()) {
                        java.io.InputStream in = socket.getInputStream();
                        in.read(new byte[8192]);
                        socket.getOutputStream().write(("HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Digest realm=\"camara\", qop=\"auth\", nonce=\"n1\"\r\n"
                            + "Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    } catch (Exception closedEarly) {
                        return;
                    }
                }
            });
            doorbell.start();
            ConnectionCheck.Result gen2 = ConnectionCheck.run("127.0.0.1", closed, closed, onvif.getLocalPort(), MAIN, "Automatic", "Automatic", "Automatic",
                "u", "wrong", 0, PICTURE, PLAYS, lines::add, english);
            check(gen2.message, english.checkLoginRefused());
            check(lines.get(0).contains("over ONVIF"), true);
            doorbell.join(3000);
            check(ConnectionCheck.run("127.0.0.1", closed, closed, onvif.getLocalPort(), MAIN, "Automatic", "Automatic", "Baichuan",
                "u", "p", 0, PICTURE, PLAYS, LOG, english).message, english.checkUnreachable("127.0.0.1"));
        }

        try (ServerSocket server = new ServerSocket(0)) {
            Thread doorbell = new Thread(() -> BaichuanClientTest.fakeDoorbell(server, "real", new CopyOnWriteArrayList<>()));
            doorbell.start();
            ConnectionCheck.Result refused = ConnectionCheck.run("127.0.0.1", server.getLocalPort(), closed, MAIN, "Automatic", "Automatic", BaichuanClientTest.USER, "wrong", PICTURE, PLAYS, LOG, english);
            check(refused.message, english.checkLoginRefused());
            doorbell.join(3000);
        }
        // A device whose RTSP answer cannot be read still ends the check with a result.
        try (ServerSocket server = new ServerSocket(0); ServerSocket rtsp = new ServerSocket(0)) {
            Thread doorbell = new Thread(() -> BaichuanClientTest.fakeDoorbell(server, "real", new CopyOnWriteArrayList<>()));
            Thread camera = new Thread(() -> {
                try (java.net.Socket socket = rtsp.accept()) {
                    socket.getInputStream().read(new byte[4096]);
                    socket.getOutputStream().write("RTSP/1.0 abc OK\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    Thread.sleep(500);
                } catch (Exception gone) {
                    // The check hung up.
                }
            });
            doorbell.start();
            camera.start();
            ConnectionCheck.Result garbled = ConnectionCheck.run("127.0.0.1", server.getLocalPort(), rtsp.getLocalPort(), MAIN, "Automatic", "Automatic", BaichuanClientTest.USER, "real", PICTURE, PLAYS, LOG, english);
            check(garbled.message, english.checkNoVideo());
            doorbell.join(3000);
            camera.join(3000);
        }
        // A whole doorbell: the check follows the chosen stream and the Talk setting.
        String video = "v=0\r\ns=x\r\nt=0 0\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\na=recvonly\r\na=control:track1\r\n";
        String backchannel = video + "m=audio 0 RTP/AVP 0\r\na=control:track3\r\na=rtpmap:0 PCMU/8000\r\na=sendonly\r\n";
        String noTalk = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<body>\n<TalkAbility version=\"1.1\">\n</TalkAbility>\n</body>\n";
        List<String> requests = new CopyOnWriteArrayList<>();
        check(doorbell(BaichuanTalkTest.ABILITY, video, "/h264Preview_01_sub", "Automatic", requests, english), english.checkOk());
        check(requests.stream().anyMatch(r -> r.startsWith("DESCRIBE rtsp://127.0.0.1:") && r.contains("/h264Preview_01_sub ")), true);  // the stream the viewer will play
        // Automatic talks over Baichuan first, so it is probed even when ONVIF talk is there: either one will do.
        check(doorbell(noTalk, backchannel, MAIN, "Automatic", requests, english), english.checkOk());
        check(doorbell(noTalk, video, MAIN, "Automatic", requests, english), english.checkNoTalk());
        // A forced path must work itself.
        check(doorbell(noTalk, backchannel, MAIN, "Baichuan", requests, english), english.checkNoBaichuanTalk());
        check(doorbell(BaichuanTalkTest.ABILITY, video, MAIN, "ONVIF", requests, english), english.checkNoTalk());
        check(doorbell(BaichuanTalkTest.ABILITY, video, MAIN, "Baichuan", requests, english), english.checkOk());
        // Video the way Video over goes, up to the first picture. Automatic falls back to RTSP, and says that it is slower.
        List<String> opened = new CopyOnWriteArrayList<>();
        check(doorbell(BaichuanTalkTest.ABILITY, video, MAIN, "Automatic", "Automatic", requests, streams(opened, "Baichuan"), PLAYS, english), english.checkOk());
        check(opened.toString(), "[Baichuan]");
        opened.clear();
        check(doorbell(BaichuanTalkTest.ABILITY, video, MAIN, "Automatic", "Automatic", requests, streams(opened, "RTSP"), PLAYS, english), english.checkVideoOverRtsp());
        check(opened.toString(), "[Baichuan, RTSP]");
        check(doorbell(BaichuanTalkTest.ABILITY, video, MAIN, "Baichuan", "Automatic", requests, streams(opened, "RTSP"), PLAYS, english), english.checkNoBaichuanVideo());
        check(doorbell(BaichuanTalkTest.ABILITY, video, MAIN, "RTSP", "Automatic", requests, streams(opened, "Baichuan"), PLAYS, english), english.checkNoVideo());
        check(doorbell(BaichuanTalkTest.ABILITY, video, MAIN, "Automatic", "Automatic", requests, streams(opened), PLAYS, english), english.checkNoVideo());
        opened.clear();
        check(doorbell(BaichuanTalkTest.ABILITY, video, MAIN, "RTSP", "Automatic", requests, streams(opened, "RTSP"), PLAYS, english), english.checkOk());
        check(opened.toString(), "[RTSP]");
        // H.265, as the Video Doorbell WiFi Gen 2 sends its main stream (#17): the decoder is asked about H.265, and a
        // device without one is told to pick Sub.
        String hevc = video.replace("H264/90000", "H265/90000");
        List<String> asked = new CopyOnWriteArrayList<>();
        check(doorbell(BaichuanTalkTest.ABILITY, hevc, MAIN, "RTSP", "Automatic", requests, streams(HEVC_KEYFRAME, opened, "RTSP"), hevcPlayer(true, asked), english), english.checkOk());
        check(asked.toString(), "[H.265 640x480]");
        asked.clear();
        check(doorbell(BaichuanTalkTest.ABILITY, hevc, MAIN, "RTSP", "Automatic", requests, streams(HEVC_KEYFRAME, opened, "RTSP"), hevcPlayer(false, asked), english), english.checkMainTooLarge("H.265, 640x480"));
        check(asked.toString(), "[H.265 640x480]");
        // An SPS that cannot be read still asks whether there is an H.265 decoder at all.
        byte[] unreadable = concat(new byte[] {0, 0, 0, 1}, java.util.Base64.getDecoder().decode("QAEMAf//BAgAAAMAnggAAAMAAFqVmAk="),
            new byte[] {0, 0, 0, 1, 0x42, 0x01, 0x01}, new byte[] {0, 0, 0, 1, 0x28, 0x01, 7});
        check(doorbell(BaichuanTalkTest.ABILITY, hevc, MAIN, "RTSP", "Automatic", requests, streams(unreadable, opened, "RTSP"), hevcPlayer(false, asked), english), english.checkMainTooLarge("H.265"));
        check(doorbell(BaichuanTalkTest.ABILITY, hevc, MAIN, "RTSP", "Automatic", requests, streams(unreadable, opened, "RTSP"), hevcPlayer(true, asked), english), english.checkOk());

        // A device that cannot play the picture: the check says so, and which stream to pick instead. The decoder's own
        // answer counts first; when it gives none, one decoded picture decides; when that is unclear too, it passes.
        List<String> decoded = new CopyOnWriteArrayList<>();
        check(doorbell(BaichuanTalkTest.ABILITY, video, MAIN, "Automatic", "Automatic", requests, PICTURE, player(false, null, decoded), english), english.checkMainTooLarge("2560x1920"));
        check(english.checkMainTooLarge("2560x1920").contains("Sub (light)"), true);
        check(decoded.isEmpty(), true);  // the decoder said no: no test decode
        check(doorbell(BaichuanTalkTest.ABILITY, video, MAIN, "Automatic", "Automatic", requests, PICTURE, player(null, false, decoded), english), english.checkMainTooLarge("2560x1920"));
        check(decoded.size(), 1);
        check(doorbell(BaichuanTalkTest.ABILITY, video, MAIN, "Automatic", "Automatic", requests, PICTURE, player(null, true, decoded), english), english.checkOk());
        check(doorbell(BaichuanTalkTest.ABILITY, video, MAIN, "Automatic", "Automatic", requests, PICTURE, player(null, null, decoded), english), english.checkOk());
        check(doorbell(BaichuanTalkTest.ABILITY, video, "/h264Preview_01_sub", "Automatic", "Automatic", requests, PICTURE, player(false, null, decoded), english), english.checkVideoTooLarge("2560x1920"));
        // The words fit any device, not only a wall panel.
        for (Texts texts : new Texts[] {english, dutch}) {
            for (String text : new String[] {texts.checkMainTooLarge("1x1"), texts.checkVideoTooLarge("1x1"), texts.videoNotShown()}) {
                check(text.toLowerCase(java.util.Locale.ROOT).contains("panel") || text.toLowerCase(java.util.Locale.ROOT).contains("paneel"), false);
            }
        }

        // The event session's reasons, in a few words; anything unknown is a lost connection.
        check(ConnectionCheck.problem("IOException: the doorbell stopped answering", dutch), "reageert niet");
        check(ConnectionCheck.problem("ConnectException: failed to connect to /192.168.1.2 (port 9000) after 5000ms: isConnected failed: EHOSTUNREACH (No route to host)", english), "not reachable");
        check(ConnectionCheck.problem("the doorbell refused the username or password", english), english.problemLogin());
        check(ConnectionCheck.problem("the doorbell is set up as a Reolink 'account device', which blocks local login", english), english.problemAccountDevice());
        check(ConnectionCheck.problem("EOFException: the doorbell closed the connection", english), "connection lost");
        System.out.println("ConnectionCheckTest passed");
    }

    private static final String MAIN = "/h264Preview_01_main";
    private static final ConnectionCheck.Player PLAYS = player(true, true, new CopyOnWriteArrayList<>());
    private static final ConnectionCheck.Log LOG = line -> {};
    /** The doorbell's main-stream keyframe: SPS (2560x1920), PPS and a slice. */
    private static final byte[] KEYFRAME = concat(new byte[] {0, 0, 0, 1}, java.util.Base64.getDecoder().decode("Z2QAM6wVFKAoAPGQ"),
        new byte[] {0, 0, 0, 1, 0x68, (byte) 0xEE, 0x3C, (byte) 0xB0}, new byte[] {0, 0, 0, 1, 0x65, (byte) 0x88, (byte) 0x84});
    private static final ConnectionCheck.Streams PICTURE = streams(new CopyOnWriteArrayList<>(), "Baichuan", "RTSP");
    /** An H.265 keyframe: VPS, SPS (640x480) and PPS from libx265, and an IDR slice. */
    private static final byte[] HEVC_KEYFRAME = concat(
        new byte[] {0, 0, 0, 1}, java.util.Base64.getDecoder().decode("QAEMAf//BAgAAAMAnggAAAMAAFqVmAk="),
        new byte[] {0, 0, 0, 1}, java.util.Base64.getDecoder().decode("QgEBBAgAAAMAnggAAAMAAFqQAKBAPCyys0kmV4C3AgIABAAAAwAEAAADAAQg"),
        new byte[] {0, 0, 0, 1}, java.util.Base64.getDecoder().decode("RAHBcoYMRiQ="), new byte[] {0, 0, 0, 1, 0x28, 0x01, 7});

    /** A decoder that answers canPlay for H.265 only, noting what it was asked. */
    private static ConnectionCheck.Player hevcPlayer(boolean plays, List<String> asked) {
        return new ConnectionCheck.Player() {
            @Override public Boolean canPlay(boolean hevc, int width, int height) {
                asked.add((hevc ? "H.265 " : "H.264 ") + width + "x" + height);
                return hevc && plays;
            }
            @Override public Boolean decodes(byte[] keyframe) { return null; }
        };
    }

    /** A decoder that answers canPlay, and decodes when asked; each test decode is noted. */
    private static ConnectionCheck.Player player(Boolean canPlay, Boolean decodes, List<String> decoded) {
        return new ConnectionCheck.Player() {
            @Override public Boolean canPlay(boolean hevc, int width, int height) { return canPlay; }
            @Override public Boolean decodes(byte[] keyframe) {
                decoded.add(keyframe.length + " bytes");
                return decodes;
            }
        };
    }

    /** Streams that bring a keyframe over the paths named, and fail over any other; each one opened is noted. */
    private static ConnectionCheck.Streams streams(List<String> opened, String... working) {
        return streams(KEYFRAME, opened, working);
    }

    private static ConnectionCheck.Streams streams(byte[] keyframe, List<String> opened, String... working) {
        List<String> works = java.util.Arrays.asList(working);
        return (path, listener) -> new io.github.patrick11994.reolinkdoorbell.media.MediaStream() {
            private VideoSink sink;
            @Override public void start() {
                opened.add(path);
                new Thread(() -> {
                    if (works.contains(path)) sink.onVideo(keyframe, 0, true);
                    else listener.onStreamState("stream failed: no picture within 2000 ms");
                }).start();
            }
            @Override public void stop() {}
            @Override public void attachVideo(VideoSink video) { sink = video; }
            @Override public void attachAudio(AudioSink audio) {}
            @Override public long firstKeyframeMillis() { return -1; }
        };
    }

    private static byte[] concat(byte[]... parts) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part, 0, part.length);
        return out.toByteArray();
    }

    /** Runs the check against a fake doorbell: Baichuan logins answering TalkAbility, and RTSP describing sdp. */
    private static String doorbell(String ability, String sdp, String path, String talkPath, List<String> requests, Texts texts) throws Exception {
        return doorbell(ability, sdp, path, "Automatic", talkPath, requests, PICTURE, PLAYS, texts);
    }

    private static String doorbell(String ability, String sdp, String path, String videoPath, String talkPath, List<String> requests, ConnectionCheck.Streams streams,
                                   ConnectionCheck.Player player, Texts texts) throws Exception {
        requests.clear();
        try (ServerSocket baichuan = new ServerSocket(0); ServerSocket rtsp = new ServerSocket(0)) {
            Thread logins = new Thread(() -> {
                for (int i = 0; i < 2; i++) BaichuanTalkTest.fakeTalkDoorbell(baichuan, ability, 0, new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>());
            });
            Thread camera = new Thread(() -> TalkTest.fakeRtsp(rtsp, sdp, requests, new CopyOnWriteArrayList<>()));
            logins.setDaemon(true);  // a check that needs one login leaves the second accept waiting until the socket closes
            logins.start();
            camera.start();
            String message = ConnectionCheck.run("127.0.0.1", baichuan.getLocalPort(), rtsp.getLocalPort(), path, videoPath, talkPath, BaichuanClientTest.USER, BaichuanClientTest.PASSWORD, streams, player, LOG, texts).message;
            camera.join(3000);
            return message;
        }
    }

    private static void check(Object actual, Object expected) {
        if (!expected.equals(actual)) throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
    }
}
