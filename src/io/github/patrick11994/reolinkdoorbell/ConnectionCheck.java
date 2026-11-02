// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Collections;
import io.github.patrick11994.reolinkdoorbell.baichuan.BaichuanClient;
import io.github.patrick11994.reolinkdoorbell.baichuan.BaichuanTalk;
import io.github.patrick11994.reolinkdoorbell.media.RtspSession;
import io.github.patrick11994.reolinkdoorbell.media.Sdp;
import io.github.patrick11994.reolinkdoorbell.media.SpsInfo;

/**
 * The settings page's Check button: the three things a call needs, in order, each with a message a person can act
 * on. Button presses (a Baichuan login on 9000), video (the chosen stream's RTSP description on 554) and talking the
 * way the Talk setting goes: Baichuan talk on 9000, the ONVIF backchannel track, or for Automatic either of them.
 * Last, whether this panel can play that stream's picture size: some, such as the Echo Show 8, cannot play the main
 * stream. The check says so instead of the viewer switching streams behind the user's back.
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
        /** Whether the decoder a call uses can show a picture of this size; true when it cannot tell. */
        boolean canPlay(int width, int height);
    }

    private ConnectionCheck() {}

    /** Always returns a result, so the settings page never stays on "Checking". */
    static Result run(String host, int baichuanPort, int rtspPort, String rtspPath, String talkPath, String username, String password, Player player, Texts texts) {
        try {
            return checks(host, baichuanPort, rtspPort, rtspPath, talkPath, username, password, player, texts);
        } catch (RuntimeException error) {
            return new Result(false, texts.checkFailed(describe(error)));
        }
    }

    private static Result checks(String host, int baichuanPort, int rtspPort, String rtspPath, String talkPath, String username, String password, Player player, Texts texts) {
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
        SpsInfo size = null;  // the stream's picture size, from the SPS in its description
        RtspSession rtsp = new RtspSession(host, rtspPort, rtspPath, username, password);
        try {
            Sdp sdp = rtsp.describe(Collections.singletonMap("Require", "www.onvif.org/ver20/backchannel"));
            Sdp.Track video = sdp.receive("video");
            if (video == null) return new Result(false, texts.checkNoVideo());
            for (byte[] set : video.parameterSets()) {
                if (size == null && set.length > 0 && (set[0] & 0x1F) == 7) size = SpsInfo.parse(set);
            }
            onvifTalk = sdp.backchannel() != null;
        } catch (IOException error) {
            return new Result(false, texts.checkNoVideo());
        } finally {
            rtsp.close();
        }
        // Automatic probes Baichuan even when ONVIF is there: it talks over Baichuan first.
        boolean talks = talkPath.equals("ONVIF") ? onvifTalk
            : baichuanTalk(host, baichuanPort, username, password) || (!talkPath.equals("Baichuan") && onvifTalk);
        if (!talks) return new Result(false, talkPath.equals("Baichuan") ? texts.checkNoBaichuanTalk() : texts.checkNoTalk());
        if (size != null && !player.canPlay(size.width, size.height)) {
            String shown = size.width + "x" + size.height;
            return new Result(false, rtspPath.endsWith("_main") ? texts.checkMainTooLarge(shown) : texts.checkVideoTooLarge(shown));
        }
        return new Result(true, texts.checkOk());
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

    private static boolean baichuanTalk(String host, int port, String username, String password) {
        try {
            return BaichuanTalk.probe(host, port, username, password) != null;
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
