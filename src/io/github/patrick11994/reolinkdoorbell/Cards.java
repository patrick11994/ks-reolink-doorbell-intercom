// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The viewer's cards, from the Cards list setting: each runs an action the user chose (a Home Assistant service, a
 * script, anything a gesture can do) and can show the live state of a Home Assistant entity, in the style of Home
 * Assistant's tile card. The look (label, icon, state text, colour) follows the entity, the way Home Assistant shows it.
 */
final class Cards {
    static final int MAX = 4;

    /** One card as the user set it up; row is its place in the list, which runAction names. */
    static final class Card {
        final int row;
        final String label, icon, entityId;
        final boolean action, confirm;

        Card(int row, String label, String icon, String entityId, boolean action, boolean confirm) {
            this.row = row;
            this.label = label;
            this.icon = icon;
            this.entityId = entityId;
            this.action = action;
            this.confirm = confirm;
        }

        String actionPath() {
            return "cards." + row + ".action";
        }

        boolean hasEntity() {
            return !entityId.isEmpty();
        }
    }

    /** The accent a card's icon takes, following Home Assistant's state colours. */
    enum Tint { NONE, AMBER, GREEN, RED, ORANGE, PURPLE }

    /** What a card shows now. */
    static final class Look {
        final String label, icon, state;
        final Tint tint;
        final boolean unavailable;

        Look(String label, String icon, String state, Tint tint, boolean unavailable) {
            this.label = label;
            this.icon = icon;
            this.state = state;
            this.tint = tint;
            this.unavailable = unavailable;
        }
    }

    private Cards() {}

    /** The rows that do something (an action, an entity or both), in the user's order, at most MAX. */
    static List<Card> parse(Object value) {
        if (!(value instanceof List)) return Collections.emptyList();
        List<Card> cards = new ArrayList<>();
        List<?> rows = (List<?>) value;
        for (int i = 0; i < rows.size() && cards.size() < MAX; i++) {
            if (!(rows.get(i) instanceof Map)) continue;
            Map<?, ?> row = (Map<?, ?>) rows.get(i);
            Object action = row.get("action");
            boolean hasAction = action instanceof Map && !((Map<?, ?>) action).isEmpty();
            String entity = text(row.get("entity"));
            if (!hasAction && entity.isEmpty()) continue;
            cards.add(new Card(i, text(row.get("label")), text(row.get("icon")), entity, hasAction, Boolean.TRUE.equals(row.get("confirm"))));
        }
        return Collections.unmodifiableList(cards);
    }

    /** The card's look from the entity's latest snapshot (KS's getHaEntityState format), or null before the first. */
    static Look look(Card card, Map<?, ?> snapshot, Texts texts) {
        if (!card.hasEntity()) {
            String label = card.label.isEmpty() ? texts.cardAction() : card.label;
            return new Look(label, card.icon.isEmpty() ? "mdi:gesture-tap" : card.icon, card.confirm ? texts.holdToRun() : "", Tint.NONE, false);
        }
        String domain = card.entityId.substring(0, Math.max(0, card.entityId.indexOf('.')));
        Map<?, ?> attributes = snapshot != null && snapshot.get("attributes") instanceof Map ? (Map<?, ?>) snapshot.get("attributes") : Collections.emptyMap();
        String friendly = text(attributes.get("friendly_name"));
        String label = !card.label.isEmpty() ? card.label : !friendly.isEmpty() ? friendly : card.entityId;
        String deviceClass = text(attributes.get("device_class"));
        if (snapshot == null) return new Look(label, icon(card, attributes, domain, deviceClass, ""), "…", Tint.NONE, false);
        String status = text(snapshot.get("status"));
        String state = text(snapshot.get("state"));
        // Never show a missing or unreachable entity as off or closed.
        if (!status.equals("available") && !status.equals("unknown")) {
            return new Look(label, icon(card, attributes, domain, deviceClass, ""), texts.unavailable(), Tint.NONE, true);
        }
        if (state.isEmpty() || state.equals("unknown")) return new Look(label, icon(card, attributes, domain, deviceClass, ""), texts.unknown(), Tint.NONE, false);
        if (state.equals("unavailable")) return new Look(label, icon(card, attributes, domain, deviceClass, ""), texts.unavailable(), Tint.NONE, true);
        return new Look(label, icon(card, attributes, domain, deviceClass, state), stateText(domain, deviceClass, state, text(attributes.get("unit_of_measurement")), texts),
            tint(domain, state), false);
    }

    /** The card's own icon, else the entity's icon from Home Assistant, else one for its domain and state. */
    static String icon(Card card, Map<?, ?> attributes, String domain, String deviceClass, String state) {
        if (!card.icon.isEmpty()) return card.icon;
        String own = text(attributes.get("icon"));
        if (own.startsWith("mdi:")) return own;
        boolean on = state.equals("on");
        switch (domain) {
            case "light": return on ? "mdi:lightbulb" : "mdi:lightbulb-outline";
            case "switch":
            case "input_boolean": return on ? "mdi:toggle-switch" : "mdi:toggle-switch-off-outline";
            case "fan": return "mdi:fan";
            case "lock":
                return state.equals("unlocked") || state.equals("open") ? "mdi:lock-open-variant" : state.equals("jammed") ? "mdi:lock-alert" : "mdi:lock";
            case "alarm_control_panel":
                switch (state) {
                    case "disarmed": return "mdi:shield-off";
                    case "armed_home": return "mdi:shield-home";
                    case "armed_away": return "mdi:shield-lock";
                    case "armed_night": return "mdi:shield-moon";
                    case "triggered": return "mdi:bell-ring";
                    default: return "mdi:shield";
                }
            case "binary_sensor":
                switch (deviceClass) {
                    case "door": return on ? "mdi:door-open" : "mdi:door-closed";
                    case "garage_door": return on ? "mdi:garage-open" : "mdi:garage";
                    case "window": return on ? "mdi:window-open" : "mdi:window-closed";
                    case "motion":
                    case "occupancy":
                    case "presence": return on ? "mdi:motion-sensor" : "mdi:motion-sensor-off";
                    default: return on ? "mdi:checkbox-marked-circle" : "mdi:radiobox-blank";
                }
            case "cover":
                boolean open = !state.equals("closed");
                if (deviceClass.equals("garage")) return open ? "mdi:garage-open" : "mdi:garage";
                if (deviceClass.equals("gate")) return open ? "mdi:gate-open" : "mdi:gate";
                if (deviceClass.equals("door")) return open ? "mdi:door-open" : "mdi:door-closed";
                return open ? "mdi:window-shutter-open" : "mdi:window-shutter";
            case "script": return "mdi:script-text";
            case "automation": return "mdi:robot";
            case "scene": return "mdi:palette";
            case "button":
            case "input_button": return "mdi:gesture-tap-button";
            case "camera": return "mdi:video";
            case "person":
            case "device_tracker": return "mdi:account";
            default: return card.action ? "mdi:gesture-tap" : "mdi:eye";
        }
    }

    /** Home Assistant's state as people read it: Open and Closed for a door sensor, Armed away for an alarm. */
    static String stateText(String domain, String deviceClass, String state, String unit, Texts texts) {
        switch (domain) {
            case "binary_sensor":
                switch (deviceClass) {
                    case "door":
                    case "garage_door":
                    case "window":
                    case "opening": return state.equals("on") ? texts.stateOpen() : texts.stateClosed();
                    case "motion":
                    case "occupancy":
                    case "presence": return state.equals("on") ? texts.stateDetected() : texts.stateClear();
                    default: return state.equals("on") ? texts.stateOn() : texts.stateOff();
                }
            case "lock":
            case "alarm_control_panel":
            case "cover":
                String known = texts.haState(state);
                if (known != null) return known;
                break;
            default:
                if (state.equals("on")) return texts.stateOn();
                if (state.equals("off")) return texts.stateOff();
        }
        String readable = state.replace('_', ' ');
        readable = readable.substring(0, 1).toUpperCase(Locale.ROOT) + readable.substring(1);
        return unit.isEmpty() ? readable : readable + " " + unit;
    }

    /** Home Assistant's accents: on is amber, secure (locked, armed) green, insecure red, changing orange. */
    static Tint tint(String domain, String state) {
        switch (domain) {
            case "lock":
                if (state.equals("locked")) return Tint.GREEN;
                if (state.equals("unlocked") || state.equals("open") || state.equals("jammed")) return Tint.RED;
                return state.endsWith("ing") ? Tint.ORANGE : Tint.NONE;
            case "alarm_control_panel":
                if (state.startsWith("armed_")) return Tint.GREEN;
                if (state.equals("triggered")) return Tint.RED;
                return state.equals("disarmed") ? Tint.NONE : Tint.ORANGE;
            case "cover":
                if (state.equals("open")) return Tint.PURPLE;
                return state.endsWith("ing") ? Tint.ORANGE : Tint.NONE;
            default:
                return state.equals("on") ? Tint.AMBER : Tint.NONE;
        }
    }

    private static String text(Object value) {
        return value instanceof String ? ((String) value).trim() : "";
    }
}
