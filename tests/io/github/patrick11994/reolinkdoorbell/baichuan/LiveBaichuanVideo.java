// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell.baichuan;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.patrick11994.reolinkdoorbell.media.StreamHub;

/**
 * Spike, run by hand against a real doorbell (REOLINK_HOST/USER/PASSWORD): live video over Baichuan the way the Reolink
 * app asks for it (cmd 3 Preview, policy newestIFrame), against RTSP, for the time to the first keyframe. Parses the
 * BcMedia stream (1002 info, 00dc keyframe, 01dc P-frame, 05wb AAC), decrypting each message's first encryptLen bytes.
 * Optionally writes the H.264 to a file for ffmpeg. Layout first learned from neolink (AGPL-3.0); no code copied.
 *
 *     java -cp <classes> io.github.patrick11994.reolinkdoorbell.baichuan.LiveBaichuanVideo [runs] [main|sub] [out.h264]
 */
public final class LiveBaichuanVideo {
    static final int CMD_PREVIEW = 3, CMD_PREVIEW_STOP = 4;
    static boolean TIMING = Boolean.getBoolean("timing");
    private static final Pattern ENCRYPT_LEN = Pattern.compile("<encryptLen>(\\d+)</encryptLen>");

    public static void main(String[] args) throws Exception {
        String host = System.getenv("REOLINK_HOST"), user = System.getenv("REOLINK_USER"), pass = System.getenv("REOLINK_PASSWORD");
        int runs = args.length > 0 ? Integer.parseInt(args[0]) : 5;
        String stream = args.length > 1 ? args[1] : "main";
        String out = args.length > 2 ? args[2] : null;
        if (Boolean.getBoolean("compare")) {
            compare(host, user, pass, stream);
            return;
        }
        for (int run = 1; run <= runs; run++) {
            Result b = baichuan(host, user, pass, stream, run == 1 ? out : null);
            Thread.sleep(800);
            long rtsp = rtsp(host, user, pass, stream);
            System.out.printf("run %d: Baichuan login %d ms + preview to first keyframe %d ms (keyframe %d KB, %s) | RTSP first keyframe %d ms%n",
                run, b.loginMillis, b.keyframeMillis, b.keyframeBytes / 1024, b.summary, rtsp);
            Thread.sleep(800);
        }
    }

    static final class Result {
        long loginMillis, keyframeMillis = -1;
        int keyframeBytes;
        String summary = "";
    }

    static Result baichuan(String host, String user, String pass, String stream, String out) throws Exception {
        Result r = new Result();
        long t0 = System.nanoTime();
        try (BaichuanClient.Login s = BaichuanClient.login(host, 9000, user, pass, 5000);
             FileOutputStream file = out == null ? null : new FileOutputStream(out)) {
            r.loginMillis = (System.nanoTime() - t0) / 1_000_000;
            String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<body>\n<Preview version=\"1.1\">\n<channelId>0</channelId>\n<handle>0</handle>\n"
                + "<streamType>" + (stream.equals("sub") ? "subStream" : "mainStream") + "</streamType>\n<policy>newestIFrame</policy>\n</Preview>\n</body>\n";
            int id = s.nextId(0);
            long asked = System.nanoTime();
            s.out.write(BcMessage.encodeModern(CMD_PREVIEW, id, new byte[0], BcCrypto.aes(s.aesKey(), xml.getBytes(StandardCharsets.UTF_8), true)));
            s.out.flush();
            ByteArrayOutputStream media = new ByteArrayOutputStream();
            int keyframes = 0, pframes = 0, aac = 0, other = 0, width = 0, height = 0, fps = 0;
            long deadline = System.nanoTime() + 4_000_000_000L;
            while (System.nanoTime() < deadline) {
                BcMessage m = BcMessage.read(s.in);
                if (m.command != CMD_PREVIEW || m.messageId != id || m.payload.length == 0) continue;
                String ext = m.body.length == 0 ? "" : new String(BcCrypto.aes(s.aesKey(), m.body, false), StandardCharsets.UTF_8);
                byte[] p = m.payload;
                Matcher e = ENCRYPT_LEN.matcher(ext);
                if (e.find()) {
                    int n = Math.min(Integer.parseInt(e.group(1)), p.length);
                    byte[] head = BcCrypto.aes(s.aesKey(), java.util.Arrays.copyOf(p, n), false);
                    p = p.clone();
                    System.arraycopy(head, 0, p, 0, n);
                }
                media.write(p, 0, p.length);
                // Parse every complete frame at the front of the buffer.
                byte[] all = media.toByteArray();
                int at = 0;
                while (true) {
                    int used = frameLength(all, at);
                    if (used <= 0 || at + used > all.length) break;
                    ByteBuffer b = ByteBuffer.wrap(all, at, used).order(ByteOrder.LITTLE_ENDIAN);
                    String magic = new String(all, at, 4, StandardCharsets.ISO_8859_1);
                    if (magic.equals("1002") || magic.equals("1001")) {
                        b.position(at + 8);
                        width = b.getInt();
                        height = b.getInt();
                        b.get();
                        fps = b.get() & 0xFF;
                    } else if (magic.endsWith("dc")) {
                        b.position(at + 8);
                        int size = b.getInt(), extra = b.getInt();
                        boolean key = magic.charAt(1) == '0';
                        if (key) keyframes++; else pframes++;
                        b.position(at + 16);
                        long micros = b.getInt() & 0xFFFFFFFFL;
                        if (TIMING && keyframes + pframes <= 30) System.out.printf("  frame %2d %s arrived %4d ms, stream time %8.3f s%n", keyframes + pframes,
                            key ? "key" : "P  ", (System.nanoTime() - asked) / 1_000_000, micros / 1e6);
                        if (key && r.keyframeMillis < 0) {
                            r.keyframeMillis = (System.nanoTime() - asked) / 1_000_000;
                            r.keyframeBytes = size;
                        }
                        if (file != null) file.write(all, at + 24 + extra, size);
                        Sink sink = SINK;
                        if (sink != null) sink.frame(slices(java.util.Arrays.copyOfRange(all, at + 24 + extra, at + 24 + extra + size)), System.nanoTime());
                    } else if (magic.endsWith("wb")) {
                        aac++;
                    } else {
                        other++;
                    }
                    at += used;
                }
                media.reset();
                media.write(all, at, all.length - at);
            }
            r.summary = width + "x" + height + " @ " + fps + " fps, in 4 s: " + keyframes + " keyframes, " + pframes + " P-frames, " + aac + " audio" + (other > 0 ? ", " + other + " unknown" : "");
            s.out.write(BcMessage.encodeModern(CMD_PREVIEW_STOP, s.nextId(0), new byte[0], BcCrypto.aes(s.aesKey(),
                "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<body>\n<Preview version=\"1.1\">\n<channelId>0</channelId>\n<handle>0</handle>\n</Preview>\n</body>\n".getBytes(StandardCharsets.UTF_8), true)));
            s.out.flush();
        }
        return r;
    }

    /** The length of the BcMedia frame at at, with its padding; 0 if not known yet, -1 if the bytes are not a frame. */
    static int frameLength(byte[] b, int at) {
        if (b.length - at < 8) return 0;
        String magic = new String(b, at, 4, StandardCharsets.ISO_8859_1);
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        if (magic.equals("1001") || magic.equals("1002")) return 32;
        if (magic.endsWith("dc")) {
            if (b.length - at < 24) return 0;
            int size = bb.getInt(at + 8), extra = bb.getInt(at + 12);
            return 24 + extra + size + pad(size);
        }
        if (magic.endsWith("wb")) {
            int size = bb.getShort(at + 4) & 0xFFFF;
            return 8 + size + pad(size);
        }
        return -1;
    }

    private static int pad(int size) {
        return (8 - size % 8) % 8;
    }

    /** Both paths at once: the same frame (matched by its H.264 size) arrives when over each. Positive: Baichuan later. */
    static void compare(String host, String user, String pass, String stream) throws Exception {
        java.util.Map<Integer, Long> rtspArrival = new java.util.concurrent.ConcurrentHashMap<>();
        long t0 = System.nanoTime();
        StreamHub hub = new StreamHub(host, 554, stream.equals("sub") ? "/h264Preview_01_sub" : "/h264Preview_01_main", user, pass, state -> {});
        hub.attachVideo((au, micros, key) -> rtspArrival.putIfAbsent(slices(au), (System.nanoTime() - t0) / 1_000_000));
        hub.start();
        Thread.sleep(1500);  // RTSP running and live first
        java.util.List<long[]> bc = new java.util.ArrayList<>();
        SINK = (size, at) -> bc.add(new long[] {size, (at - t0) / 1_000_000});
        baichuan(host, user, pass, stream, null);
        SINK = null;
        Thread.sleep(500);
        hub.stop();
        java.util.List<Long> deltas = new java.util.ArrayList<>();
        int shown = 0;
        for (long[] f : bc) {
            Long r = rtspArrival.get((int) f[0]);
            if (r == null) continue;
            deltas.add(f[1] - r);
            if (shown++ < 12) System.out.printf("  frame %7d bytes: RTSP at %5d ms, Baichuan at %5d ms, Baichuan %+d ms%n", f[0], r, f[1], f[1] - r);
        }
        java.util.Collections.sort(deltas);
        System.out.println("matched " + deltas.size() + " of " + bc.size() + " Baichuan frames; Baichuan minus RTSP: median "
            + (deltas.isEmpty() ? "-" : deltas.get(deltas.size() / 2) + " ms, min " + deltas.get(0) + ", max " + deltas.get(deltas.size() - 1)));
    }

    interface Sink { void frame(long size, long nanos); }
    static volatile Sink SINK;

    /** The H.264 slice bytes of an Annex-B access unit, without parameter sets and start codes: comparable across paths. */
    static int slices(byte[] au) {
        int total = 0, start = -1, type = -1;
        for (int i = 0; i + 3 <= au.length; i++) {
            boolean code = au[i] == 0 && au[i + 1] == 0 && (au[i + 2] == 1 || (i + 3 < au.length && au[i + 2] == 0 && au[i + 3] == 1));
            if (code || i + 3 == au.length) {
                if (start >= 0 && (type == 1 || type == 5)) total += (code ? i : au.length) - start;
                if (!code) break;
                int skip = au[i + 2] == 1 ? 3 : 4;
                start = i + skip;
                type = start < au.length ? au[start] & 0x1F : -1;
                i += skip - 1;
            }
        }
        return total;
    }

    /** Time from starting an RTSP session to its first keyframe, as the plugin does it today. */
    static long rtsp(String host, String user, String pass, String stream) throws Exception {
        long t0 = System.nanoTime();
        long[] first = {-1};
        StreamHub hub = new StreamHub(host, 554, stream.equals("sub") ? "/h264Preview_01_sub" : "/h264Preview_01_main", user, pass, state -> {});
        hub.attachVideo((au, micros, key) -> {
            if (key && first[0] < 0) first[0] = (System.nanoTime() - t0) / 1_000_000;
        });
        hub.start();
        long deadline = System.currentTimeMillis() + 4000;
        while (first[0] < 0 && System.currentTimeMillis() < deadline) Thread.sleep(10);
        hub.stop();
        return first[0];
    }

}
