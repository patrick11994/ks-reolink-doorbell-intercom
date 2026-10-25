// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell.media;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One live doorbell stream (H.264 video + AAC audio over RTSP) for the length of a ring. It starts as soon as the
 * button is pressed, keeps the frames since the last keyframe and replays them to a video sink that attaches later,
 * so the viewer shows a picture at once instead of waiting for the next keyframe. Sinks are called on the stream's
 * reader thread and must hand the work off quickly.
 */
public final class StreamHub {
    public interface VideoSink {
        void onVideo(byte[] annexB, long presentationMicros, boolean keyframe);
    }

    public interface AudioSink {
        /** AudioSpecificConfig and sample rate, before the first frame. */
        void onAudioFormat(byte[] config, int sampleRate);
        void onAudio(byte[] aacFrame, long presentationMicros);
    }

    public interface Listener {
        void onStreamState(String state);
    }

    private static final int MAX_CACHED_FRAMES = 150;
    private static final int MAX_CACHED_BYTES = 12 * 1024 * 1024;

    private final String host, path, username, password;
    private final int port;
    private final Listener listener;
    private final List<byte[]> gop = new ArrayList<>();
    private final List<Long> gopTimes = new ArrayList<>();
    private int gopBytes;
    private VideoSink video;
    private AudioSink audio;
    private byte[] audioConfig = new byte[0];
    private int audioRate;
    private RtspSession session;
    private Thread starter;
    private volatile boolean running;
    private long startedAt;
    private volatile long firstKeyframeMillis = -1;
    private long videoBase = -1, audioBase = -1;
    private int videoClock = 90000;

    public StreamHub(String host, int port, String path, String username, String password, Listener listener) {
        this.host = host;
        this.port = port;
        this.path = path;
        this.username = username;
        this.password = password;
        this.listener = listener;
    }

    /** Connects on its own thread and returns at once. */
    public synchronized void start() {
        if (running) return;
        running = true;
        startedAt = System.currentTimeMillis();
        Thread thread = new Thread(this::connect, "reolink-stream-start");
        thread.setDaemon(true);
        starter = thread;
        thread.start();
    }

    public void stop() {
        RtspSession s;
        Thread t;
        synchronized (this) {
            running = false;
            s = session;
            session = null;
            t = starter;
            starter = null;
            gop.clear();
            gopTimes.clear();
            gopBytes = 0;
        }
        if (t != null) t.interrupt();
        if (s != null) s.close();
    }

    /** Milliseconds from start to the first keyframe, or -1 before it. */
    public long firstKeyframeMillis() {
        return firstKeyframeMillis;
    }

    /** Attaches the decoder; it first gets the frames since the last keyframe, then live frames. */
    public synchronized void attachVideo(VideoSink sink) {
        video = sink;
        if (sink == null) return;
        for (int i = 0; i < gop.size(); i++) sink.onVideo(gop.get(i), gopTimes.get(i), i == 0);
    }

    public synchronized void attachAudio(AudioSink sink) {
        audio = sink;
        if (sink != null && audioRate > 0) sink.onAudioFormat(audioConfig, audioRate);
    }

    private void connect() {
        RtspSession s = new RtspSession(host, port, path, username, password);
        try {
            listener.onStreamState("connecting");
            Sdp sdp = s.describe(Collections.<String, String>emptyMap());
            Sdp.Track videoTrack = sdp.receive("video");
            Sdp.Track audioTrack = sdp.receive("audio");
            if (videoTrack == null) throw new java.io.IOException("the doorbell offers no video track");
            if (videoTrack.clock > 0) videoClock = videoTrack.clock;
            H264Depacketizer h264 = new H264Depacketizer(this::onAccessUnit);
            int videoChannel = s.setup(videoTrack, 0, Collections.<String, String>emptyMap());
            AacDepacketizer aac = null;
            int audioChannel = -1;
            if (audioTrack != null && audioTrack.codec.equals("MPEG4-GENERIC")) {
                synchronized (this) {
                    audioConfig = audioTrack.audioConfig();
                    audioRate = audioTrack.clock > 0 ? audioTrack.clock : 16000;
                    if (audio != null) audio.onAudioFormat(audioConfig, audioRate);
                }
                aac = new AacDepacketizer(this::onAacFrame, audioTrack.fmtpInt("sizelength", 13), audioTrack.fmtpInt("indexlength", 3));
                audioChannel = s.setup(audioTrack, 2, Collections.<String, String>emptyMap());
            }
            s.play(Collections.<String, String>emptyMap());
            final AacDepacketizer audioDepacketizer = aac;
            final int vc = videoChannel, ac = audioChannel;
            synchronized (this) {
                if (!running) {
                    s.close();
                    return;
                }
                session = s;
            }
            listener.onStreamState("streaming");
            s.start(new RtspSession.Listener() {
                @Override public void onPacket(int channel, byte[] buffer, int offset, int length) {
                    if (channel == vc) h264.onRtp(buffer, offset, length);
                    else if (channel == ac && audioDepacketizer != null) audioDepacketizer.onRtp(buffer, offset, length);
                }

                @Override public void onClosed(String reason) {
                    if (reason != null && running) listener.onStreamState("stream ended: " + reason);
                }
            }, "reolink-stream");
        } catch (Exception error) {
            s.close();
            if (running) listener.onStreamState("stream failed: " + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
        }
    }

    private void onAccessUnit(byte[] annexB, long rtpTimestamp, boolean keyframe) {
        synchronized (this) {
            VideoSink sink;
            long micros;
            if (!running) return;
            if (videoBase < 0) {
                if (!keyframe) return;  // nothing decodes before the first keyframe
                videoBase = rtpTimestamp;
                firstKeyframeMillis = System.currentTimeMillis() - startedAt;
            }
            micros = ((rtpTimestamp - videoBase) & 0xFFFFFFFFL) * 1_000_000L / videoClock;
            if (keyframe) {
                gop.clear();
                gopTimes.clear();
                gopBytes = 0;
            }
            if (gop.size() < MAX_CACHED_FRAMES && gopBytes + annexB.length < MAX_CACHED_BYTES) {
                gop.add(annexB);
                gopTimes.add(micros);
                gopBytes += annexB.length;
            }
            sink = video;
            // Delivered under the lock, so a sink attaching meanwhile gets each frame exactly once.
            if (sink != null) sink.onVideo(annexB, micros, keyframe);
        }
    }

    private void onAacFrame(byte[] frame, long rtpTimestamp) {
        synchronized (this) {
            AudioSink sink;
            long micros;
            if (!running) return;
            if (audioBase < 0) audioBase = rtpTimestamp;
            micros = ((rtpTimestamp - audioBase) & 0xFFFFFFFFL) * 1_000_000L / Math.max(1, audioRate);
            sink = audio;
            if (sink != null) sink.onAudio(frame, micros);
        }
    }
}
