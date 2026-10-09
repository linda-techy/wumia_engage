package in.brand.engage.orchestrator.templates;

import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import jakarta.inject.Singleton;

/**
 * Meta's verdict on one WhatsApp template locale ({@code wa_templates}, kept
 * by the sync and the template webhooks): only APPROVED, and quality not
 * RED, may be sent. Checked by the router per locale, because each locale is
 * its own Meta template and can be approved, paused or rejected on its own.
 */
@Singleton
public class WaTemplateGate {

    private final Db db;

    public WaTemplateGate(Db db) {
        this.db = db;
    }

    /** @return null when sendable, else why not (for {@code sends.failed_reason}) */
    public String refusal(String providerName, String language) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, """
                    SELECT status, quality FROM wa_templates WHERE provider_name = ? AND language = ?""", providerName, language);
                 var rs = ps.executeQuery()) {
                if (!rs.next()) return "wa_template_unknown";
                if (!"APPROVED".equals(rs.getString(1))) return "wa_template_" + rs.getString(1).toLowerCase(java.util.Locale.ROOT);
                if ("RED".equals(rs.getString(2))) return "wa_template_quality_red";
                return null;
            }
        });
    }
}
