package in.brand.engage.admin.config;

import io.micronaut.context.annotation.ConfigurationProperties;

/** Typed config for admin-api, bound from application.yml (config/local.env). */
@ConfigurationProperties("engage.admin")
public record AdminProperties(String jwtKeyFile, String mfaKey, String bootstrapEmail) {}
