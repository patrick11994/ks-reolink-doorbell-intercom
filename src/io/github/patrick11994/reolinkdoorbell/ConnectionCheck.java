// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import io.github.patrick11994.reolinkdoorbell.baichuan.BaichuanClient;
import io.github.patrick11994.reolinkdoorbell.baichuan.BaichuanTalk;
import io.github.patrick11994.reolinkdoorbell.media.AvcConfig;
import io.github.patrick11994.reolinkdoorbell.media.MediaStream;
import io.github.patrick11994.reolinkdoorbell.media.RtspSession;
import io.github.patrick11994.reolinkdoorbell.media.Sdp;

/**
 * The settings page's Check button: what a call needs, in order, each with a message a person can act on. Button
 * presses (a Baichuan login on 9000); the chosen stream's RTSP description on 554, which also tells whether ONVIF
 * talk is there; video the way the Video over setting goes, up to the first picture; whether this panel can play
 * that picture (some, such as the Echo Show 8, cannot play the main stream: the check says so instead of the viewer
 * switching streams behind the user's back); and talking the way the Talk setting goes: Baichuan talk on 9000, the
 * ONVIF backchannel track, or for Automatic either of them.
 */
final class ConnectionCheck {
    static final class Result {
        final boolean ok;
        final String message;

        Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    /** The panel's video decoder, as a seam for tests. */
    interface Player {
        /** Whether the decoder a call uses can show a picture of this size, or null when it does not say. */
        Boolean canPlay(int width, int height);

        /** Decodes one keyframe without showing it: true when a picture came out, false when refused, null unclear. */
        Boolean decodes(byte[] keyframe);
    }

    /** The streams a call opens, as a seam for tests. */
    interface Streams {
        /** The stream over path, Baichuan or RTSP, not yet started. */
        MediaStream open(String path, MediaStream.Listener listener);
    }

    /** Where the check writes what failed, beyond the message on the settings page. */
    interface Log {
        void line(String text);
    }

    /**
     * How long a stream may take to its first picture: Baichuan brings one in about 0.3 s, RTSP within a GOP, and a
     * battery doorbell behind a Home Hub once it is awake, about 12 s (#9).
     */
    static final long PICTURE_MILLIS = 18_000;

    private ConnectionCheck() {}

    /** Always returns a result, so the settings page never stays on "Checking". */
    static Result run(String host, int baichuanPort, int rtspPort, String rtspPath, String videoPath, String talkPath, String username, String password,
                      Streams streams, Player player, Log log, Texts texts) {
        return run(host, baichuanPort, rtspPort, rtspPath, videoPath, talkPath, username, password, 0, streams, player, log, texts);
    }

    /** channel: the doorbell's channel behind an NVR, counted from 0; 0 for a doorbell reached directly. */
    static Result run(String host, int baichuanPort, int rtspPort, String rtspPath, String videoPath, String talkPath, String username, String password,
                      int channel, Streams streams, Player player, Log log, Texts texts) {
        try {
            return checks(host, baichuanPort, rtspPort, rtspPath, videoPath, talkPath, username, password, channel, streams, player, log, texts);
        } catch (RuntimeException error) {
            return new Result(false, texts.checkFailed(describe(error)));
        }
    }

    private static Result checks(String host, int baichuanPort, int rtspPort, String rtspPath, String videoPath, String talkPath, String username, String password,
                                 int channel, Streams streams, Player player, Log log, Texts texts) {
        if (host.isEmpty() || username.isEmpty() || password.isEmpty()) return new Result(false, texts.checkNotConfigured());
        try {
            BaichuanClient.verify(host, baichuanPort, username, password);
        } catch (BaichuanClient.LoginRefused refused) {
            boolean account = refused.getMessage() != null && refused.getMessage().contains("account device");
            return new Result(false, account ? texts.checkAccountDevice() : texts.checkLoginRefused());
        } catch (IOException error) {
            return new Result(false, unreachable(error) ? texts.checkUnreachable(host) : texts.checkFailed(describe(error)));
        }
        boolean onvifTalk;
        RtspSession rtsp = new RtspSession(host, rtspPort, rtspPath, username, password);
        try {
            Sdp sdp = rtsp.describe(Collections.singletonMap("Require", "www.onvif.org/ver20/backchannel"));
            if (sdp.receive("video") == null && !videoPath.equals("Baichuan")) return new Result(false, texts.checkNoVideo());
            onvifTalk = sdp.backchannel() != null;
        } catch (IOException error) {
            // Video and talk over Baichuan alone need no RTSP: the steps below find out whether they work.
            if (!videoPath.equals("Baichuan")) return new Result(false, texts.checkNoVideo());
            onvifTalk = false;
        } finally {
            rtsp.close();
        }

        // Video, as a call opens it. Automatic tries Baichuan first and falls back to RTSP, which works but is slower.
        byte[] keyframe;
        boolean overRtspOnly = false;
        if (videoPath.equals("RTSP")) {
            keyframe = firstPicture(streams, "RTSP", log);
            if (keyframe == null) return new Result(false, texts.checkNoVideo());
        } else {
            keyframe = firstPicture(streams, "Baichuan", log);
            if (keyframe == null && videoPath.equals("Baichuan")) return new Result(false, texts.checkNoBaichuanVideo());
            if (keyframe == null) {
                keyframe = firstPicture(streams, "RTSP", log);
                if (keyframe == null) return new Result(false, texts.checkNoVideo());
                overRtspOnly = true;
            }
        }

        // Whether this panel can play it: the decoder's own answer, or else one picture decoded as a test.
        AvcConfig config = AvcConfig.of(keyframe);
        if (config.size != null) {
            Boolean plays = player.canPlay(config.size.width, config.size.height);
            if (plays == null) plays = player.decodes(keyframe);
            if (Boolean.FALSE.equals(plays)) {
                String size = config.size.width + "x" + config.size.height;
                log.line("check: this device's decoder cannot play " + size);
                return new Result(false, rtspPath.endsWith("_main") ? texts.checkMainTooLarge(size) : texts.checkVideoTooLarge(size));
            }
        }

        // Automatic probes Baichuan even when ONVIF is there: it talks over Baichuan first.
        boolean talks = talkPath.equals("ONVIF") ? onvifTalk
            : baichuanTalk(host, baichuanPort, username, password, channel) || (!talkPath.equals("Baichuan") && onvifTalk);
        if (!talks) return new Result(false, talkPath.equals("Baichuan") ? texts.checkNoBaichuanTalk() : texts.checkNoTalk());
        return new Result(true, overRtspOnly ? texts.checkVideoOverRtsp() : texts.checkOk());
    }

    /** Opens the stream over path and waits for its first keyframe, or null when none comes; why goes to the log. */
    private static byte[] firstPicture(Streams streams, String path, Log log) {
        CountDownLatch done = new CountDownLatch(1);
        byte[][] picture = new byte[1][];
        String[] failed = new String[1];
        MediaStream stream = streams.open(path, state -> {
            if (state.startsWith("stream failed") || state.startsWith("stream ended")) {
                failed[0] = state;
                done.countDown();
            }
        });
        stream.attachVideo((annexB, micros, keyframe) -> {
            if (keyframe && picture[0] == null) {
                picture[0] = annexB;
                done.countDown();
            }
        });
        stream.start();
        try {
            if (!done.await(PICTURE_MILLIS, TimeUnit.MILLISECONDS)) failed[0] = "no picture within " + PICTURE_MILLIS + " ms";
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } finally {
            stream.stop();
        }
        if (picture[0] == null) log.line("check: video over " + path + ": " + (failed[0] == null ? "stopped" : failed[0]));
        return picture[0];
    }

    /**
     * Why the event session dropped, in a few words for the status and the connection reading. The detail is the
     * session's own (an exception and its message), which only the log shows.
     */
    static String problem(String detail, Texts texts) {
        if (detail.contains("account device")) return texts.problemAccountDevice();
        if (detail.contains("refused the username or password")) return texts.problemLogin();
        if (detail.contains("stopped answering") || detail.contains("stopped in the middle")) return texts.problemNotAnswering();
        for (String unreachable : new String[] {"ConnectException", "NoRouteToHostException", "UnknownHostException", "SocketTimeoutException", "EHOSTUNREACH", "ENETUNREACH"}) {
            if (detail.contains(unreachable)) return texts.problemUnreachable();
        }
        return texts.problemLost();
    }

    private static boolean baichuanTalk(String host, int port, String username, String password, int channel) {
        try {
            return BaichuanTalk.probe(host, port, username, password, channel) != null;
        } catch (IOException error) {
            return false;
        }
    }

    private static boolean unreachable(IOException error) {
        return error instanceof ConnectException || error instanceof SocketTimeoutException || error instanceof NoRouteToHostException
            || error instanceof UnknownHostException;
    }

    private static String describe(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
