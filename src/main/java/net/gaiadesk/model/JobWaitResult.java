package net.gaiadesk.model;

/**
 * {@code waitJob}: the job as it ended (its {@code exitCode} is a result, not an error) or, {@code timedOut}, as it stands, still running.
 */
public final class JobWaitResult extends ModelObject {
    private Job job;
    private boolean timedOut;

    /** For JSON only. */
    JobWaitResult() {}

    /** The job. */
    public Job getJob() {
        return job;
    }

    /** The wait's timeout ran out first. */
    public boolean isTimedOut() {
        return timedOut;
    }
}
