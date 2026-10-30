// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Everything the plugin shows by itself: the viewer, its readings, status and the connection check, in the language
 * files under assets/i18n (one per language, English the reference; see the README's Translations). The settings page
 * comes from the manifest, which Kiosk Satellite shows as written (English). A text a translation leaves out shows in
 * English.
 */
final class Texts {
    /** Opens a file under the plugin's assets folder: the host's openAsset in Kiosk Satellite. */
    interface Assets {
        InputStream open(String name) throws IOException;
    }

    static final String DIR = "i18n/";
    private static volatile Assets assets = Texts::fromDisk;
    private static final Map<String, Map<String, String>> loaded = new HashMap<>();  // guarded by itself

    private final Map<String, String> texts, english;

    private Texts(Map<String, String> texts, Map<String, String> english) {
        this.texts = texts;
        this.english = english;
    }

    /** Where the language files come from; the plugin sets the host's assets, tests and tools read them from disk. */
    static void setAssets(Assets source) {
        assets = source;
        synchronized (loaded) {
            loaded.clear();
        }
    }

    /** choice is the Language setting: a language's name from languages.properties, or "Automatic" for Android's. */
    static Texts of(String choice) {
        String code = null;
        for (Map.Entry<String, String> language : languages().entrySet()) {
            if (language.getValue().equals(choice)) code = language.getKey();
        }
        if (code == null) code = forLocale(Locale.getDefault());
        return new Texts(load(code), load("en"));
    }

    /** The languages there are files for, in the setting's order: code, such as pt-BR, to the name it shows. */
    static Map<String, String> languages() {
        Map<String, String> names = load("languages");
        return names.isEmpty() ? Collections.singletonMap("en", "English") : names;
    }

    /** Android's language: language and region (pt-BR) if there is a file for it, else the language (pt), else English. */
    static String forLocale(Locale locale) {
        Map<String, String> languages = languages();
        String tag = locale.getLanguage() + (locale.getCountry().isEmpty() ? "" : "-" + locale.getCountry());
        if (languages.containsKey(tag)) return tag;
        if (languages.containsKey(locale.getLanguage())) return locale.getLanguage();
        return "en";
    }

    private static Map<String, String> load(String name) {
        synchronized (loaded) {
            Map<String, String> texts = loaded.get(name);
            if (texts == null) {
                texts = read(name);
                loaded.put(name, texts);
            }
            return texts;
        }
    }

    /** A language file's texts, in the file's order; empty when it cannot be read. */
    static Map<String, String> read(String name) {
        Map<String, String> texts = new LinkedHashMap<>();
        String content;
        try (InputStream in = assets.open(DIR + name + ".properties")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) bytes.write(buffer, 0, n);
            content = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException missing) {
            return texts;  // no such language, or no assets (an older Kiosk Satellite): English, or the keys themselves
        }
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(content));
        } catch (IOException | IllegalArgumentException broken) {
            return texts;
        }
        // Properties forgets the order, which the Language setting's list follows: take it from the lines.
        for (String line : content.split("\n")) {
            line = line.trim();
            int equals = line.indexOf('=');
            if (line.isEmpty() || line.startsWith("#") || equals <= 0) continue;
            String key = line.substring(0, equals).trim();
            if (properties.containsKey(key)) texts.put(key, properties.getProperty(key));
        }
        return texts;
    }

    /** For tests and tools: the files in the repository's assets folder (-Dreolink.assets, else ./assets). */
    private static InputStream fromDisk(String name) throws IOException {
        return new FileInputStream(System.getProperty("reolink.assets", "assets") + "/" + name);
    }

    /** A text by key, in this language or else English; the key itself if neither has it, so a gap shows. */
    String get(String key) {
        String text = texts.get(key);
        if (text == null) text = english.get(key);
        return text == null ? key : text;
    }

    /** A text with its {placeholders} filled in, given as name, value pairs. */
    String format(String key, Object... pairs) {
        String text = get(key);
        for (int i = 0; i + 1 < pairs.length; i += 2) text = text.replace("{" + pairs[i] + "}", String.valueOf(pairs[i + 1]));
        return text;
    }

    // Viewer
    String answer() { return get("answer"); }
    String speaker() { return get("speaker"); }
    String microphone() { return get("microphone"); }
    String hangUp() { return get("hangUp"); }
    String endCall() { return get("endCall"); }
    String on() { return get("on"); }
    String off() { return get("off"); }
    String microphoneActive() { return get("microphoneActive"); }
    String holdToTalk() { return get("holdToTalk"); }
    String holdToTalkHint() { return get("holdToTalkHint"); }
    String connecting() { return get("connecting"); }
    String noPictureYet() { return get("noPictureYet"); }
    String noVideo(String reason) { return format("noVideo", "reason", reason); }
    String noMicrophone() { return get("noMicrophone"); }
    String defaultName() { return get("defaultName"); }

    // Cards
    String cardAction() { return get("cardAction"); }
    String holdToRun() { return get("holdToRun"); }
    String cardDone() { return get("cardDone"); }
    String cardFailed() { return get("cardFailed"); }
    String cardsNeedNewerKs() { return get("cardsNeedNewerKs"); }
    String unavailable() { return get("unavailable"); }
    String unknown() { return get("unknown"); }
    String stateOn() { return get("stateOn"); }
    String stateOff() { return get("stateOff"); }
    String stateOpen() { return get("stateOpen"); }
    String stateClosed() { return get("stateClosed"); }
    String stateDetected() { return get("stateDetected"); }
    String stateClear() { return get("stateClear"); }

    /** Lock, alarm and cover states, or null for one without its own text. */
    String haState(String state) {
        if (state.equals("open")) return stateOpen();
        if (state.equals("closed")) return stateClosed();
        String key = "state." + state;
        return texts.containsKey(key) || english.containsKey(key) ? get(key) : null;
    }

    // Readings and status
    String connectionReading() { return get("connectionReading"); }
    String lastRingReading() { return get("lastRingReading"); }
    String firstPictureReading() { return get("firstPictureReading"); }
    String connected() { return get("connected"); }
    String connectingState() { return get("connectingState"); }
    String notConfigured() { return get("notConfigured"); }
    String noneYet() { return get("noneYet"); }
    String waiting() { return get("waiting"); }
    String afterRing(long millis) { return format("afterRing", "millis", millis); }
    String enterSettings() { return get("enterSettings"); }
    String connectedTo(String name) { return format("connectedTo", "name", name); }
    String doorbellProblem(String detail) { return format("doorbellProblem", "detail", detail); }

    // Connection check
    String checking() { return get("checking"); }
    String checkOk() { return get("checkOk"); }
    String checkNotConfigured() { return get("checkNotConfigured"); }
    String checkUnreachable(String host) { return format("checkUnreachable", "host", host); }
    String checkLoginRefused() { return get("checkLoginRefused"); }
    String checkAccountDevice() { return get("checkAccountDevice"); }
    String checkNoVideo() { return get("checkNoVideo"); }
    String checkNoTalk() { return get("checkNoTalk"); }
    String checkFailed(String detail) { return format("checkFailed", "detail", detail); }
}
