package net.gaiadesk.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One desk, as {@code GET /desks} lists it: the CLI's {@code devices --json} row plus the reach log's word on an offline desk and, while online, its end-to-end key.
 */
public class Device extends ModelObject {
    private String deskId = "";
    private @Nullable String name;
    private @Nullable Boolean online;
    private @Nullable Long signalIdleSecs;
    private @Nullable Long lastSeen;
    private @Nullable String os;
    private @Nullable String appVersion;
    private @Nullable Boolean anytime;
    private @Nullable String owner;
    private List<String> sources = Collections.emptyList();
    private @Nullable Boolean reachable;
    private @Nullable ReachSuccess lastOk;
    private @Nullable ReachFailure lastFailure;
    private @Nullable JsonNode probe;
    private @Nullable Long offlineSince;
    private @Nullable String offlineReason;
    private @Nullable String offlineReasonText;
    private @Nullable String offlineDetail;
    private @Nullable List<String> features;
    private @Nullable String e2ePub;
    private @Nullable Boolean e2eRequired;

    /** For JSON only. */
    Device() {}

    /** Its nine-digit id. */
    public String getDeskId() {
        return deskId;
    }

    /** Its name. */
    public @Nullable String getName() {
        return name;
    }

    /** Online now (its signaling socket is registered). */
    public @Nullable Boolean getOnline() {
        return online;
    }

    /** Seconds since it was last heard from. */
    public @Nullable Long getSignalIdleSecs() {
        return signalIdleSecs;
    }

    /** Unix seconds it was last seen. */
    public @Nullable Long getLastSeen() {
        return lastSeen;
    }

    /** {@code macos}, {@code windows}, {@code linux}. */
    public @Nullable String getOs() {
        return os;
    }

    /** Its GaiaDesk version. */
    public @Nullable String getAppVersion() {
        return appVersion;
    }

    /** Unattended access is on. */
    public @Nullable Boolean getAnytime() {
        return anytime;
    }

    /** Its owner ({@code you}, or a team mate's email). */
    public @Nullable String getOwner() {
        return owner;
    }

    /** Where it was listed from ({@code account}, {@code team}, ...). */
    public List<String> getSources() {
        return sources;
    }

    /** Reachable when probed (CLI transport only). */
    public @Nullable Boolean getReachable() {
        return reachable;
    }

    /** The last time it was reached. */
    public @Nullable ReachSuccess getLastOk() {
        return lastOk;
    }

    /** The last time reaching it failed. */
    public @Nullable ReachFailure getLastFailure() {
        return lastFailure;
    }

    /** A probe's result (CLI transport only). */
    public @Nullable JsonNode getProbe() {
        return probe;
    }

    /** Unix seconds it went offline (the reach log's word). */
    public @Nullable Long getOfflineSince() {
        return offlineSince;
    }

    /** Why: {@code closed}, {@code silent}, {@code error}, {@code updating}, {@code server-restart}, {@code id-changed}, {@code unknown}. */
    public @Nullable String getOfflineReason() {
        return offlineReason;
    }

    /** Why, for a person. */
    public @Nullable String getOfflineReasonText() {
        return offlineReasonText;
    }

    /** {@code id-changed}: the id it answers to now. */
    public @Nullable String getOfflineDetail() {
        return offlineDetail;
    }

    /** What it takes while online: {@code desk_op}, {@code desk_op_e2e}. */
    public @Nullable List<String> getFeatures() {
        return features;
    }

    /** Its end-to-end X25519 public key (base64url, 32 bytes), while online and able to open sealed operations. */
    public @Nullable String getE2ePub() {
        return e2ePub;
    }

    /** Its owner requires end-to-end encryption for API commands. */
    public @Nullable Boolean getE2eRequired() {
        return e2eRequired;
    }
}
