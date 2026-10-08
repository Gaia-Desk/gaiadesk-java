/**
 * The GaiaDesk SDK for Java and Kotlin.
 *
 * <p>Start with {@link net.gaiadesk.GaiaDesk}: build a client for the hosted GaiaDesk API
 * ({@code GaiaDesk.builder().apiKey(...)}), a desk's own local API ({@link net.gaiadesk.GaiaDesk#localBuilder()})
 * or its LAN gateway ({@link net.gaiadesk.GaiaDesk#lanBuilder(String, String)}), then call its methods. Every
 * failure is a {@link net.gaiadesk.GaiaDeskException} (unchecked) of the class its {@code kind} names.
 *
 * <p>Everything in this package is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package net.gaiadesk;

import org.jspecify.annotations.NullMarked;
