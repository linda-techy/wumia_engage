package in.brand.engage.core.privacy;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The customers this deployment may touch, by email. While the store is live
 * but Engage is still being built, only named test customers may flow in:
 * everyone else's webhooks are acknowledged and dropped, never stored.
 *
 * <p>Fails closed. An empty value is a configuration error, not "allow
 * everyone"; opening up to all customers takes an explicit {@code *}.
 */
public final class CustomerAllowlist {

    private final boolean allowAll;
    private final Set<String> emails;

    private CustomerAllowlist(boolean allowAll, Set<String> emails) {
        this.allowAll = allowAll;
        this.emails = Set.copyOf(emails);
    }

    /** Parses {@code CUSTOMER_ALLOWLIST_EMAILS}: comma-separated emails, or {@code *}. */
    public static CustomerAllowlist parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(
                    "CUSTOMER_ALLOWLIST_EMAILS is empty. List the customer emails Engage may process, "
                    + "or set it to * to allow every customer.");
        }
        var parts = Arrays.stream(raw.split("[,;\\s]+")).filter(s -> !s.isBlank()).toList();
        if (parts.equals(java.util.List.of("*"))) {
            return new CustomerAllowlist(true, Set.of());
        }
        var emails = new LinkedHashSet<String>();
        for (var p : parts) {
            if (p.equals("*")) {
                throw new IllegalArgumentException("CUSTOMER_ALLOWLIST_EMAILS: use * on its own, not mixed with emails");
            }
            var e = normalize(p);
            if (e.indexOf('@') <= 0 || e.indexOf('@') != e.lastIndexOf('@') || e.endsWith("@")) {
                throw new IllegalArgumentException("CUSTOMER_ALLOWLIST_EMAILS: not an email address: " + p);
            }
            emails.add(e);
        }
        return new CustomerAllowlist(false, emails);
    }

    public static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public boolean allowAll() {
        return allowAll;
    }

    /** Normalised (trimmed, lower-case) emails. Empty when {@link #allowAll()}. */
    public Set<String> emails() {
        return emails;
    }

    public boolean allows(String email) {
        return allowAll || (email != null && emails.contains(normalize(email)));
    }

    /** For logs: never prints the addresses themselves. */
    @Override
    public String toString() {
        return allowAll ? "ALL customers" : emails.size() + " allowlisted customer(s)";
    }
}
