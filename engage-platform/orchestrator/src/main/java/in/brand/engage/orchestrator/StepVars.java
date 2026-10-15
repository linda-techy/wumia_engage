package in.brand.engage.orchestrator;

import java.util.Map;

/**
 * Values a step can only get at send time, not when the run starts: a
 * single-use discount code must be created when the message goes out, and
 * only for runs that get that far. A step names its provider with
 * {@link CascadeDefinition.Step#withVarsFrom}; the runner calls it after the
 * guards pass, outside any transaction, and keeps what it returns on the run
 * (so a retry reuses it). Providers live in the worker, which may call Shopify.
 */
public interface StepVars {

    /** The name steps refer to. */
    String name();

    /**
     * @param vars the run's variables so far (including any this provider kept earlier)
     * @return values to add; empty when there is nothing to add (e.g. already provided)
     * @throws Unavailable the step cannot go: it is skipped like a failed guard, wait not consumed
     */
    Map<String, String> provide(CascadeRun run, Map<String, String> vars) throws Unavailable;

    /** The provider cannot supply what the step needs; the message says why (kept as the skip reason). */
    final class Unavailable extends Exception {
        private static final long serialVersionUID = 1L;

        public Unavailable(String reason) {
            super(reason);
        }
    }
}
