package in.brand.engage.channels.whatsapp;

import in.brand.engage.channels.ChannelException;
import java.util.List;

/**
 * A Graph API error → what the router and cascade should do (P4-T02 table,
 * CLAUDE.md §4). The router's generic "permanent → suppression" must never
 * apply to 131026 or 131049: those are capability signals, and a suppression
 * would silently write the customer off (CLAUDE.md §4.1). So no WhatsApp
 * error here suppresses; the per-code effects (capability strike, backoff,
 * consent withdrawal, template pause) happen where the status is observed.
 */
public final class WaError {

    private WaError() {}

    /** Not a WhatsApp user, or not reachable now: try the next channel at once. */
    public static final int UNDELIVERABLE = 131026;
    /** Meta's per-user marketing limit: back off, try the next channel at once. */
    public static final int MARKETING_LIMIT = 131049;
    /** Re-engagement needed: free-form outside the 24 h window. Our window logic is wrong. */
    public static final int OUTSIDE_WINDOW = 131047;
    /** The user stopped marketing messages in WhatsApp. */
    public static final int USER_STOPPED_MARKETING = 131050;
    public static final int RATE_LIMITED = 130429;
    public static final int PAIR_RATE_LIMITED = 131056;

    public static boolean templateProblem(int code) {
        return code >= 132000 && code <= 132015;
    }

    public static ChannelException classify(int httpStatus, Integer code, String message) {
        if (code == null) {
            return httpStatus >= 500 || httpStatus == 429
                    ? new ChannelException.Transient("wa_http_" + httpStatus, message, List.of(), null)
                    : new ChannelException.Permanent("wa_http_" + httpStatus, message, false, List.of());
        }
        var c = "wa_" + code;
        if (code == UNDELIVERABLE || code == MARKETING_LIMIT) {
            return new ChannelException.Permanent(c, message, false, true, List.of());
        }
        if (code == RATE_LIMITED || code == PAIR_RATE_LIMITED || httpStatus >= 500) {
            return new ChannelException.Transient(c, message, List.of(), null);
        }
        // 131047 (our window bug), 131050 (user stopped marketing), 1320xx (template
        // problem) and everything else (bad parameters, auth): permanent, never a
        // suppression. Retrying would fail the same way.
        return new ChannelException.Permanent(c, message, false, List.of());
    }
}
