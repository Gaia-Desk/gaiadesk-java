package net.gaiadesk.model;

/**
 * What a wake rang.
 */
public final class WakeRang extends ModelObject {
    private int doorbell;
    private int lanHelpers;

    /** For JSON only. */
    WakeRang() {}

    /** Doorbell sockets rung. */
    public int getDoorbell() {
        return doorbell;
    }

    /** Awake LAN siblings asked to send Wake-on-LAN. */
    public int getLanHelpers() {
        return lanHelpers;
    }
}
