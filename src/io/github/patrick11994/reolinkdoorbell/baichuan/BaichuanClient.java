// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell.baichuan;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps one Baichuan (TCP 9000) session to the doorbell open for push events: nonce, login, subscribe, keepalive,
 * and reconnect with backoff. Everything runs on one thread the client owns; listener calls come from that thread.
 * Protocol knowledge from reolink_aio (MIT), reimplemented here.
 */
public final class BaichuanClient {
    public interface Listener {
        /** Connecting, connected, or why the session dropped. */
        void onConnection(boolean connected, String detail);
        /** Every channel in every cmd 33 push, in order. */
        void onAlarm(AlarmEvents.Event event);
    }

    static final int CMD_LOGIN = 1;
    static final int CMD_SUBSCRIBE = 31;
    static final int CMD_ALARM = 33;
    static final int CMD_KEEPALIVE = 93;
    static final int CHANNEL_HOST = 250;
    static final int CHANNEL_PUSH = 251;
    private static final Pattern NONCE = Pattern.compile("<nonce>([^<]+)</nonce>");
    private static final boolean TRACE = Boolean.getBoolean("baichuan.trace");  // -Dbaichuan.trace=true for the Mac-side live check
    private static final Pattern SIG_VER = Pattern.compile("<sigVer>([^<]+)</sigVer>");
    private static final long MAX_RETRY = 8_000;

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final Listener listener;
    private final long keepaliveMillis;
    private final int timeoutMillis;
    private volatile boolean running;
    private volatile Socket socket;
    private Thread thread;
    private int counter;
    private byte[] aesKey;
    private long connectedSince;
    long firstRetry = 2_000;  // set before start(); tests shorten it

    public BaichuanClient(String host, int port, String username, String password, Listener listener) {
        this(host, port, username, password, listener, 25_000, 5_000);
    }

    BaichuanClient(String host, int port, String username, String password, Listener listener, long keepaliveMillis, int timeoutMillis) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.listener = listener;
        this.keepaliveMillis = keepaliveMillis;
        this.timeoutMillis = timeoutMillis;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::run, "reolink-baichuan");
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
            connectedSince = 0;
            boolean refused = false;
            try {
                listener.onConnection(false, "connecting");
                session();
            } catch (LoginRefused error) {
                listener.onConnection(false, error.getMessage());
                refused = true;
            } catch (IOException | RuntimeException error) {
                if (running) listener.onConnection(false, describe(error));
            } finally {
                closeSocket();
            }
            // A session that held for a minute was healthy: start the backoff over.
            if (connectedSince > 0 && System.nanoTime() / 1_000_000 - connectedSince > 60_000) backoff = firstRetry;
            if (!running) break;
            try {
                // Wrong credentials: do not hammer the doorbell's login limiter. Anything else: rings are being
                // missed, so keep retrying often enough to be back within seconds of the doorbell.
                Thread.sleep(refused ? 60_000 : backoff);
            } catch (InterruptedException e) {
                break;
            }
            if (!refused) backoff = Math.min(MAX_RETRY, backoff * 2);
        }
    }

    /** One connected session: returns or throws when it ends. */
    private void session() throws IOException {
        Socket s = new Socket();
        socket = s;
        if (!running) throw new IOException("stopped");  // stop() ran before it could see this socket
        DataInputStream in = connect(s);
        OutputStream out = s.getOutputStream();
        login(in, out);

        // 3. Subscribe to pushes, then keep the session alive.
        int subscribeId = nextId(CHANNEL_PUSH);
        out.write(BcMessage.encodeModern(CMD_SUBSCRIBE, subscribeId, new byte[0], new byte[0]));
        out.flush();
        connectedSince = System.nanoTime() / 1_000_000;
        listener.onConnection(true, "connected");
        s.setSoTimeout(1000);
        // Only what arrives counts as a sign of life: a write succeeds into the send buffer even when the doorbell is gone.
        long lastReceived = System.nanoTime() / 1_000_000;
        long lastSent = lastReceived;
        boolean pushesSeen = false;
        while (running) {
            try {
                // The short timeout only polls for the next message; it must not cut one that has started.
                in.mark(1);
                if (in.read() < 0) throw new EOFException("the doorbell closed the connection");
                in.reset();
            } catch (SocketTimeoutException quiet) {
                long now = System.nanoTime() / 1_000_000;
                if (now - Math.max(lastReceived, lastSent) > keepaliveMillis) {
                    // reolink_aio: LinkType (93) keeps a live subscription open; re-subscribing revives a silent one.
                    // The doorbell answers both, so allow one interval for the answer before sending again.
                    int id = nextId(pushesSeen ? CHANNEL_HOST : CHANNEL_PUSH);
                    out.write(BcMessage.encodeModern(pushesSeen ? CMD_KEEPALIVE : CMD_SUBSCRIBE, id, new byte[0], new byte[0]));
                    out.flush();
                    lastSent = now;
                }
                if (now - lastReceived > 3 * keepaliveMillis) throw new IOException("the doorbell stopped answering");
                continue;
            }
            BcMessage message;
            s.setSoTimeout(timeoutMillis);
            try {
                message = trace(BcMessage.read(in));
            } catch (SocketTimeoutException stalled) {
                throw new IOException("the doorbell stopped in the middle of a message");
            }
            s.setSoTimeout(1000);
            lastReceived = System.nanoTime() / 1_000_000;
            if (message.command == CMD_ALARM) {
                pushesSeen = true;
                List<AlarmEvents.Event> events = AlarmEvents.parse(decode(message, false));
                for (AlarmEvents.Event event : events) listener.onAlarm(event);
            }
        }
    }

    /**
     * Logs in once and disconnects, for the settings page's check. Throws with a message a person can act on:
     * a wrong address, a refused login or an account device.
     */
    public static void verify(String host, int port, String username, String password) throws IOException {
        login(host, port, username, password, 5_000).close();
    }

    /** A logged-in session for other work than events, such as talking. The caller owns and closes it. */
    static Login login(String host, int port, String username, String password, int timeoutMillis) throws IOException {
        BaichuanClient client = new BaichuanClient(host, port, username, password, new Listener() {
            @Override public void onConnection(boolean connected, String detail) {}
            @Override public void onAlarm(AlarmEvents.Event event) {}
        }, 25_000, timeoutMillis);
        Socket s = new Socket();
        try {
            DataInputStream in = client.connect(s);
            client.login(in, s.getOutputStream());
            return new Login(client, s, in);
        } catch (IOException | RuntimeException error) {
            s.close();
            throw error;
        }
    }

    /** One logged-in Baichuan session: its socket, its AES key and its message ids. */
    static final class Login implements java.io.Closeable {
        final Socket socket;
        final DataInputStream in;
        final OutputStream out;
        boolean stopSent;  // guarded by out: a stream's stop message went out
        private final BaichuanClient client;

        private Login(BaichuanClient client, Socket socket, DataInputStream in) throws IOException {
            this.client = client;
            this.socket = socket;
            this.in = in;
            this.out = socket.getOutputStream();
        }

        byte[] aesKey() {
            return client.aesKey;
        }

        synchronized int nextId(int channel) {
            return client.nextId(channel);
        }

        @Override public void close() throws IOException {
            socket.close();
        }
    }

    private DataInputStream connect(Socket s) throws IOException {
        s.setTcpNoDelay(true);
        s.connect(new InetSocketAddress(host, port), timeoutMillis);
        s.setSoTimeout(timeoutMillis);
        return new DataInputStream(new BufferedInputStream(s.getInputStream()));
    }

    /** The nonce exchange and the login; afterwards aesKey decrypts what the doorbell sends. */
    private void login(DataInputStream in, OutputStream out) throws IOException {
        // 1. A header-only legacy request: the reply carries the nonce, XOR-encrypted.
        int nonceId = nextId(CHANNEL_HOST);
        out.write(BcMessage.encodeNonceRequest(nonceId));
        out.flush();
        String nonceReply = decode(await(in, CMD_LOGIN, nonceId), true);
        Matcher sig = SIG_VER.matcher(nonceReply);
        if (sig.find() && "v3".equals(sig.group(1)))
            throw new LoginRefused("the doorbell is set up as a Reolink 'account device', which blocks local login");
        Matcher nonce = NONCE.matcher(nonceReply);
        if (!nonce.find()) throw new IOException("no nonce in the login reply");
        aesKey = BcCrypto.aesKey(nonce.group(1), password);

        // 2. Login with hashed credentials, XOR-encrypted.
        String login = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<body>\n<LoginUser version=\"1.1\">\n"
            + "<userName>" + BcCrypto.md5Modern(username + nonce.group(1)) + "</userName>\n"
            + "<password>" + BcCrypto.md5Modern(password + nonce.group(1)) + "</password>\n"
            + "<userVer>1</userVer>\n</LoginUser>\n<LoginNet version=\"1.1\">\n<type>LAN</type>\n<udpPort>0</udpPort>\n</LoginNet>\n</body>\n";
        int loginId = nextId(CHANNEL_HOST);
        out.write(BcMessage.encodeModern(CMD_LOGIN, loginId, new byte[0], BcCrypto.xor(login.getBytes(StandardCharsets.UTF_8), CHANNEL_HOST)));
        out.flush();
        BcMessage loginReply = await(in, CMD_LOGIN, loginId);
        if (loginReply.headerLength == 24 && loginReply.statusOrEncryption == 401) throw new LoginRefused("the doorbell refused the username or password");
        if (loginReply.headerLength == 24 && loginReply.statusOrEncryption != 200) throw new IOException("login failed with status " + loginReply.statusOrEncryption);
    }

    /** Reads until the reply to (command, id) arrives; pushes that arrive meanwhile are delivered. */
    private BcMessage await(DataInputStream in, int command, int id) throws IOException {
        while (true) {
            BcMessage message = trace(BcMessage.read(in));
            if (message.command == command && message.messageId == id) return message;
            if (message.command == CMD_ALARM && aesKey != null) {
                for (AlarmEvents.Event event : AlarmEvents.parse(decode(message, false))) listener.onAlarm(event);
            }
        }
    }

    /** Decrypts a body: XOR for the login exchange, AES after it, as the header says. */
    private String decode(BcMessage message, boolean expectXor) {
        if (message.body.length == 0) return "";
        int encryption = message.statusOrEncryption;
        boolean xor = expectXor || (message.headerLength == 20 && (encryption == 0xdd01 || encryption == 0xdd12));
        byte[] plain = xor ? BcCrypto.xor(message.body, message.channelByte()) : BcCrypto.aes(aesKey, message.body, false);
        return new String(plain, StandardCharsets.UTF_8);
    }

    private static BcMessage trace(BcMessage message) {
        if (TRACE) System.out.println("  <- cmd " + message.command + " id " + Integer.toHexString(message.messageId) + " status/enc "
            + Integer.toHexString(message.statusOrEncryption) + " class " + Integer.toHexString(message.messageClass) + " body " + message.body.length + " payload " + message.payload.length);
        return message;
    }

    private int nextId(int channel) {
        counter = (counter + 1) & 0xFFFFFF;
        return (channel & 0xFF) | (counter << 8);
    }

    private void closeSocket() {
        Socket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // Closing is best effort.
            }
        }
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    /** The doorbell answered but refused this login: wrong credentials or an account device. */
    public static final class LoginRefused extends IOException {
        LoginRefused(String message) {
            super(message);
        }
    }
}
