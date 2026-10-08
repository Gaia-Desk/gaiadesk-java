package net.gaiadesk.model;

/**
 * One disk.
 */
public final class DiskStat extends ModelObject {
    private String mount = "";
    private long totalMb;
    private long freeMb;

    /** For JSON only. */
    DiskStat() {}

    /** Where it is mounted. */
    public String getMount() {
        return mount;
    }

    /** Its size. */
    public long getTotalMb() {
        return totalMb;
    }

    /** Free space. */
    public long getFreeMb() {
        return freeMb;
    }
}
