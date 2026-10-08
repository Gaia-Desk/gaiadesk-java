package net.gaiadesk.model;

import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code GET /desks}: the desks on the account and its team, online first.
 */
public final class DeviceList extends ModelObject {
    private List<Device> devices = Collections.emptyList();
    private List<String> sources = Collections.emptyList();
    private List<String> notes = Collections.emptyList();
    private @Nullable Identity identity;

    /** For JSON only. */
    DeviceList() {}

    /** The desks. */
    public List<Device> getDevices() {
        return devices;
    }

    /** Where the list came from ({@code server}). */
    public List<String> getSources() {
        return sources;
    }

    /** Notes about the list. */
    public List<String> getNotes() {
        return notes;
    }

    /** Who asked. */
    public @Nullable Identity getIdentity() {
        return identity;
    }
}
