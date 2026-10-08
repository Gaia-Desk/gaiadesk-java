package net.gaiadesk.model;

/**
 * A new webhook subscription with its signing secret, shown once.
 */
public final class WebhookCreated extends Webhook {
    private String secret = "";

    /** For JSON only. */
    WebhookCreated() {}

    /** The signing secret ({@code whsec_…}); verify deliveries with {@link net.gaiadesk.Webhooks#verify}. */
    public String getSecret() {
        return secret;
    }
}
