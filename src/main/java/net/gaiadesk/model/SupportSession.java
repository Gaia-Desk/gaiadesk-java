package net.gaiadesk.model;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A support session for the web embed SDK.
 */
public class SupportSession extends ModelObject {
    private String id = "";
    private String state = "";
    private String mode = "";
    private @Nullable Map<String, Object> customer;
    private boolean customerPresent;
    private boolean customerVerified;
    private String joinCode = "";
    private String joinUrl = "";
    private @Nullable String deskId;
    private @Nullable String origin;
    private String owner = "";
    private long createdAt;
    private long expiresAt;
    private @Nullable Long joinedAt;
    private @Nullable String joinedBy;
    private @Nullable Long endedAt;
    private @Nullable String endReason;

    /** For JSON only. */
    SupportSession() {}

    /** {@code ss_…}. */
    public String getId() {
        return id;
    }

    /** {@code waiting}, {@code joined}, {@code ended}, {@code expired}. */
    public String getState() {
        return state;
    }

    /** {@code view} or {@code cobrowse}. */
    public String getMode() {
        return mode;
    }

    /** Who the customer is, as the company (or, unverified, the page) said. */
    public @Nullable Map<String, Object> getCustomer() {
        return customer;
    }

    /** The customer's tab holds its connection right now. */
    public boolean isCustomerPresent() {
        return customerPresent;
    }

    /** False when a publishable key (the page itself) created it. */
    public boolean isCustomerVerified() {
        return customerVerified;
    }

    /** The nine digits an agent joins with. */
    public String getJoinCode() {
        return joinCode;
    }

    /** The console page that joins it. */
    public String getJoinUrl() {
        return joinUrl;
    }

    /** While the customer is present, the id the console dials. */
    public @Nullable String getDeskId() {
        return deskId;
    }

    /** The page origin the embed must run on, if pinned. */
    public @Nullable String getOrigin() {
        return origin;
    }

    /** The account that created it. */
    public String getOwner() {
        return owner;
    }

    /** Unix seconds. */
    public long getCreatedAt() {
        return createdAt;
    }

    /** Unix seconds. */
    public long getExpiresAt() {
        return expiresAt;
    }

    /** Unix seconds an agent joined. */
    public @Nullable Long getJoinedAt() {
        return joinedAt;
    }

    /** The last agent who joined. */
    public @Nullable String getJoinedBy() {
        return joinedBy;
    }

    /** Unix seconds it ended. */
    public @Nullable Long getEndedAt() {
        return endedAt;
    }

    /** {@code stopped}, {@code disconnected}, {@code expired}. */
    public @Nullable String getEndReason() {
        return endReason;
    }
}
