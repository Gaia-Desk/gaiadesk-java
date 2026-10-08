package net.gaiadesk.model;

import java.util.Collections;
import java.util.List;

/**
 * {@code GET /desks/{id}/reach}: a desk's online and offline history, newest first.
 */
public final class ReachLog extends ModelObject {
    private String deskId = "";
    private long since;
    private List<ReachEvent> events = Collections.emptyList();

    /** For JSON only. */
    ReachLog() {}

    /** The desk. */
    public String getDeskId() {
        return deskId;
    }

    /** Unix seconds the log starts at. */
    public long getSince() {
        return since;
    }

    /** The transitions, newest first. */
    public List<ReachEvent> getEvents() {
        return events;
    }
}
