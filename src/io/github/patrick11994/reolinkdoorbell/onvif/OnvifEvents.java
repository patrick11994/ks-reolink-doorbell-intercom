// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell.onvif;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Button presses over ONVIF, for doorbells without Reolink's own protocol on port 9000, such as the Video Doorbell WiFi
 * Gen 2 (#14). A PullPoint subscription on port 8000 brings the doorbell's tns1:RuleEngine/MyRuleDetector/Visitor
 * event, State true for about 15 s per press, the same as Baichuan's visitor flag.
 *
 * The doorbell's ONVIF server closes the connection after every answer, and its small stack starts dropping new
 * connections when they come too often. So the session keeps to one long pull at a time, logs in with HTTP digest
 * (its nonce holds across connections, so no extra round trip per request, and no clock to agree on), and backs off
 * instead of retrying in a loop.
 */
public final class OnvifEvents {
    public interface Listener {
        /** Connecting, connected, or why the session dropped. */
        void onConnection(boolean connected, String detail);
        /** The Visitor state, from every message that carries it. */
        void onVisitor(boolean visitor);
    }

    static final String EVENTS = "http://www.onvif.org/ver10/events/wsdl";
    private static final Pattern ADDRESS = Pattern.compile("<(?:\\w+:)?Address>\\s*([^<\\s]+)\\s*</");
    private static final Pattern MESSAGE = Pattern.compile("<(?:\\w+:)?NotificationMessage\\b.*?</(?:\\w+:)?NotificationMessage>", Pattern.DOTALL);
    private static final Pattern TOPIC = Pattern.compile("<(?:\\w+:)?Topic\\b[^>]*>\\s*([^<]*?)\\s*</");
    private static final Pattern STATE = Pattern.compile("<(?:\\w+:)?SimpleItem\\b[^>]*Name=\"State\"[^>]*Value=\"(\\w+)\"|<(?:\\w+:)?SimpleItem\\b[^>]*Value=\"(\\w+)\"[^>]*Name=\"State\"");
    private static final Pattern CURRENT = Pattern.compile("<(?:\\w+:)?CurrentTime>([^<]+)<");
    private static final Pattern TERMINATION = Pattern.compile("<(?:\\w+:)?TerminationTime>([^<]+)<");
    private static final Pattern CHALLENGE = Pattern.compile("(\\w+)=\"?([^\",]*)\"?");
    private static final int MAX_ANSWER = 1 << 20;

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final Listener listener;
    private volatile boolean running;
    private volatile Socket socket;
    private Thread thread;
    private Map<String, String> challenge;  // the doorbell's digest challenge, kept: its nonce holds across connections
    private int nonceCount;
    long firstRetry = 5_000;  // set before start(); tests shorten them
    long maxRetry = 60_000;
    int pullSeconds = 50;  // under the subscription's 120 s, with time to renew between pulls
    int connectMillis = 5_000;

    public OnvifEvents(String host, int port, String username, String password, Listener listener) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.listener = listener;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::run, "reolink-onvif-events");
        thread.setDaemon(true);
        thread.start();
    }

    /** Stops and waits up to a second for the thread, so a plugin stop leaves nothing running. */
    public void stop() {
        Thread current;
        synchronized (this) {
            running = false;
            current = thread;
            thread = null;
        }
        closeSocket();
        if (current != null) {
            current.interrupt();
            try {
                current.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void run() {
        long backoff = firstRetry;
        while (running) {
            boolean refused = false;
            boolean pulled = false;
            String subscription = null;
            try {
                listener.onConnection(false, "connecting");
                subscription = subscribe();
                listener.onConnection(true, "connected");
                pulled = true;
                session(subscription);
            } catch (Refused error) {
                listener.onConnection(false, error.getMessage());
                refused = true;
            } catch (IOException | RuntimeException error) {
                if (running) listener.onConnection(false, describe(error));
            } finally {
                closeSocket();
            }
            if (subscription != null && !running) unsubscribe(subscription);
            if (!running) break;
            if (pulled) backoff = firstRetry;  // it held: a fresh subscription soon, without retry churn
            try {
                // Wrong credentials: do not hammer the doorbell. Anything else: back off, since quick retries are what
                // make this doorbell's ONVIF server stop answering.
                Thread.sleep(refused ? 60_000 : backoff);
            } catch (InterruptedException e) {
                break;
            }
            if (!refused && !pulled) backoff = Math.min(maxRetry, backoff * 2);
        }
    }

    /** For the Check button: subscribes once and unsubscribes, or throws why that failed (Refused for the login). */
    public static void verify(String host, int port, String username, String password) throws IOException {
        OnvifEvents check = new OnvifEvents(host, port, username, password, null);
        check.running = true;
        String subscription = check.subscribe();
        try {
            check.post(subscription, "http://docs.oasis-open.org/wsn/bw-2/SubscriptionManager/UnsubscribeRequest", subscription,
                "<Unsubscribe xmlns=\"http://docs.oasis-open.org/wsn/b-2\"/>", 3_000);
        } catch (IOException ignored) {
            // The subscription worked; it runs out by itself.
        }
    }

    /** Creates the PullPoint subscription and returns its path on the doorbell. */
    private String subscribe() throws IOException {
        String answer = post("/onvif/event_service", EVENTS + "/EventPortType/CreatePullPointSubscriptionRequest", null,
            "<CreatePullPointSubscription xmlns=\"" + EVENTS + "\"><InitialTerminationTime>PT120S</InitialTerminationTime></CreatePullPointSubscription>", 10_000);
        Matcher address = ADDRESS.matcher(answer);
        if (!address.find()) throw new IOException("the doorbell gave no PullPoint address");
        return path(address.group(1));
    }

    /** Long pulls, one at a time, until one fails. */
    private void session(String subscription) throws IOException {
        while (running) {
            String answer = post(subscription, EVENTS + "/PullPointSubscription/PullMessagesRequest", subscription,
                "<PullMessages xmlns=\"" + EVENTS + "\"><Timeout>PT" + pullSeconds + "S</Timeout><MessageLimit>32</MessageLimit></PullMessages>",
                (pullSeconds + 15) * 1000);
            Matcher message = MESSAGE.matcher(answer);
            while (message.find()) {
                Boolean visitor = visitor(message.group());
                if (visitor != null) listener.onVisitor(visitor);
            }
            // The subscription ends at its TerminationTime, on the doorbell's own clock: renew it before the next pull
            // could outlast it.
            long left = secondsLeft(answer);
            if (left < pullSeconds + 15) {
                post(subscription, "http://docs.oasis-open.org/wsn/bw-2/SubscriptionManager/RenewRequest", subscription,
                    "<Renew xmlns=\"http://docs.oasis-open.org/wsn/b-2\"><TerminationTime>PT120S</TerminationTime></Renew>", 10_000);
            }
        }
    }

    /** The Visitor state in one notification, or null for any other event. */
    static Boolean visitor(String message) {
        Matcher topic = TOPIC.matcher(message);
        if (!topic.find() || !topic.group(1).endsWith("/Visitor")) return null;
        Matcher state = STATE.matcher(message);
        if (!state.find()) return null;
        return Boolean.parseBoolean(state.group(1) != null ? state.group(1) : state.group(2));
    }

    /** Seconds until the subscription ends, from a PullMessages answer; 0 when the answer does not say. */
    static long secondsLeft(String answer) {
        Matcher current = CURRENT.matcher(answer), termination = TERMINATION.matcher(answer);
        if (!current.find() || !termination.find()) return 0;
        try {
            return (time(termination.group(1)) - time(current.group(1))) / 1000;
        } catch (ParseException unreadable) {
            return 0;
        }
    }

    private static long time(String text) throws ParseException {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.parse(text.trim()).getTime();  // fractions and the Z after the seconds are left out
    }

    /** The path and query of a PullPoint address: always asked of the doorbell itself, whatever host it names. */
    static String path(String address) {
        int scheme = address.indexOf("://");
        if (scheme < 0) return address.startsWith("/") ? address : "/" + address;
        int slash = address.indexOf('/', scheme + 3);
        return slash < 0 ? "/" : address.substring(slash);
    }

    /** Best effort, so the doorbell does not keep a subscription nobody pulls until it runs out. */
    private void unsubscribe(String subscription) {
        Thread thread = new Thread(() -> {
            try {
                post(subscription, "http://docs.oasis-open.org/wsn/bw-2/SubscriptionManager/UnsubscribeRequest", subscription,
                    "<Unsubscribe xmlns=\"http://docs.oasis-open.org/wsn/b-2\"/>", 3_000);
            } catch (IOException | RuntimeException ignored) {
                // It runs out by itself within two minutes.
            }
        }, "reolink-onvif-unsubscribe");
        thread.setDaemon(true);
        thread.start();
    }

    /** One SOAP request on its own connection, as the doorbell closes each one after its answer. */
    private String post(String path, String action, String to, String body, int readMillis) throws IOException {
        String header = to == null ? "" : "<a:Action>" + action + "</a:Action><a:To>http://" + host + ":" + port + escape(to) + "</a:To>";
        byte[] envelope = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\""
            + " xmlns:a=\"http://www.w3.org/2005/08/addressing\"><s:Header>" + header + "</s:Header><s:Body>" + body + "</s:Body></s:Envelope>")
            .getBytes(StandardCharsets.UTF_8);
        for (int attempt = 0; attempt < 2; attempt++) {
            Answer answer = exchange(path, action, envelope, readMillis);
            if (answer.status == 401) {
                Map<String, String> offered = challenge(answer.authenticate);
                boolean stale = challenge != null && !offered.getOrDefault("nonce", "").equals(challenge.get("nonce"));
                if (offered.isEmpty() || (attempt > 0 && !stale)) throw new Refused("the doorbell refused the username or password");
                challenge = offered;
                nonceCount = 0;
                continue;
            }
            if (answer.status != 200) throw new IOException("ONVIF " + action.substring(action.lastIndexOf('/') + 1) + " failed with " + answer.status);
            return answer.body;
        }
        throw new Refused("the doorbell refused the username or password");
    }

    private static final class Answer {
        int status;
        String authenticate = "";
        String body = "";
    }

    private Answer exchange(String path, String action, byte[] envelope, int readMillis) throws IOException {
        Socket s = new Socket();
        socket = s;
        if (!running) throw new IOException("stopped");
        try {
            s.connect(new InetSocketAddress(host, port), connectMillis);
            s.setSoTimeout(readMillis);
            StringBuilder request = new StringBuilder();
            request.append("POST ").append(path).append(" HTTP/1.1\r\n");
            request.append("Host: ").append(host).append(':').append(port).append("\r\n");
            request.append("Content-Type: application/soap+xml; charset=utf-8; action=\"").append(action).append("\"\r\n");
            request.append("Content-Length: ").append(envelope.length).append("\r\n");
            String authorization = authorization(path);
            if (authorization != null) request.append("Authorization: ").append(authorization).append("\r\n");
            request.append("Connection: close\r\n\r\n");
            OutputStream out = s.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.US_ASCII));
            out.write(envelope);
            out.flush();
            return read(s.getInputStream());
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
    }

    static Answer read(InputStream in) throws IOException {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int headEnd = -1;
        int length = -1;
        Answer answer = new Answer();
        for (int n; (n = in.read(buffer)) >= 0; ) {
            all.write(buffer, 0, n);
            if (all.size() > MAX_ANSWER) throw new IOException("the doorbell's ONVIF answer is too long");
            if (headEnd < 0) {
                String text = new String(all.toByteArray(), StandardCharsets.ISO_8859_1);
                headEnd = text.indexOf("\r\n\r\n");
                if (headEnd < 0) continue;
                String[] lines = text.substring(0, headEnd).split("\r\n");
                String[] status = lines[0].split(" ");
                if (status.length < 2 || !status[0].startsWith("HTTP/")) throw new IOException("not an HTTP answer");
                answer.status = Integer.parseInt(status[1]);
                for (int i = 1; i < lines.length; i++) {
                    int colon = lines[i].indexOf(':');
                    if (colon < 0) continue;
                    String name = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), value = lines[i].substring(colon + 1).trim();
                    if (name.equals("content-length")) length = Integer.parseInt(value);
                    if (name.equals("www-authenticate") && value.regionMatches(true, 0, "Digest", 0, 6)) answer.authenticate = value.substring(6);
                }
            }
            if (length >= 0 && all.size() >= headEnd + 4 + length) break;
        }
        if (headEnd < 0) throw new IOException("the doorbell closed the connection without an answer");
        byte[] bytes = all.toByteArray();
        int bodyLength = length >= 0 ? Math.min(length, bytes.length - headEnd - 4) : bytes.length - headEnd - 4;
        answer.body = new String(bytes, headEnd + 4, bodyLength, StandardCharsets.UTF_8);
        return answer;
    }

    static Map<String, String> challenge(String header) {
        Map<String, String> values = new LinkedHashMap<>();
        Matcher m = CHALLENGE.matcher(header);
        while (m.find()) values.put(m.group(1).toLowerCase(Locale.ROOT), m.group(2));
        return values.containsKey("nonce") && values.containsKey("realm") ? values : new LinkedHashMap<>();
    }

    /** HTTP digest (RFC 2617) with the kept challenge, or null before the doorbell has sent one. */
    private String authorization(String uri) {
        Map<String, String> c = challenge;
        if (c == null) return null;
        String realm = c.get("realm"), nonce = c.get("nonce"), opaque = c.get("opaque");
        boolean qop = c.containsKey("qop");
        String nc = String.format(Locale.ROOT, "%08x", ++nonceCount);
        byte[] random = new byte[8];
        new SecureRandom().nextBytes(random);
        String cnonce = hex(random);
        String ha1 = md5(username + ":" + realm + ":" + password), ha2 = md5("POST:" + uri);
        String response = md5(qop ? ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2 : ha1 + ":" + nonce + ":" + ha2);
        // This doorbell refuses an answer that leaves out algorithm=MD5.
        StringBuilder header = new StringBuilder("Digest algorithm=MD5, username=\"").append(username).append("\", realm=\"").append(realm)
            .append("\", nonce=\"").append(nonce).append("\", uri=\"").append(uri).append("\", response=\"").append(response).append('"');
        if (qop) header.append(", qop=auth, nc=").append(nc).append(", cnonce=\"").append(cnonce).append('"');
        if (opaque != null) header.append(", opaque=\"").append(opaque).append('"');
        return header.toString();
    }

    private static String md5(String text) {
        try {
            return hex(MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder();
        for (byte b : bytes) s.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return s.toString();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void closeSocket() {
        Socket s = socket;
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
            // Already closed.
        }
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    /** The doorbell refused this login. */
    public static final class Refused extends IOException {
        Refused(String message) {
            super(message);
        }
    }
}
