package net.gaiadesk;

/**
 * Reasons ({@link GaiaDeskException#getReason()}) worth branching on. The server and desks may give others;
 * compare with {@code equals}.
 */
public final class Reasons {
    /** 429: over the key's rate limit; see {@link GaiaDeskException#getRetryAfter()}. */
    public static final String RATE_LIMITED = "rate_limited";
    /** 429: the desk already runs 16 API operations. */
    public static final String DESK_BUSY = "desk_busy";
    /** 403: the key lacks a scope. */
    public static final String MISSING_SCOPE = "missing_scope";
    /** 401: no credential, or one that does not verify. */
    public static final String UNAUTHENTICATED = "unauthenticated";
    /** 403: from an API key, desk operations need a scoped agent token. */
    public static final String DESK_TOKEN_REQUIRED = "desk_token_required";
    /** 403: the desk's owner turned off "Allow commands from the GaiaDesk API". */
    public static final String DESK_OPTED_OUT = "desk_opted_out";
    /** 403: an agent token may not administer tokens. */
    public static final String AGENT_CANNOT_ADMIN = "agent_cannot_admin";
    /** 404: no desk with this id on the account or team. */
    public static final String UNKNOWN_DESK = "unknown_desk";
    /** 409: nothing could wake the desk. */
    public static final String NO_WAKE_PATH = "no_wake_path";
    /** 409: the desk runs a GaiaDesk from before desk operations. */
    public static final String DESK_TOO_OLD = "desk_too_old";
    /** 409: the desk requires end-to-end encryption (the SDK seals and retries by itself in AUTO). */
    public static final String E2E_REQUIRED = "e2e_required";
    /** The SDK would not send in the clear and had no key ({@link E2eException}). */
    public static final String E2E_UNAVAILABLE = "e2e_unavailable";
    /** The server listed a key other than the pinned one ({@link E2eException}). */
    public static final String E2E_KEY_MISMATCH = "e2e_key_mismatch";
    /** A sealed message did not open. */
    public static final String E2E_DECRYPT_FAILED = "e2e_decrypt_failed";
    /** Exec {@code admin}: the token has no {@code admin} scope (or the caller is a person). */
    public static final String ADMIN_SCOPE_MISSING = "admin_scope_missing";
    /** Exec {@code admin}: the desk owner's Admin access switch is off. */
    public static final String ADMIN_NOT_ENABLED = "admin_not_enabled";
    /** Exec {@code admin}: the person at the desk said no, did not answer, or nobody is signed in; or a confined token. */
    public static final String ADMIN_DENIED = "admin_denied";
    /** Exec {@code admin}: no privileged GaiaDesk process, or a desk too old for it (never run as the user instead). */
    public static final String ADMIN_UNAVAILABLE = "admin_unavailable";
    /** Windows Smart App Control / WDAC refused the program, SYSTEM included. */
    public static final String BLOCKED_BY_OS_POLICY = "blocked_by_os_policy";
    /** The local API is not being served here. */
    public static final String LOCAL_API_UNAVAILABLE = "local_api_unavailable";
    /** The LAN gateway's certificate is not the pinned one ({@link FingerprintMismatchException}). */
    public static final String FINGERPRINT_MISMATCH = "fingerprint_mismatch";

    private Reasons() {}
}
