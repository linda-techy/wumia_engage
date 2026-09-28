package in.brand.engage.orchestrator.templates;

import in.brand.engage.core.messaging.Category;
import in.brand.engage.core.messaging.Channel;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * A template as authored in {@code templates/<channel>/*.yaml}. Code, not data:
 * copy changes go through review and the lint, and the {@code templates} table
 * only mirrors key, channel and category (plus an operator's pause).
 *
 * @param locales  locale tag → copy; {@code en} is mandatory and is the fallback
 * @param vars     every variable the caller must supply
 * @param samples  the longest realistic value of each variable used in the copy
 * @param source   where it was loaded from, for lint messages
 */
public record MessageTemplate(String key, Channel channel, Category category, Duration cooldown,
                              Map<String, Copy> locales, List<String> vars, Map<String, String> samples,
                              String source) {

    public static final String FALLBACK_LOCALE = "en";

    public record Copy(String title, String body) {}
}
