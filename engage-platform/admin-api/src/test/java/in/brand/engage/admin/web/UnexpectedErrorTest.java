package in.brand.engage.admin.web;

import static org.junit.jupiter.api.Assertions.*;

import in.brand.engage.admin.auth.PublicEndpoint;
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

/** P7-T06: an unexpected failure answers a plain 500, never the exception's message. */
@MicronautTest(transactional = false)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "^(?!CHANGE_ME).+")
class UnexpectedErrorTest {

    @Requires(env = "test")
    @Controller("/test-only/boom")
    static class Boom {
        @Get
        @PublicEndpoint("test only: throws on purpose")
        String boom() {
            throw new IllegalStateException("duplicate key (email)=(secret@example.com) in operators");
        }
    }

    @Inject @Client("/") HttpClient client;

    @Test void the_cause_stays_in_the_log() {
        var e = assertThrows(HttpClientResponseException.class,
                () -> client.toBlocking().retrieve(HttpRequest.GET("/test-only/boom"), String.class));
        assertEquals(500, e.getStatus().getCode());
        var body = e.getResponse().getBody(String.class).orElse("");
        assertFalse(body.contains("secret@example.com"), body);
        assertFalse(body.contains("IllegalStateException"), body);
        assertTrue(body.matches(".*Reference [0-9a-f]{8}\\..*"), body);
    }

    @Test void bad_json_is_still_a_400_not_swallowed_as_a_500() {
        var e = assertThrows(HttpClientResponseException.class, () -> client.toBlocking().retrieve(
                HttpRequest.POST("/api/auth/login", "{not json").contentType("application/json"), String.class));
        assertEquals(400, e.getStatus().getCode());
    }
}
