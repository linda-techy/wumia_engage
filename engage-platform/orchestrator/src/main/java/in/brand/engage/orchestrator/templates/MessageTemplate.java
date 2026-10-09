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
 * @param buttons  WhatsApp URL buttons (empty for other channels)
 * @param source   where it was loaded from, for lint messages
 */
public record MessageTemplate(String key, Channel channel, Category category, Duration cooldown,
                              Map<String, Copy> locales, List<String> vars, Map<String, String> samples,
                              List<Button> buttons, String source) {

    public static final String FALLBACK_LOCALE = "en";

    /**
     * One locale's copy. Push uses title and body. WhatsApp uses body only,
     * plus the name and language Meta knows this copy by: Hinglish is
     * submitted as its own template (e.g. {@code order_shipped_v1_hi}) in
     * language {@code en}, since Meta has no Hinglish language code.
     */
    public record Copy(String title, String body, String language, String providerName) {

        public Copy(String title, String body) {
            this(title, body, null, null);
        }
    }

    /**
     * A WhatsApp URL button. Meta allows one variable, at the end of the URL:
     * {@code https://wumika.com/apps/track/{{order_no}}} is submitted as
     * {@code .../track/{{1}}} and the value of {@code order_no} is sent as its suffix.
     */
    public record Button(String text, String url) {}

    /** WhatsApp templates in category SERVICE are free-form replies inside the 24 h window, never submitted to Meta. */
    public boolean freeForm() {
        return channel == Channel.WHATSAPP && category == Category.SERVICE;
    }
}
