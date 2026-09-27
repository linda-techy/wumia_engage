package in.brand.engage.policy;

import in.brand.engage.core.messaging.Category;
import java.time.Instant;
import java.util.Map;

/**
 * The outcome of {@link PolicyEngine#decide}. Sealed, so every caller must
 * handle all three, and every one carries the config snapshot it was made
 * under (CLAUDE.md invariant 4): a block or a deferral is evidence too.
 */
public sealed interface Decision {

    long configSnapshotId();

    /**
     * @param unitCostPaise what one send is expected to cost (0 for push and email)
     * @param freeWindow    WhatsApp: the person messaged us within 24 h
     */
    record Allow(Template template, Category effectiveCategory, long unitCostPaise,
                 boolean freeWindow, Addresses addresses, long configSnapshotId) implements Decision {}

    /** {@code detail} goes into {@code sends.decision}: no PII, no secrets. */
    record Block(BlockReason reason, Map<String, Object> detail, long configSnapshotId) implements Decision {}

    /** Not a refusal but a "not yet" (invariant 5). The caller reschedules; it never drops. */
    record Defer(Instant until, BlockReason reason, long configSnapshotId) implements Decision {}
}
