package in.brand.engage.orchestrator;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * What a caller wants to achieve, never which channel (CLAUDE.md invariant 2).
 *
 * @param intentKey  resolves to a {@link CascadeDefinition}
 * @param subjectKey dedupe and cancel key: cart token, order id, variant id.
 *                   One live run per (intentKey, subjectKey), across people,
 *                   so a subject shared by many people (a restocked variant)
 *                   must include the identity: {@code <variantId>:<identityId>}.
 * @param vars       template variables, as strings (they are rendered text)
 * @param notBefore  null = now
 */
public record MessageIntent(UUID identityId, String intentKey, String subjectKey,
                            Map<String, String> vars, Instant notBefore) {

    public MessageIntent {
        Objects.requireNonNull(identityId, "identityId");
        Objects.requireNonNull(intentKey, "intentKey");
        Objects.requireNonNull(subjectKey, "subjectKey");
        vars = Map.copyOf(vars);
    }
}
