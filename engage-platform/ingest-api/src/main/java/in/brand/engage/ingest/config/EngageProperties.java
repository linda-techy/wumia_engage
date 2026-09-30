package in.brand.engage.ingest.config;

import io.micronaut.context.annotation.ConfigurationProperties;

/** Typed configuration, bound from application.yml (which reads config/local.env). */
public final class EngageProperties {

    private EngageProperties() {}

    @ConfigurationProperties("engage.shopify")
    public record Shopify(String shopDomain, String apiSecret, String clientId) {}

    @ConfigurationProperties("engage.razorpay")
    public record Razorpay(String webhookSecret, String mode, int matchWindowMinutes) {}

    /** Web pixel intake (P2-T06). Blank = the endpoint answers 503. */
    @ConfigurationProperties("engage.pixel")
    public record Pixel(String writeKey) {}

    @ConfigurationProperties("engage.privacy")
    public record Privacy(String customerAllowlistEmails) {}
}
