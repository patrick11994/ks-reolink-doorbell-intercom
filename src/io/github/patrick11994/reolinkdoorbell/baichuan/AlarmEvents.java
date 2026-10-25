// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell.baichuan;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Parses a cmd 33 push: an AlarmEventList with one AlarmEvent per channel. status is a comma list such as
 * "none", "MD" or "MD,visitor"; visitor is the doorbell button and holds for about 15 s.
 */
public final class AlarmEvents {
    public static final class Event {
        public final int channel;
        public final Set<String> status;
        public final String aiType;

        Event(int channel, Set<String> status, String aiType) {
            this.channel = channel;
            this.status = status;
            this.aiType = aiType;
        }

        public boolean visitor() {
            return status.contains("visitor");
        }

        @Override public String toString() {
            return "ch" + channel + " status=" + status + " ai=" + aiType;
        }
    }

    private AlarmEvents() {}

    public static List<Event> parse(String xml) {
        List<Event> events = new ArrayList<>();
        if (xml == null || xml.trim().isEmpty()) return events;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setExpandEntityReferences(false);
            javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);  // the default handler prints every malformed push to stderr
            Document document = builder.parse(new ByteArrayInputStream(xml.trim().getBytes(StandardCharsets.UTF_8)));
            NodeList nodes = document.getElementsByTagName("AlarmEvent");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element event = (Element) nodes.item(i);
                int channel = parseInt(text(event, "channelId"), 0);
                Set<String> status = new HashSet<>();
                for (String part : text(event, "status").toLowerCase(Locale.ROOT).split(",")) {
                    String trimmed = part.trim();
                    if (!trimmed.isEmpty() && !"none".equals(trimmed)) status.add(trimmed);
                }
                events.add(new Event(channel, Collections.unmodifiableSet(status), text(event, "AItype")));
            }
        } catch (Exception malformed) {
            // A push we cannot read is ignored; the next one carries the full state again.
        }
        return events;
    }

    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
