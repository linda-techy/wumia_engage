package in.brand.engage.ingest;

import static org.junit.jupiter.api.Assertions.*;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** The service worker the storefront registers at /apps/push/sw.js (phase-2 §5). */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class StorefrontProxyControllerTest {

    @Inject @Client("/") HttpClient client;

    @Test
    void serves_the_service_worker_as_revalidated_javascript() {
        var res = client.toBlocking().exchange(HttpRequest.GET("/shopify/proxy/sw.js"), String.class);

        assertEquals(HttpStatus.OK, res.status());
        // Browsers refuse to register a worker served with a non-JavaScript MIME type.
        assertTrue(res.getContentType().orElseThrow().toString().startsWith("application/javascript"));
        assertEquals("no-cache", res.header("Cache-Control"));

        var version = res.header("X-Engage-SW");
        assertNotNull(version);
        assertTrue(version.matches("[0-9a-f]{12}"), "version is a short content hash: " + version);

        var body = res.body();
        assertTrue(body.contains("firebase-messaging-compat.js"));
        assertTrue(body.contains("const SW_VERSION = '" + version + "'"), "version injected into the worker");
        assertFalse(body.contains("__SW_VERSION__"), "placeholder replaced");
    }
}
