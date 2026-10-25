// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell.baichuan;

import java.time.LocalTime;

/** Manual check against a real doorbell: REOLINK_HOST/USER/PASSWORD from the environment, prints the session for N seconds. */
public final class LiveBaichuan {
    public static void main(String[] args) throws Exception {
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 40;
        BaichuanClient client = new BaichuanClient(System.getenv("REOLINK_HOST"), 9000, System.getenv("REOLINK_USER"), System.getenv("REOLINK_PASSWORD"),
            new BaichuanClient.Listener() {
                @Override public void onConnection(boolean connected, String detail) { System.out.println(LocalTime.now() + " connection " + connected + " " + detail); }
                @Override public void onAlarm(AlarmEvents.Event event) { System.out.println(LocalTime.now() + " alarm " + event + (event.visitor() ? "  <-- VISITOR" : "")); }
            });
        long started = System.currentTimeMillis();
        client.start();
        Thread.sleep(seconds * 1000L);
        client.stop();
        System.out.println(LocalTime.now() + " stopped after " + (System.currentTimeMillis() - started) / 1000 + " s; threads alive: " + Thread.getAllStackTraces().keySet().stream().filter(t -> t.getName().startsWith("reolink")).count());
    }
}
