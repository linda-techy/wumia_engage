package in.brand.engage.orchestrator;

import in.brand.engage.core.messaging.Channel;
import in.brand.engage.persistence.Db;
import in.brand.engage.persistence.Sql;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code campaign:<id>} → the campaign as a one-off cascade (P6-T06): its
 * channel and template, then the optional follow-up after
 * {@code follow_up_after_minutes}. A click on the first step is a success
 * signal, which ends the run, so the follow-up reaches only non-clickers.
 *
 * <p>Lowest priority: a campaign yields to every journey (phase-5 §5). Stale
 * push tokens are never used: campaigns do not reach dormant devices.
 *
 * <p>Cached per campaign. A campaign's content is frozen once armed (the API
 * edits only DRAFT/READY ones), so a cached definition cannot go stale while
 * it runs.
 */
@Singleton
public class CampaignCascades implements CascadeDefinitionSource {

    public static final String PREFIX = "campaign:";
    static final Duration DEFAULT_TTL = Duration.ofHours(12);

    private final Db db;
    private final ConcurrentHashMap<UUID, Optional<CascadeDefinition>> cache = new ConcurrentHashMap<>();

    public CampaignCascades(Db db) {
        this.db = db;
    }

    public static String intentKey(UUID campaignId) {
        return PREFIX + campaignId;
    }

    @Override
    public Optional<CascadeDefinition> find(String intentKey) {
        if (intentKey == null || !intentKey.startsWith(PREFIX)) return Optional.empty();
        UUID id;
        try {
            id = UUID.fromString(intentKey.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        var cached = cache.get(id);
        if (cached != null) return cached;
        var loaded = load(intentKey, id);
        if (loaded.isPresent()) cache.put(id, loaded);       // absent: maybe not committed yet, look again next time
        return loaded;
    }

    private Optional<CascadeDefinition> load(String intentKey, UUID id) {
        return db.inTx(c -> {
            try (var ps = Sql.prepare(c, """
                    SELECT channel::text AS channel, template_key, ttl_seconds,
                           follow_up_channel::text AS follow_up_channel, follow_up_template_key, follow_up_after_minutes
                      FROM campaigns WHERE id = ?""", id);
                 var rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.<CascadeDefinition>empty();
                var ttl = rs.getObject("ttl_seconds") == null ? DEFAULT_TTL : Duration.ofSeconds(rs.getInt("ttl_seconds"));
                var followUp = rs.getString("follow_up_channel");
                var steps = new ArrayList<CascadeDefinition.Step>();
                steps.add(new CascadeDefinition.Step(Channel.fromDb(rs.getString("channel")), rs.getString("template_key"),
                        ttl, false, false,
                        followUp == null ? Duration.ZERO : Duration.ofMinutes(rs.getInt("follow_up_after_minutes")),
                        List.of()));
                if (followUp != null) {
                    steps.add(new CascadeDefinition.Step(Channel.fromDb(followUp), rs.getString("follow_up_template_key"),
                            ttl, false, false, Duration.ZERO, List.of()));
                }
                return Optional.of(new CascadeDefinition(intentKey, Priority.LOW_INTENT, steps));
            }
        });
    }
}
