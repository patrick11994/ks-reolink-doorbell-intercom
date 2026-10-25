// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/** A synthesized two-tone "ding-dong", played at the notification volume on a short-lived thread. */
final class Chime {
    private static final int RATE = 24000;
    private static volatile short[] samples;

    private Chime() {}

    static void play() {
        Thread thread = new Thread(Chime::playNow, "reolink-chime");
        thread.setDaemon(true);
        thread.start();
    }

    private static void playNow() {
        short[] pcm = samples();
        AudioTrack track = null;
        try {
            track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
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

    private static short[] samples() {
        short[] cached = samples;
        if (cached != null) return cached;
        double[][] notes = {{659.25, 0.0}, {523.25, 0.55}};  // E5 "ding", C5 "dong"
        double length = 1.8;
        short[] pcm = new short[(int) (RATE * length)];
        for (double[] note : notes) {
            int start = (int) (note[1] * RATE);
            for (int i = start; i < pcm.length; i++) {
                double t = (i - start) / (double) RATE;
                double envelope = Math.exp(-3.2 * t) * Math.min(1, t * 200);
                double value = envelope * (Math.sin(2 * Math.PI * note[0] * t) + 0.35 * Math.sin(2 * Math.PI * note[0] * 2 * t));
                pcm[i] = (short) Math.max(-32767, Math.min(32767, pcm[i] + value * 9000));
            }
        }
        samples = pcm;
        return pcm;
    }
}
