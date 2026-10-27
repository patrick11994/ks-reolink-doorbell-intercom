// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell.media;

import java.util.ArrayList;
import java.util.List;

public final class FallbackStreamTest {
    /** A stream that records what happens to it. */
    private static final class FakeStream implements MediaStream {
        final List<String> log = new ArrayList<>();
        long firstKeyframe = -1;
        VideoSink video;
        AudioSink audio;

        @Override public void start() { log.add("start"); }
        @Override public void stop() { log.add("stop"); }
        @Override public void attachVideo(VideoSink sink) { video = sink; log.add(sink == null ? "video off" : "video on"); }
        @Override public void attachAudio(AudioSink sink) { audio = sink; log.add(sink == null ? "audio off" : "audio on"); }
        @Override public long firstKeyframeMillis() { return firstKeyframe; }
    }

    public static void main(String[] args) {
        // Fails before a picture: RTSP takes over, and the viewer's sinks move along.
        FakeStream baichuan = new FakeStream(), rtsp = new FakeStream();
        int[] created = {0};
        FallbackStream stream = new FallbackStream(baichuan, () -> { created[0]++; return rtsp; });
        stream.start();
        MediaStream.VideoSink video = (au, micros, key) -> {};
        stream.attachVideo(video);
        check(created[0], 0);  // RTSP only opens when it is needed
        check(stream.primaryFailed(), true);
        check(stream.primaryFailed(), false);  // once is enough
        check(created[0], 1);
        check(baichuan.log.contains("stop"), true);
        check(rtsp.log.toString(), "[video on, start]");  // the sink first, so the cached keyframe reaches it
        check(rtsp.video == video, true);
        rtsp.firstKeyframe = 120;
        check(stream.firstKeyframeMillis(), 120L);
        stream.attachAudio(new MediaStream.AudioSink() {
            @Override public void onAudioFormat(byte[] c, int r) {}
            @Override public void onAudio(byte[] f, long m) {}
        });
        check(rtsp.audio != null, true);
        stream.stop();
        check(rtsp.log.contains("stop"), true);

        // Ends after it showed a picture: no switch, the ending is reported as usual.
        FakeStream shown = new FakeStream();
        shown.firstKeyframe = 150;
        int[] late = {0};
        FallbackStream after = new FallbackStream(shown, () -> { late[0]++; return new FakeStream(); });
        after.start();
        check(after.primaryFailed(), false);
        check(late[0], 0);

        // Stopped before the failure arrives: nothing new opens.
        FakeStream gone = new FakeStream();
        FallbackStream stopped = new FallbackStream(gone, () -> { late[0]++; return new FakeStream(); });
        stopped.start();
        stopped.stop();
        check(stopped.primaryFailed(), false);
        check(late[0], 0);

        System.out.println("FallbackStreamTest passed");
    }

    private static void check(Object actual, Object expected) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " but got " + actual);
    }
}
