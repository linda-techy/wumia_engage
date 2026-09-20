package in.brand.engage.core.razorpay;

import java.util.Locale;

/**
 * Why a Razorpay payment failed, reduced to the distinction the copy needs.
 *
 * <p>Both kinds are real, gateway-confirmed payment attempts, so both qualify
 * for the utility {@code payment_failed} template. They differ in what the
 * second touch should say:
 * <ul>
 *   <li>{@link #TECHNICAL}: bank, network or gateway trouble. Suggest another rail
 *       ("UPI playing up? Card and netbanking work too").</li>
 *   <li>{@link #CUSTOMER_CANCELLED}: the shopper explicitly cancelled. Do not
 *       blame the bank; reassure instead (return terms, secure payment).</li>
 * </ul>
 *
 * <p>A customer-side <em>timeout</em> is deliberately TECHNICAL. On UPI it most
 * often means the collect request never surfaced in the shopper's UPI app,
 * which is exactly who "try card or netbanking" is for.
 *
 * <p>Razorpay's {@code error_reason} values vary by payment method, so this
 * errs towards TECHNICAL for anything unrecognised. The raw fields are stored
 * in {@code payment_attempts} so the mapping can be refined from real data.
 */
public enum FailureKind {
    TECHNICAL,
    CUSTOMER_CANCELLED;

    public static FailureKind classify(String errorSource, String errorReason) {
        var source = errorSource == null ? "" : errorSource.toLowerCase(Locale.ROOT);
        var reason = errorReason == null ? "" : errorReason.toLowerCase(Locale.ROOT);
        if (source.equals("customer") && reason.contains("cancel")) {
            return CUSTOMER_CANCELLED;
        }
        return TECHNICAL;
    }
}
