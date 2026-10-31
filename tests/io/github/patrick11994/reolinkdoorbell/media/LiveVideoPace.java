// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell.media;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import io.github.patrick11994.reolinkdoorbell.baichuan.BaichuanStream;

/**
 * Manual check against a real doorbell (REOLINK_HOST/USER/PASSWORD from the environment): how evenly video frames
 * arrive over Baichuan compared with the doorbell's own capture timestamps. A frame's lateness is its arrival time
 * minus its capture time, both from the first frame; jitter is lateness above the lowest lateness of the last
 * WINDOW_MILLIS, which is the delay a player pacing by timestamps would need to show that frame on time.
 *
 *     java -cp <classes> io.github.patrick11994.reolinkdoorbell.media.LiveVideoPace [seconds] [main|sub] [frames.csv]
 */
public final class LiveVideoPace {
    private static final long WINDOW_MILLIS = 2000;

    public static void main(String[] args) throws Exception {
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 30;
        boolean sub = args.length > 1 && args[1].equals("sub");
        String csv = args.length > 2 ? args[2] : null;
        List<long[]> frames = new ArrayList<>();  // arrival micros, capture micros, bytes, keyframe
        long started = System.nanoTime();
        BaichuanStream stream = new BaichuanStream(System.getenv("REOLINK_HOST"), 9000, System.getenv("REOLINK_USER"), System.getenv("REOLINK_PASSWORD"), sub,
            state -> System.out.println((System.nanoTime() - started) / 1_000_000 + " ms: " + state));
        stream.attachVideo((annexB, micros, keyframe) -> {
            synchronized (frames) {
                frames.add(new long[] {System.nanoTime() / 1000, micros, annexB.length, keyframe ? 1 : 0});
            }
        });
        stream.start();
        Thread.sleep(seconds * 1000L);
        stream.stop();
        long[][] f;
        synchronized (frames) {
            f = frames.toArray(new long[0][]);
        }
        // The frames replayed from the last keyframe on attach arrive at once: start at the first frame after them.
        int from = 0;
        while (from + 1 < f.length && f[from + 1][0] - f[from][0] < 2000) from++;
        f = Arrays.copyOfRange(f, from, f.length);
        if (f.length < 20) {
            System.out.println("only " + f.length + " frames");
            return;
        }
        int n = f.length;
        double[] lateness = new double[n], jitter = new double[n], arrivalGap = new double[n - 1], captureGap = new double[n - 1];
        for (int i = 0; i < n; i++) lateness[i] = ((f[i][0] - f[0][0]) - (f[i][1] - f[0][1])) / 1000.0;
        for (int i = 0; i < n; i++) {
            double lowest = Double.MAX_VALUE;
            for (int j = i; j >= 0 && (f[i][0] - f[j][0]) / 1000 <= WINDOW_MILLIS; j--) lowest = Math.min(lowest, lateness[j]);
            jitter[i] = lateness[i] - lowest;
        }
        int bursts = 0, keyframes = 0;
        double keyJitter = 0, otherJitter = 0;
        for (int i = 0; i + 1 < n; i++) {
            arrivalGap[i] = (f[i + 1][0] - f[i][0]) / 1000.0;
            captureGap[i] = (f[i + 1][1] - f[i][1]) / 1000.0;
            if (arrivalGap[i] < 5) bursts++;
        }
        for (int i = 0; i < n; i++) {
            if (f[i][3] == 1) { keyframes++; keyJitter += jitter[i]; } else otherJitter += jitter[i];
        }
        double secondsSeen = (f[n - 1][1] - f[0][1]) / 1e6;
        long bytes = 0;
        for (long[] x : f) bytes += x[2];
        System.out.printf("%s stream: %d frames over %.1f s of capture time = %.1f fps, %d keyframes, %d kbit/s%n",
            sub ? "sub" : "main", n, secondsSeen, (n - 1) / secondsSeen, keyframes, (long) (bytes * 8 / secondsSeen / 1000));
        System.out.println("capture gap (doorbell's own timestamps), ms: " + spread(captureGap));
        System.out.println("arrival gap (here), ms:                     " + spread(arrivalGap));
        System.out.println("jitter (delay needed to play on time), ms:  " + spread(jitter));
        System.out.printf("frames arriving under 5 ms after the one before: %d (%.0f%%)%n", bursts, 100.0 * bursts / (n - 1));
        System.out.printf("average jitter of keyframes %.0f ms, other frames %.0f ms%n", keyframes == 0 ? 0 : keyJitter / keyframes, otherJitter / Math.max(1, n - keyframes));
        System.out.printf("drift of arrival against capture time: %.1f ms over the run%n", lateness[n - 1] - lateness[0]);
        for (int cushion : new int[] {40, 60, 80, 100, 150, 200}) {
            int late = 0;
            for (double j : jitter) if (j > cushion) late++;
            System.out.printf("a %3d ms cushion would show %5.1f%% of frames late%n", cushion, 100.0 * late / n);
        }
        if (csv != null) {
            try (PrintWriter out = new PrintWriter(csv)) {
                out.println("arrival_ms,capture_ms,bytes,keyframe,lateness_ms,jitter_ms");
                for (int i = 0; i < n; i++) out.printf(java.util.Locale.ROOT, "%.1f,%.1f,%d,%d,%.1f,%.1f%n", (f[i][0] - f[0][0]) / 1000.0, (f[i][1] - f[0][1]) / 1000.0, f[i][2], f[i][3], lateness[i], jitter[i]);
            }
            System.out.println("per frame: " + csv);
        }
    }

    /** Median, 90th, 99th percentile and maximum. */
    private static String spread(double[] values) {
        double[] s = values.clone();
        Arrays.sort(s);
        return String.format("median %.0f, p90 %.0f, p99 %.0f, max %.0f", s[s.length / 2], s[(int) (s.length * 0.9)], s[(int) (s.length * 0.99)], s[s.length - 1]);
    }
}
