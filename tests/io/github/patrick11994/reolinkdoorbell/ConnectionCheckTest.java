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
        check(ConnectionCheck.run("", 9000, 554, MAIN, "Automatic", "u", "p", PLAYS, english).message, english.checkNotConfigured());
        check(dutch.answer(), "Opnemen");

        int closed;
        try (ServerSocket probe = new ServerSocket(0)) {
            closed = probe.getLocalPort();
        }
        ConnectionCheck.Result unreachable = ConnectionCheck.run("127.0.0.1", closed, closed, MAIN, "Automatic", "u", "p", PLAYS, english);
        check(unreachable.ok, false);
        check(unreachable.message, english.checkUnreachable("127.0.0.1"));

        try (ServerSocket server = new ServerSocket(0)) {
            Thread doorbell = new Thread(() -> BaichuanClientTest.fakeDoorbell(server, "real", new CopyOnWriteArrayList<>()));
            doorbell.start();
            ConnectionCheck.Result refused = ConnectionCheck.run("127.0.0.1", server.getLocalPort(), closed, MAIN, "Automatic", BaichuanClientTest.USER, "wrong", PLAYS, english);
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
            ConnectionCheck.Result garbled = ConnectionCheck.run("127.0.0.1", server.getLocalPort(), rtsp.getLocalPort(), MAIN, "Automatic", BaichuanClientTest.USER, "real", PLAYS, english);
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
        // A panel that cannot play the stream's picture size: the check says so, and which stream to pick instead.
        String main = video.replace("a=recvonly", "a=fmtp:96 packetization-mode=1;sprop-parameter-sets=Z2QAM6wVFKAoAPGQ,aO48sA==\r\na=recvonly");
        String sub = video.replace("a=recvonly", "a=fmtp:96 packetization-mode=1;sprop-parameter-sets=Z2QAM6wVFKCgPZA=,aO48sA==\r\na=recvonly");
        check(doorbell(BaichuanTalkTest.ABILITY, main, MAIN, "Automatic", requests, FULL_HD, english), english.checkMainTooLarge("2560x1920"));
        check(english.checkMainTooLarge("2560x1920").contains("Sub (light)"), true);
        check(doorbell(BaichuanTalkTest.ABILITY, sub, "/h264Preview_01_sub", "Automatic", requests, FULL_HD, english), english.checkOk());
        check(doorbell(BaichuanTalkTest.ABILITY, main, MAIN, "Automatic", requests, english), english.checkOk());
        check(doorbell(BaichuanTalkTest.ABILITY, sub, "/h264Preview_01_sub", "Automatic", requests, (width, height) -> false, english), english.checkVideoTooLarge("640x480"));

        // The event session's reasons, in a few words; anything unknown is a lost connection.
        check(ConnectionCheck.problem("IOException: the doorbell stopped answering", dutch), "reageert niet");
        check(ConnectionCheck.problem("ConnectException: failed to connect to /192.168.1.2 (port 9000) after 5000ms: isConnected failed: EHOSTUNREACH (No route to host)", english), "not reachable");
        check(ConnectionCheck.problem("the doorbell refused the username or password", english), english.problemLogin());
        check(ConnectionCheck.problem("the doorbell is set up as a Reolink 'account device', which blocks local login", english), english.problemAccountDevice());
        check(ConnectionCheck.problem("EOFException: the doorbell closed the connection", english), "connection lost");
        System.out.println("ConnectionCheckTest passed");
    }

    private static final String MAIN = "/h264Preview_01_main";
    private static final ConnectionCheck.Player PLAYS = (width, height) -> true;
    /** An Echo Show 8: its decoder fails on the doorbell's 2560x1920 main stream. */
    private static final ConnectionCheck.Player FULL_HD = (width, height) -> width <= 1920 && height <= 1088;

    /** Runs the check against a fake doorbell: Baichuan logins answering TalkAbility, and RTSP describing sdp. */
    private static String doorbell(String ability, String sdp, String path, String talkPath, List<String> requests, Texts texts) throws Exception {
        return doorbell(ability, sdp, path, talkPath, requests, PLAYS, texts);
    }

    private static String doorbell(String ability, String sdp, String path, String talkPath, List<String> requests, ConnectionCheck.Player player, Texts texts) throws Exception {
        requests.clear();
        try (ServerSocket baichuan = new ServerSocket(0); ServerSocket rtsp = new ServerSocket(0)) {
            Thread logins = new Thread(() -> {
                for (int i = 0; i < 2; i++) BaichuanTalkTest.fakeTalkDoorbell(baichuan, ability, 0, new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>());
            });
            Thread camera = new Thread(() -> TalkTest.fakeRtsp(rtsp, sdp, requests, new CopyOnWriteArrayList<>()));
            logins.setDaemon(true);  // a check that needs one login leaves the second accept waiting until the socket closes
            logins.start();
            camera.start();
            String message = ConnectionCheck.run("127.0.0.1", baichuan.getLocalPort(), rtsp.getLocalPort(), path, talkPath, BaichuanClientTest.USER, BaichuanClientTest.PASSWORD, player, texts).message;
            camera.join(3000);
            return message;
        }
    }

    private static void check(Object actual, Object expected) {
        if (!expected.equals(actual)) throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
    }
}
