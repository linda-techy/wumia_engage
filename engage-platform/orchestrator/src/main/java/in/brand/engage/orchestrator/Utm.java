package in.brand.engage.orchestrator;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Attribution parameters on every outbound link (phase-3 §5), added here and
 * never in templates, so no template can forget them or get them wrong:
 * {@code utm_source=engage&utm_medium=<channel>&utm_campaign=<intent>&utm_content=<sid>}.
 *
 * <p>UTMs say where a click came from. Whether the order would have happened
 * anyway is the holdout's job (P7), not theirs.
 */
final class Utm {

    private Utm() {}

    static String apply(String url, String medium, String campaign, long sendId) {
        int hash = url.indexOf('#');
        var base = hash < 0 ? url : url.substring(0, hash);
        var fragment = hash < 0 ? "" : url.substring(hash);
        var params = "utm_source=engage&utm_medium=" + enc(medium) + "&utm_campaign=" + enc(campaign)
                + "&utm_content=" + sendId;
        var sep = base.contains("?") ? (base.endsWith("?") || base.endsWith("&") ? "" : "&") : "?";
        return base + sep + params + fragment;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
