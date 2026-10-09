package in.brand.engage.admin.campaigns;

import in.brand.engage.admin.segments.Predicates.Fragment;
import in.brand.engage.admin.segments.SegmentCompiler;
import in.brand.engage.admin.segments.SegmentDsl;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A campaign's audience, as one query shared by the dry run and arming, so
 * the estimate and the frozen recipients never disagree about who is in.
 * Per identity in the segment:
 *
 * <ul>
 * <li>{@code held}: in the campaign's own holdout, by a stable hash of
 *     identity and campaign. Never messaged; measured against.</li>
 * <li>{@code excluded}: a campaign rule, not policy. Push: only stale tokens
 *     ({@code STALE_TOKENS}); they inflate the audience and deflate clicks.
 *     WhatsApp: capability {@code UNKNOWN} or {@code INCAPABLE}; campaigns do
 *     not pay to discover capability (phase-6 §4).</li>
 * </ul>
 *
 * Everyone else goes to the policy engine: per identity in the dry run, per
 * send in the executor.
 */
final class CampaignAudience {

    private CampaignAudience() {}

    static final String STALE_TOKENS = "STALE_TOKENS";
    static final String WA_UNKNOWN = "WA_CAPABILITY_UNKNOWN";
    static final String WA_INCAPABLE = "WA_INCAPABLE";

    /** {@code SELECT id, held, excluded FROM identities i WHERE <segment>}. */
    static Fragment query(SegmentDsl segment, String channel, UUID campaignId, BigDecimal holdoutPct) {
        var where = SegmentCompiler.where(segment);
        var params = new ArrayList<Object>();
        params.add(campaignId.toString());
        params.add(holdoutPct.movePointRight(2).intValue());          // basis points
        params.addAll(where.params());
        var sql = "SELECT i.id, (abs(hashtextextended(i.id::text || ?, 0)) % 10000) < ? AS held, "
                + exclusion(channel) + " AS excluded FROM identities i WHERE " + where.sql();
        return new Fragment(sql, List.copyOf(params));
    }

    private static String exclusion(String channel) {
        return switch (channel) {
            case "push" -> """
                    CASE WHEN EXISTS (SELECT 1 FROM device_health d WHERE d.identity_id = i.id AND d.state = 'stale')
                          AND NOT EXISTS (SELECT 1 FROM device_health d WHERE d.identity_id = i.id AND d.state = 'active')
                         THEN '%s' END""".formatted(STALE_TOKENS);
            case "whatsapp" -> """
                    CASE COALESCE((SELECT cc.state FROM channel_capability cc
                                    WHERE cc.identity_id = i.id AND cc.channel = 'whatsapp'), 'UNKNOWN')
                         WHEN 'UNKNOWN' THEN '%s' WHEN 'INCAPABLE' THEN '%s' END""".formatted(WA_UNKNOWN, WA_INCAPABLE);
            default -> throw new IllegalArgumentException("campaigns run on push or whatsapp, not " + channel);
        };
    }
}
