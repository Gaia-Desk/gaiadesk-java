package net.gaiadesk.model;

import java.util.Collections;
import java.util.List;

/**
 * {@code createToken}: one token per desk, each with its secret.
 */
public final class MintResult extends ModelObject {
    private List<MintedToken> tokens = Collections.emptyList();

    /** For JSON only. */
    MintResult() {}

    /** The tokens. */
    public List<MintedToken> getTokens() {
        return tokens;
    }
}
