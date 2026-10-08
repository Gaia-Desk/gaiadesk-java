package net.gaiadesk.model;

/**
 * One file that failed to copy.
 */
public final class CopyFailure extends ModelObject {
    private String path = "";
    private String message = "";

    /** For JSON only. */
    CopyFailure() {}

    /** The file. */
    public String getPath() {
        return path;
    }

    /** Why. */
    public String getMessage() {
        return message;
    }
}
