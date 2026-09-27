package in.brand.engage.ingest.web;

import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Storefront endpoints behind the Shopify App Proxy: the storefront's
 * {@code /apps/push/*} arrives here as {@code /shopify/proxy/*}
 * (shopify.app.dev.toml [app_proxy]). See phase-2-shopify-web-push.md §8.
 *
 * <p>Only the service worker is served so far. The subscriber endpoints
 * (register, refresh, unregister, cart, notify-me, prompt-event, engagement)
 * follow, each verifying the proxy signature with AppProxyVerifier first.
 */
@Controller("/shopify/proxy")
public class StorefrontProxyController {

    private final String serviceWorker;
    private final String serviceWorkerVersion;

    public StorefrontProxyController() {
        var raw = resource("/push/sw.js");
        // Content hash, so a deploy that changes the worker changes the version
        // the worker reports in its beacons.
        this.serviceWorkerVersion = sha256Hex(raw).substring(0, 12);
        this.serviceWorker = raw.replace("__SW_VERSION__", serviceWorkerVersion);
    }

    /**
     * Unsigned on purpose: it is a public script, and browsers fetch it again on
     * every update check. {@code no-cache}, not {@code no-store}: the browser
     * must revalidate, but may keep the file for offline wake-ups.
     */
    @Get(value = "/sw.js", produces = "application/javascript")
    public HttpResponse<String> serviceWorker() {
        return HttpResponse.ok(serviceWorker)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Engage-SW", serviceWorkerVersion);
    }

    private static String resource(String path) {
        try (var in = StorefrontProxyController.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException(path + " missing from the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
