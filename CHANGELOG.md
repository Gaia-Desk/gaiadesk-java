# Changelog

## 0.1.1 (unreleased)

- Never hang on a dropped or stalled connection. `Timeouts` (`.timeouts(...)`
  on every builder: hosted API, local, LAN): `responseTimeout` (default 16
  minutes, above the API's 15-minute call limit; it was unlimited) bounds the
  wait for an answer to begin, sending the request included;
  `idleTimeout` (default 90 s; streams and held waits keep alive every 15 s)
  bounds every read of a body (JSON, error bodies, downloads plain and sealed,
  event streams), which nothing bounded before: a peer that sent the headers
  and then went silent with the socket open hung the call forever. Exceeded:
  `UnreachableException` / `ConnectionLostException`, kind `timeout`; a timed
  out connection is closed, never reused. `null` is no limit.
- One retry rule across the GaiaDesk SDKs (`RetryPolicy`). A request is sent
  again only when that cannot run anything twice: a connection never made
  (DNS, refused, TLS handshake broken off, no local socket or pipe), any
  method; a connection lost after sending (closed or reset before any answer)
  or a 502/503/504, `GET`s only, a 503 `api_disabled`/`desk_ops_disabled`/
  `local_api_off` final; a 429 or a 409 `idempotency_key_in_flight`, any
  method. What changed: a `GET` whose connection was lost after sending is now
  retried (it was not); any 429 is retried, whatever its reason (only
  `rate_limited`/`desk_busy`/`idempotency_key_in_flight` were), and so is a
  409 `idempotency_key_in_flight` (it was not); a 503 `local_api_off` is final (it was
  retried); a connect that times out is now kind `timeout` and never retried
  (it was kind `network` and retried for every method). Timeouts and answers
  that had begun are never retried; an `Idempotency-Key` never unlocks a retry.
- New defaults: backoff `min(maxDelay, baseDelay × 2^n) × 0.5–1.0` with
  `baseDelay` 250 ms (was 500 ms) and `maxDelay` 8 s (was 30 s; it was full
  jitter, 0–1.0). `Retry-After` (429 and 503 only; 502/504 back off) is waited
  up to the new `maxRetryWait` (60 s; it was capped by `maxDelay`), a longer
  one thrown at once carrying it: `RetryPolicy.of(maxRetries, baseDelay,
  maxDelay, maxRetryWait)`, `getMaxRetryWait()`, `backoffMillis(n, jitter)`.
  Invalid values are a `UsageException` (was `IllegalArgumentException`).
- The JDK's `HttpClient` re-sends only `GET`/`HEAD` by itself; pinned on the
  raw-socket server: a POST, PUT or DELETE (bodiless included) on a reused
  keep-alive connection that closes reaches the server exactly once.
- A stream ended by a transport error reports its class's kind in
  `Exit.getError()` (`connection_lost`, reason `timeout`, for an idle timeout).
- Proven on a raw-socket test server: closed or reset before any response
  byte (with and without reading a 4 MiB upload), stalled mid-body, mid-JSON
  and mid-stream, silent, and 300 dropped requests in a row.

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
