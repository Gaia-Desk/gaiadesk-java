package net.gaiadesk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.gaiadesk.internal.Check;
import net.gaiadesk.internal.E2eAnswers;
import net.gaiadesk.internal.Errors;
import net.gaiadesk.internal.Json;
import net.gaiadesk.internal.Urls;
import net.gaiadesk.model.CopyResult;
import net.gaiadesk.model.ExecResult;
import net.gaiadesk.model.Job;
import net.gaiadesk.model.JobWaitResult;
import net.gaiadesk.model.MintResult;
import net.gaiadesk.model.DeskStats;
import net.gaiadesk.model.TokenInfo;
import net.gaiadesk.model.TokenRevokeResult;
import org.jspecify.annotations.Nullable;

/** The desk operations, over whichever /v1 API the core speaks to. */
final class DeskOps {
    private final Core core;

    DeskOps(Core core) {
        this.core = core;
    }

    static String deskPath(String desk) {
        return "/desks/" + Urls.encode(desk);
    }

    private Core.Req req(String method, String path, Calls.Scope s, @Nullable CallOptions<?> o) {
        return req(method, path, s.cancel, o);
    }

    static Core.Req req(String method, String path, Cancellation cancel, @Nullable CallOptions<?> o) {
        Core.Req r = new Core.Req(method, path, cancel);
        if (o != null) {
            r.deskToken = o.deskToken;
            r.wake = o.wake;
            r.timeout = o.requestTimeout;
            r.idempotencyKey = o.idempotencyKey;
        }
        return r;
    }

    private static ObjectNode opRequest(String op) {
        ObjectNode o = Json.object();
        o.put("op", op);
        return o;
    }

    // ───────────────────────────── exec ─────────────────────────────

    static ObjectNode execSpec(Object command, ExecOptions o) {
        ObjectNode spec = Json.object();
        if (command instanceof String) {
            String c = (String) command;
            if (c.trim().isEmpty()) throw Check.usage("exec needs a command");
            spec.put("command", c);
        } else {
            @SuppressWarnings("unchecked")
            List<String> argv = (List<String>) command;
            if (argv.isEmpty() || (argv.size() == 1 && argv.get(0).trim().isEmpty())) throw Check.usage("exec needs a command");
            ArrayNode a = spec.putArray("argv");
            for (String x : argv) a.add(x);
        }
        if (o.shell != null) spec.put("shell", o.shell.wire());
        if (o.env != null) {
            ObjectNode env = spec.putObject("env");
            for (Map.Entry<String, String> e : o.env.entrySet()) env.put(e.getKey(), e.getValue());
        }
        if (o.cwd != null) spec.put("cwd", o.cwd);
        if (o.timeoutSecs != null) spec.put("timeout_secs", o.timeoutSecs);
        if (o.stdin != null) spec.put("stdin", o.stdin);
        if (o.admin) spec.put("admin", true);
        return spec;
    }

    ExecResult exec(String deskId, Object command, @Nullable ExecOptions options) {
        ExecOptions o = options != null ? options : new ExecOptions();
        String desk = Check.desk(deskId);
        ObjectNode spec = execSpec(command, o);
        String path = deskPath(desk) + "/exec";
        String op = "POST " + path;
        JsonNode json;
        try (Calls.Scope s = Calls.scope(o)) {
            Core.Req r = req("POST", path, s, o);
            r.json = spec;
            ObjectNode sealed = opRequest("exec");
            sealed.set("spec", spec);
            json = core.json(r.sealedAs(desk, "exec", sealed));
        }
        if (!json.isObject() || !json.path("exit").isNumber()) throw Errors.protocol("the GaiaDesk API answered exec without a result", op, json, null);
        ObjectNode norm = ((ObjectNode) json).deepCopy();
        ObjectNode error = Errors.execError(json.get("error"));
        if (error != null) norm.set("error", error);
        else norm.putNull("error");
        ExecResult result = Json.convert(norm, ExecResult.class);
        boolean noCode = !json.path("remote_code").isNumber();
        if (noCode && !result.isTimedOut() && error != null) {
            // It never ran (unreachable, refused, a cwd that is not there, admin refused, ...): the error, typed by its kind.
            String kind = error.get("kind").asText();
            String reason = Json.text(error, "reason");
            String msg = error.get("message").asText();
            String errDesk = Json.text(error, "desk");
            throw Errors.forKind(kind, msg.isEmpty() ? "the command did not run" : msg,
                    ErrorDetails.builder().kind(Errors.sdkKind(kind, reason)).exitCode(result.getExit()).argv(List.of(op)).json(json)
                            .reason(reason).desk(errDesk != null ? errDesk : result.getDesk()).build());
        }
        if (o.check && result.getExit() != 0) {
            String why = result.isTimedOut() ? "timed out" : "exited " + result.getExit();
            throw new CommandException("command on desk " + result.getDesk() + " " + why, result,
                    ErrorDetails.builder().kind("failed").exitCode(result.getExit()).argv(List.of(op)).json(json).desk(result.getDesk()).build());
        }
        return result;
    }

    ExecStream execStream(String deskId, Object command, @Nullable ExecOptions options) {
        ExecOptions o = options != null ? options : new ExecOptions();
        String desk = Check.desk(deskId);
        ObjectNode spec = execSpec(command, o);
        String path = deskPath(desk) + "/exec";
        return new ExecStream("POST " + path, false, "", cancel -> {
            Core.Req r = req("POST", path, cancel, o);
            r.json = spec;
            r.query("stream", 1);
            r.accept = "text/event-stream";
            ObjectNode sealed = opRequest("exec");
            sealed.set("spec", spec);
            sealed.put("stream", true);
            Core.Res res = core.request(r.sealedAs(desk, "exec", sealed));
            return new ExecStream.Start(res, res.seal == null ? null : new E2eAnswers.SseUnsealer(res.seal, false, res.op));
        }, core.executor, Calls.outer(o));
    }

    // ───────────────────────────── files ─────────────────────────────

    private static String basename(String p) {
        String[] parts = p.split("[\\\\/]+");
        for (int i = parts.length - 1; i >= 0; i--) if (!parts[i].isEmpty()) return parts[i];
        return "";
    }

    private static GaiaDeskException local(String message, String op, @Nullable Throwable cause) {
        return new GaiaDeskException(message, ErrorDetails.builder().kind("local").reason("local").argv(List.of(op)).build(), cause);
    }

    CopyResult upload(Path local, String deskId, String remote, @Nullable RequestOptions o) {
        String desk = Check.desk(deskId);
        if (remote == null) throw Check.usage("a remote path is required");
        if (Files.isDirectory(local)) {
            throw core.notServed("uploading the folder " + local, "the API copies single files; copy folders through the CLI or native transport");
        }
        long size;
        try {
            size = Files.size(local);
        } catch (IOException e) {
            throw local("cannot read " + local + ": " + e.getMessage(), "upload", e);
        }
        if (size > GaiaDesk.API_FILE_LIMIT) {
            throw Check.usage(local + " is " + size + " bytes; the API takes files up to 256 MB (copy larger ones through the CLI or native transport)", "upload");
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(local);
        } catch (IOException e) {
            throw local("cannot read " + local + ": " + e.getMessage(), "upload", e);
        }
        Path name = local.getFileName();
        String target = remote.isEmpty() || remote.endsWith("/") || remote.endsWith("\\") ? remote + (name == null ? "" : name.toString()) : remote;
        return uploadBytes(bytes, desk, target, o);
    }

    CopyResult uploadBytes(byte[] bytes, String deskId, String remote, @Nullable RequestOptions o) {
        String desk = Check.desk(deskId);
        if (remote == null || remote.isEmpty()) throw Check.usage("a remote path is required");
        if (bytes.length > GaiaDesk.API_FILE_LIMIT) throw Check.usage("the API takes files up to 256 MB", "upload");
        String path = deskPath(desk) + "/files";
        JsonNode json;
        try (Calls.Scope s = Calls.scope(o)) {
            Core.Req r = req("PUT", path, s, o).query("path", remote);
            r.bytes = bytes;
            ObjectNode sealed = opRequest("file_put");
            sealed.put("path", remote);
            sealed.put("size", bytes.length);
            json = core.json(r.sealedAs(desk, "file_put", sealed));
        }
        CopyResult res = convert(json, CopyResult.class, "PUT " + path);
        if (!res.getFailed().isEmpty()) {
            throw new OperationFailedException(res.getFailed().size() + " file(s) failed to copy",
                    ErrorDetails.builder().kind("failed").exitCode(1).argv(List.of("PUT " + path)).json(json).desk(desk).build());
        }
        return res;
    }

    /** {@code GET /desks/{id}/files?path=} into {@code out}: the bytes written. */
    long downloadTo(String deskId, String remote, OutputStream out, @Nullable RequestOptions o) {
        String desk = Check.desk(deskId);
        if (remote == null || remote.isEmpty()) throw Check.usage("a remote path is required");
        String path = deskPath(desk) + "/files";
        String op = "GET " + path;
        try (Calls.Scope s = Calls.scope(o)) {
            Core.Req r = req("GET", path, s, o).query("path", remote);
            r.accept = "application/octet-stream";
            ObjectNode sealed = opRequest("file_get");
            sealed.put("path", remote);
            try (Core.Res res = core.request(r.sealedAs(desk, "file_get", sealed))) {
                Cancellation.Registration reg = s.cancel.onCancel(res::close);
                try {
                    if (res.seal != null) return E2eAnswers.openDownload(res.http.body(), res.seal, op, out);
                    long total = 0;
                    InputStream in = res.http.body();
                    byte[] buf = new byte[65536];
                    for (int n; (n = in.read(buf)) >= 0; total += n) out.write(buf, 0, n);
                    return total;
                } catch (IOException e) {
                    if (s.cancel.isCancelled()) throw Errors.interrupted(op);
                    throw new ConnectionLostException("the download of " + remote + " broke: " + e.getMessage(),
                            ErrorDetails.builder().kind("connection_lost").exitCode(255).argv(List.of(op)).desk(desk).build(), e);
                } finally {
                    reg.close();
                }
            }
        }
    }

    byte[] downloadBytes(String deskId, String remote, @Nullable RequestOptions o) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        downloadTo(deskId, remote, out, o);
        return out.toByteArray();
    }

    CopyResult download(String deskId, String remote, Path local, @Nullable RequestOptions o) {
        String desk = Check.desk(deskId);
        long started = System.currentTimeMillis();
        String l = local.toString();
        boolean isDir = l.endsWith("/") || l.endsWith("\\") || Files.isDirectory(local);
        Path dest = isDir ? local.resolve(basename(remote)) : local;
        Path dir = dest.toAbsolutePath().getParent();
        Path tmp;
        try {
            tmp = Files.createTempFile(dir, ".gaiadesk-", ".part");
        } catch (IOException e) {
            throw local("cannot write " + dest + ": " + e.getMessage(), "download", e);
        }
        long bytes;
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                bytes = downloadTo(desk, remote, out, o);
            } catch (IOException e) {
                throw local("cannot write " + dest + ": " + e.getMessage(), "download", e);
            }
            try {
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw local("cannot write " + dest + ": " + e.getMessage(), "download", e);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // a leftover temporary file
            }
        }
        ObjectNode r = Json.object();
        r.put("direction", "download");
        r.put("desk", desk);
        r.put("destination", dest.toString());
        r.put("files", 1);
        r.put("dirs", 0);
        r.put("bytes", bytes);
        r.put("resumed_bytes", 0);
        r.putArray("failed");
        r.put("seconds", (System.currentTimeMillis() - started) / 1000.0);
        return Json.convert(r, CopyResult.class);
    }

    // ───────────────────────────── jobs ─────────────────────────────

    Job runJob(String deskId, String name, Object command, @Nullable JobOptions options) {
        JobOptions o = options != null ? options : new JobOptions();
        String desk = Check.desk(deskId);
        Check.jobName(name);
        ObjectNode spec = Json.object();
        spec.put("name", name);
        ArrayNode cmd = spec.putArray("command");
        if (command instanceof String) {
            if (((String) command).trim().isEmpty()) throw Check.usage("run needs a command");
            cmd.add((String) command);
        } else {
            @SuppressWarnings("unchecked")
            List<String> argv = (List<String>) command;
            if (argv.isEmpty() || (argv.size() == 1 && argv.get(0).trim().isEmpty())) throw Check.usage("run needs a command");
            for (String a : argv) cmd.add(a);
        }
        ObjectNode limits = spec.putObject("limits");
        if (o.priority != null) limits.put("priority", o.priority.wire());
        if (o.cpu != null) limits.put("cpu_percent", o.cpu);
        if (o.memMb != null) limits.put("mem_mb", o.memMb);
        if (o.keepAwake != null) limits.put("keep_awake", o.keepAwake);
        if (o.cwd != null) spec.put("cwd", o.cwd);
        if (o.shell != null) spec.put("shell", o.shell.wire());
        if (o.env != null) {
            ObjectNode env = spec.putObject("env");
            for (Map.Entry<String, String> e : o.env.entrySet()) env.put(e.getKey(), e.getValue());
        }
        String path = deskPath(desk) + "/jobs";
        try (Calls.Scope s = Calls.scope(o)) {
            Core.Req r = req("POST", path, s, o);
            r.json = spec;
            ObjectNode sealed = opRequest("job_start");
            sealed.set("spec", spec);
            return convert(core.json(r.sealedAs(desk, "job_start", sealed)), Job.class, "POST " + path);
        }
    }

    JobWaitResult waitJob(String deskId, String name, @Nullable WaitOptions options) {
        WaitOptions o = options != null ? options : new WaitOptions();
        String desk = Check.desk(deskId);
        Check.jobName(name);
        String path = deskPath(desk) + "/jobs/" + Urls.encode(name) + "/wait";
        String op = "GET " + path;
        Long total = o.timeoutSecs;
        long started = System.currentTimeMillis();
        try (Calls.Scope s = Calls.scope(o)) {
            for (;;) {
                double elapsed = (System.currentTimeMillis() - started) / 1000.0;
                double left = total == null ? GaiaDesk.API_WAIT_MAX : Math.max(0, total - elapsed);
                long timeout = Math.min(GaiaDesk.API_WAIT_MAX, (long) Math.ceil(left));
                Core.Req r = req("GET", path, s, o).query("timeout", timeout);
                ObjectNode sealed = opRequest("job_wait");
                sealed.put("name", name);
                sealed.put("timeout_ms", timeout * 1000);
                JsonNode json = core.json(r.sealedAs(desk, "job_wait", sealed));
                Errors.Envelope env = Errors.envelope(json);
                // A held wait that failed after its 200 began: the envelope, in the body.
                if (env != null) throw Errors.fromEnvelope(env, "the wait failed", ErrorDetails.builder().exitCode(Errors.deskOpExit(env.kind)).argv(List.of(op)).json(json));
                if (!json.isObject() || !json.path("job").isObject() || !json.path("timed_out").isBoolean()) {
                    throw Errors.protocol("the GaiaDesk API answered a wait without a job", op, json, null);
                }
                JobWaitResult res = convert(json, JobWaitResult.class, op);
                boolean over = total != null && (System.currentTimeMillis() - started) / 1000.0 >= total;
                if (!res.isTimedOut() || over || (total != null && total == 0)) return res;
            }
        }
    }

    List<Job> jobs(String deskId, @Nullable RequestOptions o) {
        String desk = Check.desk(deskId);
        String path = deskPath(desk) + "/jobs";
        try (Calls.Scope s = Calls.scope(o)) {
            JsonNode json = core.json(req("GET", path, s, o).sealedAs(desk, "job_list", opRequest("job_list")));
            return listOf(json, "jobs", Job.class, "GET " + path);
        }
    }

    Job killJob(String deskId, String name, @Nullable RequestOptions o) {
        String desk = Check.desk(deskId);
        Check.jobName(name);
        String path = deskPath(desk) + "/jobs/" + Urls.encode(name);
        try (Calls.Scope s = Calls.scope(o)) {
            ObjectNode sealed = opRequest("job_kill");
            sealed.put("name", name);
            return convert(core.json(req("DELETE", path, s, o).sealedAs(desk, "job_kill", sealed)), Job.class, "DELETE " + path);
        }
    }

    String jobLogs(String deskId, String name, @Nullable LogsOptions o) {
        String desk = Check.desk(deskId);
        Check.jobName(name);
        String path = deskPath(desk) + "/jobs/" + Urls.encode(name) + "/logs";
        Long tail = o == null ? null : o.tail;
        try (Calls.Scope s = Calls.scope(o)) {
            ObjectNode sealed = opRequest("job_logs");
            sealed.put("name", name);
            if (tail != null) sealed.put("tail", tail);
            JsonNode json = core.json(req("GET", path, s, o).query("tail", tail).sealedAs(desk, "job_logs", sealed));
            return json.path("output").isTextual() ? json.get("output").asText() : "";
        }
    }

    ExecStream followJobLogs(String deskId, String name, @Nullable LogsOptions o) {
        String desk = Check.desk(deskId);
        Check.jobName(name);
        String path = deskPath(desk) + "/jobs/" + Urls.encode(name) + "/logs";
        Long tail = o == null ? null : o.tail;
        return new ExecStream("GET " + path, true, name, cancel -> {
            ObjectNode sealed = opRequest("job_logs");
            sealed.put("name", name);
            if (tail != null) sealed.put("tail", tail);
            sealed.put("follow", true);
            Core.Req r = req("GET", path, cancel, o).query("follow", 1).query("tail", tail);
            r.accept = "text/event-stream";
            Core.Res res = core.request(r.sealedAs(desk, "job_logs", sealed));
            return new ExecStream.Start(res, res.seal == null ? null : new E2eAnswers.SseUnsealer(res.seal, true, res.op));
        }, core.executor, Calls.outer(o));
    }

    DeskStats stats(String deskId, @Nullable RequestOptions o) {
        String desk = Check.desk(deskId);
        String path = deskPath(desk) + "/stats";
        try (Calls.Scope s = Calls.scope(o)) {
            return convert(core.json(req("GET", path, s, o).sealedAs(desk, "stats", opRequest("stats"))), DeskStats.class, "GET " + path);
        }
    }

    // ───────────────────────────── tokens ─────────────────────────────

    MintResult createToken(TokenSpec spec) {
        if (spec.desks.isEmpty()) throw Check.usage("at least one desk is required");
        if (spec.name == null) throw Check.usage("createToken needs a name over the " + core.transport.label() + " transport");
        List<String> scopes = spec.scopes != null ? spec.scopes : Scopes.DEFAULT;
        if (scopes.contains(Scopes.ADMIN) && (spec.cwd != null || spec.lowPriv)) {
            throw Check.usage("a token with the admin scope cannot be confined (cwd, lowPriv): a confined token never runs as administrator");
        }
        ObjectNode body = Json.object();
        body.put("name", spec.name);
        body.put("expires_secs", spec.expiresSecs);
        ArrayNode sc = body.putArray("scopes");
        for (String x : scopes) sc.add(x);
        if (spec.cwd != null) body.put("cwd", spec.cwd);
        if (spec.lowPriv) body.put("low_priv", true);
        ArrayNode minted = Json.array();
        try (Calls.Scope s = Calls.scope(spec)) {
            for (String desk : spec.desks) {
                String path = deskPath(desk) + "/tokens";
                try {
                    Core.Req r = req("POST", path, s, spec);
                    r.json = body;
                    ObjectNode sealed = opRequest("token_mint");
                    sealed.set("spec", body);
                    JsonNode json = core.json(r.sealedAs(desk, "token_mint", sealed));
                    if (!json.path("tokens").isArray()) throw Errors.protocol("the desk minted no tokens list", "token_mint", json, null);
                    for (JsonNode t : json.get("tokens")) minted.add(t);
                } catch (GaiaDeskException e) {
                    if (minted.size() == 0) throw e;
                    // The tokens already minted, in the error's JSON: their secrets are shown once.
                    ObjectNode j = e.getJson() != null && e.getJson().isObject() ? ((ObjectNode) e.getJson()).deepCopy() : Json.object();
                    j.set("tokens", minted);
                    throw Errors.withJson(e, j);
                }
            }
        }
        ObjectNode result = Json.object();
        result.set("tokens", minted);
        return Json.convert(result, MintResult.class);
    }

    List<TokenInfo> listTokens(String deskId, @Nullable RequestOptions o) {
        String desk = Check.desk(deskId);
        String path = deskPath(desk) + "/tokens";
        try (Calls.Scope s = Calls.scope(o)) {
            return listOf(core.json(req("GET", path, s, o).sealedAs(desk, "token_list", opRequest("token_list"))), "tokens", TokenInfo.class, "GET " + path);
        }
    }

    TokenRevokeResult revokeToken(String deskId, String token, @Nullable RequestOptions o) {
        String desk = Check.desk(deskId);
        if (token == null || token.isEmpty() || token.startsWith("-")) throw Check.usage("a token name or id is required");
        String path = deskPath(desk) + "/tokens/" + Urls.encode(token);
        try (Calls.Scope s = Calls.scope(o)) {
            ObjectNode sealed = opRequest("token_revoke");
            sealed.put("token", token);
            return convert(core.json(req("DELETE", path, s, o).sealedAs(desk, "token_revoke", sealed)), TokenRevokeResult.class, "DELETE " + path);
        }
    }

    // ───────────────────────────── results ─────────────────────────────

    static <T> T convert(JsonNode json, Class<T> type, String op) {
        if (!json.isObject()) throw Errors.protocol("the GaiaDesk API answered " + op + " with something that is not a " + type.getSimpleName(), op, json, null);
        try {
            return Json.convert(json, type);
        } catch (IllegalArgumentException e) {
            throw Errors.protocol("the GaiaDesk API answered " + op + " with something that is not a " + type.getSimpleName() + ": " + e.getMessage(), op, json, null);
        }
    }

    static <T> List<T> listOf(JsonNode json, String key, Class<T> type, String op) {
        if (!json.isObject() || !json.path(key).isArray()) throw Errors.protocol("the GaiaDesk API answered " + op + " with no " + key + " list", op, json, null);
        List<T> out = new ArrayList<>();
        for (JsonNode n : json.get(key)) out.add(convert(n, type, op));
        return out;
    }
}
