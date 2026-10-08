package net.gaiadesk.model;

/**
 * How a desk could be woken now.
 */
public final class WakeHints extends ModelObject {
    private int doorbellSockets;
    private boolean lanWake;

    /** For JSON only. */
    WakeHints() {}

    /** Doorbell sockets it holds on the wake listener (a sleeping Mac's). */
    public int getDoorbellSockets() {
        return doorbellSockets;
    }

    /** It said how it can be woken on its LAN (Wake-on-LAN by an awake sibling). */
    public boolean isLanWake() {
        return lanWake;
    }
}
