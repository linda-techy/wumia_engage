package in.brand.engage.core.privacy;

/** Display masking. The console shows these by default; unmasking is audited. */
public final class Masks {

    private static final String DOTS = "••••";

    private Masks() {}

    /** {@code mary@example.com} → {@code m••••@example.com}; anything else → {@code ••••}. */
    public static String email(String email) {
        if (email == null) return null;
        int at = email.indexOf('@');
        if (at <= 0 || at == email.length() - 1) return DOTS;
        return email.charAt(0) + DOTS + email.substring(at);
    }

    /** An opaque id (push token, browser id): enough of the end to tell two apart. */
    public static String opaque(String value) {
        if (value == null) return null;
        return value.length() <= 6 ? DOTS : DOTS + value.substring(value.length() - 6);
    }
}
