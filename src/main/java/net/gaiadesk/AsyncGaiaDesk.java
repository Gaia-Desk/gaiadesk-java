package net.gaiadesk;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.gaiadesk.model.AuditEvent;
import net.gaiadesk.model.CopyResult;
import net.gaiadesk.model.DeskDetail;
import net.gaiadesk.model.DeskStats;
import net.gaiadesk.model.DeviceList;
import net.gaiadesk.model.ExecResult;
import net.gaiadesk.model.Job;
import net.gaiadesk.model.JobWaitResult;
import net.gaiadesk.model.MintResult;
import net.gaiadesk.model.ReachLog;
import net.gaiadesk.model.SupportSession;
import net.gaiadesk.model.SupportSessionCreated;
import net.gaiadesk.model.TokenInfo;
import net.gaiadesk.model.TokenRevokeResult;
import net.gaiadesk.model.WakeResult;
import net.gaiadesk.model.Webhook;
import net.gaiadesk.model.WebhookCreated;
import org.jspecify.annotations.Nullable;

/**
 * {@link GaiaDesk}'s operations as {@link CompletableFuture}s ({@code gd.async()}), run on the client's executor.
 * A failure completes the future exceptionally with the same {@link GaiaDeskException} the blocking call throws
 * ({@code join()} wraps it in a {@code CompletionException}; Kotlin's {@code await()} does not).
 * {@code cancel(true)} on a returned future cancels its call: the request is closed. Options may be null.
 * Streams ({@code execStream}, {@code followJobLogs}) never block: use them from the client itself
 * ({@link ExecStream#exitAsync()}).
 */
public final class AsyncGaiaDesk {
    private final GaiaDesk gd;
    private final Executor executor;

    AsyncGaiaDesk(GaiaDesk gd, Executor executor) {
        this.gd = gd;
        this.executor = executor;
    }

    /** {@link GaiaDesk#devices}: {@code GET /desks}. */
    public CompletableFuture<DeviceList> devices(@Nullable RequestOptions o) {
        return Calls.async(executor, () -> gd.devices(o));
    }

    /** {@link GaiaDesk#device}: {@code GET /desks/{id}}. */
    public CompletableFuture<DeskDetail> device(String deskId, @Nullable RequestOptions o) {
        return Calls.async(executor, () -> gd.device(deskId, o));
    }

    /** {@link GaiaDesk#reach}: {@code GET /desks/{id}/reach}. */
    public CompletableFuture<ReachLog> reach(String deskId, @Nullable ReachQuery q) {
        return Calls.async(executor, () -> gd.reach(deskId, q));
    }

    /** {@link GaiaDesk#wake}: {@code POST /desks/{id}/wake}. */
    public CompletableFuture<WakeResult> wake(String deskId, @Nullable WakeOptions o) {
        return Calls.async(executor, () -> gd.wake(deskId, o));
    }

    /** {@link GaiaDesk#exec}: {@code POST /desks/{id}/exec}. */
    public CompletableFuture<ExecResult> exec(String deskId, String command, @Nullable ExecOptions o) {
        return Calls.async(executor, () -> gd.exec(deskId, command, o));
    }

    /** {@link GaiaDesk#exec}: {@code POST /desks/{id}/exec} with an argument vector. */
    public CompletableFuture<ExecResult> exec(String deskId, List<String> argv, @Nullable ExecOptions o) {
        return Calls.async(executor, () -> gd.exec(deskId, argv, o));
    }

    /** {@link GaiaDesk#upload}: {@code PUT /desks/{id}/files}. */
    public CompletableFuture<CopyResult> upload(Path local, String deskId, String remote, @Nullable RequestOptions o) {
        return Calls.async(executor, () -> gd.upload(local, deskId, remote, o));
    }

    /** {@link GaiaDesk#uploadBytes}: {@code PUT /desks/{id}/files} with bytes in memory. */
    public CompletableFuture<CopyResult> uploadBytes(byte[] data, String deskId, String remote, @Nullable RequestOptions o) {
        return Calls.async(executor, () -> gd.uploadBytes(data, deskId, remote, o));
    }

    /** {@link GaiaDesk#download}: {@code GET /desks/{id}/files into a local file}. */
    public CompletableFuture<CopyResult> download(String deskId, String remote, Path local, @Nullable RequestOptions o) {
        return Calls.async(executor, () -> gd.download(deskId, remote, local, o));
    }

    /** {@link GaiaDesk#downloadBytes}: {@code GET /desks/{id}/files}. */
    public CompletableFuture<byte[]> downloadBytes(String deskId, String remote, @Nullable RequestOptions o) {
        return Calls.async(executor, () -> gd.downloadBytes(deskId, remote, o));
    }

    /** {@link GaiaDesk#runJob}: {@code POST /desks/{id}/jobs}. */
    public CompletableFuture<Job> runJob(String deskId, String name, String command, @Nullable JobOptions o) {
        return Calls.async(executor, () -> gd.runJob(deskId, name, command, o));
    }

    /** {@link GaiaDesk#runJob}: {@code POST /desks/{id}/jobs} with an argument vector. */
    public CompletableFuture<Job> runJob(String deskId, String name, List<String> argv, @Nullable JobOptions o) {
        return Calls.async(executor, () -> gd.runJob(deskId, name, argv, o));
    }

    /** {@link GaiaDesk#waitJob}: {@code GET /desks/{id}/jobs/{name}/wait}. */
    public CompletableFuture<JobWaitResult> waitJob(String deskId, String name, @Nullable WaitOptions o) {
        return Calls.async(executor, () -> gd.waitJob(deskId, name, o));
    }

    /** {@link GaiaDesk#jobs}: {@code GET /desks/{id}/jobs}. */
    public CompletableFuture<List<Job>> jobs(String deskId, @Nullable RequestOptions o) {
        return Calls.async(executor, () -> gd.jobs(deskId, o));
    }

    /** {@link GaiaDesk#killJob}: {@code DELETE /desks/{id}/jobs/{name}}. */
    public CompletableFuture<Job> killJob(String deskId, String name, @Nullable RequestOptions o) {
        return Calls.async(executor, () -> gd.killJob(deskId, name, o));
    }

    /** {@link GaiaDesk#jobLogs}: {@code GET /desks/{id}/jobs/{name}/logs}. */
    public CompletableFuture<String> jobLogs(String deskId, String name, @Nullable LogsOptions o) {
        return Calls.async(executor, () -> gd.jobLogs(deskId, name, o));
    }

    /** {@link GaiaDesk#stats}: {@code GET /desks/{id}/stats}. */
    public CompletableFuture<DeskStats> stats(String deskId, @Nullable RequestOptions o) {
        return Calls.async(executor, () -> gd.stats(deskId, o));
    }

    /** {@link GaiaDesk#createToken}: {@code POST /desks/{id}/tokens}, once per desk. */
    public CompletableFuture<MintResult> createToken(TokenSpec spec) {
        return Calls.async(executor, () -> gd.createToken(spec));
    }

    /** {@link GaiaDesk#listTokens}: {@code GET /desks/{id}/tokens}. */
    public CompletableFuture<List<TokenInfo>> listTokens(String deskId, @Nullable RequestOptions o) {
        return Calls.async(executor, () -> gd.listTokens(deskId, o));
    }

    /** {@link GaiaDesk#revokeToken}: {@code DELETE /desks/{id}/tokens/{token}}. */
    public CompletableFuture<TokenRevokeResult> revokeToken(String deskId, String token, @Nullable RequestOptions o) {
        return Calls.async(executor, () -> gd.revokeToken(deskId, token, o));
    }

    /** {@link GaiaDesk#audit}: {@code GET /audit}. */
    public CompletableFuture<List<AuditEvent>> audit(@Nullable AuditQuery q) {
        return Calls.async(executor, () -> gd.audit(q));
    }

    /** {@link GaiaDesk#webhooks}: {@code GET /webhooks}. */
    public CompletableFuture<List<Webhook>> webhooks(@Nullable RequestOptions o) {
        return Calls.async(executor, () -> gd.webhooks(o));
    }

    /** {@link GaiaDesk#createWebhook}: {@code POST /webhooks}. */
    public CompletableFuture<WebhookCreated> createWebhook(WebhookSpec spec) {
        return Calls.async(executor, () -> gd.createWebhook(spec));
    }

    /** {@link GaiaDesk#deleteWebhook}: {@code DELETE /webhooks/{id}}. */
    public CompletableFuture<String> deleteWebhook(String webhookId, @Nullable RequestOptions o) {
        return Calls.async(executor, () -> gd.deleteWebhook(webhookId, o));
    }

    /** {@link GaiaDesk#createSupportSession}: {@code POST /support/sessions}. */
    public CompletableFuture<SupportSessionCreated> createSupportSession(@Nullable SupportSessionSpec spec) {
        return Calls.async(executor, () -> gd.createSupportSession(spec));
    }

    /** {@link GaiaDesk#supportSessions}: {@code GET /support/sessions}. */
    public CompletableFuture<List<SupportSession>> supportSessions(@Nullable SupportSessionQuery q) {
        return Calls.async(executor, () -> gd.supportSessions(q));
    }

    /** {@link GaiaDesk#supportSession}: {@code GET /support/sessions/{id}}. */
    public CompletableFuture<SupportSession> supportSession(String sessionId, @Nullable RequestOptions o) {
        return Calls.async(executor, () -> gd.supportSession(sessionId, o));
    }
}
