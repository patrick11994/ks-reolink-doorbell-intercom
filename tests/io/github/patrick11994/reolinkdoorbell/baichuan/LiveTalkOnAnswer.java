// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell.baichuan;

/**
 * Manual check against a real doorbell (REOLINK_HOST/USER/PASSWORD): how soon the door hears the first words when
 * Baichuan talk only opens at Answer. With --fresh (the default) every beep gets a new talk session, written from the
 * moment the session starts, the way the microphone does after Answer. With --held one session stays open and the
 * beeps follow, as when talk opened at the ring. Beeps of 700, 1000, 1300, 1600 and 1900 Hz, 600 ms each, logged as
 * "beep <pitch> Hz sent at <epoch ms>" for mac_mic_meter.py: the difference is the delay to the door, setup included.
 *
 *     java -cp <classes> io.github.patrick11994.reolinkdoorbell.baichuan.LiveTalkOnAnswer [--fresh | --held] [--level N]
 */
public final class LiveTalkOnAnswer {
    private static final int[] PITCHES = {700, 1000, 1300, 1600, 1900};
    private static final int RATE = BaichuanTalk.RATE, CHUNK = RATE / 50;  // 20 ms, as the microphone delivers

    public static void main(String[] args) throws Exception {
        String host = System.getenv("REOLINK_HOST"), user = System.getenv("REOLINK_USER"), pass = System.getenv("REOLINK_PASSWORD");
        if (host == null || user == null || pass == null) throw new IllegalStateException("set REOLINK_HOST, REOLINK_USER and REOLINK_PASSWORD");
        boolean held = false;
        int level = 4000;  // about -18 dBFS: heard by the Mac at the door without bothering the neighbours
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--held")) held = true;
            else if (args[i].equals("--fresh")) held = false;
            else if (args[i].equals("--level")) level = Integer.parseInt(args[++i]);
        }
        BaichuanTalk.Listener log = state -> System.out.println(System.currentTimeMillis() + " talk: " + state);
        BaichuanTalk session = null;
        if (held) {
            session = new BaichuanTalk(host, 9000, user, pass, log);
            session.start();
            Thread.sleep(3000);
        }
        for (int pitch : PITCHES) {
            BaichuanTalk talk = held ? session : new BaichuanTalk(host, 9000, user, pass, log);
            if (!held) talk.start();
            System.out.println("beep " + pitch + " Hz sent at " + System.currentTimeMillis());
            beep(talk, pitch, level, 600);
            silence(talk, 2000);
            if (!held) {
                talk.stop();
                Thread.sleep(1500);
            } else {
                Thread.sleep(1500);
            }
        }
        if (session != null) session.stop();
        System.out.println("done");
    }

    /** Writes a tone in 20 ms chunks in real time, with 10 ms fades so it starts and ends without a click. */
    private static void beep(BaichuanTalk talk, int pitch, int level, int millis) throws InterruptedException {
        int total = RATE * millis / 1000, fade = RATE / 100;
        short[] chunk = new short[CHUNK];
        long next = System.nanoTime();
        for (int done = 0; done < total; done += CHUNK) {
            for (int i = 0; i < CHUNK; i++) {
                int n = done + i;
                double envelope = Math.min(1.0, Math.min(n, total - n) / (double) fade);
                chunk[i] = (short) (level * Math.max(0, envelope) * Math.sin(2 * Math.PI * pitch * n / RATE));
            }
            talk.write(chunk, CHUNK);
            next += 20_000_000L;
            long wait = next - System.nanoTime();
            if (wait > 0) Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
        }
    }

    private static void silence(BaichuanTalk talk, int millis) throws InterruptedException {
        short[] chunk = new short[CHUNK];
        for (int done = 0; done < millis; done += 20) {
            talk.write(chunk, CHUNK);
            Thread.sleep(20);
        }
    }
}
