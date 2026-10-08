package net.gaiadesk.internal;

/** One server-sent event: its {@code event:} name (default {@code message}) and its {@code data:} lines joined by newlines. */
public final class SseEvent {
    public final String event;
    public final String data;

    public SseEvent(String event, String data) {
        this.event = event;
        this.data = data;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SseEvent && ((SseEvent) o).event.equals(event) && ((SseEvent) o).data.equals(data);
    }

    @Override
    public int hashCode() {
        return event.hashCode() * 31 + data.hashCode();
    }

    @Override
    public String toString() {
        return "SseEvent{" + event + ": " + data + "}";
    }
}
