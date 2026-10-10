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
 * The abandoned-checkout discount code, made when the discount WhatsApp goes
 * out ({@link StepVars} {@value #NAME}). In {@code unique} mode
 * ({@code journey.abandon.discount_mode}) each customer gets their own
 * single-use code, valid for {@code discount_valid_hours}: a code that leaks
 * to a coupon site is worth one order, and nobody learns to abandon for a
 * shared code. In {@code shared} mode the run already carries the operator's
 * code and nothing is created.
 *
 * <p>If Shopify refuses (no {@code write_discounts}, an outage), the step is
 * skipped with the reason recorded; the customer never gets a code that does
 * not exist.
 */
@Singleton
public class AbandonDiscount implements StepVars {

    public static final String NAME = "abandon_discount";
    /** No 0/O or 1/I/L: the code is read off a phone and typed in. */
    static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final Logger LOG = LoggerFactory.getLogger(AbandonDiscount.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DiscountCodes codes;
    private final ConfigResolver config;
    private final Clock clock;

    public AbandonDiscount(DiscountCodes codes, ConfigResolver config, Clock clock) {
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
        if (existing != null && !existing.isBlank()) return Map.of();      // shared code, or made on an earlier attempt
        if (!"unique".equals(vars.get("discount_mode"))) throw new Unavailable("no_code");

        var snapshot = config.snapshot();
        int percent = (int) snapshot.longValue("journey.abandon.discount_percent", "*");
        long minOrder = snapshot.longValue("journey.abandon.discount_min_order_paise", "*");
        var now = clock.instant();
        var ends = now.plus(Duration.ofHours(snapshot.longValue("journey.abandon.discount_valid_hours", "*")));
        for (int attempt = 0; attempt < 2; attempt++) {
            var code = newCode();
            try {
                codes.create(new DiscountCodes.Spec(code, "Abandoned checkout " + code + " (run " + run.id() + ")",
                        percent, minOrder, now, ends));
                return Map.of("code", code, "code_ends_at", ends.toString());
            } catch (DiscountCodes.Failed e) {
                if (e.taken() && attempt == 0) continue;                     // 31^8 codes: a clash is rare, retry once
                LOG.warn("run {}: no discount code: {}", run.id(), e.getMessage());
                throw new Unavailable("shopify: " + e.getMessage());
            }
        }
        throw new Unavailable("shopify: code taken twice");
    }

    static String newCode() {
        var sb = new StringBuilder("WUM");
        for (int i = 0; i < 8; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }
}
