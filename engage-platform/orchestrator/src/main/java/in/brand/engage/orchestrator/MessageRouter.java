package in.brand.engage.orchestrator;

import in.brand.engage.channels.ChannelAdapter;
import in.brand.engage.channels.ChannelException;
import in.brand.engage.channels.RenderedMessage;
import in.brand.engage.core.messaging.Category;
import in.brand.engage.core.messaging.Channel;
import in.brand.engage.orchestrator.templates.MessageTemplate;
import in.brand.engage.orchestrator.templates.Renderer;
import in.brand.engage.orchestrator.templates.TemplateRegistry;
import in.brand.engage.policy.Decision;
import in.brand.engage.policy.PolicyEngine;
import jakarta.inject.Singleton;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one door to the providers: the only class that holds
 * {@link ChannelAdapter}s (ArchUnit enforces it from P3-T08).
 *
 * <p>The transaction boundary differs from the design doc on purpose. Holding
 * a transaction across an HTTP call to FCM or Meta pins a pool connection for
 * the provider's latency, and a slow provider then exhausts the pool. So:
 * <ol>
 *   <li>Tx 1: idempotency check, policy decision, insert the {@code sends} row
 *       (blocked, deferred or queued). Commit.</li>
 *   <li>No transaction: render and call the adapter.</li>
 *   <li>Tx 2: mark sent or failed, book spend, prune dead tokens.</li>
 * </ol>
 * A crash between 1 and 3 leaves a queued row, which
 * {@link SendRepository#sweepLostInFlight} fails after 10 minutes without re-sending.
 */
@Singleton
public class MessageRouter {

    private static final Logger LOG = LoggerFactory.getLogger(MessageRouter.class);

    private final PolicyEngine policy;
    private final SendRepository sends;
    private final TemplateRegistry templates;
    private final Renderer renderer;
    private final Map<Channel, ChannelAdapter> adapters = new EnumMap<>(Channel.class);

    public MessageRouter(PolicyEngine policy, SendRepository sends, TemplateRegistry templates, Renderer renderer,
                         List<ChannelAdapter> adapters) {
        this.policy = policy;
        this.sends = sends;
        this.templates = templates;
        this.renderer = renderer;
        for (var a : adapters) {
            if (this.adapters.put(a.channel(), a) != null) {
                throw new IllegalStateException("two adapters for channel " + a.channel());
            }
        }
    }

    /** Channels with an adapter. */
    public java.util.Set<Channel> channels() {
        return java.util.Set.copyOf(adapters.keySet());
    }

    public SendResult send(SendCommand cmd) {
        var existing = sends.findByKey(cmd.idempotencyKey());
        if (existing.isPresent()) return new SendResult.Duplicate(existing.get().id(), existing.get().status());

        var decision = policy.decide(cmd.toDecisionRequest());
        var template = templates.find(cmd.templateKey()).orElse(null);
        // A block can come before policy knows the category (unknown template);
        // the registry knows it, else record the conservative one.
        var category = template != null ? template.category() : Category.MARKETING;

        return switch (decision) {
            case Decision.Block b -> sends.recordBlocked(cmd, category, b)
                    .<SendResult>map(id -> new SendResult.Blocked(id, b.reason()))
                    .orElseGet(() -> duplicate(cmd));
            case Decision.Defer d ->
                    new SendResult.Deferred(sends.recordDeferred(cmd, category, d), d.until(), d.reason());
            case Decision.Allow a -> sends.recordQueued(cmd, a)
                    .map(id -> dispatch(cmd, a, template, id))
                    .orElseGet(() -> duplicate(cmd));
        };
    }

    private SendResult dispatch(SendCommand cmd, Decision.Allow allow, MessageTemplate template, long sendId) {
        var adapter = adapters.get(cmd.channel());
        if (adapter == null) return fail(cmd, sendId, "no_adapter", false, false, List.of());
        if (template == null) return fail(cmd, sendId, "template_not_in_registry", false, false, List.of());

        RenderedMessage message;
        try {
            var r = renderer.render(template, allow.locale(), cmd.vars());
            if (r.url() == null) throw new IllegalArgumentException("template " + template.key() + " needs variable 'url'");
            message = new RenderedMessage(cmd.channel(), template.key(), sendId, cmd.intentKey(), r.title(), r.body(),
                    r.url(), r.image(), null, cmd.ttl(), cmd.highUrgency());
        } catch (IllegalArgumentException e) {
            LOG.error("send {}: cannot render {}: {}", sendId, template.key(), e.getMessage());
            return fail(cmd, sendId, "render_failed", false, false, List.of());
        }

        try {
            var result = adapter.send(message, allow.addresses());
            sends.markSent(sendId, cmd.channel(), allow.effectiveCategory(), allow.unitCostPaise(), result);
            return new SendResult.Sent(sendId, result.delivered());
        } catch (ChannelException.Permanent e) {
            return fail(cmd, sendId, e.code(), e.suppress(), false, e.prunes());
        } catch (ChannelException.Transient e) {
            LOG.warn("send {}: transient provider failure: {}", sendId, e.getMessage());
            return fail(cmd, sendId, e.code(), false, true, e.prunes());
        } catch (ChannelException e) {
            throw new IllegalStateException("unhandled ChannelException kind", e);   // sealed: unreachable
        }
    }

    private SendResult fail(SendCommand cmd, long sendId, String code, boolean suppress, boolean retriable,
                            List<in.brand.engage.channels.TokenPrune> prunes) {
        sends.markFailed(sendId, cmd.identityId(), cmd.channel(), code, suppress, prunes);
        return new SendResult.Failed(sendId, code, retriable);
    }

    private SendResult duplicate(SendCommand cmd) {
        var row = sends.findByKey(cmd.idempotencyKey()).orElseThrow();
        return new SendResult.Duplicate(row.id(), row.status());
    }
}
