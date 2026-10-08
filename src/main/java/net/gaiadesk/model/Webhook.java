package net.gaiadesk.model;

import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A webhook subscription (never its secret).
 */
public class Webhook extends ModelObject {
    private String id = "";
    private String url = "";
    private List<String> events = Collections.emptyList();
    private @Nullable String description;
    private long createdAt;

    /** For JSON only. */
    Webhook() {}

    /** {@code wh_…}. */
    public String getId() {
        return id;
    }

    /** The HTTPS endpoint. */
    public String getUrl() {
        return url;
    }

    /** The event types it hears. */
    public List<String> getEvents() {
        return events;
    }

    /** Its description. */
    public @Nullable String getDescription() {
        return description;
    }

    /** Unix seconds. */
    public long getCreatedAt() {
        return createdAt;
    }
}
