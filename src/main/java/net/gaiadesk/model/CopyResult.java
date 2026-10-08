package net.gaiadesk.model;

import java.util.Collections;
import java.util.List;

/**
 * What a copy did ({@code cp --json}).
 */
public final class CopyResult extends ModelObject {
    private String direction = "";
    private String desk = "";
    private String destination = "";
    private long files;
    private long dirs;
    private long bytes;
    private long resumedBytes;
    private List<CopyFailure> failed = Collections.emptyList();
    private double seconds;

    /** For JSON only. */
    CopyResult() {}

    /** {@code upload} or {@code download}. */
    public String getDirection() {
        return direction;
    }

    /** The desk. */
    public String getDesk() {
        return desk;
    }

    /** Where it was written. */
    public String getDestination() {
        return destination;
    }

    /** Files copied. */
    public long getFiles() {
        return files;
    }

    /** Folders created. */
    public long getDirs() {
        return dirs;
    }

    /** Bytes copied. */
    public long getBytes() {
        return bytes;
    }

    /** Bytes a resumed copy did not send again. */
    public long getResumedBytes() {
        return resumedBytes;
    }

    /** Files that failed. */
    public List<CopyFailure> getFailed() {
        return failed;
    }

    /** How long it took. */
    public double getSeconds() {
        return seconds;
    }
}
