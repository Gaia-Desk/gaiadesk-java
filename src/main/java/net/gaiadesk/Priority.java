package net.gaiadesk;

import java.util.Locale;

/** A background job's priority. */
public enum Priority {
    /** Below normal. */
    LOW,
    /** Normal. */
    NORMAL,
    /** Above normal. */
    HIGH;

    /** The name sent to the API. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
