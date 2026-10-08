package net.gaiadesk.model;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code GET /desks/{id}/stats}: CPU, memory, disks and running jobs, as the desk measures them.
 */
public final class DeskStats extends ModelObject {
    private String desk = "";
    private String hostname = "";
    private String os = "";
    private @Nullable String osVersion;
    private double cpuPercent;
    private int cpus;
    private @Nullable List<Double> load;
    private long memTotalMb;
    private long memFreeMb;
    private @Nullable List<DiskStat> disks;
    private long uptimeSecs;
    private int jobsRunning;

    /** For JSON only. */
    DeskStats() {}

    /** The desk. */
    public String getDesk() {
        return desk;
    }

    /** Its host name. */
    public String getHostname() {
        return hostname;
    }

    /** Its OS. */
    public String getOs() {
        return os;
    }

    /** Its OS version. */
    public @Nullable String getOsVersion() {
        return osVersion;
    }

    /** CPU use, percent of the whole machine. */
    public double getCpuPercent() {
        return cpuPercent;
    }

    /** Logical CPUs. */
    public int getCpus() {
        return cpus;
    }

    /** Load averages (1, 5, 15 minutes), where the OS has them. */
    public @Nullable List<Double> getLoad() {
        return load;
    }

    /** Memory. */
    public long getMemTotalMb() {
        return memTotalMb;
    }

    /** Free memory. */
    public long getMemFreeMb() {
        return memFreeMb;
    }

    /** Its disks. */
    public @Nullable List<DiskStat> getDisks() {
        return disks;
    }

    /** Uptime. */
    public long getUptimeSecs() {
        return uptimeSecs;
    }

    /** Background jobs running. */
    public int getJobsRunning() {
        return jobsRunning;
    }
}
