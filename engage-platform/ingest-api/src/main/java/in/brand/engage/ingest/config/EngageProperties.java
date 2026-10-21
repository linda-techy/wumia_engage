package in.brand.engage.ingest.config;

import io.micronaut.context.annotation.ConfigurationProperties;

/** Typed configuration, bound from application.yml (which reads config/local.env). */
public final class EngageProperties {

    private EngageProperties() {}

    @ConfigurationProperties("engage.shopify")
    public record Shopify(String shopDomain, String apiSecret, String clientId) {}

    @ConfigurationProperties("engage.razorpay")
    public record Razorpay(String webhookSecret, String mode, int matchWindowMinutes) {}

    /**
     * When the checkout phone field started showing the WhatsApp/SMS order-update
     * notice ({@code checkout_notice_v1}): ISO date (IST midnight) or instant.
     * Blank = the notice is not live, so no order gets that basis. Recording a
     * notice that was never shown would be false evidence. The version is the
     * label text in use (checkout_notice_v2 names offers; v1 order updates only).
     *
     * <p>{@code shopifyPhoneMarketingSince}: when Shopify's own phone marketing
     * checkbox was relabelled to name WhatsApp and SMS ({@code shopify_phone_marketing_v1}).
     * Blank = it still says SMS only, and its state is not reused.
     */
    @ConfigurationProperties("engage.consent")
    public record Consent(String checkoutNoticeSince, String checkoutNoticeVersion, String shopifyPhoneMarketingSince) {}

    /** Shipping aggregator webhooks (P1-T02, ADR-005). Blank token = every courier webhook is refused. */
    @ConfigurationProperties("engage.courier")
    public record Courier(String webhookToken) {}

    /** Web pixel intake (P2-T06). Blank = the endpoint answers 503. */
    @ConfigurationProperties("engage.pixel")
    public record Pixel(String writeKey) {}

    /** WhatsApp Cloud API webhooks (P4-T01). Empty values refuse every webhook and every verification. */
    @ConfigurationProperties("engage.meta")
    public record Meta(String appSecret, String verifyToken) {}

    /** MSG91 delivery reports (P4-T06): the secret in the report URL. Blank = every report is refused. */
    @ConfigurationProperties("engage.msg91")
    public record Msg91(String dlrToken) {}

    @ConfigurationProperties("engage.privacy")
    public record Privacy(String customerAllowlistEmails) {}
}
