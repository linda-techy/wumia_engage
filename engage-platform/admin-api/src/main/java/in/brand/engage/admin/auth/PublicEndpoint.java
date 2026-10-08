package in.brand.engage.admin.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Deliberately reachable without a session: sign-in steps, the one-time
 * set-password link, the public signing key. Each such method checks its own
 * credential (password, MFA token, link token, refresh cookie). The reason
 * is required, so every exception to {@link RequiresRole} explains itself.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PublicEndpoint {
    /** Why this endpoint needs no session, and what it checks instead. */
    String value();
}
