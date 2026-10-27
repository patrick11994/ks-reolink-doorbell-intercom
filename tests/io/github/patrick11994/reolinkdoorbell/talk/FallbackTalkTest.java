// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell.talk;

import java.util.ArrayList;
import java.util.List;

public final class FallbackTalkTest {
    /** A channel that records what happens to it. */
    private static final class FakeChannel implements TalkChannel {
        final List<String> log = new ArrayList<>();
        final int rate;
        int samples;

        FakeChannel(int rate) {
            this.rate = rate;
        }

        @Override public void start() { log.add("start"); }
        @Override public void stop() { log.add("stop"); }
        @Override public void write(short[] s, int count) { samples += count; }
        @Override public void clear() { log.add("clear"); }
        @Override public int rate() { return rate; }
    }

    public static void main(String[] args) {
        FakeChannel baichuan = new FakeChannel(16000), onvif = new FakeChannel(8000);
        int[] created = {0};
        FallbackTalk talk = new FallbackTalk(baichuan, () -> { created[0]++; return onvif; });
        check(talk.rate(), 16000);
        talk.start();
        talk.write(new short[160], 160);  // 10 ms at 16 kHz
        check(baichuan.samples, 160);
        check(created[0], 0);  // ONVIF only opens when it is needed

        talk.primaryRefused();
        talk.primaryRefused();  // once is enough
        check(created[0], 1);
        check(talk.usingFallback(), true);
        check(onvif.log.get(0), "start");
        talk.write(new short[160], 160);  // still 16 kHz from the microphone, halved for ONVIF
        check(onvif.samples, 80);
        check(baichuan.samples, 160);
        talk.clear();
        check(onvif.log.contains("clear"), true);

        talk.stop();
        check(baichuan.log.contains("stop"), true);
        check(onvif.log.contains("stop"), true);

        // Refused after a stop (the session was already going away): nothing new opens.
        FakeChannel second = new FakeChannel(16000);
        int[] late = {0};
        FallbackTalk stopped = new FallbackTalk(second, () -> { late[0]++; return new FakeChannel(8000); });
        stopped.start();
        stopped.stop();
        stopped.primaryRefused();
        check(late[0], 0);

        System.out.println("FallbackTalkTest passed");
    }

    private static void check(Object actual, Object expected) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " but got " + actual);
    }
}
