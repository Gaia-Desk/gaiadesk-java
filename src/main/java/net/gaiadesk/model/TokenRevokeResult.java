package net.gaiadesk.model;

/**
 * {@code revokeToken}: the token revoked and the live sessions it ended.
 */
public final class TokenRevokeResult extends ModelObject {
    private String revoked = "";
    private int stoppedSessions;

    /** For JSON only. */
    TokenRevokeResult() {}

    /** The token revoked. */
    public String getRevoked() {
        return revoked;
    }

    /** Its live sessions that ended. */
    public int getStoppedSessions() {
        return stoppedSessions;
    }
}
