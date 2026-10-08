/**
 * End-to-end encrypted desk operations, v1: the caller's side of the sealing the GaiaDesk API relays without
 * reading. X25519 (the JDK's {@code XDH}), HKDF-SHA256 (the JDK's {@code HmacSHA256}) and XChaCha20-Poly1305
 * (HChaCha20 here, over the JDK's {@code ChaCha20-Poly1305}); the protocol's fixed test vectors are reproduced
 * byte for byte by this SDK's tests.
 *
 * <p>The client uses this by itself (see {@link net.gaiadesk.E2eMode}); it is public for tooling and tests.
 */
@NullMarked
package net.gaiadesk.e2e;

import org.jspecify.annotations.NullMarked;
