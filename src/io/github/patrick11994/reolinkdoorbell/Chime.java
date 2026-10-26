// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/**
 * A synthesized two-tone "ding-dong" on a short-lived thread. It follows one of Android's volumes (notifications,
 * media or alarm, from the Chime sound setting) and is scaled by the Chime volume setting: 100 % peaks just under
 * full scale, the default 40 % is the soft chime earlier versions played.
 */
final class Chime {
    private static final int RATE = 24000;
    private static final double PEAK = 32767 * 0.89;  // -1 dBFS at 100 %
    /** The Chime sound setting's options. */
    static final String[] STREAMS = {"Notifications", "Media", "Alarm"};
    private static volatile double[] samples;  // peak 1.0

    private Chime() {}

    /** percent is the Chime volume setting, stream one of STREAMS. */
    static void play(int percent, String stream) {
        Thread thread = new Thread(() -> playNow(percent, stream), "reolink-chime");
        thread.setDaemon(true);
        thread.start();
    }

    private static void playNow(int percent, String stream) {
        double[] shape = samples();
        double gain = PEAK * Math.max(0, Math.min(100, percent)) / 100.0;
        short[] pcm = new short[shape.length];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (short) Math.round(shape[i] * gain);
        int usage = "Media".equals(stream) ? AudioAttributes.USAGE_MEDIA
            : "Alarm".equals(stream) ? AudioAttributes.USAGE_ALARM : AudioAttributes.USAGE_NOTIFICATION_RINGTONE;
        AudioTrack track = null;
        try {
            track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.length * 2).setTransferMode(AudioTrack.MODE_STATIC).build();
            track.write(pcm, 0, pcm.length);
            track.play();
            Thread.sleep(pcm.length * 1000L / RATE + 100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException unavailable) {
            // No sound is better than a failed ring.
        } finally {
            if (track != null) {
                try { track.stop(); } catch (RuntimeException ignored) {}
                track.release();
            }
        }
    }

    private static double[] samples() {
        double[] cached = samples;
        if (cached != null) return cached;
        double[][] notes = {{659.25, 0.0}, {523.25, 0.55}};  // E5 "ding", C5 "dong"
        double length = 1.8;
        double[] pcm = new double[(int) (RATE * length)];
        for (double[] note : notes) {
            int start = (int) (note[1] * RATE);
            for (int i = start; i < pcm.length; i++) {
                double t = (i - start) / (double) RATE;
                double envelope = Math.exp(-3.2 * t) * Math.min(1, t * 200);
                double value = envelope * (Math.sin(2 * Math.PI * note[0] * t) + 0.35 * Math.sin(2 * Math.PI * note[0] * 2 * t));
                pcm[i] += value;
            }
        }
        double peak = 0;
        for (double v : pcm) peak = Math.max(peak, Math.abs(v));
        for (int i = 0; i < pcm.length; i++) pcm[i] /= peak;
        samples = pcm;
        return pcm;
    }
}
