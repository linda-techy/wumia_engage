package in.brand.engage.worker.intents;

import in.brand.engage.orchestrator.CascadeRun;
import in.brand.engage.orchestrator.StepVars;
import in.brand.engage.policy.ConfigResolver;
import jakarta.inject.Singleton;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A journey's discount code, made when its discount WhatsApp goes out
 * ({@link StepVars} {@value #NAME}). Used by abandoned checkout and win-back;
 * the run's {@code discount_config} var names the journey's config prefix
 * ({@code journey.abandon}, {@code journey.winback}).
 *
 * <p>In {@code unique} mode ({@code <prefix>.discount_mode}) each customer gets
 * their own single-use code, valid for {@code <prefix>.discount_valid_hours}:
 * a code that leaks to a coupon site is worth one order, and nobody learns to
 * wait for a shared code. In {@code shared} mode the run already carries the
 * operator's code and nothing is created.
 *
 * <p>If Shopify refuses (no {@code write_discounts}, an outage), the step is
 * skipped with the reason recorded; the customer never gets a code that does
 * not exist.
 */
@Singleton
public class JourneyDiscount implements StepVars {

    public static final String NAME = "journey_discount";
    /** No 0/O or 1/I/L: the code is read off a phone and typed in. */
    static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final Logger LOG = LoggerFactory.getLogger(JourneyDiscount.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DiscountCodes codes;
    private final ConfigResolver config;
    private final Clock clock;

    public JourneyDiscount(DiscountCodes codes, ConfigResolver config, Clock clock) {
        this.codes = codes;
        this.config = config;
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Map<String, String> provide(CascadeRun run, Map<String, String> vars) throws Unavailable {
        var existing = vars.get("code");
        if (existing != null && !existing.isBlank()) return withPath(vars, existing, Map.of());   // shared code, or made earlier
        if (!"unique".equals(vars.get("discount_mode"))) throw new Unavailable("no_code");
        var prefix = vars.getOrDefault("discount_config", "journey.abandon");

        var snapshot = config.snapshot();
        int percent = (int) snapshot.longValue(prefix + ".discount_percent", "*");
        long minOrder = snapshot.longValue(prefix + ".discount_min_order_paise", "*");
        var now = clock.instant();
        var ends = now.plus(Duration.ofHours(snapshot.longValue(prefix + ".discount_valid_hours", "*")));
        for (int attempt = 0; attempt < 2; attempt++) {
            var code = newCode();
            try {
                codes.create(new DiscountCodes.Spec(code, run.intentKey() + " " + code + " (run " + run.id() + ")",
                        percent, minOrder, now, ends));
                return withPath(vars, code, Map.of("code", code, "code_ends_at", ends.toString()));
            } catch (DiscountCodes.Failed e) {
                if (e.taken() && attempt == 0) continue;                     // 31^8 codes: a clash is rare, retry once
                LOG.warn("run {}: no discount code: {}", run.id(), e.getMessage());
                throw new Unavailable("shopify: " + e.getMessage());
            }
        }
        throw new Unavailable("shopify: code taken twice");
    }

    /**
     * When the run names a {@code discount_redirect} (e.g. {@code /cart}), also the
     * WhatsApp button suffix of Shopify's auto-apply link, {@code discount/<code>?redirect=%2Fcart}:
     * the tap applies the code by itself, so nobody has to type it.
     */
    static Map<String, String> withPath(Map<String, String> vars, String code, Map<String, String> out) {
        var redirect = vars.get("discount_redirect");
        if (redirect == null || redirect.isBlank()) return out;
        var all = new java.util.HashMap<>(out);
        all.put("discount_path", "discount/" + java.net.URLEncoder.encode(code, java.nio.charset.StandardCharsets.UTF_8)
                + "?redirect=" + java.net.URLEncoder.encode(redirect, java.nio.charset.StandardCharsets.UTF_8));
        return Map.copyOf(all);
    }

    static String newCode() {
        var sb = new StringBuilder("WUM");
        for (int i = 0; i < 8; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }
}
