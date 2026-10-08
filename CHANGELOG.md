# Changelog

## 0.1.0 (unreleased)

First release: `net.gaiadesk:gaiadesk`, the GaiaDesk SDK for Java and Kotlin (Java 11+).

- The hosted GaiaDesk API, every `/v1` route: desks (`devices`, `device`,
  `reach`, `wake`), desk operations (`exec`, `execStream`, files up and down,
  jobs run/list/wait/logs/follow/kill, `stats`, tokens mint/list/revoke),
  `audit` (with `auditAll` paging), webhooks and support sessions.
- End-to-end encrypted desk operations (`E2eMode.AUTO`/`REQUIRE`/`OFF`, pinned
  keys, the `e2e_required` and `e2e_decrypt_failed` retries), reproducing the
  protocol's test vectors byte for byte; XChaCha20-Poly1305 on the JDK's
  ChaCha20-Poly1305 with HChaCha20.
- `exec` as administrator (`ExecOptions.admin`), the `admin` token scope and
  its refusal reasons (`admin_scope_missing`, `admin_not_enabled`,
  `admin_denied`, `admin_unavailable`).
- The local transport (the desk's Unix socket on Java 16+, or its Windows
  named pipe) and the LAN transport (TLS pinned to the gateway's certificate).
- Typed unchecked exceptions from the error envelope; retries with backoff
  that never repeat a mutation; request timeouts; cancellation;
  `CompletableFuture` variants (`gd.async()`); streams as iterators, collected
  results or `java.util.concurrent.Flow` publishers; webhook signature
  verification (`Webhooks.verify`); JSpecify nullability for Kotlin.
