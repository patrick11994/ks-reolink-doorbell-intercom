// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell.baichuan;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The client against a fake doorbell that answers the way the real one did (subscribe answers 400, pushes still flow). */
public final class BaichuanClientTest {
    static final String NONCE = "testNonce42";
    public static final String USER = "viewer";
    static final String PASSWORD = "s3cret";

    public static void main(String[] args) throws Exception {
        cryptoMatchesReolinkAio();
        alarmParsing();
        loginSubscribeAndPush();
        wrongPasswordIsReported();
        verifyChecksTheLogin();
        System.out.println("BaichuanClientTest passed");
    }

    /** Vectors produced by reolink_aio 0.21.18 (util.md5_str_modern, encrypt_baichuan, AES-CFB128). */
    static void cryptoMatchesReolinkAio() {
        check(BcCrypto.md5Modern("adminnonce123"), "21ED9A48CE34F6D3CDEB1DD1A2C1205");
        check(hex(BcCrypto.aesKey("nonce123", "secret")), "38443132384537423139323043464637");
        check(hex(BcCrypto.xor("<body>hello</body>".getBytes(StandardCharsets.UTF_8), 250)), "fad3cff7fb3b8db2aaddcfafad678ab3bf8f");
        check(hex(BcCrypto.aes(BcCrypto.aesKey("nonce123", "secret"), "<body>hello world, this is longer than one block</body>".getBytes(StandardCharsets.UTF_8), true)),
            "f82e08c11a5f8fc2c38918baed7c0c001048248f635133fc731d28aff49ab8c34f84dc99b719a860a526b88d035106b80b9d6d4c044010");
    }

    static void alarmParsing() {
        List<AlarmEvents.Event> events = AlarmEvents.parse(push("MD,visitor", "none"));
        check(events.size(), 1);
        check(events.get(0).channel, 0);
        check(events.get(0).visitor(), true);
        check(AlarmEvents.parse(push("MD", "people")).get(0).visitor(), false);
        check(AlarmEvents.parse(push("none", "none")).get(0).status.isEmpty(), true);
        check(AlarmEvents.parse("not xml").isEmpty(), true);
    }

    static void loginSubscribeAndPush() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CountDownLatch visitor = new CountDownLatch(1);
            List<String> log = new CopyOnWriteArrayList<>();
            Thread doorbell = new Thread(() -> fakeDoorbell(server, PASSWORD, log));
            doorbell.start();
            BaichuanClient client = new BaichuanClient("127.0.0.1", server.getLocalPort(), USER, PASSWORD, new BaichuanClient.Listener() {
                @Override public void onConnection(boolean connected, String detail) { log.add("connection " + connected + " " + detail); }
                @Override public void onAlarm(AlarmEvents.Event event) { log.add("alarm " + event); if (event.visitor()) visitor.countDown(); }
            }, 500, 2000);
            client.start();
            boolean rang = visitor.await(5, TimeUnit.SECONDS);
            Thread.sleep(1200);  // long enough for a keepalive
            client.stop();
            doorbell.join(3000);
            check(rang, true);
            check(log.contains("login ok"), true);
            check(log.contains("connection true connected"), true);
            check(log.stream().anyMatch(l -> l.startsWith("keepalive")), true);
            check(Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.getName().equals("reolink-baichuan") && t.isAlive()), true);
        }
    }

    static void wrongPasswordIsReported() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CountDownLatch refused = new CountDownLatch(1);
            List<String> log = new CopyOnWriteArrayList<>();
            Thread doorbell = new Thread(() -> fakeDoorbell(server, "other", log));
            doorbell.start();
            BaichuanClient client = new BaichuanClient("127.0.0.1", server.getLocalPort(), USER, PASSWORD, new BaichuanClient.Listener() {
                @Override public void onConnection(boolean connected, String detail) { if (detail.contains("refused the username or password")) refused.countDown(); }
                @Override public void onAlarm(AlarmEvents.Event event) {}
            }, 500, 2000);
            client.start();
            check(refused.await(5, TimeUnit.SECONDS), true);
            client.stop();
            doorbell.join(3000);
        }
    }

    static void verifyChecksTheLogin() throws Exception {
        for (String real : new String[] {PASSWORD, "other"}) {
            try (ServerSocket server = new ServerSocket(0)) {
                Thread doorbell = new Thread(() -> fakeDoorbell(server, real, new CopyOnWriteArrayList<>()));
                doorbell.start();
                String outcome;
                try {
                    BaichuanClient.verify("127.0.0.1", server.getLocalPort(), USER, PASSWORD);
                    outcome = "ok";
                } catch (BaichuanClient.LoginRefused refused) {
                    outcome = "refused";
                }
                check(outcome, real.equals(PASSWORD) ? "ok" : "refused");
                doorbell.join(3000);
            }
        }
    }

    /** One session, as the real doorbell answers: nonce, login check, 400 on subscribe, two pushes, keepalive replies. */
    public static void fakeDoorbell(ServerSocket server, String realPassword, List<String> log) {
        try (Socket socket = server.accept()) {
            socket.setSoTimeout(3000);
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            OutputStream out = socket.getOutputStream();
            BcMessage nonceRequest = BcMessage.read(in);
            String nonceXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<body>\n<Encryption version=\"1.1\">\n<type>md5</type>\n<nonce>" + NONCE + "</nonce>\n</Encryption>\n</body>\n";
            out.write(reply20(1, nonceRequest.messageId, 0xdd12, BcCrypto.xor(nonceXml.getBytes(StandardCharsets.UTF_8), nonceRequest.channelByte())));
            BcMessage login = BcMessage.read(in);
            String loginXml = new String(BcCrypto.xor(login.body, login.channelByte()), StandardCharsets.UTF_8);
            Matcher user = Pattern.compile("<userName>([^<]+)</userName>").matcher(loginXml);
            Matcher pass = Pattern.compile("<password>([^<]+)</password>").matcher(loginXml);
            boolean ok = user.find() && pass.find() && user.group(1).equals(BcCrypto.md5Modern(USER + NONCE)) && pass.group(1).equals(BcCrypto.md5Modern(realPassword + NONCE));
            if (!ok) {
                out.write(reply24(1, login.messageId, 401, new byte[0]));
                return;
            }
            log.add("login ok");
            out.write(reply24(1, login.messageId, 200, BcCrypto.xor("<body><DeviceInfo/></body>".getBytes(StandardCharsets.UTF_8), login.channelByte())));
            BcMessage subscribe = BcMessage.read(in);
            if (subscribe.command == BaichuanClient.CMD_SUBSCRIBE) out.write(reply24(31, subscribe.messageId, 400, new byte[0]));
            byte[] key = BcCrypto.aesKey(NONCE, realPassword);
            out.write(reply24(33, 0, 200, BcCrypto.aes(key, push("none", "none").getBytes(StandardCharsets.UTF_8), true)));
            out.write(reply24(33, 0, 200, BcCrypto.aes(key, push("visitor", "none").getBytes(StandardCharsets.UTF_8), true)));
            out.flush();
            while (true) {
                BcMessage message = BcMessage.read(in);
                log.add("keepalive cmd " + message.command);
                out.write(reply24(message.command, message.messageId, message.command == BaichuanClient.CMD_SUBSCRIBE ? 400 : 200, new byte[0]));
            }
        } catch (Exception closed) {
            // The client stopped.
        }
    }

    static String push(String status, String ai) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<body>\n<AlarmEventList version=\"1.1\">\n<AlarmEvent version=\"1.1\">\n<channelId>0</channelId>\n<status>"
            + status + "</status>\n<AItype>" + ai + "</AItype>\n<recording>0</recording>\n<timeStamp>0</timeStamp>\n</AlarmEvent>\n</AlarmEventList>\n</body>\n";
    }

    private static byte[] reply24(int command, int id, int status, byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[] ints = {BcMessage.MAGIC, command, body.length, id};
        for (int v : ints) le(out, v, 4);
        le(out, status, 2);
        le(out, BcMessage.CLASS_ZERO, 2);
        le(out, 0, 4);
        out.write(body, 0, body.length);
        return out.toByteArray();
    }

    private static byte[] reply20(int command, int id, int encryption, byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[] ints = {BcMessage.MAGIC, command, body.length, id};
        for (int v : ints) le(out, v, 4);
        le(out, encryption, 2);
        le(out, BcMessage.CLASS_MODERN_20, 2);
        out.write(body, 0, body.length);
        return out.toByteArray();
    }

    private static void le(ByteArrayOutputStream out, int value, int bytes) {
        for (int i = 0; i < bytes; i++) out.write(value >>> (8 * i));
    }

    private static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder();
        for (byte b : bytes) s.append(String.format("%02x", b & 0xFF));
        return s.toString();
    }

    private static void check(Object actual, Object expected) {
        if (!expected.equals(actual)) throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
    }
}
