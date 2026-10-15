package in.brand.engage.ingest.web;

import static org.junit.jupiter.api.Assertions.*;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** P7-T06: storefronts and providers never see an exception's message. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class UnexpectedErrorTest {

    @Requires(env = "test")
    @Controller("/test-only/boom")
    static class Boom {
        @Get
        String boom() {
            throw new IllegalStateException("null value in column \"phone\" for 919812345678");
        }
    }

    @Inject @Client("/") HttpClient client;

    @Test void the_cause_stays_in_the_log() {
        var e = assertThrows(HttpClientResponseException.class,
                () -> client.toBlocking().retrieve(HttpRequest.GET("/test-only/boom"), String.class));
        assertEquals(500, e.getStatus().getCode());
        var body = e.getResponse().getBody(String.class).orElse("");
        assertFalse(body.contains("919812345678") || body.contains("IllegalState") || body.contains("phone"), body);
        assertTrue(body.contains("\"ref\""), body);
    }
}
