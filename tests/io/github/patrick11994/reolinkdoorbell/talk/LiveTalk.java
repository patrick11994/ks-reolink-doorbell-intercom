// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell.talk;

import io.github.patrick11994.reolinkdoorbell.media.StreamHub;

/** Manual check against a real doorbell (REOLINK_HOST/USER/PASSWORD): backchannel sending silence next to the video stream. */
public final class LiveTalk {
    public static void main(String[] args) throws Exception {
        String host = System.getenv("REOLINK_HOST"), user = System.getenv("REOLINK_USER"), pass = System.getenv("REOLINK_PASSWORD");
        long started = System.currentTimeMillis();
        long[] frames = new long[1];
        StreamHub video = new StreamHub(host, 554, "/h264Preview_01_main", user, pass, s -> System.out.println((System.currentTimeMillis() - started) + " ms video: " + s));
        video.attachVideo((au, micros, key) -> frames[0]++);
        Backchannel talk = new Backchannel(host, 554, "/h264Preview_01_main", user, pass, s -> System.out.println((System.currentTimeMillis() - started) + " ms talk: " + s));
        video.start();
        talk.start();
        Thread.sleep(12_000);  // past one 10 s keepalive
        System.out.println("after 12 s: talk packets sent " + talk.packetsSent() + ", RTCP reports from the doorbell " + talk.rtcpReceived() + ", video frames " + frames[0]);
        talk.stop();
        video.stop();
    }
}
