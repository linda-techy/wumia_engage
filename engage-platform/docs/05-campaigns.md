# 05 — Campaign lifecycle

A campaign is the most dangerous thing in the console: irreversible, expensive, and aimed at every customer you have. The lifecycle exists to make the dangerous step boring.

## States

```
  DRAFT ──estimate──▶ ESTIMATING ──▶ READY
                                       │
                          ┌────────────┴────────────┐
                   under threshold            over threshold
                          │                         │
                          ▼                         ▼
                      SCHEDULED             PENDING_APPROVAL
                          │                         │
                          │                    approve (2nd person)
                          │                         │
                          └──────────┬──────────────┘
                                     ▼
                                  RUNNING ──▶ COMPLETED
                                   │  ▲
                                 pause│resume
                                   ▼  │
                                  PAUSED ──▶ CANCELLED
```

Transitions are enforced in the service layer and re-checked in the executor. An operator clicking "start" twice, or two operators clicking it at once, must not double-send — the state machine plus a row lock is what prevents that.

## Dry run before anything

A campaign cannot leave `DRAFT` without an estimate, and the estimate runs the **real policy engine** against the **real audience**. Not a sample, not an approximation.

```java
@Singleton
public class CampaignEstimator {

    /**
     * Runs the full decision path per recipient with no side effects. This is
     * slower than a COUNT(*) and worth it: the number that matters is not
     * "how many are in the segment" but "how many will actually receive this,
     * and why not for the rest".
     */
    public Estimate estimate(Campaign campaign) {
        var audience = segments.resolve(campaign.segmentId());
        var blocks = new EnumMap<BlockReason, Integer>(BlockReason.class);
        int deliverable = 0;

        for (var identityId : audience) {
            var decision = policy.decide(DecisionRequest.forCampaign(campaign, identityId));
            switch (decision) {
                case Decision.Allow ignored -> deliverable++;
                case Decision.Defer ignored -> deliverable++;   // will send, just later
                case Decision.Block b -> blocks.merge(b.reason(), 1, Integer::sum);
            }
        }

        var unit = config.snapshot().rateFor(campaign.channel(), campaign.category());
        return new Estimate(
                audience.size(),
                deliverable,
                blocks,
                Paise.of(unit.value() * deliverable),
                config.snapshot().id());
    }
}
```

The UI shows this before the send button becomes active:

```
Audience                      48,210
Will receive                  31,884   (66%)
Estimated spend               ₹27,506

Not receiving                 16,326
  No marketing consent         9,140   ← largest bucket. Fix acquisition, not this campaign.
  Frequency cap                4,201   ← they heard from you today already
  Global holdout               2,410   ← intentional, never message
  Suppressed                     412
  Unreachable                    163
```

That breakdown is the single most useful screen in the console. "No marketing consent" dominating means your opt-in capture is broken and no campaign will fix it. "Frequency cap" dominating means you are already over-messaging and this campaign is fighting your own journeys.

Estimates carry the `config_snapshot_id` they were computed under. If config changes between estimate and approval, the UI flags the estimate stale and requires a re-run.

## Arming: freeze the audience

On transition to `SCHEDULED`, the audience is materialised into `campaign_recipients` in one transaction, with holdout buckets assigned.

```java
@Transactional
public void arm(UUID campaignId, UUID actor) {
    var campaign = campaigns.requireInState(campaignId, READY, PENDING_APPROVAL);

    // Materialise once. A live segment query during execution is a moving
    // target: someone who converts mid-run leaves the segment and their row
    // vanishes, someone who signs up joins halfway and gets a partial send,
    // and the final report reconciles against nothing.
    int inserted = recipients.materialise(campaignId, campaign.segmentId(),
                                          campaign.holdoutPct());

    campaigns.transition(campaignId, SCHEDULED, actor);
    audit.record("campaign.arm", actor, Map.of(
            "campaign", campaignId, "recipients", inserted));
}
```

Holdout buckets are assigned deterministically by hashing `campaignId:identityId`, not randomly, so the assignment is reproducible when you analyse the result weeks later.

## Execution

Workers claim batches with `SKIP LOCKED`, rate-limited by a token bucket in Postgres so all pods share one budget.

```java
@Singleton
public class CampaignExecutor {

    @Scheduled(fixedDelay = "5s")
    void run() {
        for (var campaign : campaigns.running()) {
            int permits = rateLimiter.acquire(campaign.id(),
                    campaign.sendRatePerMinute(), Duration.ofSeconds(5));
            if (permits == 0) continue;

            var batch = recipients.claim(campaign.id(), permits);
            for (var r : batch) {
                // Re-check the budget cap every message, not once per batch.
                // A 200k campaign at ₹0.86 is ₹172,000; the ceiling has to
                // hold mid-run, not just at arm time.
                if (campaign.budgetCapPaise() != null
                        && spend.forCampaign(campaign.id()).value() >= campaign.budgetCapPaise()) {
                    campaigns.pause(campaign.id(), "budget cap reached");
                    alerts.notify("Campaign paused: budget cap", campaign);
                    break;
                }
                dispatch(campaign, r);
            }
            if (recipients.pendingCount(campaign.id()) == 0) {
                campaigns.complete(campaign.id());
            }
        }
    }

    private void dispatch(Campaign campaign, Recipient r) {
        var result = router.send(SendCommand.forCampaign(campaign, r));
        recipients.record(campaign.id(), r.identityId(), result);
    }
}
```

Note that the executor calls `router.send()` like everything else. **There is no campaign fast path.** An operator with `CAMPAIGN_SEND` cannot bypass consent, caps or budget — the console is a way to propose work, not a way to override policy. If a campaign genuinely needs a higher cap, that is a `CRITICAL` config change with its own approval, effective window and audit trail.

### Rate limiting

```sql
-- Token bucket shared across pods. One row per campaign, refilled lazily.
UPDATE campaign_rate_buckets
   SET tokens = LEAST(
         capacity,
         tokens + FLOOR(EXTRACT(EPOCH FROM (now() - refilled_at)) * refill_per_second)
       ) - :requested,
       refilled_at = now()
 WHERE campaign_id = :id
   AND tokens + FLOOR(EXTRACT(EPOCH FROM (now() - refilled_at)) * refill_per_second) >= :requested
RETURNING tokens;
```

Rate limiting is not politeness. A 200k blast in ten minutes is how a High quality rating becomes Low overnight, and a Low rating can cut your daily messaging limit by two orders of magnitude within 24 hours. Default 600/minute; raise it only with evidence.

## Pause and cancel

**Pause** stops claiming new recipients. In-flight messages complete — you cannot unsend an HTTP request already in the air. Resume picks up exactly where it stopped, because `campaign_recipients.state` is the position.

**Cancel** pauses and marks remaining recipients `skipped`. Terminal.

Both are one click from the campaign detail page and from the global dashboard header. During an incident nobody should be hunting through a table to find the stop button.

## Reporting

Per campaign: sent, delivered, read, clicked, failed, blocked by reason, spend, and — the one that matters — **incremental revenue against the holdout**.

```sql
WITH cohort AS (
  SELECT identity_id, bucket FROM campaign_recipients WHERE campaign_id = :id
),
rev AS (
  SELECT c.bucket, c.identity_id, COALESCE(SUM(v.value_paise), 0) AS paise
    FROM cohort c
    LEFT JOIN conversions v
      ON v.identity_id = c.identity_id
     AND v.occurred_at BETWEEN :startedAt AND :startedAt + interval '7 days'
   GROUP BY 1, 2
)
SELECT bucket,
       COUNT(*)                          AS customers,
       ROUND(AVG(paise))                 AS arpu_paise,
       COUNT(*) FILTER (WHERE paise > 0) AS converters
  FROM rev GROUP BY bucket;
```

Report ARPU lift and cost side by side, never open rate alone. WhatsApp open rates run above 90%, which makes every campaign look like a triumph and tells you nothing about whether it caused a sale.

A campaign with a 94% open rate, ₹40,000 spend and a 0.3% ARPU lift over holdout lost money. The console should say so plainly.

## Templates

Templates are versioned and the category is validated on save, because the expensive mistake is promotional language inside a utility template. Meta re-classifies or rejects it, and a rejection on a live order-update template breaks your transactional flow — not just a campaign.

```java
private static final List<Pattern> PROMO_MARKERS = List.of(
        Pattern.compile("\\b\\d{1,3}\\s?%\\s?(off|discount)", CASE_INSENSITIVE),
        Pattern.compile("\\bsale\\b", CASE_INSENSITIVE),
        Pattern.compile("\\boffer\\b", CASE_INSENSITIVE),
        Pattern.compile("\\bshop now\\b", CASE_INSENSITIVE),
        Pattern.compile("\\bflat\\s?₹", CASE_INSENSITIVE),
        Pattern.compile("\\blimited time\\b", CASE_INSENSITIVE));

void validateCategory(Template t) {
    if (t.category() != UTILITY && t.category() != AUTHENTICATION) return;
    PROMO_MARKERS.stream()
        .filter(p -> p.matcher(t.bodyText()).find())
        .findFirst()
        .ifPresent(p -> { throw new ValidationException(
            "Template '%s' is %s but contains promotional language (%s). Meta will "
          + "re-classify or reject it. Move the offer to a marketing template."
              .formatted(t.key(), t.category(), p.pattern())); });
}
```

The same check runs in CI, so a bad template fails the build rather than failing in production at 9am on sale day.

Template edits go through submit-to-Meta and wait for approval. The UI shows the Meta status (`PENDING`, `APPROVED`, `REJECTED`) and the per-template quality rating, and a template whose quality drops to `LOW` is auto-paused with an alert. That auto-pause is worth more than it looks: a single bad template dragging down the whole phone number's rating is the most common way brands lose their messaging tier.
