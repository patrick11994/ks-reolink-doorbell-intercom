// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import io.github.patrick11994.reolinkdoorbell.talk.Backchannel;
import io.github.patrick11994.reolinkdoorbell.talk.Downsampler;

/**
 * The panel microphone into the backchannel, 10 ms at a time. Panels differ in which source really delivers audio:
 * on an RK3576 panel only CAMCORDER does, while MIC, VOICE_COMMUNICATION and VOICE_RECOGNITION go silent. Silence
 * cannot tell a dead source apart, because Rockchip drivers also turn a quiet room into exact zeros, so the source
 * is picked once per call and kept: the one from the Microphone setting, or in Automatic the first that opens,
 * CAMCORDER first. Records only while the microphone is on.
 */
final class MicCapture {
    interface Listener {
        void onMicState(String state);
    }

    /** The Microphone setting's options, in Automatic's order. */
    static final String[] CHOICES = {"Camcorder", "Voice communication", "Microphone", "Voice recognition", "Unprocessed"};
    private static final int[] SOURCES = {
        MediaRecorder.AudioSource.CAMCORDER,
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        MediaRecorder.AudioSource.MIC,
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        Build.VERSION.SDK_INT >= 24 ? MediaRecorder.AudioSource.UNPROCESSED : MediaRecorder.AudioSource.DEFAULT,
    };
    private static final int[] RATES = {48000, 16000};

    private final Backchannel backchannel;
    private final Listener listener;
    private final String choice;
    private volatile boolean running = true;
    private final Thread thread;

    /** choice is the Microphone setting: one of CHOICES, or Automatic. */
    MicCapture(Backchannel backchannel, String choice, Listener listener) {
        this.backchannel = backchannel;
        this.choice = choice;
        this.listener = listener;
        thread = new Thread(this::run, "reolink-mic");
        thread.setDaemon(true);
        thread.start();
    }

    void release() {
        running = false;
        thread.interrupt();
        try {
            thread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        AudioRecord recorder = null;
        int rate = 0;
        String opened = null;
        for (int i = 0; i < SOURCES.length && recorder == null; i++) {
            if (!choice.equals("Automatic") && !choice.equals(CHOICES[i])) continue;
            for (int r : RATES) {
                recorder = open(SOURCES[i], r);
                if (recorder != null) {
                    rate = r;
                    opened = CHOICES[i];
                    break;
                }
            }
        }
        if (recorder == null) {
            listener.onMicState("no microphone (" + choice + ")");
            return;
        }
        try {
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                listener.onMicState("the microphone is in use elsewhere");
                return;
            }
            listener.onMicState("live, " + opened + " " + rate + " Hz");
            Downsampler downsampler = new Downsampler(rate, Backchannel.RATE);
            short[] chunk = new short[rate / 100];  // 10 ms
            short[] out = new short[chunk.length];
            while (running) {
                int read = recorder.read(chunk, 0, chunk.length);
                if (read < 0) {
                    listener.onMicState("microphone read error " + read);
                    return;
                }
                int n = downsampler.process(chunk, read, out, 0);
                if (n > 0) backchannel.write(out, n);
            }
        } catch (RuntimeException error) {
            if (running) listener.onMicState("microphone: " + error);
        } finally {
            try {
                recorder.stop();
            } catch (RuntimeException ignored) {
                // Not started.
            }
            recorder.release();
            backchannel.clear();
        }
    }

    private static AudioRecord open(int source, int rate) {
        try {
            int minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) return null;
            AudioRecord recorder = new AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minimum, rate / 10 * 2));
            if (recorder.getState() == AudioRecord.STATE_INITIALIZED) return recorder;
            recorder.release();
        } catch (RuntimeException unsupported) {
            // Try the next rate or source.
        }
        return null;
    }
}
