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
 * @param beaconSig   push: signature of {@code sendId}, echoed back by the click beacon (null = none)
 * @param whatsapp    WhatsApp: the template call; null sends {@code body} as a free-form service reply
 * @param sms         SMS: the DLT template and its slot values (null for other channels)
 * @param email       email: subject, HTML and text bodies, unsubscribe link (null for other channels)
 * @param extra       push: more data fields for whoever draws the notification, e.g.
 *                    {@code ends_at} (epoch ms) of an offer countdown, which the web service
 *                    worker and a native app (Android chronometer) both read; empty for none
 */
public record RenderedMessage(Channel channel, String templateKey, long sendId, String kind,
                              String title, String body, String url, String image, String tag,
                              Duration ttl, boolean highUrgency, String beaconSig, WaTemplateCall whatsapp,
                              SmsCall sms, EmailContent email, Map<String, String> extra) {

    /** Every channel's full form, without extra push data. */
    public RenderedMessage(Channel channel, String templateKey, long sendId, String kind, String title, String body,
                           String url, String image, String tag, Duration ttl, boolean highUrgency, String beaconSig,
                           WaTemplateCall whatsapp, SmsCall sms, EmailContent email) {
        this(channel, templateKey, sendId, kind, title, body, url, image, tag, ttl, highUrgency, beaconSig, whatsapp, sms,
                email, Map.of());
    }

    public RenderedMessage {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(templateKey, "templateKey");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(ttl, "ttl");
        extra = extra == null ? Map.of() : Map.copyOf(extra);
    }

    /** SMS (and the other channels, with null calls). */
    public RenderedMessage(Channel channel, String templateKey, long sendId, String kind, String title, String body,
                           String url, String image, String tag, Duration ttl, boolean highUrgency, String beaconSig,
                           WaTemplateCall whatsapp, SmsCall sms) {
        this(channel, templateKey, sendId, kind, title, body, url, image, tag, ttl, highUrgency, beaconSig, whatsapp, sms, null);
    }

    /** WhatsApp (and push, with a null call). */
    public RenderedMessage(Channel channel, String templateKey, long sendId, String kind, String title, String body,
                           String url, String image, String tag, Duration ttl, boolean highUrgency, String beaconSig,
                           WaTemplateCall whatsapp) {
        this(channel, templateKey, sendId, kind, title, body, url, image, tag, ttl, highUrgency, beaconSig, whatsapp, null, null);
    }

    /** Push and the other channels without a provider template call. */
    public RenderedMessage(Channel channel, String templateKey, long sendId, String kind, String title, String body,
                           String url, String image, String tag, Duration ttl, boolean highUrgency, String beaconSig) {
        this(channel, templateKey, sendId, kind, title, body, url, image, tag, ttl, highUrgency, beaconSig, null, null, null);
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
        if (beaconSig != null) data.put("sig", beaconSig);
        extra.forEach(data::putIfAbsent);             // never overrides the fields above
        return data;
    }
}
