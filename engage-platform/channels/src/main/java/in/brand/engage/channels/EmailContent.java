package in.brand.engage.channels;

import java.util.Objects;

/**
 * One marketing email, rendered (P7-T01). Engage sends marketing email only;
 * Shopify sends the transactional ones (order confirmations, invoices), so the
 * adapter refuses anything not marked marketing.
 *
 * @param unsubscribeUrl the one-click link (RFC 8058), also in the footer
 */
public record EmailContent(String subject, String html, String text, String unsubscribeUrl, boolean marketing) {

    public EmailContent {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(html, "html");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(unsubscribeUrl, "unsubscribeUrl");
    }
}
