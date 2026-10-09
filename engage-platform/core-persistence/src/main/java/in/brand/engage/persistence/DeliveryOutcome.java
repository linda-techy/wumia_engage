package in.brand.engage.persistence;

import in.brand.engage.core.messaging.Category;
import java.time.Instant;
import java.util.Objects;

/**
 * What a provider told us happened to one send, for capability learning
 * (CLAUDE.md §4.2).
 *
 * @param providerCode the provider's error code on {@code FAILED} (WhatsApp 131026, ...); null otherwise
 * @param category     the category the template was sent as
 * @param msisdn       the number it went to, {@code 91XXXXXXXXXX}
 * @param at           when it happened (the provider's timestamp, not processing time)
 */
public record DeliveryOutcome(Type type, Integer providerCode, Category category, String msisdn, Instant at) {

    public enum Type { DELIVERED, READ, REPLIED, FAILED }

    public DeliveryOutcome {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(at, "at");
    }

    public static DeliveryOutcome failed(int code, Category category, String msisdn, Instant at) {
        return new DeliveryOutcome(Type.FAILED, code, category, msisdn, at);
    }

    public static DeliveryOutcome of(Type type, Instant at) {
        return new DeliveryOutcome(type, null, null, null, at);
    }
}
