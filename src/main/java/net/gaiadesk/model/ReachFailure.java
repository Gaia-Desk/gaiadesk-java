package net.gaiadesk.model;

/**
 * The last time reaching a desk failed.
 */
public final class ReachFailure extends ModelObject {
    private long at;
    private String kind = "";
    private String message = "";

    /** For JSON only. */
    ReachFailure() {}

    /** Unix milliseconds. */
    public long getAt() {
        return at;
    }

    /** The error kind. */
    public String getKind() {
        return kind;
    }

    /** What happened. */
    public String getMessage() {
        return message;
    }
}
