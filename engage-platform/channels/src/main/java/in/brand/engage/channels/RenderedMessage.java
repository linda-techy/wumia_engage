package in.brand.engage.channels;

import in.brand.engage.core.messaging.Channel;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One template rendered for one recipient in one locale. Rendering happens in
 * the orchestrator; adapters never see template variables.
 *
 * @param sendId      {@code sends.id}; the push {@code sid} that joins click beacons back
 * @param kind        the intent key; the push notification's default tag
 * @param url         absolute click-through, UTMs already applied (P3-T08)
 * @param image       optional large image (push; Chrome only)
 * @param tag         optional collapse key, e.g. {@code restock:<variantId>}; defaults to {@code kind}
 * @param ttl         how long the provider may hold it for an offline device
 * @param highUrgency push: Web Push {@code Urgency: high} and Android high priority
 */
public record RenderedMessage(Channel channel, String templateKey, long sendId, String kind,
                              String title, String body, String url, String image, String tag,
                              Duration ttl, boolean highUrgency) {

    public RenderedMessage {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(templateKey, "templateKey");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(ttl, "ttl");
    }

    /** The push data payload (phase-3 §3). Data-only: the service worker renders it. */
    public Map<String, String> pushData() {
        var data = new LinkedHashMap<String, String>();
        data.put("sid", Long.toString(sendId));
        data.put("kind", kind);
        data.put("title", title);
        data.put("body", body);
        data.put("url", url);
        if (image != null) data.put("image", image);
        data.put("tag", tag != null ? tag : kind);
        return data;
    }
}
