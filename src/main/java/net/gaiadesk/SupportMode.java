package net.gaiadesk;

import java.util.Locale;

/** What a support agent may do in a customer's shared tab. */
public enum SupportMode {
    /** The agent sees the shared tab. */
    VIEW,
    /** The agent may also point, highlight, click, scroll and type inside the page (applied by the embed to its own DOM; the customer sees it and can stop it). */
    COBROWSE;

    /** The name sent to the API. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
