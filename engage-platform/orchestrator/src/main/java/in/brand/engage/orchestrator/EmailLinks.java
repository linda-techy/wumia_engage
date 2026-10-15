package in.brand.engage.orchestrator;

import in.brand.engage.core.crypto.UnsubscribeToken;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import java.util.UUID;

/**
 * The one-click unsubscribe link in every marketing email (P7-T01):
 * {@code <PUBLIC_BASE_URL>/u/<token>}, served by ingest-api, which verifies
 * the same signed token.
 */
@Singleton
public class EmailLinks {

    private final String base;
    private final UnsubscribeToken tokens;

    public EmailLinks(@Value("${PUBLIC_BASE_URL:}") String publicBaseUrl, @Value("${SHOPIFY_API_SECRET:}") String appSecret) {
        this.base = publicBaseUrl == null ? "" : publicBaseUrl.strip().replaceAll("/+$", "");
        this.tokens = appSecret == null || appSecret.isBlank() || appSecret.contains("CHANGE_ME")
                ? null : UnsubscribeToken.fromAppSecret(appSecret);
    }

    /** @throws IllegalStateException without PUBLIC_BASE_URL or the app secret: no email may go without its unsubscribe link */
    public String unsubscribeUrl(UUID identity) {
        if (base.isEmpty() || !base.startsWith("https://")) {
            throw new IllegalStateException("PUBLIC_BASE_URL (https) is needed for unsubscribe links");
        }
        if (tokens == null) throw new IllegalStateException("SHOPIFY_API_SECRET is needed to sign unsubscribe links");
        return base + "/u/" + tokens.of(identity);
    }
}
