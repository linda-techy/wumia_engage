package in.brand.engage.core.identity;

import java.util.Optional;

/**
 * Indian mobile numbers, normalised to 12 digits without a plus: {@code 91XXXXXXXXXX}.
 * That is also WhatsApp's {@code wa_id} format, so one value serves identity,
 * WhatsApp and DLT SMS.
 *
 * <p>Accepts what real payloads contain: {@code "+91 98765 43210"} (Razorpay contact),
 * {@code "09876543210"}, {@code "9876543210"} (Shopify checkout), {@code "919876543210"}.
 * Rejects landlines and short codes: they cannot receive WhatsApp or DLT SMS, so
 * storing them as phone keys only produces failed sends later.
 */
public final class Msisdn {

    private Msisdn() {}

    public static Optional<String> normalise(String raw) {
        if (raw == null) return Optional.empty();
        var d = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') d.append(c);
        }
        var digits = d.toString();

        if (digits.startsWith("0091")) digits = digits.substring(2);
        if (digits.length() == 11 && digits.startsWith("0")) digits = digits.substring(1);
        if (digits.length() == 10) digits = "91" + digits;

        // Indian mobiles begin 6–9 after the country code.
        if (digits.length() == 12 && digits.startsWith("91") && "6789".indexOf(digits.charAt(2)) >= 0) {
            return Optional.of(digits);
        }
        return Optional.empty();
    }

    /** For logs and UI: "+91 98•••••210". Never log a full number. */
    public static String mask(String normalised) {
        if (normalised == null || normalised.length() != 12) return "invalid";
        return "+91 " + normalised.substring(2, 4) + "•••••" + normalised.substring(9);
    }
}
