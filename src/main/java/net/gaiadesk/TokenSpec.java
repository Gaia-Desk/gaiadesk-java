package net.gaiadesk;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.gaiadesk.internal.Check;
import org.jspecify.annotations.Nullable;

/**
 * A scoped agent token to mint ({@link GaiaDesk#createToken}): one per desk, under one name. Defaults: 7 days,
 * scopes {@code exec}, {@code cp}, {@code jobs}. The desk enforces the scopes, the folder, the low-privilege
 * user and the expiry on every request.
 */
public final class TokenSpec extends CallOptions<TokenSpec> {
    final List<String> desks = new ArrayList<>();
    @Nullable String name;
    long expiresSecs = 7 * 86400L;
    @Nullable List<String> scopes;
    @Nullable String cwd;
    boolean lowPriv;

    /** A token for these desks (at least one). */
    public TokenSpec(String... desks) {
        desks(Arrays.asList(desks));
    }

    /** A token for these desks (at least one). */
    public TokenSpec(List<String> desks) {
        desks(desks);
    }

    /** The desks it is minted on (one token each). */
    public TokenSpec desks(List<String> desks) {
        this.desks.clear();
        for (String d : desks) this.desks.add(Check.desk(d));
        return this;
    }

    /** Its name (required over the API). */
    public TokenSpec name(String name) {
        if (name == null || name.trim().isEmpty()) throw Check.usage("a token's name must be a non-empty string");
        this.name = name;
        return this;
    }

    /** How long it lasts: {@code 30m}, {@code 24h}, {@code 7d}, {@code 2w}. */
    public TokenSpec expires(String duration) {
        this.expiresSecs = Check.seconds(duration, "expires");
        return this;
    }

    /** How long it lasts. */
    public TokenSpec expires(Duration duration) {
        this.expiresSecs = Check.seconds(duration.toMillis() / 1000.0, "expires");
        return this;
    }

    /** What it may do (see {@link Scopes}); the {@code admin} scope cannot be minted through the API ({@code admin_not_via_api}). */
    public TokenSpec scopes(String... scopes) {
        return scopes(Arrays.asList(scopes));
    }

    /** What it may do (see {@link Scopes}). */
    public TokenSpec scopes(List<String> scopes) {
        if (scopes.isEmpty()) throw Check.usage("scopes must not be empty");
        for (String s : scopes) {
            if (s == null || s.trim().isEmpty() || s.contains(",")) throw Check.usage("a scope is one word: " + Check.quote(s));
        }
        this.scopes = new ArrayList<>(scopes);
        return this;
    }

    /** Confine its work to this directory on the desk. */
    public TokenSpec cwd(String cwd) {
        this.cwd = Check.cwd(cwd);
        return this;
    }

    /** Run its work as the desk's low-privilege agent user, or refuse. */
    public TokenSpec lowPriv(boolean lowPriv) {
        this.lowPriv = lowPriv;
        return this;
    }
}
