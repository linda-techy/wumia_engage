package in.brand.engage.channels.push;

import in.brand.engage.channels.ChannelAdapter;
import in.brand.engage.channels.ChannelException;
import in.brand.engage.channels.DispatchResult;
import in.brand.engage.channels.RenderedMessage;
import in.brand.engage.channels.TokenPrune;
import in.brand.engage.core.messaging.Addresses;
import in.brand.engage.core.messaging.Channel;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FCM push: data-only multicast to every live token of one person
 * (phase-3 §2). One {@code sends} row, many devices; the shared tag collapses
 * the copies a person does not act on.
 *
 * <p>Per-token errors follow the phase-2 §9 table: UNREGISTERED and
 * SENDER_ID_MISMATCH prune the token; INVALID_ARGUMENT prunes only when the
 * error names the registration token, because otherwise the payload is at
 * fault and pruning would silently destroy good subscribers. Everything else
 * is transient and prunes nothing. Prunes are returned, not applied: the
 * router applies them after the send ({@link FcmTokenPruner}).
 */
@Singleton
@Named("push")
public class FcmAdapter implements ChannelAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(FcmAdapter.class);

    /** FCM's data limit is 4 KB; keep headroom for its own framing. */
    public static final int MAX_DATA_BYTES = 4_000;

    private static final Pattern TOPIC_SAFE = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private final FcmClient fcm;

    public FcmAdapter(FcmClient fcm) {
        this.fcm = fcm;
    }

    @Override
    public Channel channel() {
        return Channel.PUSH;
    }

    @Override
    public DispatchResult send(RenderedMessage message, Addresses addresses) throws ChannelException {
        var targets = addresses.push();
        if (targets.isEmpty()) {
            // Policy returns UNREACHABLE before this; reaching here is a caller bug.
            throw new ChannelException.Permanent("no_live_tokens", null, false, List.of());
        }
        var data = message.pushData();
        int size = dataBytes(data);
        if (size > MAX_DATA_BYTES) {
            throw new ChannelException.Permanent("payload_too_large",
                    size + " bytes > " + MAX_DATA_BYTES + " (template " + message.templateKey() + ")", false, List.of());
        }
        var topic = topic(data.get("tag"));

        int accepted = 0;
        boolean anyTransient = false;
        String lastError = null;
        var prunes = new ArrayList<TokenPrune>();

        for (int from = 0; from < targets.size(); from += FcmClient.MAX_TOKENS) {
            var chunk = targets.subList(from, Math.min(from + FcmClient.MAX_TOKENS, targets.size()));
            List<FcmClient.TokenResult> results;
            try {
                results = fcm.send(new FcmClient.Batch(
                        chunk.stream().map(Addresses.PushTarget::token).toList(),
                        data, message.ttl(), message.highUrgency(), topic));
            } catch (FcmClient.FcmCallException e) {
                // The whole call failed. Earlier chunks may already have been
                // accepted; report them rather than claim nothing went out.
                if (accepted > 0) {
                    LOG.warn("FCM call failed after {} tokens were accepted for send {}: {}",
                            accepted, message.sendId(), e.getMessage());
                    return new DispatchResult(null, accepted, prunes);
                }
                throw new ChannelException.Transient("fcm_call_failed", e.getMessage(), prunes, e);
            }
            if (results.size() != chunk.size()) {
                throw new IllegalStateException("FCM returned " + results.size() + " results for " + chunk.size() + " tokens");
            }

            for (int i = 0; i < chunk.size(); i++) {
                var r = results.get(i);
                if (r.success()) {
                    accepted++;
                    continue;
                }
                lastError = r.errorCode() + (r.message() == null ? "" : " (" + r.message() + ")");
                long deviceId = chunk.get(i).deviceId();
                switch (r.errorCode() == null ? "" : r.errorCode()) {
                    case "UNREGISTERED" -> prunes.add(new TokenPrune(deviceId, "unregistered"));
                    case "SENDER_ID_MISMATCH" -> prunes.add(new TokenPrune(deviceId, "sender_mismatch"));
                    case "INVALID_ARGUMENT" -> {
                        if (namesToken(r.message())) {
                            prunes.add(new TokenPrune(deviceId, "invalid_token"));
                        } else {
                            // Our payload, not the token. Alert on this line; prune nothing.
                            LOG.error("FCM rejected the payload of template {} (send {}): {}",
                                    message.templateKey(), message.sendId(), r.message());
                            anyTransient = true;
                        }
                    }
                    default -> anyTransient = true;     // QUOTA_EXCEEDED, UNAVAILABLE, INTERNAL, THIRD_PARTY_AUTH_ERROR
                }
            }
        }

        if (accepted > 0) return new DispatchResult(null, accepted, prunes);
        // Zero accepted is a failure, not a send. Retry only if something might recover.
        if (anyTransient) throw new ChannelException.Transient("no_token_accepted", lastError, prunes, null);
        throw new ChannelException.Permanent("all_tokens_dead", lastError, false, prunes);
    }

    /** Payload size as FCM counts it: UTF-8 bytes of every key and value. */
    static int dataBytes(Map<String, String> data) {
        int n = 0;
        for (var e : data.entrySet()) {
            n += e.getKey().getBytes(StandardCharsets.UTF_8).length;
            n += e.getValue().getBytes(StandardCharsets.UTF_8).length;
        }
        return n;
    }

    /**
     * Web Push {@code Topic} allows at most 32 characters of the URL-safe
     * base64 alphabet (RFC 8030 §5.4), so {@code restock:123} is invalid as is.
     * A safe tag passes through; anything else becomes a stable 32-character hash.
     */
    static String topic(String tag) {
        if (tag == null) return null;
        if (TOPIC_SAFE.matcher(tag).matches()) return tag;
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(tag.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** "The registration token is not a valid FCM registration token" names the token. */
    static boolean namesToken(String message) {
        return message != null && message.toLowerCase(Locale.ROOT).contains("registration token");
    }
}
