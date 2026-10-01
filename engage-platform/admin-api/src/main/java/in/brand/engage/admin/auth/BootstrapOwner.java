package in.brand.engage.admin.auth;

import in.brand.engage.admin.config.AdminProperties;
import io.micronaut.context.annotation.Context;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates the first OWNER when the operators table is empty, and logs a
 * one-time set-password link (24 h). Never runs again once any operator
 * exists, so a leaked bootstrap email cannot mint a second owner later.
 *
 * <p>After setting the password the owner signs in, gets "MFA enrolment
 * required" with an enrolment-only token, enrols an authenticator app, and
 * signs in again with password + code.
 */
@Context
public class BootstrapOwner {

    private static final Logger LOG = LoggerFactory.getLogger(BootstrapOwner.class);

    public BootstrapOwner(AdminProperties properties, OperatorRepository operators, PasswordHasher hasher,
                          Tokens tokens) {
        var email = properties.bootstrapEmail();
        if (email == null || email.isBlank() || !operators.isEmpty()) return;

        // An unusable placeholder: the account cannot be signed into until the link is used.
        var id = operators.createInvitedOwner(email, "Owner", hasher.hash(UUID.randomUUID().toString().toCharArray()));
        var operator = operators.findById(id).orElseThrow();
        var link = tokens.issuePurpose(id, "set-password", Map.of("pwd", String.valueOf(operator.ver())),
                Duration.ofHours(24));
        LOG.warn("Bootstrap OWNER created for {}. Set the password within 24h:\n  /set-password?token={}", email, link);
    }
}
