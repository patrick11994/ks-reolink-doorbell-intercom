// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CardsTest {
    public static void main(String[] args) {
        Texts en = Texts.of("English");

        // Rows without an action or entity are skipped, but keep their place for runAction's path.
        List<Object> rows = new ArrayList<>();
        rows.add(row("Porch", "", "light.porch", action(), false));
        rows.add(row("Nothing", "", "", Collections.emptyMap(), false));
        rows.add(row("", "", "alarm_control_panel.home", Collections.emptyMap(), false));
        rows.add(row("Open door", "mdi:door", "", action(), true));
        rows.add(row("Fifth", "", "sensor.a", action(), false));
        rows.add(row("Sixth", "", "sensor.b", action(), false));
        List<Cards.Card> cards = Cards.parse(rows);
        check(cards.size(), Cards.MAX);
        check(cards.get(0).actionPath(), "cards.0.action");
        check(cards.get(1).action, false);
        check(cards.get(2).actionPath(), "cards.3.action");
        check(cards.get(2).confirm, true);
        check(cards.get(3).label, "Fifth");
        check(Cards.parse(null).size(), 0);
        check(Cards.parse("junk").size(), 0);

        // A light: amber when on, its own label over the friendly name.
        Cards.Card porch = cards.get(0);
        Cards.Look look = Cards.look(porch, snapshot("available", "on", "friendly_name", "Porch light"), en);
        check(look.label, "Porch");
        check(look.state, "On");
        check(look.icon, "mdi:lightbulb");
        check(look.tint, Cards.Tint.AMBER);
        look = Cards.look(porch, snapshot("available", "off"), en);
        check(look.state + " " + look.icon + " " + look.tint, "Off mdi:lightbulb-outline NONE");

        // Before the first snapshot: a placeholder, not off.
        look = Cards.look(porch, null, en);
        check(look.state, "…");
        check(look.unavailable, false);

        // An unreachable or missing entity never reads as off.
        for (String status : new String[] {"missing", "disconnected", "not_configured", "connecting"}) {
            look = Cards.look(porch, snapshot(status, null), en);
            check(look.state, "Unavailable");
            check(look.unavailable, true);
        }
        look = Cards.look(porch, snapshot("available", "unavailable"), en);
        check(look.unavailable, true);
        look = Cards.look(porch, snapshot("unknown", "unknown"), en);
        check(look.state + " " + look.unavailable, "Unknown false");

        // An alarm: the friendly name without a label, green when armed, red when triggered.
        Cards.Card alarm = cards.get(1);
        look = Cards.look(alarm, snapshot("available", "armed_away", "friendly_name", "Alarm"), en);
        check(look.label + ": " + look.state + " " + look.icon + " " + look.tint, "Alarm: Armed away mdi:shield-lock GREEN");
        look = Cards.look(alarm, snapshot("available", "disarmed"), Texts.of("Nederlands"));
        check(look.label + ": " + look.state + " " + look.tint, "alarm_control_panel.home: Uitgeschakeld NONE");
        check(Cards.look(alarm, snapshot("available", "triggered"), en).tint, Cards.Tint.RED);
        check(Cards.look(alarm, snapshot("available", "arming"), en).tint, Cards.Tint.ORANGE);

        // An action without an entity: its own icon, and the hold hint when it asks for one.
        look = Cards.look(cards.get(2), null, en);
        check(look.label + " " + look.icon + " " + look.state, "Open door mdi:door Hold to run");

        // Door sensors read Open and Closed; locks and covers their own words; others as written, with the unit.
        Cards.Card door = new Cards.Card(0, "", "", "binary_sensor.front_door", false, false);
        look = Cards.look(door, snapshot("available", "on", "device_class", "door"), en);
        check(look.state + " " + look.icon + " " + look.tint, "Open mdi:door-open AMBER");
        Cards.Card lock = new Cards.Card(0, "", "", "lock.front", true, true);
        check(Cards.look(lock, snapshot("available", "unlocked"), en).tint, Cards.Tint.RED);
        check(Cards.look(lock, snapshot("available", "locked"), en).state, "Locked");
        Cards.Card gate = new Cards.Card(0, "", "", "cover.gate", true, false);
        look = Cards.look(gate, snapshot("available", "opening", "device_class", "gate"), en);
        check(look.state + " " + look.icon + " " + look.tint, "Opening… mdi:gate-open ORANGE");
        Cards.Card temperature = new Cards.Card(0, "", "", "sensor.outside", false, false);
        check(Cards.look(temperature, snapshot("available", "12.5", "unit_of_measurement", "°C"), en).state, "12.5 °C");
        check(Cards.look(temperature, snapshot("available", "heavy_rain"), en).state, "Heavy rain");

        // Home Assistant's own icon wins over the domain's, the card's own over both.
        check(Cards.look(temperature, snapshot("available", "1", "icon", "mdi:thermometer"), en).icon, "mdi:thermometer");
        Cards.Card own = new Cards.Card(0, "", "mdi:star", "sensor.outside", false, false);
        check(Cards.look(own, snapshot("available", "1", "icon", "mdi:thermometer"), en).icon, "mdi:star");

        System.out.println("CardsTest passed");
    }

    private static Map<String, Object> row(String label, String icon, String entity, Map<String, Object> action, boolean confirm) {
        Map<String, Object> row = new HashMap<>();
        row.put("label", label);
        row.put("icon", icon);
        row.put("entity", entity);
        row.put("action", action);
        row.put("confirm", confirm);
        return row;
    }

    private static Map<String, Object> action() {
        return Collections.<String, Object>singletonMap("type", "ha_service");
    }

    /** A snapshot as KS sends it, with attributes as name, value pairs. */
    private static Map<String, Object> snapshot(String status, String state, String... attributes) {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("status", status);
        snapshot.put("state", state);
        Map<String, Object> attrs = new HashMap<>();
        for (int i = 0; i + 1 < attributes.length; i += 2) attrs.put(attributes[i], attributes[i + 1]);
        snapshot.put("attributes", attrs);
        return snapshot;
    }

    private static void check(Object actual, Object expected) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " but got " + actual);
    }
}
