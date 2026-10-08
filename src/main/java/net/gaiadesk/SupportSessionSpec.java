package net.gaiadesk;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import net.gaiadesk.internal.Check;
import org.jspecify.annotations.Nullable;

/** A support session to create for the web embed SDK ({@link GaiaDesk#createSupportSession}). */
public final class SupportSessionSpec extends CallOptions<SupportSessionSpec> {
    @Nullable SupportMode mode;
    @Nullable Map<String, Object> customer;
    @Nullable Long expiresIn;
    @Nullable String origin;

    /** A session with the API's defaults (an hour; the mode the account sets). */
    public SupportSessionSpec() {}

    /** What the agent may do in the shared tab. */
    public SupportSessionSpec mode(SupportMode mode) {
        this.mode = mode;
        return this;
    }

    /**
     * Who the customer is, as the company knows them: at most 16 fields; names of 1-40 letters, digits,
     * {@code _ - .}; values strings (at most 200 characters), numbers, booleans or null.
     */
    public SupportSessionSpec customer(Map<String, ?> customer) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<String, ?> e : customer.entrySet()) {
            Object v = e.getValue();
            if (v != null && !(v instanceof String) && !(v instanceof Number) && !(v instanceof Boolean)) {
                throw Check.usage("customer." + e.getKey() + " must be a string, number, boolean or null");
            }
            m.put(e.getKey(), v);
        }
        this.customer = m;
        return this;
    }

    /** One customer field. */
    public SupportSessionSpec customer(String name, @Nullable Object value) {
        Map<String, Object> m = customer == null ? new LinkedHashMap<>() : new LinkedHashMap<>(customer);
        m.put(name, value);
        return customer(m);
    }

    /** How long until it ends (60 s to 24 h). */
    public SupportSessionSpec expiresIn(Duration expiresIn) {
        long s = expiresIn.getSeconds();
        if (s < 60 || s > 86400) throw Check.usage("expiresIn is 60 seconds to 24 hours");
        this.expiresIn = s;
        return this;
    }

    /** The page origin the embed will run on ({@code https://app.example.com}); its connection must then come from it. */
    public SupportSessionSpec origin(String origin) {
        this.origin = origin;
        return this;
    }
}
