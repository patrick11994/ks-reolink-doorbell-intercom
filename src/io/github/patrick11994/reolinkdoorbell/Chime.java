// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import java.util.function.Consumer;

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
    /** How long a chime sounds, about, from play: 1.8 s of ding-dong, the silence after it and the track starting. */
    static final long MILLIS = 2_300;
    private static volatile double[] samples;  // peak 1.0

    private Chime() {}

    /** percent is the Chime volume setting, stream one of STREAMS; log hears why a chime may stay silent. */
    static void play(int percent, String stream, Consumer<String> log) {
        Thread thread = new Thread(() -> playNow(percent, stream, log), "reolink-chime");
        thread.setDaemon(true);
        thread.start();
    }

    private static void playNow(int percent, String stream, Consumer<String> log) {
        double[] shape = samples();
        double gain = PEAK * Math.max(0, Math.min(100, percent)) / 100.0;
        short[] pcm = new short[shape.length + RATE * 3 / 10];  // 0.3 s of silence after it, so the speaker plays the end before stop
        for (int i = 0; i < shape.length; i++) pcm[i] = (short) Math.round(shape[i] * gain);
        int usage = "Media".equals(stream) ? AudioAttributes.USAGE_MEDIA
            : "Alarm".equals(stream) ? AudioAttributes.USAGE_ALARM : AudioAttributes.USAGE_NOTIFICATION_RINGTONE;
        AudioTrack track = null;
        try {
            // Streamed, not static: an Echo Dot cannot create a static track at all (#20), streaming plays everywhere.
            AudioFormat format = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();
            int buffer = Math.max(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2, RATE / 5 * 2);
            track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(format).setBufferSizeInBytes(buffer).setTransferMode(AudioTrack.MODE_STREAM).build();
            if (track.getState() == AudioTrack.STATE_UNINITIALIZED) {
                log.accept("chime: Android did not open the " + stream + " sound");
                return;
            }
            track.play();
            muted(stream, usage, log);
            // Queued without blocking and then played out, both within a deadline: a stalled output must not keep the
            // track and this thread forever.
            long deadline = System.nanoTime() + (pcm.length * 1000L / RATE + 1000) * 1_000_000L;
            int written = 0;
            while (written < pcm.length && System.nanoTime() < deadline) {
                int taken = track.write(pcm, written, pcm.length - written, AudioTrack.WRITE_NON_BLOCKING);
                if (taken < 0) break;
                written += taken;
                if (written < pcm.length) Thread.sleep(10);
            }
            if (written != pcm.length) log.accept("chime: Android took " + written + " of " + pcm.length + " samples");
            while (track.getPlaybackHeadPosition() < written && System.nanoTime() < deadline) Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException unavailable) {
            log.accept("chime: no sound: " + unavailable);  // no sound is better than a failed ring
        } finally {
            if (track != null) {
                try { track.stop(); } catch (RuntimeException ignored) {}
                track.release();
            }
        }
    }

    /** Says so when the Android volume the chime follows is all the way down, such as the ring volume on an Echo Dot (#20). */
    private static void muted(String stream, int usage, Consumer<String> log) {
        try {
            Context app = app();
            if (app == null) return;
            AudioManager audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            int type = usage == AudioAttributes.USAGE_MEDIA ? AudioManager.STREAM_MUSIC
                : usage == AudioAttributes.USAGE_ALARM ? AudioManager.STREAM_ALARM : AudioManager.STREAM_RING;
            int volume = audio.getStreamVolume(type), max = audio.getStreamMaxVolume(type);
            String name = type == AudioManager.STREAM_MUSIC ? "media" : type == AudioManager.STREAM_ALARM ? "alarm" : "ring";
            if (volume == 0) log.accept("chime: the " + name + " volume (Chime sound: " + stream + ") is 0 of " + max + " on this device, so the chime is silent");
            if (type == AudioManager.STREAM_RING && audio.getRingerMode() != AudioManager.RINGER_MODE_NORMAL) {
                log.accept("chime: this device is on silent or vibrate, which mutes the " + stream + " sound");
            }
        } catch (RuntimeException unknown) {
            // Only a hint for the logs.
        }
    }

    /** Kiosk Satellite's application, which a plugin runs in; start gives no Context. Null if Android hides it. */
    static Context app() {
        try {
            return (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError hidden) {
            return null;
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
