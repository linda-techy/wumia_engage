package in.brand.engage.admin.config;

import in.brand.engage.core.crypto.SecretBox;
import io.micronaut.context.annotation.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Refuses to start on placeholder config, and names what is missing. */
@Context
public class AdminStartupCheck {

    private static final Logger LOG = LoggerFactory.getLogger(AdminStartupCheck.class);

    public AdminStartupCheck(AdminProperties properties) {
        var key = properties.mfaKey();
        if (key == null || key.isBlank() || key.contains("CHANGE_ME")) {
            throw new IllegalStateException("Edit config/local.env and set: ADMIN_MFA_KEY (openssl rand -hex 32)");
        }
        SecretBox.keyFromHex(key);   // throws with a clear message when it is not 32 bytes of hex
        LOG.info("Admin API ready: jwt key {}, bootstrap {}", properties.jwtKeyFile(),
                properties.bootstrapEmail() == null || properties.bootstrapEmail().isBlank() ? "disabled" : "armed");
    }
}
