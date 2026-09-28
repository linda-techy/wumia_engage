package in.brand.engage.channels;

import java.util.List;

/**
 * A send that the provider did not accept. Two kinds, so the router's handling
 * is exhaustive: {@link Permanent} is never retried, {@link Transient} may be.
 *
 * <p>Every failure carries any dead tokens found along the way, so pruning
 * still happens when nothing was delivered.
 */
public abstract sealed class ChannelException extends Exception
        permits ChannelException.Permanent, ChannelException.Transient {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final transient List<TokenPrune> prunes;

    ChannelException(String code, String message, List<TokenPrune> prunes, Throwable cause) {
        super(code + (message == null ? "" : ": " + message), cause);
        this.code = code;
        this.prunes = List.copyOf(prunes);
    }

    /** Short machine code, e.g. {@code payload_too_large}; goes into {@code sends.failed_reason}. */
    public String code() {
        return code;
    }

    public List<TokenPrune> prunes() {
        return prunes;
    }

    /**
     * This message cannot be delivered as it is. {@code suppress} says whether
     * the recipient is at fault: only then may the router add a suppression.
     * An oversized payload is our bug and must never suppress a customer
     * (README: per-code effect, P4-T02).
     */
    public static final class Permanent extends ChannelException {
        private static final long serialVersionUID = 1L;
        private final boolean suppress;

        public Permanent(String code, String message, boolean suppress, List<TokenPrune> prunes) {
            super(code, message, prunes, null);
            this.suppress = suppress;
        }

        public boolean suppress() {
            return suppress;
        }
    }

    /** May succeed on a later attempt: provider outage, quota, network. */
    public static final class Transient extends ChannelException {
        private static final long serialVersionUID = 1L;

        public Transient(String code, String message, List<TokenPrune> prunes, Throwable cause) {
            super(code, message, prunes, cause);
        }
    }
}
