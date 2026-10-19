package in.brand.engage.orchestrator;

import in.brand.engage.channels.ChannelAdapter;
import in.brand.engage.channels.ChannelException;
import in.brand.engage.channels.RenderedMessage;
import in.brand.engage.core.crypto.BeaconSignature;
import in.brand.engage.core.messaging.Category;
import in.brand.engage.core.messaging.Channel;
import in.brand.engage.orchestrator.templates.MessageTemplate;
import in.brand.engage.orchestrator.templates.Renderer;
import in.brand.engage.orchestrator.templates.TemplateRegistry;
import in.brand.engage.orchestrator.templates.WaTemplateGate;
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
    private final BeaconSignature beacons;
    private final WaTemplateGate waGate;
    private final EmailLinks emailLinks;
    private final Map<Channel, ChannelAdapter> adapters = new EnumMap<>(Channel.class);

    public MessageRouter(PolicyEngine policy, SendRepository sends, TemplateRegistry templates, Renderer renderer,
                         BeaconSignature beacons, WaTemplateGate waGate, EmailLinks emailLinks, List<ChannelAdapter> adapters) {
        this.waGate = waGate;
        this.emailLinks = emailLinks;
        this.policy = policy;
        this.sends = sends;
        this.templates = templates;
        this.renderer = renderer;
        this.beacons = beacons;
        for (var a : adapters) {
            if (this.adapters.put(a.channel(), a) != null) {
                throw new IllegalStateException("two adapters for channel " + a.channel());
            }
        }
        if (this.adapters.containsKey(Channel.SMS)) {
            var pending = templates.all().stream()
                    .filter(t -> t.channel() == Channel.SMS && (t.sms() == null || t.sms().pending()))
                    .map(MessageTemplate::key).toList();
            if (!pending.isEmpty()) {
                LOG.error("SMS is on (MSG91_AUTH_KEY) but {} are not registered on DLT yet: every send of them fails "
                        + "dlt_pending. Put the DLT and MSG91 ids in templates/sms/.", pending);
            }
        }
    }

    /** Channels with an adapter. */
    public java.util.Set<Channel> channels() {
        return java.util.Set.copyOf(adapters.keySet());
    }

    public SendResult send(SendCommand requested) {
        var existing = sends.findByKey(requested.idempotencyKey());
        if (existing.isPresent()) return new SendResult.Duplicate(existing.get().id(), existing.get().status());

        var cmd = requested;
        var wanted = templates.find(requested.templateKey()).orElse(null);
        if (wanted != null && wanted.fallback() != null) {
            // Decided before policy, so the send row, cooldown and spend are those of what goes out.
            var why = unusable(wanted, requested.vars());
            if (why != null) {
                LOG.debug("{}: {} instead of {} ({})", requested.idempotencyKey(), wanted.fallback(), wanted.key(), why);
                cmd = requested.withTemplateKey(wanted.fallback());
            }
        }
        return decideAndSend(cmd);
    }

    private SendResult decideAndSend(SendCommand cmd) {
        var template = templates.find(cmd.templateKey()).orElse(null);
        var decision = policy.decide(cmd.toDecisionRequest());
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

    /**
     * Why a template with a fallback cannot go to this person now: a variable of
     * its header photo is missing (the product has no photo), or Meta has not
     * approved one of its locales yet. Null when it can.
     */
    private String unusable(MessageTemplate template, Map<String, String> vars) {
        if (template.headerImage() != null) {
            for (var name : Renderer.placeholders(template.headerImage())) if (vars.get(name) == null) return "no_photo";
        }
        if (template.channel() == Channel.WHATSAPP && !template.freeForm()) {
            for (var copy : template.locales().values()) {
                var refusal = waGate.refusal(copy.providerName(), copy.language());
                if (refusal != null) return refusal;
            }
        }
        return null;
    }

    private SendResult dispatch(SendCommand cmd, Decision.Allow allow, MessageTemplate template, long sendId) {
        var adapter = adapters.get(cmd.channel());
        if (adapter == null) return fail(cmd, sendId, "no_adapter", false, false, List.of());
        if (template == null) return fail(cmd, sendId, "template_not_in_registry", false, false, List.of());

        RenderedMessage message;
        if (cmd.channel() == Channel.WHATSAPP) {
            try {
                var wa = renderer.whatsapp(template, allow.locale(), cmd.vars());
                if (wa.call() == null && !allow.freeWindow()) {
                    // A free-form reply outside the 24 h window: Meta would refuse it (131047).
                    return fail(cmd, sendId, "outside_service_window", false, false, List.of());
                }
                if (wa.call() != null) {
                    var refusal = waGate.refusal(wa.call().name(), wa.call().language());
                    if (refusal != null) return fail(cmd, sendId, refusal, false, false, List.of());
                }
                message = new RenderedMessage(cmd.channel(), template.key(), sendId, cmd.intentKey(), "", wa.body(),
                        cmd.vars().getOrDefault("url", ""), null, null, cmd.ttl(), false, null, wa.call());
            } catch (IllegalArgumentException e) {
                LOG.error("send {}: cannot render {}: {}", sendId, template.key(), e.getMessage());
                return fail(cmd, sendId, "render_failed", false, false, List.of());
            }
            return deliver(cmd, allow, sendId, adapter, message);
        }
        if (cmd.channel() == Channel.EMAIL) {
            try {
                var vars = new java.util.HashMap<>(cmd.vars());
                if (vars.get("url") != null) vars.put("url", Utm.apply(vars.get("url"), "email", cmd.intentKey(), sendId));
                var unsubscribe = emailLinks.unsubscribeUrl(cmd.identityId());
                var e = renderer.email(template, allow.locale(), vars, unsubscribe);
                message = new RenderedMessage(cmd.channel(), template.key(), sendId, cmd.intentKey(), e.subject(), e.text(),
                        vars.getOrDefault("url", ""), null, null, cmd.ttl(), false, null, null, null,
                        new in.brand.engage.channels.EmailContent(e.subject(), e.html(), e.text(), unsubscribe,
                                allow.effectiveCategory() == Category.MARKETING));
            } catch (IllegalArgumentException | IllegalStateException e) {
                LOG.error("send {}: cannot render {}: {}", sendId, template.key(), e.getMessage());
                return fail(cmd, sendId, "render_failed", false, false, List.of());
            }
            return deliver(cmd, allow, sendId, adapter, message);
        }
        if (cmd.channel() == Channel.SMS) {
            // Not registered on DLT yet: the operator would drop it after MSG91 said "success".
            if (template.sms() == null || template.sms().pending()) return fail(cmd, sendId, "dlt_pending", false, false, List.of());
            try {
                var sms = renderer.sms(template, cmd.vars());
                message = new RenderedMessage(cmd.channel(), template.key(), sendId, cmd.intentKey(), "", sms.body(),
                        "", null, null, cmd.ttl(), false, null, null, sms.call());
            } catch (IllegalArgumentException e) {
                LOG.error("send {}: cannot render {}: {}", sendId, template.key(), e.getMessage());
                return fail(cmd, sendId, "render_failed", false, false, List.of());
            }
            return deliver(cmd, allow, sendId, adapter, message);
        }
        try {
            var r = renderer.render(template, allow.locale(), cmd.vars());
            if (r.url() == null) throw new IllegalArgumentException("template " + template.key() + " needs variable 'url'");
            boolean push = cmd.channel() == Channel.PUSH;
            message = new RenderedMessage(cmd.channel(), template.key(), sendId, cmd.intentKey(), r.title(), r.body(),
                    Utm.apply(r.url(), cmd.channel().dbName(), cmd.intentKey(), sendId), r.image(), null,
                    cmd.ttl(), cmd.highUrgency(), push ? beacons.sign(sendId) : null);
        } catch (IllegalArgumentException e) {
            LOG.error("send {}: cannot render {}: {}", sendId, template.key(), e.getMessage());
            return fail(cmd, sendId, "render_failed", false, false, List.of());
        }

        return deliver(cmd, allow, sendId, adapter, message);
    }

    private SendResult deliver(SendCommand cmd, Decision.Allow allow, long sendId, ChannelAdapter adapter,
                               RenderedMessage message) {
        try {
            var result = adapter.send(message, allow.addresses());
            sends.markSent(sendId, cmd.channel(), allow.effectiveCategory(), allow.unitCostPaise(), result);
            return new SendResult.Sent(sendId, result.delivered());
        } catch (ChannelException.Permanent e) {
            sends.markFailed(sendId, cmd.identityId(), cmd.channel(), e.code(), e.suppress(), e.prunes());
            return new SendResult.Failed(sendId, e.code(), false, e.escalateImmediately());
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
