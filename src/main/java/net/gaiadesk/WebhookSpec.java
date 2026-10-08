package net.gaiadesk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.gaiadesk.internal.Check;
import org.jspecify.annotations.Nullable;

/**
 * A webhook subscription to create ({@link GaiaDesk#createWebhook}): an {@code https://} URL on the public
 * internet and the events it hears ({@code desk.online}, {@code desk.offline}, {@code desk.woke},
 * {@code job.finished}, {@code support.session.joined}, {@code support.session.ended}).
 */
public final class WebhookSpec extends CallOptions<WebhookSpec> {
    final String url;
    final List<String> events;
    @Nullable String description;

    /** A subscription of {@code url} to {@code events} (at least one). */
    public WebhookSpec(String url, String... events) {
        this(url, Arrays.asList(events));
    }

    /** A subscription of {@code url} to {@code events} (at least one). */
    public WebhookSpec(String url, List<String> events) {
        if (url == null || !url.regionMatches(true, 0, "https://", 0, 8)) throw Check.usage("a webhook's url is an https:// URL");
        if (events.isEmpty()) throw Check.usage("a webhook hears at least one event");
        this.url = url;
        this.events = new ArrayList<>(events);
    }

    /** A note for people (at most 200 characters). */
    public WebhookSpec description(String description) {
        if (description.length() > 200) throw Check.usage("a webhook's description is at most 200 characters");
        this.description = description;
        return this;
    }
}
