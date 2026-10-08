package net.gaiadesk.model;

/**
 * A new support session with its embed token, shown once.
 */
public final class SupportSessionCreated extends SupportSession {
    private String embedToken = "";

    /** For JSON only. */
    SupportSessionCreated() {}

    /** The page's one credential for this session ({@code gdemb_…}): hand it to {@code GaiaDeskEmbed.start({ embedToken })}. */
    public String getEmbedToken() {
        return embedToken;
    }
}
