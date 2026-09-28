package in.brand.engage.policy;

import static in.brand.engage.core.messaging.Category.MARKETING;
import static in.brand.engage.core.messaging.Category.SERVICE;
import static in.brand.engage.core.messaging.Channel.WHATSAPP;

import in.brand.engage.core.messaging.Addresses;
import in.brand.engage.core.messaging.Channel;
import in.brand.engage.persistence.Db;
import jakarta.inject.Singleton;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Whether a message may go out, and under which config. One public method.
 *
 * <p>The order is {@code docs/04-backend-micronaut.md} §The policy engine, with
 * the consent step from phase-3 §1 and three changes from the P3-T02 task:
 * the marketing halt is checked once the template (and so its category) is
 * known; a budget overrun defers to the next IST day instead of blocking
 * (CLAUDE.md invariant 5 outranks the design doc); and the global holdout
 * applies to every marketing send, not only journeys, because campaigns must
 * not message it either (05-campaigns.md).
 *
 * <p>No side effects except the holdout assignment row, plus a
 * {@code config_snapshots} row when the config cache is refreshed.
 *
 * <p>Not yet enforced: {@code HIGHER_PRIORITY_ACTIVE} (P5).
 */
@Singleton
public class PolicyEngine {

    private final Db db;
    private final ConfigResolver config;
    private final KillSwitch killSwitch;
    private final Templates templates;
    private final TemplateCooldowns cooldowns;
    private final SubjectLoader subjects;
    private final Usage usage;
    private final Holdouts holdouts;
    private final Clock clock;

    public PolicyEngine(Db db, ConfigResolver config, KillSwitch killSwitch, Templates templates,
                        TemplateCooldowns cooldowns, SubjectLoader subjects, Usage usage, Holdouts holdouts,
                        Clock clock) {
        this.db = db;
        this.config = config;
        this.killSwitch = killSwitch;
        this.templates = templates;
        this.cooldowns = cooldowns;
        this.subjects = subjects;
        this.usage = usage;
        this.holdouts = holdouts;
        this.clock = clock;
    }

    public Decision decide(DecisionRequest req) {
        var snapshot = config.snapshot();
        var now = clock.instant();
        return db.inTx(c -> decide(c, req, snapshot, now));
    }

    private Decision decide(Connection c, DecisionRequest req, ConfigSnapshot snapshot, Instant now)
            throws SQLException {
        var channel = req.channel();
        var out = new Out(snapshot.id());

        // 1. Kill switches, uncached. An incident must stop everything at once.
        var halts = killSwitch.read(c, channel, req.journeyKey());
        if (halts.channel()) return out.block(BlockReason.CHANNEL_HALTED);
        if (req.journeyKey() != null) {
            if (halts.journey()) return out.block(BlockReason.JOURNEY_DISABLED, Map.of("halted", true));
            if (!snapshot.boolValue("journey.enabled", req.journeyKey())) return out.block(BlockReason.JOURNEY_DISABLED);
        }

        // 2. Template validity; then the marketing halt, which needs the category.
        var template = templates.find(c, req.templateKey()).orElse(null);
        if (template == null) return out.block(BlockReason.TEMPLATE_UNKNOWN);
        if (template.channel() != channel) return out.block(BlockReason.CHANNEL_MISMATCH);
        if (!template.sendable()) return out.block(BlockReason.TEMPLATE_PAUSED, Map.of("status", template.status()));
        if (halts.marketing() && template.category() == MARKETING) return out.block(BlockReason.MARKETING_HALTED);

        // 3. One read for everything about this person.
        var subject = subjects.load(c, req.identityId(), now, snapshot.longValue("push.campaign_stale_days", "*"));
        var addresses = new Addresses(
                channel == Channel.PUSH ? subject.pushTargets(req.allowStaleDevices()) : List.of(),
                subject.phone(), subject.email());
        var unreachable = reachability(channel, subject, addresses, now, out);
        if (unreachable != null) return unreachable;
        var suppression = subject.suppressions().get(channel);
        if (suppression != null) return out.block(BlockReason.SUPPRESSED, Map.of("reason", suppression));

        // 4. Consent (phase-3 §1). Marketing needs an explicit grant on every
        //    channel. Transactional is implied for SMS and email only; WhatsApp
        //    needs an opt-in for every business-initiated message (Meta policy).
        switch (channel) {
            case WHATSAPP -> {
                boolean replying = subject.inServiceWindow(now) && req.isReply();
                if (!replying && !subject.hasAnyGrant(WHATSAPP)) return out.block(BlockReason.NO_WHATSAPP_OPT_IN);
                if (template.category() == MARKETING && !subject.hasGrant(WHATSAPP, "marketing"))
                    return out.block(BlockReason.NO_MARKETING_CONSENT);
            }
            // Browser permission is capability; the soft-ask grant is consent.
            case PUSH -> {
                if (!subject.hasGrant(Channel.PUSH, "marketing")) return out.block(BlockReason.NO_MARKETING_CONSENT);
            }
            default -> {
                if (template.category() == MARKETING && !subject.hasGrant(channel, "marketing"))
                    return out.block(BlockReason.NO_MARKETING_CONSENT);
            }
        }

        // 5. WhatsApp service window: a reply inside it is a free service message.
        boolean freeWindow = channel == WHATSAPP && subject.inServiceWindow(now);
        var category = freeWindow && req.isReply() ? SERVICE : template.category();

        // 6. Quiet hours: a deferral, never a drop. Only marketing waits.
        if (category == MARKETING) {
            var quiet = snapshot.quietHours();
            if (quiet.contains(now)) return out.defer(quiet.nextOpen(now), BlockReason.QUIET_HOURS);
        }

        // 7. Frequency caps across ALL journeys and campaigns, not per journey.
        for (var cap : snapshot.capsFor(channel, category)) {
            long used = usage.sendsSince(c, req.identityId(), channel, category, now.minus(cap.window()));
            if (used >= cap.max())
                return out.block(BlockReason.FREQUENCY_CAP, Map.of("cap", cap.key(), "max", cap.max(), "used", used));
        }

        // 8. Per-template cooldown, authored with the copy (templates/*.yaml).
        var cooldown = cooldowns.cooldown(template.key()).orElse(null);
        if (cooldown != null && usage.templateSentSince(c, req.identityId(), template, now.minus(cooldown)))
            return out.block(BlockReason.TEMPLATE_COOLDOWN, Map.of("cooldown", cooldown.toString()));

        // 9. Budget guard. Exhaustion is temporary, so it defers to the next IST day.
        long unit = category == SERVICE ? 0
                : snapshot.longOrZero("rate." + channel.dbName() + "." + category.dbName() + "_paise");
        long budget = snapshot.longOrZero("budget." + channel.dbName() + "." + category.dbName() + ".daily_paise");
        if (budget > 0) {
            var today = LocalDate.ofInstant(now, ConfigSnapshot.IST);
            long spent = usage.spentOn(c, today, channel, category);
            if (spent + unit > budget) {
                return out.defer(today.plusDays(1).atStartOfDay(ConfigSnapshot.IST).toInstant(),
                        BlockReason.DAILY_BUDGET_EXHAUSTED);
            }
        }

        // 10. Holdouts. Control is measured, not messaged.
        if (req.journeyKey() != null) {
            var pct = snapshot.decimalValue("holdout.journey_pct", req.journeyKey());
            if (inControl(c, req, req.journeyKey(), pct))
                return out.block(BlockReason.HOLDOUT_CONTROL, Map.of("experiment", req.journeyKey()));
        }
        if (template.category() == MARKETING
                && inControl(c, req, Holdouts.GLOBAL, snapshot.decimalValue("holdout.global_pct", "*")))
            return out.block(BlockReason.HOLDOUT_GLOBAL);

        return new Decision.Allow(template, category, unit, freeWindow, addresses, snapshot.id());
    }

    private static Decision reachability(Channel channel, Subject subject, Addresses addresses, Instant now, Out out) {
        return switch (channel) {
            // iOS web cannot receive push (CLAUDE.md §2): falling through to
            // WhatsApp beats counting a push that will never arrive.
            case PUSH -> addresses.push().isEmpty()
                    ? out.block(BlockReason.UNREACHABLE, Map.of("active_devices", subject.devices().size()))
                    : null;
            case WHATSAPP -> {
                if (subject.phone() == null) yield out.block(BlockReason.UNREACHABLE, Map.of("phone", false));
                if ("INCAPABLE".equals(subject.capabilityOf(WHATSAPP)))
                    yield out.block(BlockReason.UNREACHABLE, Map.of("capability", "INCAPABLE"));
                if (subject.waBackedOff(now))
                    yield out.block(BlockReason.WA_BACKOFF, Map.of("until", subject.waBackoffUntil().toString()));
                yield null;
            }
            case SMS -> subject.phone() == null ? out.block(BlockReason.UNREACHABLE, Map.of("phone", false)) : null;
            case EMAIL -> subject.email() == null ? out.block(BlockReason.UNREACHABLE, Map.of("email", false)) : null;
            case RCS -> out.block(BlockReason.UNREACHABLE, Map.of("channel", "not built"));
        };
    }

    private boolean inControl(Connection c, DecisionRequest req, String experiment, BigDecimal pct)
            throws SQLException {
        return pct.signum() > 0 && holdouts.bucket(c, req.identityId(), experiment, pct) == Holdouts.Bucket.CONTROL;
    }

    /** Stamps every outcome with the snapshot it was decided under. */
    private record Out(long snapshotId) {
        Decision block(BlockReason reason) {
            return new Decision.Block(reason, Map.of(), snapshotId);
        }

        Decision block(BlockReason reason, Map<String, Object> detail) {
            return new Decision.Block(reason, detail, snapshotId);
        }

        Decision defer(Instant until, BlockReason reason) {
            return new Decision.Defer(until, reason, snapshotId);
        }
    }
}
