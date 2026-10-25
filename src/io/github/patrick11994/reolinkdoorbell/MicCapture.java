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
 * on an RK3576 panel only CAMCORDER does, while MIC, VOICE_COMMUNICATION and VOICE_RECOGNITION go silent after a
 * second. So each source is tried in turn, and one that sends exact digital silence after a warm-up second counts as
 * dead (a live microphone always has some noise). The first source that works is remembered for later calls.
 * Records only while the microphone is on.
 */
final class MicCapture {
    interface Listener {
        void onMicState(String state);
    }

    private static final int[] SOURCES = {
        MediaRecorder.AudioSource.CAMCORDER,
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        MediaRecorder.AudioSource.MIC,
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        Build.VERSION.SDK_INT >= 24 ? MediaRecorder.AudioSource.UNPROCESSED : MediaRecorder.AudioSource.DEFAULT,
    };
    private static final String[] NAMES = {"CAMCORDER", "VOICE_COMMUNICATION", "MIC", "VOICE_RECOGNITION", "UNPROCESSED"};
    private static final int[] RATES = {48000, 16000};
    private static final int WARM_UP_MILLIS = 1000;
    private static final int SILENT_MILLIS = 1000;  // this long of exact zeros after the warm-up: the source is dead

    private static volatile int working = -1;  // index into SOURCES that delivered audio, for the next call

    private final Backchannel backchannel;
    private final Listener listener;
    private volatile boolean running = true;
    private final Thread thread;

    MicCapture(Backchannel backchannel, Listener listener) {
        this.backchannel = backchannel;
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
        int first = working >= 0 ? working : 0;
        for (int attempt = 0; attempt < SOURCES.length && running; attempt++) {
            int index = (first + attempt) % SOURCES.length;
            Result result = capture(index);
            if (result == Result.STOPPED) return;
            if (result == Result.WORKED) return;
            if (working == index) working = -1;  // the remembered source stopped working: search again
        }
        if (running) listener.onMicState("no working microphone found");
        backchannel.clear();
    }

    private enum Result { STOPPED, WORKED, UNAVAILABLE, SILENT }

    /** Records from one source until stopped; reports SILENT or UNAVAILABLE so the next source can be tried. */
    private Result capture(int index) {
        AudioRecord recorder = null;
        int rate = 0;
        for (int r : RATES) {
            recorder = open(SOURCES[index], r);
            if (recorder != null) {
                rate = r;
                break;
            }
        }
        if (recorder == null) return Result.UNAVAILABLE;
        try {
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) return Result.UNAVAILABLE;
            Downsampler downsampler = new Downsampler(rate, Backchannel.RATE);
            short[] chunk = new short[rate / 100];  // 10 ms
            short[] out = new short[chunk.length];
            long samples = 0, silentRun = 0;
            boolean confirmed = index == working;
            if (confirmed) listener.onMicState("live, " + NAMES[index]);
            while (running) {
                int read = recorder.read(chunk, 0, chunk.length);
                if (read < 0) return Result.UNAVAILABLE;
                samples += read;
                boolean silent = true;
                for (int i = 0; i < read && silent; i++) silent = chunk[i] == 0;
                silentRun = silent ? silentRun + read : 0;
                if (samples > (long) rate * WARM_UP_MILLIS / 1000) {
                    if (silentRun >= (long) rate * SILENT_MILLIS / 1000) {
                        backchannel.clear();
                        return Result.SILENT;
                    }
                    // Dead sources on the RK3576 still send noise for about a second: only trust one past that window.
                    if (!confirmed && silentRun == 0 && samples > (long) rate * (WARM_UP_MILLIS + SILENT_MILLIS) / 1000) {
                        confirmed = true;
                        working = index;
                        listener.onMicState("live, " + NAMES[index]);
                    }
                }
                int n = downsampler.process(chunk, read, out, 0);
                if (n > 0) backchannel.write(out, n);
            }
            return confirmed ? Result.WORKED : Result.STOPPED;
        } catch (RuntimeException error) {
            return running ? Result.UNAVAILABLE : Result.STOPPED;
        } finally {
            try {
                recorder.stop();
            } catch (RuntimeException ignored) {
                // Not started.
            }
            recorder.release();
            if (!running) backchannel.clear();
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
            // Try the next source or rate.
        }
        return null;
    }
}
