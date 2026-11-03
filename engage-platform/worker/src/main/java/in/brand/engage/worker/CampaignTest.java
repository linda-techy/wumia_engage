package in.brand.engage.worker;

import in.brand.engage.orchestrator.CampaignCascades;
import in.brand.engage.orchestrator.MessageIntent;
import in.brand.engage.orchestrator.MessageOrchestrator;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import io.micronaut.context.ApplicationContext;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * {@code worker campaign-test <campaign-id> <email>}: the campaign's message to
 * one person, before it is armed (owner, 2026-11-04: test with
 * marylindajohnson22 first). The person is found by email; the message is the
 * campaign's template and values with their first name, through the
 * orchestrator, so consent, caps and quiet hours apply as to the real send.
 *
 * <p>Its own run ({@code test:<identity>} subject), so the real campaign still
 * reaches the tester. The worker service sends it on its next tick.
 */
final class CampaignTest {

    /** A tester found by email: the identity and their first name. */
    record Tester(UUID identityId, String firstName) {}

    private CampaignTest() {}

    static boolean handles(String[] args) {
        return args.length > 0 && args[0].equals("campaign-test");
    }

    static void run(String[] args) {
        if (args.length < 3) {
            System.out.println("usage: campaign-test <campaign-id> <email>");
            return;
        }
        UUID campaignId = UUID.fromString(args[1]);
        var email = args[2].strip().toLowerCase(Locale.ROOT);
        try (var ctx = ApplicationContext.builder()
                .properties(Map.of("engage.worker.jobs-enabled", "false", "micronaut.server.port", "-1"))
                .start()) {
            var db = ctx.getBean(Db.class);
            var orchestrator = ctx.getBean(MessageOrchestrator.class);
            var intent = db.inTx(c -> intent(c, campaignId, email));
            if (intent == null) {
                System.out.println("campaign-test: no such campaign, or no customer with that email");
                return;
            }
            var run = orchestrator.dispatch(intent);
            System.out.printf("campaign-test: run %s %s for the tester; the worker sends it on its next tick%n",
                    run.id(), run.status());
        }
    }

    /** Null when the campaign or the person is unknown. */
    static MessageIntent intent(Connection c, UUID campaignId, String email) throws SQLException {
        var vars = new HashMap<String, String>();
        try (var ps = Sql.prepare(c, """
                SELECT key, value FROM campaigns k, jsonb_each_text(k.vars) WHERE k.id = ?""", campaignId);
             var rs = ps.executeQuery()) {
            while (rs.next()) vars.put(rs.getString(1), rs.getString(2));
        }
        try (var ps = Sql.prepare(c, "SELECT 1 FROM campaigns WHERE id = ?", campaignId); var rs = ps.executeQuery()) {
            if (!rs.next()) return null;
        }
        var tester = tester(c, email);
        if (tester == null) return null;
        return new MessageIntent(tester.identityId(), CampaignCascades.intentKey(campaignId),
                "test:" + tester.identityId(), CampaignExecutor.forRecipient(vars, tester.firstName()), null);
    }

    /** The live identity holding this email (a merged one points at the survivor). */
    static Tester tester(Connection c, String email) throws SQLException {
        try (var ps = Sql.prepare(c, """
                SELECT COALESCE(i.merged_into, i.id) AS id,
                       (SELECT p.attrs->>'first_name' FROM profiles p WHERE p.identity_id = COALESCE(i.merged_into, i.id))
                  FROM identity_keys k JOIN identities i ON i.id = k.identity_id
                 WHERE k.kind = 'email' AND lower(k.value) = ?
                 LIMIT 1""", email); var rs = ps.executeQuery()) {
            return rs.next() ? new Tester(rs.getObject(1, UUID.class), rs.getString(2)) : null;
        }
    }
}
