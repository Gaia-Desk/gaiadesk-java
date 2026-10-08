# GaiaDesk SDK for Java and Kotlin

Drive your GaiaDesk machines ("desks") from Java and Kotlin through the hosted
GaiaDesk API: list them and see why one is offline, wake them, run commands and
get exit codes back, stream output, copy files, run background jobs, read
stats, mint and revoke scoped agent tokens, read the audit trail, manage
webhooks and create support sessions. Desk operations are
[end-to-end encrypted](#end-to-end-encryption) whenever the desk can open them.
The same client also speaks to a desk's own [local API and LAN gateway](#local-and-lan).

- Maven coordinates: `net.gaiadesk:gaiadesk:0.1.1`, package `net.gaiadesk`
- Java 11+ (Kotlin-friendly: JSpecify nullability, no checked exceptions)
- HTTP: `java.net.http.HttpClient` from the JDK; no OkHttp, no Netty
- Runtime dependencies: Jackson databind (JSON) and JSpecify (annotations); see [Dependencies](#dependencies)

Other GaiaDesk developer tools: the
[TypeScript SDK](https://github.com/Gaia-Desk/gaiadesk-typescript),
the [Python SDK](https://github.com/Gaia-Desk/gaiadesk-python) and the
[MCP server](https://github.com/Gaia-Desk/gaiadesk-mcp).

MIT-licensed. GaiaDesk itself is proprietary and not covered by this license.

---

## Contents

- [Install](#install)
- [Quick start](#quick-start)
- [Credentials](#credentials)
- [API](#api)
- [Streams](#streams)
- [Files](#files)
- [Jobs](#jobs)
- [Tokens](#tokens)
- [Desks, wake and reach](#desks-wake-and-reach)
- [Audit, webhooks and support sessions](#audit-webhooks-and-support-sessions)
- [Running as administrator](#running-as-administrator)
- [End-to-end encryption](#end-to-end-encryption)
- [Local and LAN](#local-and-lan)
- [Errors](#errors)
- [Retries, timeouts and cancellation](#retries-timeouts-and-cancellation)
- [Async](#async)
- [Kotlin](#kotlin)
- [Dependencies](#dependencies)
- [Not available here](#not-available-here)
- [Development](#development)

---

## Install

Gradle (Kotlin DSL):

```kotlin
dependencies {
    implementation("net.gaiadesk:gaiadesk:0.1.1")
}
```

Maven:

```xml
<dependency>
  <groupId>net.gaiadesk</groupId>
  <artifactId>gaiadesk</artifactId>
  <version>0.1.1</version>
</dependency>
```

## Quick start

```java
import net.gaiadesk.*;
import net.gaiadesk.model.*;

GaiaDesk gd = GaiaDesk.builder()
    .apiKey(System.getenv("GAIADESK_API_KEY"))         // an Atlas API key (ak_…), or a signed-in person's token
    .deskToken(System.getenv("GAIADESK_DESK_TOKEN"))   // a scoped agent token (gdagt_…), verified by the desk
    .build();

for (Device d : gd.devices().getDevices()) {
    System.out.println(d.getDeskId() + " " + d.getName() + " online=" + d.getOnline());
}

ExecResult r = gd.exec("123456789", "uname -a", new ExecOptions().shell(Shell.SH).timeoutSeconds(60));
System.out.println(r.getExit() + " " + r.getStdout());

try (ExecStream s = gd.execStream("123456789", List.of("npm", "test"))) {
    for (Chunk c : s) System.out.print(c.getText());
    System.out.println("exit " + s.exit().getExitCode());
}
```

Every result is the API's own JSON shape (the same as `gaiadesk-cli --json`
prints) as a read-only object with camelCase getters (`net.gaiadesk.model`):
`getExit()`, `getRemoteCode()`, `getDurationMs()`, ... Fields this SDK version
does not know are kept in `getOther()`, and `toJson()` gives the object back
as JSON. Two results are equal when their JSON is.

The client is thread-safe: build one and share it.

## Credentials

Every request carries `Authorization: Bearer <apiKey>` and, when set,
`X-GaiaDesk-Desk-Token: <deskToken>`.

- **API key** (`ak_…`, minted on gaiadesk.net/account → API keys) with the
  scopes each route names: `desks:read`, `desks:write`, `exec`, `files`,
  `jobs`, `tokens`, `audit:read`, `webhooks`, `support`.
- **Desk operations** (exec, jobs, files, stats, tokens) are verified by the
  desk itself. From an API key they need a **scoped agent token**
  (`gdagt_…`) in `deskToken`; a signed-in person's own session works on their
  own desks without one.
- Per call, `deskToken(...)` on the options overrides the client's, and
  `wake(seconds)` (0-120) rings a sleeping desk and waits (`wake_s`).
- **Token administration** (`createToken`, `listTokens`, `revokeToken`) is
  the desk owner's: a signed-in person's own desk, never an API key or an
  agent token (403 `agent_cannot_admin`).

## API

| Method | Route |
|---|---|
| `devices()` | `GET /desks` |
| `device(deskId)` | `GET /desks/{id}` (reachability, `e2ePub`, wake hints) |
| `reach(deskId, ReachQuery)` | `GET /desks/{id}/reach?since=&limit=` |
| `wake(deskId, WakeOptions)` | `POST /desks/{id}/wake` (`wait_s`) |
| `exec(deskId, command \| argv, ExecOptions)` | `POST /desks/{id}/exec` |
| `execStream(deskId, command \| argv, ExecOptions)` | `POST /desks/{id}/exec?stream=1` (Server-Sent Events) |
| `upload(Path, deskId, remote)` / `uploadBytes` / `uploadText` | `PUT /desks/{id}/files?path=` |
| `download(deskId, remote, Path)` / `downloadBytes` / `downloadTo(…, OutputStream, …)` | `GET /desks/{id}/files?path=` |
| `runJob(deskId, name, command \| argv, JobOptions)` | `POST /desks/{id}/jobs` |
| `jobs(deskId)` | `GET /desks/{id}/jobs` |
| `waitJob(deskId, name, WaitOptions)` | `GET /desks/{id}/jobs/{name}/wait?timeout=` |
| `jobLogs(deskId, name, LogsOptions)` | `GET /desks/{id}/jobs/{name}/logs?tail=` |
| `followJobLogs(deskId, name, LogsOptions)` | `GET /desks/{id}/jobs/{name}/logs?follow=1` (SSE) |
| `killJob(deskId, name)` | `DELETE /desks/{id}/jobs/{name}` |
| `stats(deskId)` | `GET /desks/{id}/stats` |
| `createToken(TokenSpec)` | `POST /desks/{id}/tokens`, once per desk |
| `listTokens(deskId)` / `revokeToken(deskId, token)` | `GET /desks/{id}/tokens` / `DELETE /desks/{id}/tokens/{token}` |
| `audit(AuditQuery)` / `auditAll(AuditQuery)` | `GET /audit` (one page / every page) |
| `webhooks()` / `createWebhook(WebhookSpec)` / `deleteWebhook(id)` | `GET` / `POST /webhooks`, `DELETE /webhooks/{id}` |
| `createSupportSession(SupportSessionSpec)` | `POST /support/sessions` |
| `supportSessions(SupportSessionQuery)` / `supportSession(id)` | `GET /support/sessions`, `GET /support/sessions/{id}` |

Every method has an overload without options; options classes chain
(`new ExecOptions().shell(Shell.BASH).env("CI", "1").wake(30)`), and every
options class also takes the per-call settings of `CallOptions`:
`deskToken`, `wake`, `requestTimeout`, `cancellation`, `idempotencyKey`.

### exec

```java
ExecResult r = gd.exec("123456789", "make test", new ExecOptions()
    .shell(Shell.BASH)               // DEFAULT, NONE, SH, BASH, ZSH, CMD, PWSH (POWERSHELL is sent as pwsh)
    .cwd("/srv/app")
    .env("STAGE", "prod")            // values go in the body, never logged by the API or the desk
    .stdin("input text")
    .timeout("10m")                  // timeout_secs; the API holds every call under 15 minutes
    .check(true));                   // a non-zero exit is a CommandException
```

A command that ran is its `ExecResult` whatever its exit code (`getExit()`:
the command's code, 124 timed out). One that never ran (refused, a `cwd`
that is not there, an administrator refusal, ...) is the typed exception of
its kind, with the exec's exit code (254 refused).
`exec(deskId, List.of("ls", "-l"))` sends an argument vector instead of a
command line (each entry quoted for the shell; `Shell.NONE` runs the program
directly).

## Streams

`execStream` and `followJobLogs` return an `ExecStream` at once; it reads on
its own thread. Read it one way:

```java
try (ExecStream s = gd.execStream("123456789", "npm test")) {
    for (Chunk c : s) {                       // blocks for each chunk
        (c.getStream() == Chunk.Stream.STDOUT ? System.out : System.err).print(c.getText());
    }
    Exit exit = s.exit();                     // exit code, getResult() (ExecExit), getError()
}

StreamResult all = gd.followJobLogs("123456789", "build").collect();   // stdout, stderr, exit
Flow.Publisher<Chunk> p = gd.execStream("123456789", "x").asPublisher(); // java.util.concurrent.Flow
CompletableFuture<Exit> f = s.exitAsync();
```

A failure never throws from a stream: it ends it, and `exit().getError()`
says why (refused before it started: exit 254; the desk lost: 255,
`connection_lost`). `kill()` (or `close()` before the end) closes the request:
the server stops the command, or stops following the job; the exit code is
then 130. A job log ends with exit 0 and `getStderrTail()` like
`job build exited (exit 0)`. Over HTTP, stdin is given up front
(`ExecOptions.stdin`); there is no writing to a running command.

## Files

```java
gd.upload(Path.of("dist/app.tar.gz"), "123456789", "deploy/");   // a remote ending in / keeps the name
gd.download("123456789", "logs/app.log", Path.of("app.log"));    // written whole or not at all
byte[] b = gd.downloadBytes("123456789", "notes.txt");
gd.uploadText("hello", "123456789", "notes/a.txt");
gd.downloadTo("123456789", "big.iso", outputStream, null);       // streamed as it arrives
```

Single files of at most **256 MB** each way through the API (larger: `413
too_large`). Folders are refused (`UsageException`): copy them with
`gaiadesk-cli cp`. A download that ends early is a `ConnectionLostException`.
(The API moves whole files in one request; ack-windowed resumable copies are
the CLI's and the native library's, over a direct connection.)

## Jobs

```java
Job j = gd.runJob("123456789", "nightly", "make release", new JobOptions()
    .priority(Priority.LOW).cpu(50).mem("2G").keepAwake(true).cwd("/srv").shell(Shell.BASH).env("CI", "1"));
List<Job> all = gd.jobs("123456789");
String tail = gd.jobLogs("123456789", "nightly", new LogsOptions().tail(4096));
JobWaitResult w = gd.waitJob("123456789", "nightly", new WaitOptions().timeout("2h"));
if (!w.isTimedOut()) System.out.println("exited " + w.getJob().getExitCode());
gd.killJob("123456789", "nightly");
```

`waitJob` holds one request at most 870 seconds (the API's limit), so a
longer or no timeout asks again until the job ends. A held answer
(`GaiaDesk-Held: 1`) starts its 200 at once; a failure after that is in the
body, and the SDK throws it as its typed exception (its JSON keeps
`error.status`, the status it would have had). A job's own exit code is a
result, not an error.

## Tokens

```java
GaiaDesk owner = GaiaDesk.builder().apiKey(personSessionToken).build();
MintResult m = owner.createToken(new TokenSpec("123456789", "234567890")
    .name("ci").expires("24h").scopes(Scopes.EXEC, Scopes.CP, Scopes.JOBS).cwd("/srv/app").lowPriv(true));
String secret = m.getTokens().get(0).getSecret();              // shown once
owner.listTokens("123456789");
owner.revokeToken("123456789", "ci");
```

Defaults: 7 days, scopes `exec`, `cp`, `jobs`. If a later desk fails, the
exception's `getJson()` carries the tokens already minted (`tokens`): their
secrets are shown once. Scopes: `exec`, `shell`, `cp`, `forward`, `jobs`,
`screen`, and `admin` (see below), never implied.

## Desks, wake and reach

```java
DeskDetail d = gd.device("123456789");     // online, offlineReason, e2ePub, wake hints
ReachLog log = gd.reach("123456789", new ReachQuery().since(Instant.now().minus(Duration.ofDays(1))).limit(100));
WakeResult w = gd.wake("123456789", new WakeOptions().waitSeconds(30));   // woke, alreadyOnline, rang
```

An offline desk answers desk operations with `UnreachableException` (409,
kind `offline` and the reach log's reason); one nothing can wake, 409
`no_wake_path`.

## Audit, webhooks and support sessions

```java
for (AuditEvent e : gd.auditAll(new AuditQuery().desk("123456789").action("api.*").since(yesterday))) {
    System.out.println(e.getAction() + " " + e.getActor().getId());
}

WebhookCreated hook = gd.createWebhook(new WebhookSpec("https://example.com/hooks/gaiadesk",
    "desk.online", "desk.offline", "job.finished").description("ops"));
String secret = hook.getSecret();          // whsec_…, shown once

// In your endpoint: verify every delivery over the raw body (constant time, five-minute window).
boolean ok = Webhooks.verify(secret, request.getHeader("GaiaDesk-Signature"), rawBody);

SupportSessionCreated s = gd.createSupportSession(new SupportSessionSpec()
    .mode(SupportMode.COBROWSE).customer("name", "Ada").customer("plan", "pro").expiresIn(Duration.ofMinutes(30)));
String embedToken = s.getEmbedToken();     // hand to GaiaDeskEmbed.start({ embedToken }) on your page
List<SupportSession> queue = gd.supportSessions();
```

**Pagination.** The API pages by `limit` and time, without cursors.
`audit(q)` returns one page (default 100, at most 500); `auditAll(q)` walks
every page lazily, newest first: each next page ends where the last one did
(`until_ms`), and events already seen are dropped, so none is lost or
repeated at a page boundary. `reach` and `supportSessions` take a `limit`
(1000 and 200 at most).

## Running as administrator

```java
ExecResult r = gd.exec("123456789", "launchctl list", new ExecOptions().admin(true));
```

`admin(true)` runs the command as **administrator** (root on macOS and
Linux, SYSTEM on Windows) in the desk's privileged GaiaDesk process. It needs
both:

1. a desk token minted with the **`admin` scope** (`Scopes.ADMIN`, never
   implied; a confined token, `cwd` or `lowPriv`, cannot have it, and the SDK
   refuses that combination with a `UsageException`), and
2. the desk owner's **Admin access** switch, turned on only at the desk with
   the computer's administrator password. No API call can turn it on. In its
   default mode the person at the desk is asked each time.

A refusal is a `RefusedException` (exit 254) with `getReason()` one of
`admin_scope_missing`, `admin_not_enabled`, `admin_denied` (said no, no
answer, nobody signed in, a confined token) or `admin_unavailable` (no
privileged process, or a desk too old for it: never run as the user
instead); constants in `Reasons`. In a stream, the exit is 254 with that
reason in `getError()`. Windows Smart App Control / WDAC may still refuse an
unsigned program (`blocked_by_os_policy`).

## End-to-end encryption

On the hosted API, desk operations are **sealed** so GaiaDesk's servers
relay only ciphertext: the command, its env, stdin, file paths and bytes, and
all output and results are readable by the caller and the desk only. The
server still sees the credentials, the route (the operation and the desk, a
job name or token id in the path), `stream`/`follow`/`wake`, sizes, and how
the operation ended (an exit, or an error's kind and reason).

Before an operation the SDK reads the desk's X25519 key (`e2e_pub` from
`GET /desks/{id}`, cached 5 minutes) and seals the request to a fresh
ephemeral key: X25519, HKDF-SHA256, XChaCha20-Poly1305, every message bound to
the desk, the operation and its place in the stream. Results, streams, errors
and file bytes come back exactly as in the clear; a desk's error carries its
own message. The tests reproduce the protocol's fixed test vectors byte for
byte.

```java
GaiaDesk gd = GaiaDesk.builder().apiKey(key).deskToken(token)
    .e2e(E2eMode.REQUIRE)                          // AUTO (default) | REQUIRE | OFF
    .e2eKey("123456789", "B6N8vBQgk8i3…")          // optional: pin a desk's e2e_pub
    .onWarning(log::warn)
    .build();
```

- `AUTO`: sealed when the desk lists a key (or one is pinned); otherwise in
  the clear with a one-time warning per desk (`onWarning`, default
  `System.Logger` `net.gaiadesk` at WARNING), unless the desk **requires**
  it: then it is woken (`POST /desks/{id}/wake`) and asked again.
- `REQUIRE`: never in the clear. A desk without a key is woken and asked
  again; still none is an `E2eException` (a `RefusedException`, reason
  `e2e_unavailable`) and nothing is sent.
- `OFF`: plaintext.
- Pinned keys seal even while the desk lists none; a different key from the
  server is an `E2eException` (`e2e_key_mismatch`) and nothing is sent.
- A plaintext call refused `e2e_required` (409) is sealed and sent once more;
  a sealed one the desk could not open (`e2e_decrypt_failed`: its key
  rotated) is sealed to the key read again, once. Answers that do not open
  (altered, reordered, or a plaintext answer to a sealed call) are a
  `ProtocolException` (`e2e_decrypt_failed`, `e2e_malformed`,
  `e2e_unsealed_answer`).
- Reading a desk's key needs the API key's `desks:read` scope (waking it,
  `desks:write`); in `AUTO`, a key that cannot be read means plaintext with
  the warning.

**The crypto.** X25519 is the JDK's `XDH`, HKDF-SHA256 is built on the JDK's
`HmacSHA256`, and XChaCha20-Poly1305 is the JDK's IETF `ChaCha20-Poly1305`
(Java 11+) under an HChaCha20 subkey: only HChaCha20 (about 40 lines,
`net.gaiadesk.e2e.XChaCha20Poly1305`) is implemented here, and it is tested
against the XChaCha draft's (draft-irtf-cfrg-xchacha-03) HChaCha20 and AEAD
vectors as well as GaiaDesk's own. No Bouncy Castle needed. The primitives
are public in `net.gaiadesk.e2e` for tooling.

## Local and LAN

Desks serve the same `/v1` desk operations themselves, with the same routes,
results, errors, streams and held waits. Neither path leaves the desk or the
LAN, so neither is sealed. Hosted-only routes (`device`, `reach`, `wake`,
`audit`, webhooks, support) are refused with a `UsageException` before
anything is sent.

**`local`: code running on the desk itself** (Settings → GaiaDesk API → Local API):

```java
GaiaDesk gd = GaiaDesk.localBuilder().build();
gd.exec("123456789", "hostname");
```

- HTTP/1.1 over the Unix socket `$GAIADESK_API_DIR/api.sock` (when that is an
  absolute directory), else `~/.gaiadesk/api.sock`; on Windows the named pipe
  `$GAIADESK_API_PIPE`, else `\\.\pipe\gaiadesk-api-<user>`. `socketPath(...)`
  overrides either.
- Credentials: a `deskToken` (agent token) as `X-GaiaDesk-Desk-Token`;
  without one, the desk's local admin token (`gdlocal_…`, read from
  `api-token` beside the socket on each request, or given as `token(...)`).
- No socket, pipe or token is an `UnreachableException`, reason
  `local_api_unavailable`.
- **JDK gating:** Unix domain sockets need **Java 16+** (reached by
  reflection, so the SDK stays Java 11 bytecode); on Java 11-15 on macOS and
  Linux `localBuilder().build()` throws a `UsageException` saying so
  (`LocalApi.isSupported()` checks first). The Windows named pipe works on
  any Java.

**`lan`: a desk's opt-in LAN gateway**, its self-signed certificate pinned by
the SHA-256 fingerprint the desk shows in Settings:

```java
GaiaDesk gd = GaiaDesk.lanBuilder("https://gaiadesk-123456789.local:7443/v1", "ab:cd:…")  // colons and case optional
    .deskToken(System.getenv("GAIADESK_DESK_TOKEN"))   // required: agent tokens only on the LAN
    .build();
```

- `https://` only. The certificate's SHA-256 is checked **during the TLS
  handshake, before any byte of the request** is sent; the chain and host
  name are not (self-signed). A mismatch is a `FingerprintMismatchException`
  (an `UnreachableException`, reason `fingerprint_mismatch`, with
  `getExpected()` and `getActual()`): it may not be your desk.
- Without a desk token, a `UsageException` before anything is sent.

Helpers: `Lan.normalizeFingerprint`, `LocalApi.pipeName`, `pipeUser`,
`socketPath`, `tokenPath`, `apiDir`.

## Errors

Every failure is a `GaiaDeskException` (unchecked). The class follows the
API's error envelope `{"error": {kind, message, reason?, desk?, request_id}}`:

| Class | `kind` in the envelope | HTTP |
|---|---|---|
| `UsageException` | `usage` (also an operation a transport does not serve) | 400 |
| `RefusedException` (`E2eException` is one) | `refused` | 401, 403, 429 |
| `UnreachableException` (`FingerprintMismatchException` is one) | `unreachable`; no connection | 404, 409, 503, 504 |
| `ConnectionLostException` | `connection_lost` | 502 |
| `OperationFailedException` | `failed` | 422 |
| `ProtocolException` | `protocol`; an answer that is not the contract | 409, 502 |
| `CommandException` | `exec` with `check(true)` and a non-zero exit | |

Getters: `getKind()` (the finest SDK kind: the reason when it is one of
`offline`, `unknown_desk`, `not_online`, `network`, `not_signed_in`,
`timeout`, `local`, ...), `getReason()`, `getDesk()`, `getStatus()`,
`getRequestId()` (quote it to support), `getRetryAfter()` (a 429's seconds),
`getExitCode()` (what `gaiadesk-cli` would exit with: 254 refused, 1 failed,
130 interrupted, 255 the rest), `getJson()`, `getArgv()`
(`["POST /desks/123456789/exec"]`). No connection is an
`UnreachableException` with kind `network`; no answer within the request
timeout, kind `timeout`; a cancelled call, a `GaiaDeskException` of kind
`interrupted` (exit 130).

```java
try {
    gd.exec(desk, "deploy");
} catch (RefusedException e) {
    if (Reasons.RATE_LIMITED.equals(e.getReason())) sleep(e.getRetryAfter());
    else throw e;
} catch (UnreachableException e) {
    System.err.println(e.getKind() + " " + e.getMessage() + " (" + e.getRequestId() + ")");
}
```

## Retries, timeouts and cancellation

**Retries** (`RetryPolicy`, default 2 retries, 500 ms base, 30 s at most,
exponential with full jitter) happen only when trying again cannot do the
operation twice: a connection that could not be opened; a `GET` whose
connection closed or reset before any answer; 429 `rate_limited`,
`desk_busy` or `idempotency_key_in_flight` (waiting `Retry-After`; one longer
than the maximum is thrown instead); 502/503/504 answering a `GET`. Commands,
job starts, uploads and token changes are never re-sent once the desk may
have run them; streams are retried only before they start. A retried sealed
operation is sealed afresh. `.retry(RetryPolicy.none())` turns them off.

**Idempotency.** POSTs take `idempotencyKey("…")`: a retry with the same key
and request within 24 hours gets the first answer again.

**Timeouts** (`Timeouts`, `.timeouts(...)` on every builder: hosted API,
local and LAN) make a server or proxy that stops answering an error, never a
hang:

- `responseTimeout` (default 16 minutes, above the API's 15-minute call
  limit): the longest wait for an answer to begin, sending the request
  included. Exceeded: `UnreachableException`, kind `timeout`. Per call,
  `requestTimeout(Duration)` replaces it; `.requestTimeout(d)` on the builder
  is `.timeouts(Timeouts.defaults().responseTimeout(d))`.
- `idleTimeout` (default 90 s; streams and held waits send a keep-alive every
  15 s): the longest silence while reading a body (JSON, an error, a
  download, an event stream). It bounds each read, not the whole body.
  Exceeded mid-answer: `ConnectionLostException`, kind `timeout` (a stream
  ends with that error in its `Exit`: kind `connection_lost`, reason
  `timeout`, exit code 255; a download to a file leaves no file).
- `null` is no limit (`Timeouts.none()` turns both off); zero or negative is
  a `UsageException`. `connectTimeout` (default 30 s) bounds connecting.
- A connection closed or reset before any answer is an
  `UnreachableException` (kind `network`) at once; a `GET` is then retried as
  the retry policy allows, nothing else is. The JDK's `HttpClient` itself may
  re-send a `GET` or `HEAD` (never another method) when the connection it
  used was closed before any answer (once on Java 11 to 17, up to
  `jdk.httpclient.redirects.retrylimit`, 5, on later Javas), so `exec`,
  uploads, jobs, tokens and wakes go at most once, unless the application
  sets the system property `jdk.httpclient.enableAllMethodRetry` (the SDK
  warns when it is set).

**Cancellation.** `new Cancellation()` given to a call's options
(`.cancellation(c)`), then `c.cancel()` from any thread: the request is
closed and the call throws kind `interrupted`. An interrupted thread
interrupts the call it is in; `cancel(true)` on an async future cancels its
call; `ExecStream.kill()` stops a stream.

## Async

`gd.async()` has the same operations returning `CompletableFuture`s, run on
the client's executor (default: a shared pool of daemon threads;
`.executor(...)` to supply your own):

```java
CompletableFuture<ExecResult> f = gd.async().exec("123456789", "uptime", null);
f.thenAccept(r -> System.out.println(r.getStdout()));
f.cancel(true);   // closes the request
```

A failure completes the future exceptionally with the same
`GaiaDeskException` (`join()` wraps it in a `CompletionException`).

## Kotlin

The SDK is written in Java for Java and Kotlin alike: every public type is
annotated with [JSpecify](https://jspecify.dev) (`@NullMarked`, `@Nullable`),
which Kotlin reads as real nullability (`String`, `Int?`), getters read as
properties, and there are no checked exceptions. There is no separate Kotlin
module: coroutines need only the standard kotlinx adapters.

```kotlin
import kotlinx.coroutines.future.await          // kotlinx-coroutines-jdk8
import kotlinx.coroutines.jdk9.asFlow           // kotlinx-coroutines-jdk9
import net.gaiadesk.*

val gd = GaiaDesk.builder()
    .apiKey(System.getenv("GAIADESK_API_KEY"))
    .deskToken(System.getenv("GAIADESK_DESK_TOKEN"))
    .build()

suspend fun hostname(desk: String): String =
    gd.async().exec(desk, "hostname", ExecOptions().timeoutSeconds(30.0)).await().stdout.trim()

suspend fun test(desk: String) {
    gd.execStream(desk, listOf("npm", "test")).use { s ->
        s.asPublisher().asFlow().collect { print(it.text) }
        println("exit ${s.exitAsync().await().exitCode}")
    }
}

try {
    gd.exec("123456789", "deploy", ExecOptions().admin(true))
} catch (e: RefusedException) {
    when (e.reason) {
        Reasons.ADMIN_NOT_ENABLED -> println("turn on Admin access at the desk")
        else -> throw e
    }
}
```

Cancelling the coroutine that awaits a future cancels the future, and with it
the call.

## Dependencies

| Dependency | Why | Licence |
|---|---|---|
| `com.fasterxml.jackson.core:jackson-databind` 2.17.2 (+ `jackson-core`, `jackson-annotations`) | JSON: results, error envelopes, sealed frames | Apache-2.0 |
| `org.jspecify:jspecify` 1.0.0 | nullability annotations Kotlin reads | Apache-2.0 |

Jackson is the JSON library Java and Kotlin projects already carry, and the
results are typed objects (a hand-written parser would have meant a second,
less tested JSON implementation in a security-relevant path). Everything else
is the JDK: `java.net.http` (HTTP/1.1 and HTTP/2, TLS), `XDH`,
`ChaCha20-Poly1305`, `HmacSHA256`. Tests: JUnit 5 (EPL-2.0, test only).

## Not available here

As in the TypeScript and Python SDKs' API transport, what needs a direct
connection to a desk is the CLI's and the native library's, not the API's:
`shell`/interactive terminals, port forwarding, the screen (agent sessions,
MCP), `measure`, Mesh, `disconnect`, `devices --probe`, folder and resumable
copies, writing stdin to a running command, revoking every token of a desk at
once or through the account, and a desk's own agent audit log (the hosted
`/audit` is the account's trail). Use
[gaiadesk-cli](https://github.com/Gaia-Desk/gaiadesk-cli) or the TypeScript
or Python SDK's CLI and native transports for those.

## Development

```sh
./gradlew build                           # compile (Java 11 bytecode, -Werror), tests, javadoc, examples
./gradlew test -PtestJavaHome=/path/to/jdk-11   # run the tests on another JDK
```

Gradle runs on JDK 17+; the library is compiled with `--release 11`. CI
(`.github/workflows/ci.yml`) builds on JDK 21 and runs the tests on JDK 11,
17 and 21 on Linux, macOS and Windows. The tests run against an in-process
mock of the API and its desks (`MockApi`): every route, plaintext and sealed,
streams split anywhere, held waits, error envelopes, retries, timeouts and
cancellation, plus the local transport over a real Unix socket (Java 16+) and
the LAN transport over real pinned TLS (a keytool-made certificate).
`src/test/resources/e2e-vectors.json` is the protocol's
`protocol/src/e2e/vectors.json`, unchanged.

### Releasing (Maven Central)

`build.gradle.kts` configures `maven-publish` (sources and javadoc jars, the
POM Central requires) and `signing` (in-memory PGP key). To publish through
the Central Portal's OSSRH-compatible staging endpoint:

```sh
./gradlew publishMavenPublicationToCentralRepository \
  -PcentralUsername=… -PcentralPassword=… \
  -PsigningKey="$(cat private-key.asc)" -PsigningPassword=…
```

then release the staged deployment in the Central Portal.
`./gradlew publishMavenPublicationToStagingRepository` writes what would be
published to `build/staging-repo` without signing or uploading.
