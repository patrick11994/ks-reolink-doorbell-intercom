// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell.onvif;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OnvifEventsTest {
    /** A press as the PoE doorbell sends it, with the Initialized message every subscription starts with before it. */
    static final String VISITOR_FALSE = message("Initialized", "false");
    static final String VISITOR_TRUE = message("Changed", "true");
    static final String PEOPLE = "<tt:NotificationMessage><wsnt:Topic Dialect=\"http://www.onvif.org/ver10/tev/topicExpression/ConcreteSet\">"
        + "tns1:RuleEngine/MyRuleDetector/PeopleDetect</wsnt:Topic><wsnt:Message><tt:Message PropertyOperation=\"Changed\"><tt:Data>"
        + "<tt:SimpleItem Name=\"State\" Value=\"true\"/></tt:Data></tt:Message></wsnt:Message></tt:NotificationMessage>";

    public static void main(String[] args) throws Exception {
        parsing();
        session();
        wrongPassword();
        System.out.println("OnvifEventsTest passed");
    }

    static String message(String operation, String state) {
        return "<wsnt:NotificationMessage><wsnt:Topic Dialect=\"http://www.onvif.org/ver10/tev/topicExpression/ConcreteSet\">"
            + "tns1:RuleEngine/MyRuleDetector/Visitor</wsnt:Topic><wsnt:Message><tt:Message UtcTime=\"2026-11-11T18:33:06Z\" PropertyOperation=\""
            + operation + "\"><tt:Source><tt:SimpleItem Name=\"Source\" Value=\"000\"/></tt:Source><tt:Data><tt:SimpleItem Name=\"State\" Value=\""
            + state + "\"/></tt:Data></tt:Message></wsnt:Message></wsnt:NotificationMessage>";
    }

    static void parsing() {
        check(OnvifEvents.visitor(VISITOR_TRUE), true);
        check(OnvifEvents.visitor(VISITOR_FALSE), false);
        check(OnvifEvents.visitor(PEOPLE) == null, true);
        check(OnvifEvents.secondsLeft("<tev:CurrentTime>2026-11-11T18:33:26Z</tev:CurrentTime><tev:TerminationTime>2026-11-11T18:35:26Z</tev:TerminationTime>"), 120L);
        check(OnvifEvents.secondsLeft("<x/>"), 0L);
        check(OnvifEvents.path("http://192.168.178.82:8000/onvif/PullSubManager?Idx=967911442"), "/onvif/PullSubManager?Idx=967911442");
        check(OnvifEvents.path("/onvif/PullSubManager?Idx=1"), "/onvif/PullSubManager?Idx=1");
        Map<String, String> c = OnvifEvents.challenge(" algorithm=MD5, realm=\"camara\", qop=\"auth\", nonce=\"6af4\", opaque=\"3272\"");
        check(c.get("realm") + " " + c.get("nonce") + " " + c.get("qop") + " " + c.get("opaque"), "camara 6af4 auth 3272");
    }

    /** One request as the fake doorbell saw it. */
    static final class Request {
        String path;
        String authorization;
        String body;
    }

    /** A fake doorbell: answers each connection with the next answer, as the real one closes every connection. */
    static final class Doorbell implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0);
        final BlockingQueue<Request> requests = new LinkedBlockingQueue<>();
        final BlockingQueue<String> answers = new LinkedBlockingQueue<>();
        final Thread thread;

        Doorbell() throws Exception {
            thread = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket socket = server.accept()) {
                        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                        Request request = new Request();
                        request.path = in.readLine().split(" ")[1];
                        int length = 0;
                        for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
                            if (line.startsWith("Content-Length:")) length = Integer.parseInt(line.substring(15).trim());
                            if (line.startsWith("Authorization:")) request.authorization = line.substring(14).trim();
                        }
                        char[] body = new char[length];
                        for (int read = 0; read < length; ) read += in.read(body, read, length - read);
                        request.body = new String(body);
                        requests.add(request);
                        String answer = answers.poll(10, TimeUnit.SECONDS);
                        if (answer == null) continue;
                        OutputStream out = socket.getOutputStream();
                        out.write(answer.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } catch (Exception closed) {
                        // The test is over, or the client gave up on this connection.
                    }
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        static String http(int status, String extra, String body) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            return "HTTP/1.1 " + status + (status == 200 ? " OK" : " Unauthorized") + "\r\n" + extra + "Content-Type: application/soap+xml\r\nContent-Length: "
                + bytes.length + "\r\nConnection: close\r\n\r\n" + body;
        }

        static String challenge(String nonce) {
            return http(401, "WWW-Authenticate: Digest algorithm=MD5, realm=\"camara\", qop=\"auth\", nonce=\"" + nonce + "\", opaque=\"327b23c6\"\r\n", "<fault/>");
        }

        static String pulled(String messages, String current, String termination) {
            return http(200, "", "<s:Envelope><s:Body><tev:PullMessagesResponse><tev:CurrentTime>" + current + "</tev:CurrentTime><tev:TerminationTime>"
                + termination + "</tev:TerminationTime>" + messages + "</tev:PullMessagesResponse></s:Body></s:Envelope>");
        }

        @Override public void close() throws Exception {
            server.close();
            thread.join(2000);
        }
    }

    static final class Recorder implements OnvifEvents.Listener {
        final BlockingQueue<String> calls = new LinkedBlockingQueue<>();
        @Override public void onConnection(boolean connected, String detail) { calls.add(connected ? "connected" : detail); }
        @Override public void onVisitor(boolean visitor) { calls.add("visitor " + visitor); }
        String next() throws InterruptedException {
            String call = calls.poll(5, TimeUnit.SECONDS);
            if (call == null) throw new AssertionError("nothing happened");
            return call;
        }
    }

    /** Subscribe with digest, pull a press, renew when the subscription runs low, and keep the nonce across connections. */
    static void session() throws Exception {
        try (Doorbell doorbell = new Doorbell()) {
            Recorder recorder = new Recorder();
            OnvifEvents events = new OnvifEvents("127.0.0.1", doorbell.server.getLocalPort(), "user", "secret", recorder);
            events.pullSeconds = 1;
            doorbell.answers.add(Doorbell.challenge("n1"));
            doorbell.answers.add(Doorbell.http(200, "", "<tev:CreatePullPointSubscriptionResponse><tev:SubscriptionReference><wsa5:Address>"
                + "http://192.168.178.82:8000/onvif/PullSubManager?Idx=7</wsa5:Address></tev:SubscriptionReference></tev:CreatePullPointSubscriptionResponse>"));
            doorbell.answers.add(Doorbell.pulled(VISITOR_FALSE + PEOPLE, "2026-11-11T18:33:06Z", "2026-11-11T18:35:06Z"));
            doorbell.answers.add(Doorbell.pulled(VISITOR_TRUE, "2026-11-11T18:33:07Z", "2026-11-11T18:33:08Z"));  // runs low
            doorbell.answers.add(Doorbell.http(200, "", "<wsnt:RenewResponse/>"));
            events.start();
            check(recorder.next(), "connecting");
            check(recorder.next(), "connected");
            check(recorder.next(), "visitor false");
            check(recorder.next(), "visitor true");

            List<Request> seen = new ArrayList<>();
            for (int i = 0; i < 5; i++) seen.add(doorbell.requests.poll(5, TimeUnit.SECONDS));
            check(seen.get(0).authorization == null, true);
            check(seen.get(0).path, "/onvif/event_service");
            check(seen.get(1).body.contains("<CreatePullPointSubscription"), true);
            checkDigest(seen.get(1), "/onvif/event_service", "n1", "00000001");
            check(seen.get(2).path, "/onvif/PullSubManager?Idx=7");  // asked of the doorbell itself, not the host it named
            check(seen.get(2).body.contains("<PullMessages"), true);
            checkDigest(seen.get(2), "/onvif/PullSubManager?Idx=7", "n1", "00000002");  // no new challenge per connection
            check(seen.get(4).body.contains("<Renew"), true);
            events.stop();
        }
    }

    static void wrongPassword() throws Exception {
        try (Doorbell doorbell = new Doorbell()) {
            Recorder recorder = new Recorder();
            OnvifEvents events = new OnvifEvents("127.0.0.1", doorbell.server.getLocalPort(), "user", "wrong", recorder);
            doorbell.answers.add(Doorbell.challenge("n1"));
            doorbell.answers.add(Doorbell.challenge("n1"));
            events.start();
            check(recorder.next(), "connecting");
            check(recorder.next(), "the doorbell refused the username or password");
            events.stop();
        }
    }

    static void checkDigest(Request request, String uri, String nonce, String nc) throws Exception {
        Matcher m = Pattern.compile("response=\"(\\w+)\".*cnonce=\"(\\w+)\"").matcher(request.authorization);
        check(m.find(), true);
        check(request.authorization.startsWith("Digest algorithm=MD5, username=\"user\""), true);
        check(request.authorization.contains("nc=" + nc), true);
        String expected = md5(md5("user:camara:secret") + ":" + nonce + ":" + nc + ":" + m.group(2) + ":auth:" + md5("POST:" + uri));
        check(m.group(1), expected);
    }

    static String md5(String text) throws Exception {
        StringBuilder s = new StringBuilder();
        for (byte b : MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8))) s.append(String.format("%02x", b & 0xff));
        return s.toString();
    }

    private static void check(Object actual, Object expected) {
        if (!expected.equals(actual)) throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
    }
}
