package net.gaiadesk.model;

/**
 * One new agent token: the desk, the token and its secret (shown once).
 */
public final class MintedToken extends ModelObject {
    private String desk = "";
    private TokenInfo token;
    private String secret = "";

    /** For JSON only. */
    MintedToken() {}

    /** The desk. */
    public String getDesk() {
        return desk;
    }

    /** The token. */
    public TokenInfo getToken() {
        return token;
    }

    /** Its secret ({@code gdagt_…}); shown once. */
    public String getSecret() {
        return secret;
    }
}
