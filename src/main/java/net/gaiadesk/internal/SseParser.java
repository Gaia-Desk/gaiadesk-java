package net.gaiadesk.internal;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** An incremental {@code text/event-stream} parser (the WHATWG rules: fields, comments, blank-line dispatch), for text split anywhere. */
public final class SseParser {
    private final StringBuilder buf = new StringBuilder();
    private String event = "";
    private final List<String> data = new ArrayList<>();

    /** Feed decoded text; the events it completed. */
    public List<SseEvent> feed(String text) {
        buf.append(text);
        List<SseEvent> out = new ArrayList<>();
        for (;;) {
            int i = 0;
            int n = buf.length();
            while (i < n && buf.charAt(i) != '\r' && buf.charAt(i) != '\n') i++;
            if (i == n) break;
            int sep = 1;
            if (buf.charAt(i) == '\r') {
                // A trailing \r may be the first half of \r\n: wait for the next chunk.
                if (i == n - 1) break;
                if (buf.charAt(i + 1) == '\n') sep = 2;
            }
            String line = buf.substring(0, i);
            buf.delete(0, i + sep);
            SseEvent ev = line(line);
            if (ev != null) out.add(ev);
        }
        return out;
    }

    /** The end of the stream: an event not finished with a blank line is still delivered. */
    public List<SseEvent> end() {
        List<SseEvent> out = new ArrayList<>();
        if (buf.length() > 0) {
            String rest = buf.toString();
            if (rest.endsWith("\r")) rest = rest.substring(0, rest.length() - 1);
            buf.setLength(0);
            SseEvent ev = line(rest);
            if (ev != null) out.add(ev);
        }
        SseEvent last = line("");
        if (last != null) out.add(last);
        return out;
    }

    private @Nullable SseEvent line(String line) {
        if (line.isEmpty()) {
            if (data.isEmpty()) {
                event = "";
                return null;
            }
            SseEvent ev = new SseEvent(event.isEmpty() ? "message" : event, String.join("\n", data));
            event = "";
            data.clear();
            return ev;
        }
        if (line.startsWith(":")) return null;
        int i = line.indexOf(':');
        String field = i < 0 ? line : line.substring(0, i);
        String value = i < 0 ? "" : line.substring(i + 1);
        if (value.startsWith(" ")) value = value.substring(1);
        if (field.equals("event")) event = value;
        else if (field.equals("data")) data.add(value);
        return null;
    }
}
