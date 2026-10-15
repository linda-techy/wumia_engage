package in.brand.engage.orchestrator;

import in.brand.engage.policy.BlockReason;
import java.time.Instant;

/**
 * What became of one {@link SendCommand}. Sealed: every caller handles every
 * outcome. Each carries the {@code sends} row it produced (or found).
 */
public sealed interface SendResult {

    long sendId();

    record Sent(long sendId, int delivered) implements SendResult {}

    record Blocked(long sendId, BlockReason reason) implements SendResult {}

    /** Not a refusal. The idempotency key stays free for the retry (invariant 5). */
    record Deferred(long sendId, Instant until, BlockReason reason) implements SendResult {}

    /** @param retriable a transient provider failure; a permanent one never is */
    /**
     * @param escalateImmediately try the cascade's next step now, without waiting
     *                            (WhatsApp 131026 / 131049: the next channel may well work)
     */
    record Failed(long sendId, String code, boolean retriable, boolean escalateImmediately) implements SendResult {

        public Failed(long sendId, String code, boolean retriable) {
            this(sendId, code, retriable, false);
        }
    }

    /** The key was already used; nothing was sent. {@code status} is that row's. */
    record Duplicate(long sendId, String status) implements SendResult {}
}
