// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The language files: every translation fits English, and the Language setting lists exactly the languages there are. */
public final class TextsTest {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\w+\\}");

    public static void main(String[] args) throws Exception {
        Map<String, String> english = Texts.read("en");
        if (english.size() < 50) throw new AssertionError("en.properties not found or short: " + english.size());

        // Every language in languages.properties has a file, and every file only has English's keys, with the same
        // {placeholders}. English comes first.
        Map<String, String> languages = Texts.languages();
        check(new ArrayList<>(languages.keySet()).get(0), "en");
        for (String code : languages.keySet()) {
            Map<String, String> texts = Texts.read(code);
            if (texts.isEmpty()) throw new AssertionError(code + ".properties is missing or empty");
            for (Map.Entry<String, String> text : texts.entrySet()) {
                String key = text.getKey();
                if (!english.containsKey(key)) throw new AssertionError(code + ": " + key + " is not a text the plugin has");
                if (!placeholders(text.getValue()).equals(placeholders(english.get(key)))) {
                    throw new AssertionError(code + ": " + key + " has " + placeholders(text.getValue()) + " where English has " + placeholders(english.get(key)));
                }
                if (text.getValue().trim().isEmpty()) throw new AssertionError(code + ": " + key + " is empty");
            }
        }

        // The manifest's Language setting offers Automatic and then these languages by name, in this order.
        String manifest = new String(Files.readAllBytes(Paths.get(System.getProperty("reolink.assets", "assets"), "..", "kiosk-satellite-plugin.json")), StandardCharsets.UTF_8);
        Matcher options = Pattern.compile("\"key\": \"language\".*?\"options\": \\[([^\\]]*)\\]").matcher(manifest);
        if (!options.find()) throw new AssertionError("no Language setting in the manifest");
        List<String> expected = new ArrayList<>();
        expected.add("\"Automatic\"");
        for (String name : languages.values()) expected.add("\"" + name + "\"");
        check(options.group(1).replace(", ", ",").trim(), String.join(",", expected));

        // Every text the plugin asks for is in English: each text method's key is there.
        Texts en = Texts.of("English");
        for (Method method : Texts.class.getDeclaredMethods()) {
            if (method.getReturnType() != String.class || Modifier.isStatic(method.getModifiers())) continue;
            if (method.getName().equals("get") || method.getName().equals("format") || method.getName().equals("haState")) continue;
            if (!english.containsKey(method.getName())) throw new AssertionError("English has no text for " + method.getName());
        }
        check(en.connectedTo("Front door"), "Connected to Front door");
        check(en.afterRing(382), "382 ms after the ring");
        check(Texts.of("Nederlands").checkUnreachable("10.0.0.5"), "10.0.0.5 is niet bereikbaar. Controleer het adres, en of de deurbel in hetzelfde netwerk zit.");
        check(en.haState("armed_away"), "Armed away");
        check(en.haState("open"), "Open");
        check(en.haState("sleeping") == null, true);

        // A translation that leaves texts out shows them in English; Automatic follows Android, region first.
        Map<String, String> files = new HashMap<>();
        files.put("i18n/languages.properties", "en=English\npt-BR=Português (Brasil)\npt=Português\n");
        files.put("i18n/en.properties", "answer=Answer\nspeaker=Speaker\nconnectedTo=Connected to {name}\n");
        files.put("i18n/pt-BR.properties", "# só um começo\nanswer=Atender\nconnectedTo=Conectado a {name}\n");
        files.put("i18n/pt.properties", "answer=Atender\n");
        Texts.setAssets(name -> {
            String file = files.get(name);
            if (file == null) throw new FileNotFoundException(name);
            return new ByteArrayInputStream(file.getBytes(StandardCharsets.UTF_8));
        });
        Texts portuguese = Texts.of("Português (Brasil)");
        check(portuguese.answer(), "Atender");
        check(portuguese.speaker(), "Speaker");
        check(portuguese.connectedTo("Portão"), "Conectado a Portão");
        check(portuguese.endCall(), "endCall");  // neither has it: the key shows, so the gap is seen
        check(Texts.forLocale(new Locale("pt", "BR")), "pt-BR");
        check(Texts.forLocale(new Locale("pt", "PT")), "pt");
        check(Texts.forLocale(new Locale("fr", "FR")), "en");
        check(Texts.forLocale(Locale.ENGLISH), "en");
        check(new ArrayList<>(Texts.languages().keySet()).toString(), "[en, pt-BR, pt]");
        // No assets at all, as on a Kiosk Satellite without them: keys, but no crash.
        Texts.setAssets(name -> { throw new IOException("no assets"); });
        check(Texts.of("English").answer(), "answer");

        System.out.println("TextsTest passed (" + languages.size() + " languages, " + english.size() + " texts)");
    }

    private static TreeSet<String> placeholders(String text) {
        TreeSet<String> found = new TreeSet<>();
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) found.add(m.group());
        return found;
    }

    private static void check(Object actual, Object expected) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " but got " + actual);
    }
}
