# 04 — Micronaut backend

Java 25, Micronaut 5, Micronaut Data JDBC, Flyway, Postgres 16.

## Why Data JDBC and not JPA

Micronaut Data JDBC generates repository implementations at compile time and maps to records. No lazy loading, no session, no N+1 surprises, no proxy magic in a stack trace. You write the query or you get a generated one that you can read.

This codebase is query-shaped rather than object-graph-shaped: the hot paths are "count sends in a rolling window", "claim 200 due runs", "resolve current config". JPA's object-graph model buys nothing here and costs predictability.

## Project setup

```kotlin
// build.gradle.kts (root)
plugins {
    id("io.micronaut.platform.application") version "4.5.4" apply false
}

subprojects {
    apply(plugin = "io.micronaut.platform.application")

    java {
        toolchain { languageVersion = JavaLanguageVersion.of(25) }
    }

    micronaut {
        version("5.0.6")
        processing {
            incremental(true)
            annotations("in.brand.engage.*")
        }
    }
}
```

```yaml
# admin-api/src/main/resources/application.yml
micronaut:
  application.name: engage-admin
  server:
    port: 8081
    # Virtual threads: this workload is IO-bound, so a thread per request
    # costs almost nothing and keeps stack traces and debuggers usable.
    thread-selection: BLOCKING
  executors:
    blocking:
      type: virtual
  security:
    authentication: bearer
    intercept-url-map:
      - pattern: /api/**
        access: isAuthenticated()       # default deny
    token:
      jwt:
        signatures.secret.generator.jws-algorithm: RS256
        generator:
          access-token.expiration: 900              # 15 min
          refresh-token.enabled: false              # ours are opaque, in Postgres

datasources:
  default:
    url: ${DB_URL}
    driver-class-name: org.postgresql.Driver
    maximum-pool-size: 20
    # Virtual threads make it trivial to exhaust a connection pool: 10,000
    # concurrent tasks will happily queue on 20 connections. Fail fast rather
    # than pile up, and size the pool against Postgres, not against threads.
    connection-timeout: 3000
    leak-detection-threshold: 20000

flyway:
  datasources.default:
    enabled: true
    locations: classpath:db/migration
```

## Domain types

Records and sealed interfaces. Money is always `long` paise — never `double`, never `BigDecimal` for storage.

The implementation is `core-domain/.../money/Paise.java`. Two points from building it:

- **`toRupeeString()` hand-rolls lakh/crore grouping.** An earlier draft of this document used `NumberFormat.getInstance(new Locale("en","IN"))`. That prints `172,000`, not `1,72,000`, because `java.text.DecimalFormat` supports only one grouping size. The unit tests pin `1,72,000`, `1,00,00,000` and `12,34,56,789.50`.
- **Two input units.** `Paise.ofRupees("1299.00")` for Shopify's decimal strings, and `Paise.of(129900)` for Razorpay, which already sends paise. It refuses to round: `"12.345"` throws rather than silently losing money.

```java
public record Paise(long value) implements Comparable<Paise> {
    public static Paise of(long paise) { return new Paise(paise); }                // Razorpay
    public static Paise ofRupees(String rupees) { /* exact, throws on >2 dp */ }   // Shopify
    public String toRupeeString() { /* "1,72,000" — Indian grouping */ }
}

public enum Channel { WHATSAPP, PUSH, EMAIL, SMS, RCS }
public enum Category { MARKETING, UTILITY, AUTHENTICATION, SERVICE }
```

The decision type is a sealed interface, so the compiler enforces that every call site handles every outcome. A new block reason cannot be silently ignored.

```java
public sealed interface Decision {

    record Allow(Template template, Category effectiveCategory, Paise unitCost,
                 boolean freeWindow, Addresses addresses, long configSnapshotId)
            implements Decision {}

    record Block(BlockReason reason, Map<String, Object> detail)
            implements Decision {}

    /** Not a refusal — a "not yet". The caller must reschedule, not drop. */
    record Defer(Instant until, BlockReason reason) implements Decision {}
}

public enum BlockReason {
    TEMPLATE_UNKNOWN, TEMPLATE_PAUSED, CHANNEL_MISMATCH,
    UNREACHABLE, SUPPRESSED, NO_MARKETING_CONSENT,
    QUIET_HOURS, FREQUENCY_CAP, TEMPLATE_COOLDOWN,
    DAILY_BUDGET_EXHAUSTED, CHANNEL_HALTED, JOURNEY_DISABLED,
    HOLDOUT_CONTROL, HOLDOUT_GLOBAL
}
```

`Defer` being distinct from `Block` matters. Quiet hours and budget exhaustion are temporary; treating them as refusals silently drops the message instead of retrying it after the window opens.

## The policy engine

One class, one public method, no side effects. Ordering is deliberate: cheap in-memory checks before database round-trips, and the kill switch first because an incident must stop everything immediately.

```java
@Singleton
public class PolicyEngine {

    public Decision decide(DecisionRequest req) {
        var snapshot = config.snapshot();

        // 1. Kill switches — uncached, checked first.
        if (killSwitch.channelHalted(req.channel()))
            return block(CHANNEL_HALTED);
        if (req.journeyKey() != null && !snapshot.journeyEnabled(req.journeyKey()))
            return block(JOURNEY_DISABLED);

        // 2. Template validity.
        var template = templates.find(req.templateKey()).orElse(null);
        if (template == null)                      return block(TEMPLATE_UNKNOWN);
        if (template.channel() != req.channel())   return block(CHANNEL_MISMATCH);
        if (template.status() == PAUSED)           return block(TEMPLATE_PAUSED);

        // 3. One read for everything about this person.
        var subject = subjects.load(req.identityId());
        if (!subject.reachableOn(req.channel()))   return block(UNREACHABLE);
        if (subject.suppressed(req.channel()))
            return block(SUPPRESSED, Map.of("reason", subject.suppressionReason()));

        // 4. Consent. Marketing needs an explicit grant on every channel.
        //    Transactional is implied for SMS and email ONLY. WhatsApp needs
        //    an opt-in for every business-initiated message, utility included
        //    (Meta policy). Full check: docs/technical/phase-3 §1.
        var consentBlock = consentCheck(req, template, subject);
        if (consentBlock != null) return consentBlock;

        // 5. WhatsApp service-window downgrade. An inbound message in the last
        //    24h lets us send free-form instead of paying for a template.
        var category = effectiveCategory(template, subject, req.channel());

        // 6. Quiet hours — a deferral, never a drop.
        if (!isQuietHoursExempt(category, req.templateKey())
                && snapshot.inQuietHours(clock.instant())) {
            return new Defer(snapshot.nextOpenWindow(clock.instant()), QUIET_HOURS);
        }

        // 7. Frequency caps across ALL journeys, not per journey. Per-journey
        //    caps are how a customer gets four messages in a day from four
        //    journeys that each think they sent one.
        for (var cap : snapshot.capsFor(req.channel(), category)) {
            int used = sends.countInWindow(req.identityId(), req.channel(),
                                           category, cap.window());
            if (used >= cap.max())
                return block(FREQUENCY_CAP, Map.of(
                        "window", cap.window(), "max", cap.max(), "used", used));
        }

        // 8. Per-template cooldown.
        if (template.cooldown() != null
                && sends.sentWithin(req.identityId(), template.key(), template.cooldown()))
            return block(TEMPLATE_COOLDOWN);

        // 9. Budget guard.
        var unit = snapshot.rateFor(req.channel(), category);
        if (category == MARKETING && req.channel() == WHATSAPP) {
            var spent = spend.todayFor(WHATSAPP, MARKETING);
            var budget = snapshot.dailyBudget(WHATSAPP, MARKETING);
            if (budget.value() > 0 && spent.plus(unit).compareTo(budget) > 0)
                return block(DAILY_BUDGET_EXHAUSTED,
                        Map.of("spent", spent.value(), "budget", budget.value()));
        }

        // 10. Holdout. Control is measured, not messaged.
        if (req.journeyKey() != null) {
            if (holdouts.bucket(req.identityId(), req.journeyKey()) == CONTROL)
                return block(HOLDOUT_CONTROL, Map.of("experiment", req.journeyKey()));
            if (template.category() == MARKETING
                    && holdouts.bucket(req.identityId(), GLOBAL) == CONTROL)
                return block(HOLDOUT_GLOBAL);
        }

        return new Decision.Allow(template, category, unit,
                subject.inServiceWindow(), subject.addresses(), snapshot.id());
    }
}
```

## The router: the one door

```java
@Singleton
public class MessageRouter {

    private final Map<Channel, ChannelAdapter> adapters;   // injected by qualifier

    @Transactional
    public SendResult send(SendCommand cmd) {
        var key = cmd.idempotencyKey();

        if (sends.existsByIdempotencyKey(key))
            return SendResult.duplicate();

        var decision = policy.decide(cmd.toDecisionRequest());

        return switch (decision) {
            case Decision.Block b -> {
                // Blocked sends are persisted. They are the answer to "why
                // didn't this customer hear from us", the denominator for
                // holdout analysis, and the evidence a compliance review needs.
                var row = sends.recordBlocked(cmd, b, config.snapshot().id());
                yield SendResult.blocked(b.reason(), row.id());
            }
            case Decision.Defer d -> {
                // Do NOT burn the idempotency key here, or the retry after the
                // window opens is swallowed as a duplicate and the customer
                // silently never hears from us.
                sends.recordDeferred(cmd, d, config.snapshot().id());
                yield SendResult.deferred(d.until());
            }
            case Decision.Allow a -> dispatch(cmd, a, key);
        };
    }

    private SendResult dispatch(SendCommand cmd, Decision.Allow a, String key) {
        var row = sends.recordQueued(cmd, a, key);
        try {
            var providerId = adapters.get(cmd.channel()).send(a, cmd.vars());
            sends.markSent(row.id(), providerId, a.unitCost());

            // WhatsApp cost is reconciled later from the status webhook, which
            // carries the category Meta actually billed — it differs from what
            // we intended when Meta re-classifies a template.
            if (cmd.channel() != Channel.WHATSAPP)
                spend.book(cmd.channel(), a.effectiveCategory(), a.unitCost());

            return SendResult.sent(row.id(), providerId);
        } catch (PermanentChannelException e) {
            sends.markFailed(row.id(), e.getMessage());
            suppressions.add(cmd.identityId(), cmd.channel(), "provider_" + e.code());
            return SendResult.failed(e.getMessage(), false);
        } catch (TransientChannelException e) {
            sends.markFailed(row.id(), e.getMessage());
            return SendResult.failed(e.getMessage(), true);
        }
    }
}
```

`ChannelAdapter` is the only interface that touches a provider:

```java
public interface ChannelAdapter {
    Channel channel();
    DispatchResult send(Decision.Allow allowed, RenderedMessage msg)
            throws ChannelException;
}

/** providerId is null for FCM multicast; delivered = devices/recipients accepted. */
public record DispatchResult(String providerId, int delivered) {
    public static DispatchResult of(String id, int n) { return new DispatchResult(id, n); }
}
```

`RenderedMessage` holds the template rendered for one recipient in one locale. Rendering happens in the router, so adapters never see raw template variables. The router snippet above passes `cmd.vars()` for brevity.

Four implementations: `WhatsAppCloudAdapter`, `FcmAdapter`, `SesAdapter`, `Msg91Adapter`. Each is `@Singleton` with a `@Named` qualifier. Swapping Meta Cloud API for a BSP is a new adapter and a config change — no journey or campaign code moves.

## Repositories

```java
@JdbcRepository(dialect = Dialect.POSTGRES)
public interface SendRepository extends CrudRepository<SendRow, Long> {

    @Query("""
        SELECT COUNT(*) FROM sends
         WHERE identity_id = :identityId
           AND channel = CAST(:channel AS channel)
           AND category = CAST(:category AS msg_category)
           AND status <> 'blocked'
           AND created_at > now() - CAST(:window AS interval)
        """)
    int countInWindow(UUID identityId, String channel, String category, String window);

    @Query("""
        SELECT s.* FROM sends s
         WHERE s.journey_key = :journeyKey
           AND s.created_at > now() - CAST(:window AS interval)
         ORDER BY s.created_at DESC
        """)
    List<SendRow> forJourney(String journeyKey, String window);
}
```

`countInWindow` runs on every send decision, so it is backed by a partial index that excludes blocked rows:

```sql
CREATE INDEX ON sends (identity_id, channel, category, created_at DESC)
  WHERE status <> 'blocked';
```

## Workers

```java
@Singleton
public class JourneyTicker {

    /**
     * Every pod runs this. SKIP LOCKED means no leader election and no
     * distributed lock — a pod claims what it can and the others move past
     * the locked rows.
     */
    @Scheduled(fixedDelay = "15s", initialDelay = "10s")
    void tick() {
        List<JourneyRun> claimed;
        do {
            claimed = runs.claimDue(200);
            claimed.forEach(this::advanceSafely);
        } while (claimed.size() == 200);       // drain a backlog rather than wait
    }

    private void advanceSafely(JourneyRun run) {
        try {
            engine.advance(run);
        } catch (Exception e) {
            log.error("journey {} run {} failed", run.journeyKey(), run.id(), e);
            runs.fail(run.id(), e.getMessage());
        }
    }
}
```

```sql
-- claimDue
UPDATE journey_runs SET status = 'active', updated_at = now()
 WHERE id IN (
   SELECT id FROM journey_runs
    WHERE status IN ('active','waiting') AND next_run_at <= now()
    ORDER BY next_run_at
    LIMIT :limit
    FOR UPDATE SKIP LOCKED
 )
RETURNING *;
```

## Outbound resilience

Every provider call goes through a retry and circuit breaker. Meta returns 429 and 5xx under load, and hammering a degraded endpoint turns a blip into an outage.

```java
@Client("${wa.graph-url}")
@Retryable(attempts = "3", delay = "1s", multiplier = "2.0",
           includes = { TransientChannelException.class })
@CircuitBreaker(reset = "30s", attempts = "5")
public interface WhatsAppClient {
    @Post("/{phoneNumberId}/messages")
    MessageResponse send(@PathVariable String phoneNumberId, @Body SendRequest body);
}
```

Permanent errors are excluded from retry deliberately. Retrying a 131047 ("outside the service window") three times is three failed calls and three marks against your standing.

## Testing

```java
@MicronautTest(transactional = false)
class PolicyEngineTest {

    // Testcontainers, not H2. This code uses enum casts, intervals, partial
    // indexes and SKIP LOCKED — none of which H2 models faithfully. A green
    // H2 suite that fails on Postgres is worse than no suite.
    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Inject PolicyEngine policy;
    @Inject Fixtures fixtures;

    @Test
    void marketing_blocked_without_consent() {
        var id = fixtures.identityWithPhone("919876543210");
        var d = policy.decide(marketing(id, WHATSAPP, "cart_recovery"));
        assertInstanceOf(Decision.Block.class, d);
        assertEquals(NO_MARKETING_CONSENT, ((Decision.Block) d).reason());
    }

    @Test
    void quiet_hours_defers_rather_than_drops() {
        var id = fixtures.consentedIdentity();
        clock.setTo("2026-09-18T17:10:00Z");         // 22:40 IST
        var d = policy.decide(marketing(id, WHATSAPP, "cart_recovery"));
        var defer = assertInstanceOf(Decision.Defer.class, d);
        assertEquals("2026-09-19T03:30:00Z", defer.until().toString());  // 09:00 IST
    }

    @Test
    void utility_order_updates_ignore_quiet_hours() {
        var id = fixtures.identityWithPhone("919876543210");
        clock.setTo("2026-09-18T17:10:00Z");
        assertInstanceOf(Decision.Allow.class,
                policy.decide(utility(id, WHATSAPP, "out_for_delivery")));
    }

    @Test
    void cap_counts_across_journeys_not_within_one() {
        var id = fixtures.consentedIdentity();
        fixtures.sentMarketing(id, WHATSAPP, "cart_abandon", hoursAgo(2));
        var d = policy.decide(marketing(id, WHATSAPP, "winback", "winback_journey"));
        assertEquals(FREQUENCY_CAP, ((Decision.Block) d).reason());
    }
}
```

The policy engine is the highest-value place to spend test effort. It is the thing protecting your Meta quality rating, and a regression there is invisible until the rating drops.
