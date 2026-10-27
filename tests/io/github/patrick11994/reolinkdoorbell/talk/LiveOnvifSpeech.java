// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell.talk;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Manual check (REOLINK_HOST/USER/PASSWORD): plays a 16 kHz mono 16-bit WAV at the door over the ONVIF backchannel,
 * through the plugin's own Downsampler and Backchannel, to compare the sound with Baichuan talk.
 *
 *     java -cp <classes> io.github.patrick11994.reolinkdoorbell.talk.LiveOnvifSpeech speech.wav
 */
public final class LiveOnvifSpeech {
    public static void main(String[] args) throws Exception {
        String host = System.getenv("REOLINK_HOST"), user = System.getenv("REOLINK_USER"), pass = System.getenv("REOLINK_PASSWORD");
        ByteBuffer wav = ByteBuffer.wrap(Files.readAllBytes(Paths.get(args[0]))).order(ByteOrder.LITTLE_ENDIAN);
        wav.position(12);
        short[] pcm = null;
        while (pcm == null && wav.remaining() >= 8) {
            String id = new String(new byte[] {wav.get(), wav.get(), wav.get(), wav.get()}, "US-ASCII");
            int size = wav.getInt();
            if (id.equals("data")) {
                pcm = new short[size / 2];
                wav.asShortBuffer().get(pcm);
            } else {
                wav.position(wav.position() + size + (size & 1));
            }
        }
        long started = System.currentTimeMillis();
        Backchannel talk = new Backchannel(host, 554, "/h264Preview_01_main", user, pass, s -> System.out.println((System.currentTimeMillis() - started) + " ms talk: " + s));
        talk.start();
        Thread.sleep(3000);  // as in the plugin: silence first, so the doorbell's buffer is full before the speech
        Downsampler down = new Downsampler(16000, Backchannel.RATE);
        short[] out = new short[160];
        long t0 = System.nanoTime();
        int chunks = 0;
        for (int offset = 0; offset + 320 <= pcm.length; offset += 320) {  // 20 ms at 16 kHz, in real time
            short[] chunk = new short[320];
            System.arraycopy(pcm, offset, chunk, 0, 320);
            int n = down.process(chunk, 320, out, 0);
            talk.write(out, n);
            chunks++;
            long wait = t0 + chunks * 20_000_000L - System.nanoTime();
            if (wait > 0) Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
        }
        Thread.sleep(3000);  // the doorbell plays ~2 s behind
        System.out.println("played " + chunks * 20 + " ms of speech, packets sent " + talk.packetsSent());
        talk.stop();
    }
}
