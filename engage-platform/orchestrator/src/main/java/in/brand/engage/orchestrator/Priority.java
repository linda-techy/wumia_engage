package in.brand.engage.orchestrator;

/**
 * Cascade priority, stored as {@code cascade_runs.priority} (V4). The scale is
 * phase-5 §5: a live higher-priority cascade suppresses lower ones for the
 * same person (HIGHER_PRIORITY_ACTIVE, P5). It supersedes the three-value
 * example in CLAUDE.md §3.1, which predates the schema.
 */
public enum Priority {
    /** payment_failed, order_tracking, ndr_rescue: never suppressed. */
    TRANSACTIONAL(1),
    /** checkout_abandon, back_in_stock. */
    HIGH_INTENT(2),
    /** cart_recovery, price_drop. */
    MID_INTENT(3),
    /** browse_abandon, winback: yields to everything. */
    LOW_INTENT(4);

    private final int level;

    Priority(int level) {
        this.level = level;
    }

    public int level() {
        return level;
    }
}
