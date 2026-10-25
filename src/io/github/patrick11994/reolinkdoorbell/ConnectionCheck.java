// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Collections;
import io.github.patrick11994.reolinkdoorbell.baichuan.BaichuanClient;
import io.github.patrick11994.reolinkdoorbell.media.RtspSession;
import io.github.patrick11994.reolinkdoorbell.media.Sdp;

/**
 * The settings page's Check button: the three things a call needs, in order, each with a message a person can act
 * on. Button presses (a Baichuan login on 9000), video (RTSP on 554) and talking (the ONVIF backchannel track).
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

    private ConnectionCheck() {}

    static Result run(String host, int baichuanPort, int rtspPort, String username, String password, Texts texts) {
        if (host.isEmpty() || username.isEmpty() || password.isEmpty()) return new Result(false, texts.checkNotConfigured());
        try {
            BaichuanClient.verify(host, baichuanPort, username, password);
        } catch (BaichuanClient.LoginRefused refused) {
            boolean account = refused.getMessage() != null && refused.getMessage().contains("account device");
            return new Result(false, account ? texts.checkAccountDevice() : texts.checkLoginRefused());
        } catch (IOException error) {
            return new Result(false, unreachable(error) ? texts.checkUnreachable(host) : texts.checkFailed(describe(error)));
        }
        RtspSession rtsp = new RtspSession(host, rtspPort, "/h264Preview_01_main", username, password);
        try {
            Sdp sdp = rtsp.describe(Collections.singletonMap("Require", "www.onvif.org/ver20/backchannel"));
            if (sdp.receive("video") == null) return new Result(false, texts.checkNoVideo());
            if (sdp.backchannel() == null) return new Result(false, texts.checkNoTalk());
        } catch (IOException error) {
            return new Result(false, texts.checkNoVideo());
        } finally {
            rtsp.close();
        }
        return new Result(true, texts.checkOk());
    }

    private static boolean unreachable(IOException error) {
        return error instanceof ConnectException || error instanceof SocketTimeoutException || error instanceof NoRouteToHostException
            || error instanceof UnknownHostException;
    }

    private static String describe(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
