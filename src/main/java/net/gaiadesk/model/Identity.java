package net.gaiadesk.model;

import org.jspecify.annotations.Nullable;

/**
 * Who the caller is ({@code devices --json}'s {@code identity}).
 */
public final class Identity extends ModelObject {
    private String source = "";
    private @Nullable String account;

    /** For JSON only. */
    Identity() {}

    /** {@code app}, {@code login}, {@code token}, {@code api_key}, {@code none}, ... */
    public String getSource() {
        return source;
    }

    /** The account, when signed in. */
    public @Nullable String getAccount() {
        return account;
    }
}
