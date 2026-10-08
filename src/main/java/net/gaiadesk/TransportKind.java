package net.gaiadesk;

/** Which /v1 API a client speaks to. */
public enum TransportKind {
    /** The hosted GaiaDesk API ({@code https://api.gaiadesk.net/v1}). */
    API,
    /** The desk's own local API, over its Unix socket or Windows named pipe. */
    LOCAL,
    /** A desk's LAN gateway, over TLS pinned to its certificate's fingerprint. */
    LAN;

    /** {@code API}, {@code local} or {@code lan}, as messages name it. */
    public String label() {
        return this == API ? "API" : name().toLowerCase(java.util.Locale.ROOT);
    }
}
