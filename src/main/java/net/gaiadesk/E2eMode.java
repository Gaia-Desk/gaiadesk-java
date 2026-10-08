package net.gaiadesk;

/** End-to-end encryption of desk operations on the hosted API. */
public enum E2eMode {
    /** Sealed when the desk lists a key (or one is pinned); otherwise in the clear, warned once per desk, unless the desk requires it. The default. */
    AUTO,
    /** Never in the clear: a desk without a key is woken and asked again; still none is an {@link E2eException} and nothing is sent. */
    REQUIRE,
    /** Never sealed. */
    OFF
}
