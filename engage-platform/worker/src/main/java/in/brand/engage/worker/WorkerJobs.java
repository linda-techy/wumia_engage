package in.brand.engage.worker;

import in.brand.engage.orchestrator.DefaultOrchestrator;
import in.brand.engage.persistence.CapabilityService;
import in.brand.engage.persistence.Db;
import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The event dispatch loop and the cascade tick. Several workers may run all of
 * them at once: every claim is {@code FOR UPDATE SKIP LOCKED} or a lease.
 * {@code engage.worker.jobs-enabled=false} turns them off (tests drive them by hand).
 */
@Singleton
@Requires(property = "engage.worker.jobs-enabled", value = "true", defaultValue = "true")
public class WorkerJobs {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerJobs.class);

    private final EventDispatcher events;
    private final DefaultOrchestrator orchestrator;
    private final CampaignExecutor campaigns;
    private final CapabilityService capability;
    private final Db db;
    private final java.util.Optional<WhatsAppTemplates> whatsappTemplates;
    private final in.brand.engage.worker.intents.LapsedCustomers lapsed;
    private final SpendReconciliation reconciliation;

    public WorkerJobs(EventDispatcher events, DefaultOrchestrator orchestrator, CampaignExecutor campaigns,
                      CapabilityService capability, Db db, java.util.Optional<WhatsAppTemplates> whatsappTemplates,
                      in.brand.engage.worker.intents.LapsedCustomers lapsed, SpendReconciliation reconciliation) {
        this.reconciliation = reconciliation;
        this.whatsappTemplates = whatsappTemplates;
        this.lapsed = lapsed;
        this.events = events;
        this.orchestrator = orchestrator;
        this.campaigns = campaigns;
        this.capability = capability;
        this.db = db;
    }

    /** Every second; drains while batches come back full. */
    @Scheduled(fixedDelay = "1s", initialDelay = "5s")
    void dispatchEvents() {
        try {
            while (events.dispatchOnce() == EventDispatcher.BATCH) {
                // more waiting
            }
        } catch (RuntimeException e) {
            LOG.error("event dispatch pass failed", e);
        }
    }

    /** Steps that came due: deferrals, delayed intents. */
    @Scheduled(fixedDelay = "5s", initialDelay = "10s")
    void tickCascades() {
        try {
            while (orchestrator.tick(200) == 200) {
                // more due
            }
        } catch (RuntimeException e) {
            LOG.error("cascade tick failed", e);
        }
    }

    /** Armed campaigns, one batch each per pass (P6-T06). The rate bucket does the pacing. */
    @Scheduled(fixedDelay = "2s", initialDelay = "15s")
    void runCampaigns() {
        try {
            campaigns.runOnce();
        } catch (RuntimeException e) {
            LOG.error("campaign pass failed", e);
        }
    }

    /**
     * 01:30 IST: customers whose last order just crossed the win-back line and
     * who have not visited since (P5-T08). Safe on several workers: each lapse
     * is one event by its dedupe key.
     */
    @Scheduled(cron = "0 30 1 * * *", zoneId = "Asia/Kolkata")
    void findLapsedCustomers() {
        try {
            lapsed.detect(java.time.Instant.now());
        } catch (RuntimeException e) {
            LOG.error("win-back lapse detection failed", e);
        }
    }

    /** 02:00 IST: a support request's reply text (often an address) is cleared 30 days after it is resolved. */
    @Scheduled(cron = "0 0 2 * * *", zoneId = "Asia/Kolkata")
    void clearResolvedSupportText() {
        try {
            int n = db.inTx(c -> in.brand.engage.persistence.Sql.update(c, """
                    UPDATE support_requests SET message_text = NULL, updated_at = now()
                     WHERE status = 'done' AND resolved_at < now() - interval '30 days' AND message_text IS NOT NULL"""));
            if (n > 0) LOG.info("support requests: cleared the reply text of {} resolved requests", n);
        } catch (RuntimeException e) {
            LOG.error("clearing resolved support request text failed", e);
        }
    }

    /**
     * 06:00 IST on the 1st to the 15th: last month's WhatsApp ledger against
     * Meta's bill (P7-T03), once the bill is uploaded; over 1% off is an ERROR.
     */
    @Scheduled(cron = "0 0 6 1-15 * *", zoneId = "Asia/Kolkata")
    void reconcileSpend() {
        try {
            var month = java.time.YearMonth.now(java.time.ZoneId.of("Asia/Kolkata")).minusMonths(1);
            if (!reconciliation.checked(month)) reconciliation.run(month);
        } catch (RuntimeException e) {
            LOG.error("spend reconciliation failed", e);
        }
    }

    /** 03:30 IST: INCAPABLE past its 120-day recheck goes back to UNKNOWN (CLAUDE.md §4.2). */
    @Scheduled(cron = "0 30 3 * * *", zoneId = "Asia/Kolkata")
    void recheckCapability() {
        try {
            int n = db.inTx(c -> capability.expireIncapable(c, java.time.Instant.now()));
            if (n > 0) LOG.info("capability: {} INCAPABLE numbers back to UNKNOWN for a recheck", n);
        } catch (RuntimeException e) {
            LOG.error("capability recheck failed", e);
        }
    }

    /** Every 30 minutes, when a WhatsApp Business Account is configured: Meta's verdicts and number quality (P4-T03). */
    @Scheduled(fixedDelay = "30m", initialDelay = "1m")
    void syncWhatsAppTemplates() {
        whatsappTemplates.ifPresent(t -> {
            try {
                var r = t.sync();
                if (r.mismatches() > 0 || r.paused() > 0) LOG.warn("WhatsApp template sync: {}", r);
            } catch (RuntimeException e) {
                LOG.error("WhatsApp template sync failed", e);
            }
        });
    }
}
