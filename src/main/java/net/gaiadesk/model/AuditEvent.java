package net.gaiadesk.model;

import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.Nullable;

/**
 * One event of the audit trail ({@code GET /audit}).
 */
public final class AuditEvent extends ModelObject {
    private String id = "";
    private String action = "";
    private String stream = "";
    private long occurredAtMs;
    private @Nullable AuditActor actor;
    private @Nullable AuditTarget target;
    private @Nullable JsonNode metadata;

    /** For JSON only. */
    AuditEvent() {}

    /** The event's id. */
    public String getId() {
        return id;
    }

    /** {@code desk.session.start}, {@code agent.exec}, {@code api.wakeDesk}, ... */
    public String getAction() {
        return action;
    }

    /** {@code session}, {@code agent}, {@code enterprise}, {@code api}. */
    public String getStream() {
        return stream;
    }

    /** Unix milliseconds. */
    public long getOccurredAtMs() {
        return occurredAtMs;
    }

    /** Who did it. */
    public @Nullable AuditActor getActor() {
        return actor;
    }

    /** What it was done to. */
    public @Nullable AuditTarget getTarget() {
        return target;
    }

    /** The event's own fields. */
    public @Nullable JsonNode getMetadata() {
        return metadata;
    }
}
