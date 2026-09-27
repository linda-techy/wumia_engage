package in.brand.engage.policy;

/**
 * Why a message did not go out (or not yet). Stored on the {@code sends} row,
 * so these names are the analytics vocabulary: rename one and history splits.
 */
public enum BlockReason {
    // 04-backend-micronaut.md
    TEMPLATE_UNKNOWN, TEMPLATE_PAUSED, CHANNEL_MISMATCH,
    UNREACHABLE, SUPPRESSED, NO_MARKETING_CONSENT,
    QUIET_HOURS, FREQUENCY_CAP, TEMPLATE_COOLDOWN,
    DAILY_BUDGET_EXHAUSTED, CHANNEL_HALTED, JOURNEY_DISABLED,
    HOLDOUT_CONTROL, HOLDOUT_GLOBAL,
    // phase-3 §1: WhatsApp needs an opt-in for every business-initiated message.
    NO_WHATSAPP_OPT_IN,
    // A live cascade of higher priority owns this person (P5).
    HIGHER_PRIORITY_ACTIVE,
    // halt.marketing kill switch.
    MARKETING_HALTED,
    // Meta throttled us (131049): no WhatsApp to this person until the backoff ends (P4).
    WA_BACKOFF
}
