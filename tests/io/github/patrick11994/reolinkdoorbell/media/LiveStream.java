// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell.media;

/** Manual check against a real doorbell: REOLINK_HOST/USER/PASSWORD from the environment. Prints what a stream delivers. */
public final class LiveStream {
    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "/h264Preview_01_main";
        int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 8;
        long[] video = new long[3];  // frames, keyframes, bytes
        long[] audio = new long[2];  // frames, bytes
        long started = System.currentTimeMillis();
        StreamHub hub = new StreamHub(System.getenv("REOLINK_HOST"), 554, path, System.getenv("REOLINK_USER"), System.getenv("REOLINK_PASSWORD"),
            state -> System.out.println((System.currentTimeMillis() - started) + " ms: " + state));
        hub.attachAudio(new MediaStream.AudioSink() {
            @Override public void onAudioFormat(byte[] config, int rate) { System.out.println("audio config " + hex(config) + " @ " + rate + " Hz"); }
            @Override public void onAudio(byte[] frame, long micros) { audio[0]++; audio[1] += frame.length; }
        });
        hub.attachVideo((au, micros, key) -> {
            if (video[0] == 0) System.out.println((System.currentTimeMillis() - started) + " ms: first frame (keyframe " + key + ", " + au.length + " bytes, NAL types " + nalTypes(au) + ")");
            video[0]++; if (key) video[1]++; video[2] += au.length;
        });
        hub.start();
        Thread.sleep(seconds * 1000L);
        hub.stop();
        System.out.println(path + ": " + video[0] + " frames (" + video[0] * 1000 / (seconds * 1000L) + " fps), " + video[1] + " keyframes, "
            + video[2] * 8 / 1000 / seconds + " kbit/s; audio " + audio[0] + " frames, " + audio[1] * 8 / 1000 / seconds + " kbit/s; first keyframe after " + hub.firstKeyframeMillis() + " ms");
    }

    static String nalTypes(byte[] au) {
        StringBuilder types = new StringBuilder();
        for (int i = 0; i + 4 < au.length; i++) if (au[i] == 0 && au[i + 1] == 0 && au[i + 2] == 0 && au[i + 3] == 1) types.append(au[i + 4] & 0x1F).append(' ');
        return types.toString().trim();
    }

    static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x & 0xff)); return s.toString(); }
}
