// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell;

import java.net.ServerSocket;
import java.util.concurrent.CopyOnWriteArrayList;
import io.github.patrick11994.reolinkdoorbell.baichuan.BaichuanClientTest;

public final class ConnectionCheckTest {
    public static void main(String[] args) throws Exception {
        Texts english = Texts.of("English"), dutch = Texts.of("Nederlands");
        check(ConnectionCheck.run("", 9000, 554, "u", "p", english).message, english.checkNotConfigured());
        check(dutch.answer(), "Opnemen");

        int closed;
        try (ServerSocket probe = new ServerSocket(0)) {
            closed = probe.getLocalPort();
        }
        ConnectionCheck.Result unreachable = ConnectionCheck.run("127.0.0.1", closed, closed, "u", "p", english);
        check(unreachable.ok, false);
        check(unreachable.message, english.checkUnreachable("127.0.0.1"));

        try (ServerSocket server = new ServerSocket(0)) {
            Thread doorbell = new Thread(() -> BaichuanClientTest.fakeDoorbell(server, "real", new CopyOnWriteArrayList<>()));
            doorbell.start();
            ConnectionCheck.Result refused = ConnectionCheck.run("127.0.0.1", server.getLocalPort(), closed, BaichuanClientTest.USER, "wrong", english);
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
            ConnectionCheck.Result garbled = ConnectionCheck.run("127.0.0.1", server.getLocalPort(), rtsp.getLocalPort(), BaichuanClientTest.USER, "real", english);
            check(garbled.message, english.checkNoVideo());
            doorbell.join(3000);
            camera.join(3000);
        }
        System.out.println("ConnectionCheckTest passed");
    }

    private static void check(Object actual, Object expected) {
        if (!expected.equals(actual)) throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
    }
}
