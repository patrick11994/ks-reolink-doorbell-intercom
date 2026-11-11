// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every choice the manifest offers must reach the plugin as its own setting. The plugin matches the option strings
 * themselves, so a reworded option would silently turn into the default ("End a call after: Off" into 10 minutes).
 * Option values are also what installed copies have stored: never rename one, add a new one instead.
 */
public final class SettingsTest {
    public static void main(String[] args) throws Exception {
        Map<String, Function<ReolinkPlugin.Settings, Object>> parsed = new HashMap<>();
        parsed.put("chimeStream", s -> s.chimeStream);
        parsed.put("musicPause", s -> s.musicPause);
        parsed.put("stream", s -> s.streamPath);
        parsed.put("videoSource", s -> s.videoPath);
        parsed.put("talk", s -> s.talkPath);
        parsed.put("events", s -> s.eventsPath);
        parsed.put("talkMode", s -> s.pushToTalk);
        parsed.put("microphone", s -> s.microphone);
        parsed.put("micRate", s -> s.micRate);
        parsed.put("language", s -> s.language);
        parsed.put("autoClose", s -> s.autoCloseMillis);
        parsed.put("maxCall", s -> s.maxCallMillis);

        String manifest = new String(Files.readAllBytes(Paths.get(System.getProperty("reolink.assets", "assets"), "..", "kiosk-satellite-plugin.json")), StandardCharsets.UTF_8);
        Matcher select = Pattern.compile("\"key\": \"(\\w+)\", \"type\": \"select\".*?\"options\": \\[([^\\]]*)\\]").matcher(manifest);
        Set<String> seen = new HashSet<>();
        while (select.find()) {
            String key = select.group(1);
            Function<ReolinkPlugin.Settings, Object> field = parsed.get(key);
            if (field == null) throw new AssertionError(key + " is a choice in the manifest that this test does not know: add it");
            seen.add(key);
            Map<Object, String> values = new HashMap<>();
            Matcher option = Pattern.compile("\"([^\"]*)\"").matcher(select.group(2));
            while (option.find()) {
                Object value = field.apply(new ReolinkPlugin.Settings(Collections.<String, Object>singletonMap(key, option.group(1))));
                String other = values.put(value, option.group(1));
                if (other != null) throw new AssertionError(key + ": \"" + option.group(1) + "\" reads the same as \"" + other + "\"");
            }
        }
        if (!seen.equals(parsed.keySet())) throw new AssertionError("not choices in the manifest: " + parsed.keySet() + " vs " + seen);

        // Channel counts from 1 as the Reolink app does; inside, from 0. RTSP paths count from 01, and 1 is a doorbell
        // reached directly, so nothing changes for one.
        Map<String, Object> values = new HashMap<>();
        check(new ReolinkPlugin.Settings(values).channel, 0);
        check(new ReolinkPlugin.Settings(values).streamPath, "/h264Preview_01_main");
        values.put("nvrChannel", 3);
        values.put("stream", "Sub (light)");
        check(new ReolinkPlugin.Settings(values).channel, 2);
        check(new ReolinkPlugin.Settings(values).streamPath, "/h264Preview_03_sub");
        values.put("nvrChannel", 12.0);  // the settings page may hand a number over as a double
        check(new ReolinkPlugin.Settings(values).streamPath, "/h264Preview_12_sub");
        values.put("nvrChannel", " 7 ");  // the text field as typed
        check(new ReolinkPlugin.Settings(values).channel, 6);
        check(new ReolinkPlugin.Settings(values).channelValid, true);
        values.put("nvrChannel", "");
        check(new ReolinkPlugin.Settings(values).channel, 0);
        for (Object wrong : new Object[] {"seven", "0", "100", 0}) {
            values.put("nvrChannel", wrong);
            check(new ReolinkPlugin.Settings(values).channelValid, false);
            check(new ReolinkPlugin.Settings(values).channel, 0);  // the doorbell's own channel until it is fixed
        }

        // Button presses over picks the event session: a change must start a new one, a changed chime must not.
        Map<String, Object> before = new HashMap<>();
        before.put("host", "192.168.1.50");
        before.put("username", "kiosk");
        before.put("password", "secret");
        Map<String, Object> after = new HashMap<>(before);
        after.put("chime", false);
        check(new ReolinkPlugin.Settings(before).sameConnection(new ReolinkPlugin.Settings(after)), true);
        after.put("events", "ONVIF");
        check(new ReolinkPlugin.Settings(before).sameConnection(new ReolinkPlugin.Settings(after)), false);
        System.out.println("SettingsTest passed (" + seen.size() + " choices)");
    }

    private static void check(Object actual, Object expected) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " but got " + actual);
    }
}
